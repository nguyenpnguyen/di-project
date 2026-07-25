"""
Ingest một nguồn.

Nạp tăng dần: mỗi bản ghi có content_hash. Lần chạy sau, tin không đổi bị bỏ qua ở
UNIQUE(source_id, external_id, content_hash); tin sửa nội dung sinh hash mới nên được
ghi thành phiên bản mới và cập nhật vào job hiện hành (SCD-2). Vì vậy bấm "Nạp lại"
trên giao diện luôn an toàn: chỉ tin mới hoặc tin đã thay đổi mới đi tiếp trong pipeline.
"""
import json
from datetime import date, datetime, timedelta, timezone
from app.common import conn, content_hash, norm
from app.wrappers.sources import REGISTRY
from app.pipeline import skills as sk, matching as mt, fusion, schema_match


def _source(cur, name):
    cur.execute("SELECT * FROM data_source WHERE name=%s", (name,))
    r = cur.fetchone()
    if r:
        return r
    cur.execute("""INSERT INTO data_source(name, kind, wrapper_class) VALUES (%s,'crawler',%s)
                   RETURNING *""", (name, name))
    return cur.fetchone()


def _company(cur, value):
    if not value:
        return None
    v = norm(value)
    cur.execute("SELECT company_id FROM company WHERE name_norm=%s", (v,))
    r = cur.fetchone()
    if r:
        return r["company_id"]
    cur.execute("INSERT INTO company(name_norm, name_display) VALUES (%s,%s) RETURNING company_id",
                (v, value))
    return cur.fetchone()["company_id"]


def _location(cur, value):
    if not value:
        return None
    cur.execute("SELECT location_id FROM location WHERE canonical=%s", (value,))
    r = cur.fetchone()
    if r:
        return r["location_id"]
    cur.execute("INSERT INTO location(canonical) VALUES (%s) RETURNING location_id", (value,))
    return cur.fetchone()["location_id"]


def candidates_in_block(cur, g):
    """Chỉ lấy các tin cùng khoá chặn -> giảm mạnh số cặp phải so."""
    cur.execute("""SELECT j.job_id, j.title_norm, j.salary_min, j.exp_years_min,
                          co.name_display AS company, l.canonical AS location
                   FROM job j LEFT JOIN company co USING (company_id)
                   LEFT JOIN location l USING (location_id)
                   WHERE j.is_current AND j.cluster_key = %s""", (mt.blocking_key(g),))
    return cur.fetchall()


def known_ids(cur, sid, cap: int = 20000) -> frozenset:
    """external_id đã có trong kho, để wrapper dừng sớm khi gặp lại tin cũ."""
    cur.execute("""SELECT DISTINCT external_id FROM raw_job WHERE source_id=%s
                   ORDER BY external_id DESC LIMIT %s""", (sid, cap))
    return frozenset(r["external_id"] for r in cur.fetchall())


def run_source(name: str, limit: int = 200, since=None, use_known: bool = True) -> dict:
    """limit — trần số tin lấy mỗi lần chạy (không bao giờ lấy hết một lượt).
       since — chỉ lấy tin từ ngày này trở đi, dạng 'YYYY-MM-DD' hoặc số ngày gần đây.
       use_known — bỏ qua tin đã có, giúp mỗi lần bấm chỉ lấy phần mới."""
    w = REGISTRY[name]
    st = {"in": 0, "new": 0, "updated": 0, "merged": 0, "review": 0, "skipped": 0}
    if isinstance(since, int):
        since = date.today() - timedelta(days=since)
    elif isinstance(since, str) and since:
        since = date.fromisoformat(since)

    with conn() as c, c.cursor() as cur:
        src = _source(cur, name)
        sid, trust = src["source_id"], float(src.get("trust_score") or 0.7)
        state = src.get("cursor_state") or {}

        # Đang trong thời gian bị nguồn chặn thì hoãn, không đâm đầu vào tường
        if state.get("blocked_until") and state["blocked_until"] > datetime.now(timezone.utc).isoformat():
            return st | {"status": "hoãn", "note": f"nguồn đang chặn tới {state['blocked_until'][:16]}"}

        cur.execute("INSERT INTO ingestion_run(source_id) VALUES (%s) RETURNING run_id", (sid,))
        run_id = cur.fetchone()["run_id"]

        w.start_page = state.get("next_page", 1) if hasattr(w, "start_page") else 1
        known = known_ids(cur, sid) if use_known else frozenset()
        records = w.fetch(limit, since, known)
        meta = getattr(w, "last_meta", {}) or {}
        schema_match.save_profile(sid, records)          # hồ sơ trường cho schema matching

        # Phiên bản mapping: đóng bản cũ, mở bản mới -> truy vết được mapping từng dùng khi nào
        cur.execute("UPDATE schema_mapping SET valid_to=now() WHERE source_id=%s AND valid_to IS NULL", (sid,))
        for _, sfield, gfield, tf in w.mapping_rows():
            cur.execute("""INSERT INTO schema_mapping(source_id, source_field, global_field, transform)
                           VALUES (%s,%s,%s,%s)""", (sid, sfield, gfield, tf))

        for rec in records:
            st["in"] += 1
            h = content_hash(rec)
            ext = str(rec.get(w.FIELD_MAP["external_id"][0]) or h)

            cur.execute("SELECT 1 FROM raw_job WHERE source_id=%s AND external_id=%s AND content_hash=%s",
                        (sid, ext, h))
            if cur.fetchone():
                st["skipped"] += 1                       # không đổi từ lần nạp trước
                continue
            cur.execute("SELECT 1 FROM raw_job WHERE source_id=%s AND external_id=%s", (sid, ext))
            is_update = cur.fetchone() is not None

            cur.execute("""INSERT INTO raw_job(source_id, run_id, external_id, url, payload, content_hash)
                           VALUES (%s,%s,%s,%s,%s,%s) RETURNING raw_id""",
                        (sid, run_id, ext, rec.get("url"), json.dumps(rec, default=str), h))
            raw_id = cur.fetchone()["raw_id"]

            g = w.to_global(rec)
            g["cluster_key"] = mt.blocking_key(g)
            comp = _company(cur, g.get("company"))
            loc = _location(cur, g.get("location"))

            # --- Entity matching ---
            best, best_score, best_vec = None, 0.0, None
            for cand in candidates_in_block(cur, g):
                vec = mt.sim_vector(g, cand)
                s = mt.score(vec)
                if s > best_score:
                    best, best_score, best_vec = cand, s, vec
            verdict = mt.decide(best_score) if best else "no-match"

            if verdict == "match":
                job_id = best["job_id"]
                st["merged"] += 1
                # --- Data fusion khi giá trị mâu thuẫn ---
                for field, new_val, old_val in [("description", g.get("description"), None),
                                                ("requirement", g.get("requirement"), None),
                                                ("salary_min", g.get("salary_min"), best["salary_min"])]:
                    merged = fusion.resolve(job_id, field, [
                        {"value": old_val, "source": "hiện có", "trust": 0.8},
                        {"value": new_val, "source": name, "trust": trust}])
                    if merged is not None:
                        cur.execute(f"UPDATE job SET {field}=%s WHERE job_id=%s", (merged, job_id))
            else:
                if verdict == "review":
                    cur.execute("""INSERT INTO match_review(kind, left_ref, right_ref, score, evidence)
                                   VALUES ('job_dedup', %s, %s, %s, %s)""",
                                (f"{name}:{ext}", f"job:{best['job_id']}", best_score,
                                 json.dumps(best_vec)))
                    st["review"] += 1
                cur.execute("""INSERT INTO job(cluster_key, title, title_norm, company_id, location_id,
                                 employment_type, exp_years_min, salary_min, salary_max,
                                 description, requirement)
                               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING job_id""",
                            (g["cluster_key"], g.get("title"), g["title_norm"], comp, loc,
                             g.get("employment_type"), g.get("exp_years_min"), g.get("salary_min"),
                             g.get("salary_max"), g.get("description"), g.get("requirement")))
                job_id = cur.fetchone()["job_id"]
                st["new"] += 1
            if is_update:
                st["updated"] += 1

            cur.execute("""INSERT INTO job_source_link(job_id, raw_id, source_id, match_score)
                           VALUES (%s,%s,%s,%s) ON CONFLICT DO NOTHING""",
                        (job_id, raw_id, sid, best_score if verdict == "match" else 1.0))
            cur.execute("""INSERT INTO change_log(entity, entity_id, change_type, diff, run_id)
                           VALUES ('job', %s, %s, %s, %s)""",
                        (str(job_id), "update" if is_update else ("merge" if verdict == "match" else "insert"),
                         json.dumps({"source": name, "external_id": ext, "score": best_score}), run_id))
            sk.persist(job_id, sid, sk.extract(g))

        status = "partial" if meta.get("blocked") else "success"
        cur.execute("""UPDATE ingestion_run SET finished_at=now(), status=%s,
                       rows_in=%s, rows_new=%s, rows_changed=%s, note=%s WHERE run_id=%s""",
                    (status, st["in"], st["new"], st["updated"] + st["merged"],
                     json.dumps(st | {"stop_reason": meta.get("stop_reason")}, ensure_ascii=False), run_id))

        # Ghi con trỏ: lần sau chạy tiếp từ trang kế, và lùi lịch nếu vừa bị chặn
        new_state = dict(state, last_run_id=run_id, next_page=meta.get("next_page", 1),
                         last_stop_reason=meta.get("stop_reason"))
        if meta.get("blocked"):
            new_state["blocked_until"] = (datetime.now(timezone.utc) +
                timedelta(seconds=meta.get("retry_after", 3600))).isoformat()
        else:
            new_state.pop("blocked_until", None)
        cur.execute("UPDATE data_source SET last_run_at=now(), cursor_state=%s WHERE source_id=%s",
                    (json.dumps(new_state), sid))
    return st | {"run_id": run_id, "status": status, "stop_reason": meta.get("stop_reason")}
