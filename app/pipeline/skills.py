"""
Skill extraction + normalization — entity matching nhiều tầng, dừng ở tầng đầu tiên khớp:
  1. exact     khớp alias đã chuẩn hoá                       conf 1.00
  2. acronym   viết tắt: k8s→Kubernetes, ML→Machine Learning conf 0.95
  3. fuzzy     RapidFuzz trên alias, ngưỡng 88               conf 0.75–0.95
  4. embedding SentenceTransformer đa ngữ (bật nếu cài)      conf 0.60–0.85
Khớp dưới REVIEW_UNDER được đẩy vào match_review cho người duyệt.
Mọi match ghi provenance: trường nguồn, vị trí ký tự, phương pháp, độ tin cậy.
"""
import re
from rapidfuzz import process, fuzz
from app.common import conn, norm

REVIEW_UNDER = 0.80
_CACHE = {"aliases": None, "embed": None}

# Viết tắt hay gặp trong tin tuyển dụng tiếng Việt
ACRONYMS = {"k8s": "kubernetes", "ml": "machine learning", "dl": "deep learning",
            "js": "javascript", "ts": "typescript", "tf": "tensorflow", "nlp": "machine learning",
            "ci cd": "devops", "db": "sql", "ba": "communication", "pm": "leadership"}


def alias_index():
    if _CACHE["aliases"] is None:
        with conn() as c, c.cursor() as cur:
            cur.execute("SELECT alias_norm, skill_id FROM skill_alias")
            idx = {}
            for r in cur.fetchall():
                idx.setdefault(r["alias_norm"], r["skill_id"])
            _CACHE["aliases"] = idx
    return _CACHE["aliases"]


TOKEN_RE = re.compile(r"[A-Za-zÀ-ỹ0-9+#.]+(?:\s+[A-Za-zÀ-ỹ0-9+#.]+){0,2}")


def candidates(text: str):
    """Sinh n-gram 1..3 kèm offset."""
    for m in TOKEN_RE.finditer(text or ""):
        yield m.group(0), m.start()


def extract(job: dict):
    """job: dict global schema. Trả list bản ghi job_skill."""
    idx = alias_index()
    keys = list(idx.keys())
    found, seen = [], set()

    fields = [("skills", " , ".join(job.get("skills_raw") or [])),
              ("requirement", job.get("requirement") or ""),
              ("description", job.get("description") or "")]

    for fname, text in fields:
        for surface, off in candidates(text):
            n = norm(surface)
            if len(n) < 2 or n in seen:
                continue
            if n in idx:                                            # tầng 1: exact
                found.append((idx[n], surface, 1.0, "exact", fname, off))
            elif n in ACRONYMS and ACRONYMS[n] in idx:                  # tầng 2: viết tắt
                found.append((idx[ACRONYMS[n]], surface, 0.95, "acronym", fname, off))
            elif fname == "skills" and len(n) > 3:                      # tầng 3: fuzzy
                m = process.extractOne(n, keys, scorer=fuzz.WRatio, score_cutoff=88)
                if m:
                    found.append((idx[m[0]], surface, m[1] / 100, "fuzzy", fname, off))
                else:
                    e = embed_match(n, keys, idx)                       # tầng 4: embedding
                    if e:
                        found.append((e[0], surface, e[1], "embedding", fname, off))
            else:
                continue
            seen.add(n)
    return found


def embed_match(text, keys, idx, threshold=0.72):
    """Bật khi cài sentence-transformers; không có thì bỏ qua êm."""
    try:
        if _CACHE["embed"] is None:
            from sentence_transformers import SentenceTransformer, util
            _CACHE["embed"] = (SentenceTransformer("paraphrase-multilingual-MiniLM-L12-v2"),
                               util, None)
        model, util, cache = _CACHE["embed"]
        if cache is None:
            cache = model.encode(keys, convert_to_tensor=True)
            _CACHE["embed"] = (model, util, cache)
        hit = util.semantic_search(model.encode(text, convert_to_tensor=True), cache, top_k=1)[0][0]
        if hit["score"] >= threshold:
            return idx[keys[hit["corpus_id"]]], round(float(hit["score"]), 3)
    except Exception:
        return None
    return None


def persist(job_id: int, source_id: int, rows):
    with conn() as c, c.cursor() as cur:
        for sid, surface, conf, method, field, off in rows:
            if conf < REVIEW_UNDER:
                cur.execute("""INSERT INTO match_review(kind, left_ref, right_ref, score, evidence)
                               VALUES ('skill_map', %s, %s, %s, %s)""",
                            (f"job:{job_id}:{surface[:60]}", f"skill:{sid}", conf,
                             '{"method":"%s","field":"%s"}' % (method, field)))
            cur.execute("""INSERT INTO job_skill(job_id, skill_id, surface_form, confidence,
                              match_method, src_field, src_offset, source_id)
                           VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                           ON CONFLICT (job_id, skill_id, surface_form) DO UPDATE
                           SET confidence = GREATEST(job_skill.confidence, EXCLUDED.confidence)""",
                        (job_id, sid, surface[:120], conf, method, field, off, source_id))
