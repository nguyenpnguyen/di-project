"""
Schema matching bán tự động cho nguồn mới.
Kết hợp 3 tín hiệu (kiểu COMA/similarity flooding rút gọn):
  1. name similarity  — RapidFuzz trên tên trường + từ điển đồng nghĩa Việt/Anh
  2. instance-based   — hồ sơ dữ liệu: độ dài, tỉ lệ số, mẫu giá trị
  3. regex đặc trưng  — lương/ngày/URL nhận diện bằng mẫu
Kết quả là gợi ý; người quản trị duyệt trên giao diện rồi mới ghi vào schema_mapping.
"""
import re, statistics
from rapidfuzz import fuzz
from app.common import conn, norm

# Từ điển đồng nghĩa: global_field -> các cách gọi thường gặp
SYNONYMS = {
    "title": ["title", "job title", "job name", "position", "occupation", "ten cong viec", "chuc danh", "vi tri"],
    "company": ["company", "employer", "company name", "cong ty", "nha tuyen dung"],
    "location": ["location", "work place", "address", "dia diem", "noi lam viec", "khu vuc"],
    "salary": ["salary", "income", "pay", "wage", "luong", "muc luong", "thu nhap"],
    "exp_years_min": ["experience", "exp", "kinh nghiem", "so nam kinh nghiem"],
    "description": ["description", "job description", "content", "mo ta", "noi dung"],
    "requirement": ["requirement", "candidate requirement", "condition", "yeu cau", "dieu kien"],
    "skills_raw": ["skill", "skills", "tags", "ky nang", "job skill set"],
    "employment_type": ["job type", "employment type", "hinh thuc", "loai hinh"],
    "posted_at": ["posted date", "created date", "date", "ngay dang"],
    "url": ["url", "link", "detail url"],
    "external_id": ["id", "job id", "idx", "code"],
}

PATTERNS = {
    "salary": re.compile(r"\d[\d.,]*\s*(tr|triệu|trieu|usd|k|m)\b|thoả thuận|thoa thuan|negotiab", re.I),
    "url": re.compile(r"^https?://"),
    "posted_at": re.compile(r"\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{4}"),
    "exp_years_min": re.compile(r"\d+\s*(năm|nam|year)", re.I),
}


def profile(records: list[dict]) -> dict:
    """Hồ sơ hoá dữ liệu thô của một nguồn."""
    out = {}
    for f in {k for r in records for k in r}:
        vals = [str(r.get(f)) for r in records if r.get(f) not in (None, "", [])]
        if not vals:
            continue
        out[f] = {
            "n_values": len(vals),
            "n_distinct": len(set(vals)),
            "avg_len": round(statistics.mean(len(v) for v in vals), 1),
            "pct_numeric": round(sum(v.replace(".", "").replace(",", "").isdigit() for v in vals) / len(vals), 2),
            "samples": vals[:3],
        }
    return out


def suggest(records: list[dict], top_k: int = 1) -> list[dict]:
    """Trả gợi ý mapping: [{source_field, global_field, score, evidence}]"""
    prof = profile(records)
    out = []
    for f, p in prof.items():
        best = []
        for g, syns in SYNONYMS.items():
            name_sc = max(fuzz.token_set_ratio(norm(f), norm(s)) for s in syns) / 100
            joined = " ".join(p["samples"])
            pat_sc = 1.0 if (g in PATTERNS and PATTERNS[g].search(joined)) else 0.0
            len_sc = 0.0
            if g in ("description", "requirement") and p["avg_len"] > 80:
                len_sc = 0.6
            if g in ("title", "company") and 5 < p["avg_len"] < 60:
                len_sc = 0.3
            score = 0.6 * name_sc + 0.3 * pat_sc + 0.1 * len_sc
            best.append((score, g, {"name": round(name_sc, 2), "pattern": pat_sc, "shape": len_sc}))
        best.sort(reverse=True)
        for sc, g, ev in best[:top_k]:
            out.append({"source_field": f, "global_field": g, "score": round(sc, 3),
                        "evidence": ev, "samples": p["samples"]})
    return sorted(out, key=lambda x: -x["score"])


def save_profile(source_id: int, records: list[dict]):
    prof = profile(records)
    with conn() as c, c.cursor() as cur:
        for f, p in prof.items():
            cur.execute("""INSERT INTO source_field_profile(source_id, field, n_values, n_distinct,
                             avg_len, pct_numeric, samples)
                           VALUES (%s,%s,%s,%s,%s,%s,%s)
                           ON CONFLICT (source_id, field) DO UPDATE SET
                             n_values=EXCLUDED.n_values, n_distinct=EXCLUDED.n_distinct,
                             avg_len=EXCLUDED.avg_len, pct_numeric=EXCLUDED.pct_numeric,
                             samples=EXCLUDED.samples, profiled_at=now()""",
                        (source_id, f, p["n_values"], p["n_distinct"], p["avg_len"],
                         p["pct_numeric"], p["samples"]))
    return len(prof)
