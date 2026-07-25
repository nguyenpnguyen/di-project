"""
Wrapper: mỗi datasource -> 1 lớp, khai báo FIELD_MAP (schema matching).
Mediator chỉ làm việc với global schema, không biết nguồn ở dưới là gì.
"""
from abc import ABC, abstractmethod
from app.common import canon_location, parse_salary, parse_exp, norm

TRANSFORMS = {
    "text": lambda v: (v or "").strip(),
    "location": canon_location,
    "salary": parse_salary,     # trả tuple (min, max)
    "exp": parse_exp,
    "list": lambda v: v if isinstance(v, list) else [x.strip() for x in str(v or "").split(",") if x.strip()],
}

GLOBAL_FIELDS = ["external_id", "url", "title", "company", "location", "employment_type",
                 "salary", "exp_years_min", "description", "requirement", "skills_raw", "posted_at"]


class Wrapper(ABC):
    name: str
    # global_field -> (source_field, transform)   << đây là schema mapping
    FIELD_MAP: dict[str, tuple[str, str]] = {}

    # Kết quả phụ của lần fetch gần nhất: lý do dừng, trang tiếp theo, có bị chặn không
    last_meta: dict = {}

    @abstractmethod
    def fetch(self, limit: int = 100, since=None, known: frozenset = frozenset()) -> list[dict]:
        """Trả bản ghi ở SCHEMA GỐC của nguồn.
        limit  — trần số tin lấy về lần này
        since  — chỉ lấy tin đăng từ ngày này trở đi (None = không giới hạn)
        known  — tập external_id đã có trong kho, để dừng sớm khi gặp lại"""

    def to_global(self, rec: dict) -> dict:
        out = {}
        for gfield, (sfield, tf) in self.FIELD_MAP.items():
            raw = rec.get(sfield)
            val = TRANSFORMS[tf](raw)
            if gfield == "salary":
                out["salary_min"], out["salary_max"] = val
            else:
                out[gfield] = val
        out["title_norm"] = norm(out.get("title", ""))
        return out

    def mapping_rows(self):
        """Xuất mapping ra bảng schema_mapping để quản lý/versioning."""
        return [(self.name, s, g, tf) for g, (s, tf) in self.FIELD_MAP.items()]
