from pathlib import Path
from fastapi import FastAPI, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.common import conn, norm as nrm
from app.pipeline import ingest, index, ontology, schema_match
from app.pipeline import skills as skills_mod
from app.wrappers.sources import REGISTRY

app = FastAPI(title="Job Skill Integration")

# ---------------- Bộ lập lịch nạp định kỳ ----------------
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger

sched = BackgroundScheduler(timezone="Asia/Ho_Chi_Minh")


def reload_schedule():
    """Đọc data_source.schedule_cron và đăng ký lại job định kỳ."""
    sched.remove_all_jobs()
    with conn() as c, c.cursor() as cur:
        cur.execute("SELECT name, schedule_cron FROM data_source WHERE enabled AND schedule_cron IS NOT NULL")
        rows = cur.fetchall()
    for r in rows:
        if r["name"] not in REGISTRY:
            continue
        try:
            sched.add_job(lambda n=r["name"]: (ingest.run_source(n, limit=100, since=7), index.reindex()),
                          CronTrigger.from_crontab(r["schedule_cron"]), id=r["name"],
                          misfire_grace_time=3600)
        except ValueError:
            pass
    return [j.id for j in sched.get_jobs()]


@app.on_event("startup")
def _startup():
    try:
        reload_schedule()
        sched.start()
    except Exception as e:
        print("scheduler off:", e)


@app.get("/api/admin/schedule")
def schedule():
    return [{"source": j.id, "next_run": str(j.next_run_time)} for j in sched.get_jobs()]
STATIC = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/")
def home():
    return FileResponse(STATIC / "index.html")


# ---------------- Tìm kiếm theo kỹ năng ----------------
@app.get("/api/search")
def search(skill: list[str] = Query(default=[]), mode: str = "any",
           expand: bool = True, stype: str | None = None, limit: int = 20):
    """mode=all -> job phải có đủ mọi kỹ năng (skill combination).
       expand=true -> tìm bằng skill cha sẽ ra cả job dùng skill con."""
    field = "skills_expanded" if expand else "skills"
    clauses = [{"term": {field: s}} for s in skill]
    q = {"bool": ({"must": clauses} if mode == "all" else {"should": clauses, "minimum_should_match": 1})}
    if stype in ("hard", "soft"):
        q["bool"].setdefault("filter", []).append({"exists": {"field": f"{stype}_skills"}})
    if not skill:
        q = {"match_all": {}}
    res = index.client().search(index=index.INDEX, body={"query": q, "size": limit,
        "aggs": {"top_skills": {"terms": {"field": "skills", "size": 15}},
                 "by_source": {"terms": {"field": "sources"}}}})
    return {"total": res["hits"]["total"]["value"],
            "items": [h["_source"] for h in res["hits"]["hits"]],
            "facets": {k: v["buckets"] for k, v in res.get("aggregations", {}).items()}}


@app.get("/api/skills")
def skill_tree(root: str | None = None):
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT s.skill_id, s.pref_label, s.skill_type, p.pref_label AS parent
                       FROM skill s
                       LEFT JOIN skill_relation r ON r.child_id=s.skill_id AND r.rel_type='broader'
                       LEFT JOIN skill p ON p.skill_id=r.parent_id
                       ORDER BY COALESCE(p.pref_label,''), s.pref_label""")
        rows = cur.fetchall()
    if root:
        rows = [r for r in rows if root.lower() in (r["pref_label"] + (r["parent"] or "")).lower()]
    return rows


@app.get("/api/skill/{label}/related")
def related(label: str, limit: int = 15):
    """Skill graph: kỹ năng hay xuất hiện cùng."""
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT s2.pref_label, count(*) AS n
                       FROM job_skill a JOIN job_skill b ON a.job_id=b.job_id AND a.skill_id<>b.skill_id
                       JOIN skill s1 ON s1.skill_id=a.skill_id
                       JOIN skill s2 ON s2.skill_id=b.skill_id
                       WHERE lower(s1.pref_label)=lower(%s)
                       GROUP BY s2.pref_label ORDER BY n DESC LIMIT %s""", (label, limit))
        return cur.fetchall()


@app.get("/api/job/{job_id}/provenance")
def provenance(job_id: int):
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT s.pref_label, js.surface_form, js.match_method, js.confidence,
                              js.src_field, js.src_offset, ds.name AS source
                       FROM job_skill js JOIN skill s USING (skill_id)
                       JOIN data_source ds ON ds.source_id=js.source_id
                       WHERE js.job_id=%s ORDER BY js.confidence DESC""", (job_id,))
        sk = cur.fetchall()
        cur.execute("""SELECT ds.name, r.url, r.fetched_at, r.content_hash
                       FROM job_source_link l JOIN raw_job r USING (raw_id)
                       JOIN data_source ds ON ds.source_id=r.source_id WHERE l.job_id=%s""", (job_id,))
        return {"skills": sk, "records": cur.fetchall()}


# ---------------- Quản trị vòng đời dữ liệu ----------------
@app.get("/api/admin/sources")
def sources():
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT ds.*, (SELECT count(*) FROM raw_job r WHERE r.source_id=ds.source_id) AS raw_rows,
                       (SELECT status FROM ingestion_run ir WHERE ir.source_id=ds.source_id
                        ORDER BY run_id DESC LIMIT 1) AS last_status
                       FROM data_source ds ORDER BY ds.name""")
        return cur.fetchall()


@app.post("/api/admin/ingest/{name}")
def trigger(name: str, limit: int = 100, since: str | None = None,
            days: int | None = None, only_new: bool = True):
    """limit    trần số tin lấy lần này
       since    'YYYY-MM-DD', chỉ lấy tin từ ngày đó trở đi
       days     tiện hơn since: chỉ lấy tin trong N ngày gần đây
       only_new bỏ qua tin đã có trong kho (mặc định bật)"""
    if name not in REGISTRY:
        return {"error": f"Chưa có wrapper cho nguồn '{name}'."}
    return ingest.run_source(name, limit, since=days or since, use_known=only_new)


@app.post("/api/admin/reindex")
def do_reindex():
    return {"indexed": index.reindex()}


@app.post("/api/admin/ontology")
def do_ontology():
    return {"seed": ontology.load_seed(), "esco": ontology.load_esco()}


@app.get("/api/admin/runs")
def runs(limit: int = 20):
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT r.*, ds.name AS source FROM ingestion_run r
                       JOIN data_source ds USING (source_id)
                       ORDER BY run_id DESC LIMIT %s""", (limit,))
        return cur.fetchall()


@app.get("/api/admin/mappings")
def mappings(current_only: bool = True):
    with conn() as c, c.cursor() as cur:
        cur.execute(f"""SELECT ds.name AS source, m.source_field, m.global_field, m.transform,
                        m.valid_from, m.valid_to FROM schema_mapping m
                        JOIN data_source ds USING (source_id)
                        {'WHERE m.valid_to IS NULL' if current_only else ''}
                        ORDER BY ds.name, m.global_field""")
        return cur.fetchall()


@app.get("/api/admin/changes")
def changes(limit: int = 50):
    with conn() as c, c.cursor() as cur:
        cur.execute("SELECT * FROM change_log ORDER BY change_id DESC LIMIT %s", (limit,))
        return cur.fetchall()


@app.get("/api/admin/quality")
def quality():
    """Chỉ số chất lượng tích hợp: độ phủ skill, tỉ lệ khớp fuzzy, job đa nguồn."""
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT
          (SELECT count(*) FROM job WHERE is_current) AS jobs,
          (SELECT count(DISTINCT job_id) FROM job_skill) AS jobs_with_skill,
          (SELECT count(*) FROM skill) AS skills,
          (SELECT count(*) FROM job_skill WHERE match_method='fuzzy') AS fuzzy_matches,
          (SELECT count(*) FROM job_skill) AS skill_links,
          (SELECT count(*) FROM (SELECT job_id FROM job_source_link
             GROUP BY job_id HAVING count(DISTINCT source_id) > 1) t) AS multi_source_jobs""")
        return cur.fetchone()


# ---------------- Quản lý nguồn ----------------
@app.post("/api/admin/source")
def upsert_source(body: dict):
    """Tạo hoặc sửa nguồn: lịch chạy, bật/tắt, độ tin cậy (dùng cho data fusion)."""
    with conn() as c, c.cursor() as cur:
        cur.execute("""INSERT INTO data_source(name, kind, base_url, schedule_cron, enabled,
                          trust_score, wrapper_class, note)
                       VALUES (%(name)s, %(kind)s, %(base_url)s, %(schedule_cron)s,
                               %(enabled)s, %(trust_score)s, %(wrapper_class)s, %(note)s)
                       ON CONFLICT (name) DO UPDATE SET
                         kind=EXCLUDED.kind, base_url=EXCLUDED.base_url,
                         schedule_cron=EXCLUDED.schedule_cron, enabled=EXCLUDED.enabled,
                         trust_score=EXCLUDED.trust_score, note=EXCLUDED.note
                       RETURNING *""",
                    {"kind": "crawler", "base_url": None, "schedule_cron": None, "enabled": True,
                     "trust_score": 0.7, "wrapper_class": body.get("name"), "note": None} | body)
        row = cur.fetchone()
    reload_schedule()
    return row


@app.delete("/api/admin/source/{name}")
def disable_source(name: str):
    with conn() as c, c.cursor() as cur:
        cur.execute("UPDATE data_source SET enabled=false WHERE name=%s", (name,))
    reload_schedule()
    return {"disabled": name}


# ---------------- Quản lý cấu trúc dữ liệu ----------------
@app.get("/api/admin/schema/suggest/{name}")
def suggest_mapping(name: str, sample: int = 30):
    """Gợi ý ánh xạ trường cho một nguồn dựa trên tên trường + hồ sơ dữ liệu."""
    if name not in REGISTRY:
        return {"error": f"Chưa có wrapper cho '{name}'."}
    recs = REGISTRY[name].fetch(sample)
    if not recs:
        return {"error": "Nguồn chưa có dữ liệu để phân tích. Nạp dữ liệu trước đã."}
    declared = {s: g for _, s, g, _tf in REGISTRY[name].mapping_rows()}
    out = schema_match.suggest(recs)
    for o in out:
        o["declared"] = declared.get(o["source_field"])
        o["agrees"] = o["declared"] == o["global_field"]
    return out


@app.get("/api/admin/schema/profile/{name}")
def field_profile(name: str):
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT p.* FROM source_field_profile p JOIN data_source d USING (source_id)
                       WHERE d.name=%s ORDER BY p.field""", (name,))
        return cur.fetchall()


@app.post("/api/admin/mapping")
def set_mapping(body: dict):
    """Ghi đè một ánh xạ trường: đóng bản cũ theo thời gian rồi mở bản mới."""
    with conn() as c, c.cursor() as cur:
        cur.execute("SELECT source_id FROM data_source WHERE name=%s", (body["source"],))
        sid = cur.fetchone()["source_id"]
        cur.execute("""UPDATE schema_mapping SET valid_to=now()
                       WHERE source_id=%s AND source_field=%s AND valid_to IS NULL""",
                    (sid, body["source_field"]))
        cur.execute("""INSERT INTO schema_mapping(source_id, source_field, global_field, transform, confidence)
                       VALUES (%s,%s,%s,%s,%s) RETURNING *""",
                    (sid, body["source_field"], body["global_field"],
                     body.get("transform", "text"), body.get("confidence", 1.0)))
        return cur.fetchone()


# ---------------- Quản lý ontology kỹ năng ----------------
@app.post("/api/admin/skill")
def add_skill(body: dict):
    """Thêm khái niệm kỹ năng, gắn cha, hoặc thêm cách gọi khác."""
    with conn() as c, c.cursor() as cur:
        uri = body.get("concept_uri") or f"local:{nrm(body['label'])}"
        cur.execute("""INSERT INTO skill(pref_label, skill_type, concept_uri, source_ont)
                       VALUES (%s,%s,%s,'manual') ON CONFLICT (concept_uri) DO UPDATE
                       SET pref_label=EXCLUDED.pref_label RETURNING skill_id""",
                    (body["label"], body.get("skill_type", "hard"), uri))
        sid = cur.fetchone()["skill_id"]
        cur.execute("""INSERT INTO skill_alias(skill_id, alias, alias_norm) VALUES (%s,%s,%s)
                       ON CONFLICT DO NOTHING""", (sid, body["label"], nrm(body["label"])))
        for a in body.get("aliases", []):
            cur.execute("""INSERT INTO skill_alias(skill_id, alias, alias_norm) VALUES (%s,%s,%s)
                           ON CONFLICT DO NOTHING""", (sid, a, nrm(a)))
        if body.get("parent"):
            cur.execute("SELECT skill_id FROM skill WHERE lower(pref_label)=lower(%s)", (body["parent"],))
            p = cur.fetchone()
            if p:
                cur.execute("""INSERT INTO skill_relation VALUES (%s,%s,'broader')
                               ON CONFLICT DO NOTHING""", (p["skill_id"], sid))
    skills_mod.alias_index.__globals__["_CACHE"]["aliases"] = None   # xoá cache alias
    return {"skill_id": sid}


@app.delete("/api/admin/skill/{skill_id}")
def del_skill(skill_id: int):
    with conn() as c, c.cursor() as cur:
        cur.execute("DELETE FROM skill WHERE skill_id=%s", (skill_id,))
    skills_mod.alias_index.__globals__["_CACHE"]["aliases"] = None
    return {"deleted": skill_id}


# ---------------- Duyệt khớp & xung đột ----------------
@app.get("/api/admin/reviews")
def reviews(status: str = "pending", limit: int = 50):
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT * FROM match_review WHERE status=%s
                       ORDER BY score DESC LIMIT %s""", (status, limit))
        return cur.fetchall()


@app.post("/api/admin/review/{review_id}")
def decide_review(review_id: int, body: dict):
    with conn() as c, c.cursor() as cur:
        cur.execute("""UPDATE match_review SET status=%s, decided_at=now(), decided_by=%s
                       WHERE review_id=%s RETURNING *""",
                    (body.get("status", "accepted"), body.get("by", "admin"), review_id))
        return cur.fetchone()


@app.get("/api/admin/conflicts")
def conflicts(limit: int = 50):
    with conn() as c, c.cursor() as cur:
        cur.execute("""SELECT v.*, j.title FROM value_conflict v JOIN job j USING (job_id)
                       ORDER BY conflict_id DESC LIMIT %s""", (limit,))
        return cur.fetchall()
