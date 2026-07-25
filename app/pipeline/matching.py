"""
Entity matching cho tin tuyển dụng (record linkage kinh điển):
  blocking → so sánh từng thuộc tính → vector tương đồng → điểm có trọng số
  → 3 vùng quyết định: khớp / cần người duyệt / không khớp.
Cặp ở vùng giữa được đẩy vào match_review cho quản trị viên quyết.
"""
from rapidfuzz import fuzz
from app.common import norm

WEIGHTS = {"title": 0.45, "company": 0.30, "location": 0.10, "salary": 0.10, "exp": 0.05}
AUTO_MATCH = 0.86      # trên ngưỡng này: gộp tự động
REVIEW_MIN = 0.70      # trong khoảng này: đưa cho người duyệt


def blocking_key(g: dict) -> str:
    """Khoá chặn: chỉ so những tin cùng công ty + cùng chữ cái đầu của tiêu đề.
    Giảm số cặp phải so từ O(n²) xuống mức chấp nhận được."""
    comp = norm(g.get("company") or "")[:12]
    t = norm(g.get("title") or "")
    head = "".join(w[0] for w in t.split()[:3])
    return f"{comp}|{head}"


def _num_sim(a, b, tol=0.3):
    if a is None or b is None:
        return None
    if max(a, b) == 0:
        return 1.0
    d = abs(a - b) / max(a, b)
    return max(0.0, 1 - d / tol) if d < tol else 0.0


def sim_vector(g: dict, other: dict) -> dict:
    """So từng thuộc tính. None = thiếu dữ liệu, sẽ bị loại khỏi trung bình có trọng số."""
    return {
        "title": fuzz.token_set_ratio(g.get("title_norm", ""), other.get("title_norm") or "") / 100,
        "company": 1.0 if norm(g.get("company") or "") == norm(other.get("company") or "") else
                   fuzz.token_set_ratio(norm(g.get("company") or ""), norm(other.get("company") or "")) / 100,
        "location": 1.0 if (g.get("location") and g.get("location") == other.get("location")) else
                    (None if not g.get("location") or not other.get("location") else 0.0),
        "salary": _num_sim(g.get("salary_min"), other.get("salary_min")),
        "exp": _num_sim(g.get("exp_years_min"), other.get("exp_years_min"), tol=0.5),
    }


def score(vec: dict) -> float:
    num = sum(WEIGHTS[k] * v for k, v in vec.items() if v is not None)
    den = sum(WEIGHTS[k] for k, v in vec.items() if v is not None)
    return round(num / den, 4) if den else 0.0


def decide(s: float) -> str:
    if s >= AUTO_MATCH:
        return "match"
    if s >= REVIEW_MIN:
        return "review"
    return "no-match"
