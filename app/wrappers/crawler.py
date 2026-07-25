"""
Crawler cho TopCV / Vieclam24h — chỉ dùng cho mục đích nghiên cứu.

Ba nguyên tắc để không bao giờ phải "crawl all":
  1. Có ngân sách  — mỗi lần chạy chỉ được đi tối đa `limit` tin và `MAX_PAGES` trang.
  2. Dừng sớm      — gặp liên tiếp `STOP_AFTER_SEEN` tin đã có trong kho, hoặc tin cũ hơn
                     mốc `since`, thì dừng luôn vì phía sau chắc chắn còn cũ hơn nữa.
  3. Chịu được chặn — gặp 403/429/captcha thì ném CrawlBlocked kèm phần đã lấy được;
                     tầng ingest vẫn lưu phần đó, ghi nhận trạng thái "partial" và lưu
                     con trỏ trang để lần sau chạy tiếp từ chỗ dừng.
"""
import time, random, re, pathlib, datetime as dt
import requests
from urllib.robotparser import RobotFileParser
from bs4 import BeautifulSoup

CACHE = pathlib.Path(__file__).resolve().parents[2] / "data" / "html"
CACHE.mkdir(parents=True, exist_ok=True)

HEADERS = {"User-Agent": "Mozilla/5.0 (research-crawler; lien he: student@hust.edu.vn)",
           "Accept-Language": "vi,en;q=0.8"}
DELAY = (2.0, 4.0)        # nghỉ ngẫu nhiên giữa hai request, giây
MAX_PAGES = 5             # trần số trang mỗi lần chạy
STOP_AFTER_SEEN = 10      # gặp bấy nhiêu tin đã biết liên tiếp thì dừng
TIMEOUT = 15
_robots: dict[str, RobotFileParser] = {}


class CrawlBlocked(Exception):
    """Nguồn chặn hoặc giới hạn tần suất. Mang theo phần dữ liệu đã lấy được."""
    def __init__(self, msg, partial=None, retry_after=3600):
        super().__init__(msg)
        self.partial = partial or []
        self.retry_after = retry_after


def allowed(url: str) -> bool:
    """Tôn trọng robots.txt. Không đọc được robots thì cho qua nhưng vẫn giữ nhịp chậm."""
    host = re.match(r"(https?://[^/]+)", url).group(1)
    if host not in _robots:
        rp = RobotFileParser()
        rp.set_url(host + "/robots.txt")
        try:
            rp.read()
        except Exception:
            rp = None
        _robots[host] = rp
    rp = _robots[host]
    return True if rp is None else rp.can_fetch(HEADERS["User-Agent"], url)


def get(url: str, use_cache=True) -> str:
    key = CACHE / (re.sub(r"\W+", "_", url)[-80:] + ".html")
    if use_cache and key.exists():
        return key.read_text(encoding="utf-8")
    if not allowed(url):
        raise CrawlBlocked(f"robots.txt không cho phép: {url}", retry_after=86400)
    time.sleep(random.uniform(*DELAY))
    try:
        r = requests.get(url, headers=HEADERS, timeout=TIMEOUT)
    except requests.RequestException as e:
        raise CrawlBlocked(f"lỗi mạng: {e}", retry_after=600)
    if r.status_code in (403, 429, 503):
        wait = int(r.headers.get("Retry-After", 1800))
        raise CrawlBlocked(f"nguồn trả {r.status_code}", retry_after=wait)
    if r.status_code != 200:
        raise CrawlBlocked(f"HTTP {r.status_code}", retry_after=600)
    if "captcha" in r.text[:4000].lower():
        raise CrawlBlocked("gặp captcha", retry_after=3600)
    key.write_text(r.text, encoding="utf-8")
    return r.text


def _txt(node) -> str:
    return node.get_text(" ", strip=True) if node else ""


def parse_date(s: str):
    """'12/06/2026', '2026-06-12', 'Hôm nay', '3 ngày trước' -> date"""
    s = (s or "").strip().lower()
    today = dt.date.today()
    if "hôm nay" in s or "today" in s:
        return today
    m = re.search(r"(\d+)\s*(ngày|day)", s)
    if m:
        return today - dt.timedelta(days=int(m.group(1)))
    for fmt in ("%d/%m/%Y", "%Y-%m-%d", "%d-%m-%Y"):
        try:
            return dt.datetime.strptime(re.sub(r"[^\d/\-]", "", s)[:10], fmt).date()
        except ValueError:
            continue
    return None


def _harvest(list_url_fn, parse_card, limit, since, known, start_page):
    """Vòng lặp chung: đi từng trang, dừng theo ngân sách / mốc ngày / tin đã biết."""
    out, seen_streak, page = [], 0, start_page
    last_page = start_page
    try:
        while len(out) < limit and page < start_page + MAX_PAGES:
            html = get(list_url_fn(page))
            soup = BeautifulSoup(html, "html.parser")
            cards = parse_card(soup)
            if not cards:
                break
            for rec in cards:
                ext = str(rec.get("__id") or "")
                if ext and ext in known:
                    seen_streak += 1
                    if seen_streak >= STOP_AFTER_SEEN:
                        return out, page, "đủ tin cũ liên tiếp, dừng sớm"
                    continue
                seen_streak = 0
                d = parse_date(rec.get("__date", ""))
                if since and d and d < since:
                    return out, page, f"đã chạm mốc ngày {since}"
                rec.pop("__id", None); rec.pop("__date", None)
                out.append(rec)
                if len(out) >= limit:
                    return out, page, "đủ số tin yêu cầu"
            last_page = page
            page += 1
    except CrawlBlocked as e:
        e.partial = out
        e.args = (f"{e.args[0]} (đã lấy được {len(out)} tin, dừng ở trang {last_page})",)
        raise
    return out, page, "hết trang trong ngân sách"


def crawl_topcv(limit=50, since=None, known=frozenset(), start_page=1):
    def url(p):
        return f"https://www.topcv.vn/tim-viec-lam-it?page={p}"

    def cards(soup):
        out = []
        for c in soup.select("div.job-item-search-result"):
            a = c.select_one("h3 a")
            if not a:
                continue
            jid = c.get("data-job-id") or (a.get("href") or "")[-12:]
            out.append({
                "__id": jid, "__date": _txt(c.select_one(".time")),
                "job_id": jid, "detail_url": a.get("href"), "title": _txt(a),
                "company_name": _txt(c.select_one(".company-name")),
                "work_place": _txt(c.select_one(".address")),
                "salary_text": _txt(c.select_one(".title-salary")),
                "experience_text": _txt(c.select_one(".exp")),
                "job_type": "Full-time",
                "job_description": _txt(c.select_one(".job-description")),
                "candidate_requirement": "",
                "tags": [_txt(t) for t in c.select(".job-tag, .skill")],
                "created_date": _txt(c.select_one(".time")),
            })
        return out
    return _harvest(url, cards, limit, since, known, start_page)


def crawl_vieclam24h(limit=50, since=None, known=frozenset(), start_page=1):
    def url(p):
        return f"https://vieclam24h.vn/tim-kiem-viec-lam-nhanh?page={p}"

    def cards(soup):
        out = []
        for c in soup.select("a[href*='/viec-lam/']"):
            jid = (c.get("href") or "")[-14:]
            out.append({
                "__id": jid, "__date": _txt(c.select_one(".date")),
                "id": jid, "link": c.get("href"),
                "job_name": _txt(c.select_one("h3, .job-title")),
                "employer": _txt(c.select_one(".company, .employer")),
                "noi_lam_viec": _txt(c.select_one(".location, .address")),
                "thu_nhap": _txt(c.select_one(".salary")),
                "kinh_nghiem": _txt(c.select_one(".experience")),
                "hinh_thuc": "", "mo_ta": _txt(c.select_one(".description")),
                "yeu_cau": "", "ky_nang": [],
                "ngay_dang": _txt(c.select_one(".date")),
            })
        return out
    return _harvest(url, cards, limit, since, known, start_page)
