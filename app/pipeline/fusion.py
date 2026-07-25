"""
Data fusion — khi nhiều nguồn cùng mô tả một tin nhưng giá trị khác nhau.
Chiến lược: trust (tin nguồn uy tín hơn) → recency (bản mới hơn) → longest (mô tả dài hơn).
Mọi lần hoà giải đều ghi lại vào value_conflict để giải thích được vì sao chọn giá trị đó.
"""
import json
from app.common import conn

STRATEGY = {
    "title": "trust", "company": "trust", "location": "trust",
    "salary_min": "trust", "salary_max": "trust",
    "description": "longest", "requirement": "longest",
    "posted_at": "recency",
}


def resolve(job_id: int, field: str, candidates: list[dict]):
    """candidates: [{value, source, trust, fetched_at}]"""
    cands = [c for c in candidates if c["value"] not in (None, "", [])]
    if len(cands) < 2:
        return cands[0]["value"] if cands else None
    strat = STRATEGY.get(field, "trust")
    if strat == "longest":
        winner = max(cands, key=lambda c: len(str(c["value"])))
    elif strat == "recency":
        winner = max(cands, key=lambda c: c.get("fetched_at") or "")
    else:
        winner = max(cands, key=lambda c: (c.get("trust") or 0, len(str(c["value"]))))

    if len({str(c["value"]) for c in cands}) > 1:
        with conn() as c_, c_.cursor() as cur:
            cur.execute("""INSERT INTO value_conflict(job_id, field, values_by_source, resolved, strategy)
                           VALUES (%s,%s,%s,%s,%s)""",
                        (job_id, field,
                         json.dumps({c["source"]: str(c["value"])[:200] for c in cands}),
                         str(winner["value"])[:200], strat))
    return winner["value"]
