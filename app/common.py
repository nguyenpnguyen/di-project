import os, re, hashlib, unicodedata
import psycopg
from psycopg.rows import dict_row

PG_DSN = os.getenv("PG_DSN", "postgresql://di:di@localhost:5432/jobdi")
OS_URL = os.getenv("OS_URL", "http://localhost:9200")


def conn():
    return psycopg.connect(PG_DSN, row_factory=dict_row, autocommit=True)


def norm(s: str) -> str:
    """Chuẩn hoá chuỗi để so khớp: bỏ dấu, lowercase, gom khoảng trắng."""
    if not s:
        return ""
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn").replace("đ", "d").replace("Đ", "D")
    s = re.sub(r"[^a-zA-Z0-9+#. ]", " ", s.lower())
    return re.sub(r"\s+", " ", s).strip()


def content_hash(d: dict) -> str:
    return hashlib.sha256(repr(sorted(d.items())).encode()).hexdigest()[:32]


# ---- Data cleaning: chuẩn hoá địa danh (entity matching thủ công, mức 1) ----
LOCATION_ALIASES = {
    "Ha Noi": ["ha noi", "hn", "hanoi", "thanh pho ha noi"],
    "Ho Chi Minh City": ["ho chi minh", "tp hcm", "tphcm", "hcm", "sai gon", "saigon", "tp. ho chi minh"],
    "Da Nang": ["da nang", "dn", "danang"],
    "Remote": ["remote", "lam viec tu xa", "work from home", "wfh"],
}


def canon_location(raw: str) -> str | None:
    n = norm(raw)
    for canon, al in LOCATION_ALIASES.items():
        if any(a in n for a in al):
            return canon
    return raw.strip() or None


SALARY_RE = re.compile(r"(\d+[\d.,]*)\s*(tr|trieu|triệu|m|k|usd)?", re.I)


def parse_salary(raw: str):
    """'15 - 25 triệu' -> (15e6, 25e6). Trả (None, None) nếu thoả thuận."""
    if not raw or "thoa thuan" in norm(raw) or "negotiab" in norm(raw):
        return None, None
    nums = [m for m in SALARY_RE.findall(raw.replace(".", "")) if m[0]]
    if not nums:
        return None, None
    unit = (nums[0][1] or "tr").lower()
    mul = {"tr": 1e6, "trieu": 1e6, "triệu": 1e6, "m": 1e6, "k": 1e3, "usd": 25_000}.get(unit, 1)
    vals = [float(n[0]) * mul for n in nums[:2]]
    return (vals[0], vals[-1])


def parse_exp(raw: str):
    m = re.search(r"(\d+)", raw or "")
    return float(m.group(1)) if m else None
