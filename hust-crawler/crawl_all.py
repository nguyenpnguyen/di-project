#!/usr/bin/env python3
"""
Crawl toàn bộ hust.edu.vn -> HTML thô, base64, đóng trong JSONL.

Chưa parse gì cả. Mục tiêu duy nhất của tầng này là "lấy đủ và lấy nhiều":
gom được bao nhiêu trang thì đóng gói nguyên xi bấy nhiêu, việc bóc tách để
tầng sau làm trên kho đã tải — sửa selector không phải đụng lại mạng.

Vì sao không chỉ đọc sitemap: sitemap-vi.news.xml bị cắt đúng 1000 URL, trong
khi riêng chuyên mục tin-tuc-su-kien đã 295 trang x 6 bài ~ 1770 bài. Vài module
(van-ban, media, videoclips, san-pham-khoa-hoc-cong-nghe) còn có sitemap rỗng
hẳn. Nên ở đây dùng BFS: sitemap + menu chỉ là hạt giống, còn lại bò theo link.

Cách đi:
  1. Hạt giống  — 34 sitemap con + trang chủ /vi/ /en/ + toàn bộ link trong menu.
  2. Nở phân trang — thấy trang danh sách có ".../page-295/" thì đẩy thẳng
     page-2..page-295 vào hàng đợi, không lần mò từng nút "trang sau".
  3. Bò theo link — mọi link nội bộ trên trang đã tải đều vào hàng đợi.
  4. Khử trùng theo ĐOẠN CUỐI đường dẫn (slug kèm số), vì NukeViet cho một bài
     xuất hiện dưới nhiều chuyên mục; URL phụ ghi lại ở trường "aliases".
     Đừng khử theo riêng con số cuối: nó KHÔNG duy nhất — xem dedup_key().

Dừng giữa chừng vô tư: --resume đọc lại state.json và đi tiếp từ hàng đợi cũ.

Ví dụ:
    python crawl_all.py                          # crawl tất, ~1-1.5 giờ
    python crawl_all.py --max-pages 300          # chạy thử cho nhanh
    python crawl_all.py --resume                 # chạy tiếp lần trước
    python crawl_all.py --lang vi --no-gzip      # chỉ tiếng Việt, shard không nén
"""
from __future__ import annotations

import argparse
import base64
import collections
import datetime as dt
import gzip
import hashlib
import json
import pathlib
import random
import re
import signal
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin, urlparse, urlunparse
from urllib.robotparser import RobotFileParser

import requests
from bs4 import BeautifulSoup

import render

BASE = "https://hust.edu.vn"
HOST = "hust.edu.vn"
UA = "hust-research-crawler/1.0 (nghien cuu mon IT5420; lien he: student@sis.hust.edu.vn)"

ROOT = pathlib.Path(__file__).resolve().parent
RAW = ROOT / "data" / "raw"

# Menu là lối vào duy nhất của những module có sitemap rỗng. Danh sách này gắn
# với hust.edu.vn; site khác để trống, crawler tự bò từ trang chủ.
SEED_MENU = {
    "hust.edu.vn": ("/vi/van-ban/", "/vi/media/", "/vi/videoclips/", "/vi/events/",
                    "/vi/du-an/", "/vi/mangluoi/", "/vi/doi-tac-hoc-thuat/",
                    "/vi/doi-tac-doanh-nghiep/", "/vi/trao-doi-can-bo/",
                    "/vi/trao-doi-sinh-vien/", "/vi/san-pham-khoa-hoc-cong-nghe/",
                    "/vi/van-bang-so-huu-tri-tue/", "/vi/tai-nguyen-so/",
                    "/vi/lich-lam-viec/Truong-dai-hoc-BKHN/", "/vi/contact/"),
}


def set_site(host: str):
    """Trỏ crawler sang site khác. Mỗi site một thư mục kho riêng, không lẫn nhau."""
    global BASE, HOST, RAW
    HOST = host.lower().removeprefix("https://").removeprefix("http://").strip("/")
    BASE = f"https://{HOST}"
    RAW = ROOT / "data" / ("raw" if HOST == "hust.edu.vn" else f"raw-{HOST}")

ART_ID = re.compile(r"-(\d+)\.html$")

# Phân trang mỗi CMS một kiểu. Bắt bằng mẫu kèm khuôn dựng lại url, để đọc được
# số trang cuối ở site này thì sinh được cả dải ở site khác mà không sửa code.
PAGE_PATTERNS = [
    (re.compile(r"^(?P<a>.*/)page-(?P<n>\d+)/?$"), "{a}page-{n}/"),      # NukeViet
    (re.compile(r"^(?P<a>.*/)trang-(?P<n>\d+)/?$"), "{a}trang-{n}/"),    # vài site VN
    (re.compile(r"^(?P<a>.*/)p(?P<n>\d+)/?$"), "{a}p{n}/"),
    (re.compile(r"^(?P<a>.*?[?&])page=(?P<n>\d+)$"), "{a}page={n}"),     # WordPress, query
    (re.compile(r"^(?P<a>.*?[?&])paged=(?P<n>\d+)$"), "{a}paged={n}"),
    (re.compile(r"^(?P<a>.*/)page/(?P<n>\d+)/?$"), "{a}page/{n}/"),      # WordPress, path
]


def page_of(url: str):
    """url -> (khuôn có {n}, số trang, gốc) nếu là trang phân trang, không thì None.

    'gốc' là phần url trước chỗ đánh số, dùng để chắc chắn hai trang phân trang
    thuộc cùng một chuyên mục.
    """
    for pat, tpl in PAGE_PATTERNS:
        m = pat.match(url)
        if m:
            a = m.group("a")
            return tpl.replace("{a}", a), int(m.group("n")), a
    return None
ASSET = re.compile(r"\.(pdf|docx?|xlsx?|pptx?|zip|rar|jpe?g|png|gif|svg|webp|mp4|mp3|css|js|ico)$", re.I)
SKIP_PREFIX = ("/admin/", "/users/", "/data/", "/includes/", "/modules/", "/install/",
               "/statistics/", "/seek/", "/rss/", "/print/")
# đường dẫn có ngôn ngữ ở đầu (/vi/feeds/) nên phải bắt theo đoạn, không theo tiền tố
SKIP_SEG = re.compile(r"/(feeds|rss|seek|print|admin|users|statistics|shout|export)(/|$)")
# endpoint xuất file: không có đuôi .pdf trên url nhưng trả về pdf/docx
SKIP_QUERY = re.compile(r"(^|&)(download|submit)=1(&|$)")


# ------------------------------------------------------------------- tiện ích
def norm(url: str, base: str | None = None) -> str | None:
    """Chuẩn hoá URL để hai đường dẫn cùng trỏ một trang không bị đếm hai lần.

    base phải đọc lúc GỌI chứ không phải lúc định nghĩa hàm: --site đổi BASE sau
    khi module đã nạp, để mặc định là BASE thì đổi site xong vẫn ghép host cũ.
    """
    base = base or BASE
    if not url or url.startswith(("javascript:", "mailto:", "tel:", "#")):
        return None
    p = urlparse(urljoin(base, url.strip()))
    if p.scheme not in ("http", "https"):
        return None
    path = re.sub(r"//+", "/", p.path)
    # bỏ query rác của NukeViet, giữ lại query thật sự phân biệt nội dung
    q = "&".join(kv for kv in p.query.split("&")
                 if kv and not kv.split("=")[0] in ("fbclid", "utm_source", "utm_medium",
                                                    "utm_campaign", "gidzl", "PHPSESSID"))
    return urlunparse(("https", p.netloc.lower().replace("www.", ""), path, "", q, ""))


ALLOW_SUFFIX: str | None = None    # đặt bởi --allow-domain: nhận cả subdomain


def host_ok(netloc: str) -> bool:
    """Host này có thuộc phạm vi crawl không?

    Mặc định chỉ đúng một host, vì mỗi site một kho riêng thì thống kê mới sạch.
    --allow-domain mở ra cả họ, cần khi crawl theo file danh sách link trộn nhiều
    subdomain.
    """
    if ALLOW_SUFFIX:
        return netloc == ALLOW_SUFFIX or netloc.endswith("." + ALLOW_SUFFIX)
    return netloc == HOST


def in_scope(url: str) -> bool:
    p = urlparse(url)
    if not host_ok(p.netloc):                 # bỏ library./svbk./jst.vn... — site khác
        return False
    if ASSET.search(p.path) or SKIP_SEG.search(p.path) or SKIP_QUERY.search(p.query):
        return False
    return not any(p.path.startswith(s) for s in SKIP_PREFIX)


def art_id(url: str) -> str | None:
    """Con số cuối url. CẢNH BÁO: KHÔNG duy nhất — chỉ giữ làm metadata.

    Đo trên site: ba bài khác hẳn nhau cùng mang đuôi -654601.html
    ("Thông báo tuyển dụng 2023", "Crystal Associate Programme 2024",
    "SAHEP cùng Bách khoa..."), mỗi bài có og:url riêng, không redirect.
    Dùng số này làm khoá khử trùng là mất bài. Xem dedup_key().
    """
    m = ART_ID.search(urlparse(url).path)
    return m.group(1) if m else None


def dedup_key(url: str) -> str | None:
    """Khoá nhận dạng bài THẬT: đoạn cuối đường dẫn, tức slug kèm số.

    Kiểm chứng cả hai chiều trên site:
      cùng đoạn cuối, khác chuyên mục -> body sha1 giống hệt  => một bài
      cùng số, khác slug              -> tiêu đề & nội dung khác => bài khác
    Nên chuyên mục ở giữa url bị bỏ qua, còn slug thì phải khớp.
    """
    p = urlparse(url).path
    return p.rstrip("/").rsplit("/", 1)[-1] if ART_ID.search(p) else None


def kind_of(url: str) -> str:
    if page_of(url):
        return "listing-page"
    p = urlparse(url).path
    if ART_ID.search(p):
        return "article"
    if p.endswith("/"):
        return "listing"
    return "other"


# --------------------------------------------------------------- kho lưu shard
class Store:
    """Ghi JSONL theo lô. Mỗi dòng một trang, HTML thô nằm ở html_b64."""

    def __init__(self, outdir: pathlib.Path, shard_size=200, use_gzip=True):
        self.dir, self.shard_size, self.gz = outdir, shard_size, use_gzip
        self.dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._fh = None
        self._n_in_shard = 0
        self.n_total = 0
        self.bytes_raw = 0
        self.shards: list[str] = []
        # chạy tiếp thì đánh số shard nối sau shard cũ, không đè
        self._shard_no = len(list(self.dir.glob("pages-*.jsonl*")))

    def _roll(self):
        if self._fh:
            self._fh.close()
        self._shard_no += 1
        name = f"pages-{self._shard_no:04d}.jsonl" + (".gz" if self.gz else "")
        path = self.dir / name
        self._fh = gzip.open(path, "at", encoding="utf-8") if self.gz else path.open("a", encoding="utf-8")
        self._n_in_shard = 0
        self.shards.append(name)

    def add(self, rec: dict):
        with self._lock:
            if self._fh is None or self._n_in_shard >= self.shard_size:
                self._roll()
            self._fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            self._n_in_shard += 1
            self.n_total += 1
            self.bytes_raw += rec.get("size") or 0
            # flush từng dòng: gzip dùng Z_SYNC_FLUSH nên shard luôn đọc được tới
            # dòng cuối cùng, kể cả khi tiến trình bị kill cứng. Mẻ chạy đầu mất
            # gần 200 trang trong một shard 2 MB vì flush thưa 25 dòng một lần —
            # với nhịp 25 trang/phút thì tiết kiệm ấy chẳng đáng gì.
            self._fh.flush()

    def close(self):
        with self._lock:
            if self._fh:
                self._fh.close()
                self._fh = None


# ------------------------------------------------------------------- crawler
class Crawler:
    def __init__(self, args):
        self.a = args
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": UA, "Accept-Language": "vi,en;q=0.8"})
        ad = requests.adapters.HTTPAdapter(pool_connections=args.workers + 2,
                                           pool_maxsize=args.workers + 2)
        self.session.mount("https://", ad)

        self.store = Store(RAW, args.shard_size, not args.no_gzip)
        self.state_path = RAW / "state.json"

        self.lock = threading.Lock()
        self.pace = threading.Lock()
        self._next_at = 0.0
        self._pause_until = 0.0      # mốc cả đàn cùng nghỉ sau khi dính 429
        self._ok_streak = 0
        self.delay = args.delay      # nhịp hiện tại, tự co giãn quanh args.delay
        self.n_429 = 0
        self.n_rendered = 0

        self.frontier: collections.deque = collections.deque()
        self.queued: set[str] = set()        # URL đã từng vào hàng đợi
        self.done: dict[str, int] = {}       # URL -> HTTP status đã tải
        self.by_key: dict[str, str] = {}     # slug bài -> URL đầu tiên gặp
        self.origin: dict[str, str] = {}     # URL -> nơi PHÁT HIỆN ra nó lần đầu
        self.aliases: dict[str, list] = {}
        self.assets: set[str] = set()
        self.errors: list[dict] = []
        self.stop = threading.Event()
        self.t0 = time.time()
        self.n_start = 0
        self._parked: list = []      # hàng đợi gác lại khi chạy chế độ --seed-file

        self.robots = RobotFileParser()
        self.robots.set_url(BASE + "/robots.txt")
        try:
            self.robots.read()
        except Exception:
            self.robots = None

    # -- log ----------------------------------------------------------------
    def log(self, *a):
        if not self.a.quiet:
            print(*a, file=sys.stderr, flush=True)

    # -- hàng đợi -----------------------------------------------------------
    def push(self, url: str, depth: int, via: str = ""):
        u = norm(url)
        if not u or not in_scope(u) or u in self.queued:
            return
        if self.a.lang != "all":
            p = urlparse(u).path
            if p.startswith(("/vi/", "/en/")) and not p.startswith(f"/{self.a.lang}/"):
                return
        if self.robots and not self.robots.can_fetch(UA, u):
            return
        key = dedup_key(u)
        if key:                               # một bài nằm ở nhiều chuyên mục -> nhiều URL
            first = self.by_key.get(key)
            if first and first != u:
                self.aliases.setdefault(first, []).append(u)
                self.queued.add(u)
                return
            self.by_key[key] = u
        self.queued.add(u)
        # lineage: nhớ nơi phát hiện đầu tiên. Tải lại sau này (vd --seed-file)
        # không được xoá dấu vết ấy, nếu không mất luôn câu trả lời "bài này ở đâu ra"
        self.origin.setdefault(u, via)
        # Thứ tự đi quyết định mẻ crawl bị cắt ngang sẽ có gì trong tay:
        #   listing (mặc định) — phủ hết chuyên mục trước, phát hiện đủ url, nhưng
        #                        dừng sớm thì trong kho gần như chưa có bài nào;
        #   article            — có bài đọc được ngay, đổi lại phát hiện chậm hơn.
        k = kind_of(u)
        # --only listing: bài vẫn được ghi vào by_key/queued/origin (nên vẫn hiện
        # trong danh sách link) nhưng KHÔNG vào hàng đợi, tức không tốn request.
        # Dùng khi chỉ cần chốt xem site có những bài nào, chưa cần nội dung.
        #
        # Loại theo "chắc chắn là bài", KHÔNG theo "chắc chắn là danh sách":
        # kind "listing" nhận diện bằng dấu / cuối url, chỉ đúng với NukeViet.
        # CMS khác để url chuyên mục không có dấu / nên rơi vào "other"; lọc
        # ngược lại thì mất sạch, research.hust.edu.vn có 31 link hợp lệ mà
        # crawler dừng ngay sau trang chủ.
        if self.a.only == "listing" and k == "article":
            return
        first = k in ("listing", "listing-page") if self.a.prefer == "listing" else k == "article"
        (self.frontier.appendleft if first else self.frontier.append)((u, depth, via))

    def pop(self):
        with self.lock:
            return self.frontier.popleft() if self.frontier else None

    # -- mạng ---------------------------------------------------------------
    # hust.edu.vn có rate-limiter: đo được ngưỡng khoảng 20-25 request/phút, vượt
    # là 429 kèm Retry-After ~30s. Nên nhịp ở đây tự dò lấy: chậm lại mạnh khi bị
    # chặn, nhanh dần lên khi yên. Và khi dính 429 thì TOÀN BỘ luồng cùng nghỉ,
    # chứ không phải mỗi luồng ngủ riêng rồi lại ùa vào tiếp — đó là lỗi khiến
    # mẻ chạy đầu tiên tụt xuống 0,55 trang/s vì luôn có luồng đang chịu phạt.
    def _wait_turn(self):
        with self.pace:
            now = time.monotonic()
            wait = max(self._next_at - now, self._pause_until - now, 0.0)
            if wait:
                time.sleep(wait)
            self._next_at = time.monotonic() + self.delay * random.uniform(0.85, 1.15)

    def _slow_down(self, retry_after: float):
        with self.pace:
            self.delay = min(self.delay * 1.5, self.a.max_delay)
            self._pause_until = max(self._pause_until, time.monotonic() + retry_after)
            self._ok_streak = 0
            self.n_429 += 1

    def _speed_up(self):
        with self.pace:
            self._ok_streak += 1
            if self._ok_streak >= 25 and self.delay > self.a.delay:
                self.delay = max(self.a.delay, self.delay * 0.9)
                self._ok_streak = 0

    def fetch(self, url: str):
        """-> (response, error). 429 không tính là hỏng, chỉ là phải chờ."""
        err = ""
        tries = throttled = 0
        while tries < self.a.retries and throttled < self.a.max_429:
            self._wait_turn()
            try:
                r = self.session.get(url, timeout=self.a.timeout, verify=not self.a.insecure)
            except requests.RequestException as e:
                err = f"{type(e).__name__}: {e}"
                tries += 1
                time.sleep(2 ** tries)
                continue
            if r.status_code == 429:
                ra = r.headers.get("Retry-After", "")
                self._slow_down(float(ra) if ra.isdigit() else 30.0)
                throttled += 1
                err = "HTTP 429"
                continue
            if r.status_code < 400 or r.status_code in (404, 403, 410):
                self._speed_up()
                return r, ""
            err = f"HTTP {r.status_code}"
            tries += 1
            time.sleep(2 ** tries)
        return None, err

    # -- một trang ----------------------------------------------------------
    def maybe_render(self, url: str, status: int, html: str):
        """Trang requests lấy hụt thì tải lại bằng trình duyệt thật.

        -> (html mới, status mới) hoặc (None, None) nếu không cần / không được.
        """
        if self.a.render == "never":
            return None, None
        if self.a.render == "auto" and not render.looks_blocked(status, html):
            return None, None
        rhtml, rstatus, rerr = render.fetch(url, timeout=self.a.render_timeout * 1000)
        if rerr:
            self.log(f"    ~ render hỏng: {rerr[:90]}")
            return None, None
        with self.lock:
            self.n_rendered += 1
        self.log(f"    ~ render bằng trình duyệt: {len(rhtml) // 1024}KB  {url[-58:]}")
        return rhtml, rstatus or status

    def visit(self, url: str, depth: int, via: str):
        r, err = self.fetch(url)
        now = dt.datetime.now().isoformat(timespec="seconds")
        if r is None:
            # requests không lấy được (TLS hỏng, chặn…) — thử trình duyệt thật
            rhtml, rstatus = self.maybe_render(url, 0, "")
            if rhtml is None:
                with self.lock:
                    self.errors.append({"url": url, "error": err, "via": via, "at": now})
                self.log(f"    ! {err}  {url[-70:]}")
                return
            body, ctype, status, enc, final, rendered = (
                rhtml.encode("utf-8"), "text/html; charset=utf-8", rstatus, "utf-8", None, True)
        else:
            ctype = r.headers.get("Content-Type", "")
            body, status, enc = r.content, r.status_code, r.encoding
            final = r.url if r.url != url else None
            rendered = False
            if "html" in ctype.lower():
                rhtml, rstatus = self.maybe_render(url, status, r.text)
                if rhtml is not None:
                    body, status, enc, rendered = rhtml.encode("utf-8"), rstatus, "utf-8", True

        is_html = "html" in ctype.lower()
        rec = {
            "url": url,
            "final_url": final,
            "status": status,
            "content_type": ctype,
            "kind": kind_of(url),
            "article_id": art_id(url),
            "depth": depth,
            "via": self.origin.get(url, via),      # nơi phát hiện gốc, không phải lần tải này
            "fetched_at": now,
            "size": len(body),
            "sha1": hashlib.sha1(body).hexdigest(),
            "encoding": enc,
            "rendered": rendered,                  # True = lấy bằng trình duyệt, không phải requests
            "html_b64": base64.b64encode(body).decode("ascii") if is_html else None,
        }
        self.store.add(rec)
        with self.lock:
            self.done[url] = status

        if not is_html or status >= 400:
            return

        # bóc link để đi tiếp — dùng body đã lấy được, dù từ requests hay trình duyệt
        soup = BeautifulSoup(body.decode(enc or "utf-8", "replace"), "lxml")
        deep = depth >= self.a.max_depth     # hết ngân sách độ sâu: ghi sổ nhưng không đi tiếp
        found = pages = 0
        with self.lock:
            for a in soup.select("a[href]"):
                href = a["href"]
                u = norm(href, url)
                if not u:
                    continue
                if host_ok(urlparse(u).netloc) and ASSET.search(urlparse(u).path):
                    self.assets.add(u)       # file đính kèm: ghi sổ, không tải
                    continue
                if deep:
                    continue
                before = len(self.queued)
                self.push(u, depth + 1, url)
                found += len(self.queued) - before

            # nở phân trang: thấy page-295 thì đẩy luôn 2..295 vào hàng đợi
            if not deep and kind_of(url) in ("listing", "listing-page"):
                pages = self.expand_pagination(url, soup, depth)

        with self.lock:
            n = len(self.done)
        if n % 20 == 0 or found + pages > 30:
            rate = (n - self.n_start) / max(time.time() - self.t0, 1)
            left = len(self.frontier)
            eta = left / rate / 60 if rate else 0
            self.log(f"    [{n:>5}+{left:<5}] {rate * 60:4.1f} tr/phút  nhịp {self.delay:.1f}s  "
                     f"429:{self.n_429}  còn ~{eta / 60:.1f}h  d{depth} +{found}l"
                     f"{f'+{pages}p' if pages else ''}  {url[-52:]}")

    def expand_pagination(self, url: str, soup, depth: int) -> int:
        """Trang danh sách in sẵn link tới trang cuối -> đẩy thẳng cả dải.

        Không đoán kiểu phân trang: lấy đúng khuôn url mà chính trang này in ra,
        nên site dùng /page-2/, ?page=2 hay /page/2/ đều chạy như nhau.
        """
        here = page_of(url)
        stem = here[2] if here else url          # trang gốc thì chính nó là gốc
        found: dict[str, int] = {}               # khuôn -> số trang lớn nhất thấy được
        for a in soup.select("a[href]"):
            pg = page_of(norm(a["href"], url) or "")
            # chỉ nhận phân trang của CHÍNH chuyên mục này; khối "tin liên quan"
            # ở sidebar hay in phân trang của chuyên mục khác
            if not pg or pg[2] != stem:
                continue
            found[pg[0]] = max(found.get(pg[0], 0), pg[1])
        if not found:
            return 0
        before = len(self.queued)
        for tpl, mx in found.items():
            for n in range(2, min(mx, self.a.max_pages_per_cat) + 1):
                self.push(tpl.replace("{n}", str(n)), depth + 1, url)
        return len(self.queued) - before

    # -- hạt giống ----------------------------------------------------------
    def seed(self):
        self.log("[1/3] Gieo hạt giống…")
        if self.a.from_file:
            # Crawl đúng danh sách link cho sẵn. Không --follow thì đẩy ở độ sâu
            # tối đa nên visit() lưu xong là dừng, không nở link — dùng khi đã có
            # sẵn danh sách url và chỉ cần nội dung của đúng ngần ấy trang.
            depth = 0 if self.a.follow else self.a.max_depth
            urls = [u.strip() for u in pathlib.Path(self.a.from_file).read_text(
                encoding="utf-8").splitlines() if u.strip() and not u.startswith("#")]
            bad = 0
            for u in urls:
                n = norm(u)
                if not n or not in_scope(n):
                    bad += 1
                    continue
                self.done.pop(n, None)
                self.queued.discard(n)
                self.push(n, depth, "from-file")
            self.log(f"      {len(urls)} dòng -> {len(self.frontier)} url vào hàng đợi"
                     + (f", {bad} dòng ngoài phạm vi bị bỏ" if bad else ""))
            if bad and not ALLOW_SUFFIX:
                self.log("      (url khác host bị loại — thêm --allow-domain hust.edu.vn nếu muốn nhận)")
            return

        if self.a.seed_file:
            # chế độ vá: chỉ tải đúng danh sách URL cho sẵn, không bò tiếp.
            # Đẩy ở depth cao nhất nên visit() lưu xong là dừng, không nở link.
            urls = [u.strip() for u in pathlib.Path(self.a.seed_file).read_text().split() if u.strip()]
            for u in urls:
                n = norm(u)
                if n:
                    self.done.pop(n, None)       # tải lại kể cả khi state bảo đã xong
                    self.queued.discard(n)
                    self.push(n, self.a.max_depth, "seed-file")
            self.log(f"      vá {len(self.frontier)} url từ {self.a.seed_file}")
            return

        self.push(f"{BASE}/", 0, "seed")
        for path in ("/vi/", "/en/", "/70year/index.html"):
            if HOST == "hust.edu.vn":
                self.push(BASE + path, 0, "seed")
        for path in SEED_MENU.get(HOST, ()):
            self.push(BASE + path, 0, "seed-menu")
        for extra in self.a.seed_url or []:
            self.push(extra, 0, "seed-cli")

        r, _ = self.fetch(BASE + "/sitemap.xml")
        if not r or "<loc>" not in r.text:
            self.log("      không có sitemap dùng được, chỉ bò từ trang chủ")
            self.log(f"      hàng đợi {len(self.frontier)} url")
            return
        locs = re.findall(r"<loc>\s*(.*?)\s*</loc>", r.text)
        # sitemap phẳng (urlset) thì đây đã là url trang, không phải sitemap con —
        # đừng đi fetch từng cái như file xml, tốn mỗi url một request vô ích
        if "<sitemapindex" not in r.text:
            for loc in locs:
                self.push(loc, 1, "sitemap")
            self.log(f"      sitemap phẳng: {len(locs)} url -> hàng đợi {len(self.frontier)}")
            return

        sitemaps = locs
        self.log(f"      {len(sitemaps)} sitemap con")
        n_sm = 0
        for sm in sitemaps:
            if self.a.lang != "all" and f"sitemap-{self.a.lang}." not in sm:
                continue
            rr, _ = self.fetch(sm)
            if not rr:
                continue
            locs = re.findall(r"<loc>\s*(.*?)\s*</loc>", rr.text)
            for loc in locs:
                self.push(loc, 1, sm)
            n_sm += len(locs)
            self.log(f"      · {sm.rsplit('/', 1)[-1]}: {len(locs)} url")

        self.log(f"      {n_sm} url từ sitemap -> hàng đợi {len(self.frontier)} url")

    # -- state --------------------------------------------------------------
    def save_state(self):
        with self.lock:
            state = {
                "at": dt.datetime.now().isoformat(timespec="seconds"),
                "done": self.done,
                "frontier": list(self.frontier) + self._parked,
                "queued": sorted(self.queued),
                "by_key": self.by_key,
                "origin": self.origin,
                "aliases": self.aliases,
                "assets": sorted(self.assets),
                "errors": self.errors[-500:],
                "pages_written": self.store.n_total,
                "shards": self.store.shards,
            }
        tmp = self.state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self.state_path)

    def load_state(self) -> bool:
        if not self.state_path.exists():
            return False
        s = json.loads(self.state_path.read_text(encoding="utf-8"))
        self.done = s.get("done", {})
        self.queued = set(s.get("queued", []))
        # state cũ dùng khoá sai (chỉ con số); có by_key thì mới tin được
        self.by_key = s.get("by_key", {})
        self.origin = s.get("origin", {})
        self.aliases = s.get("aliases", {})
        self.assets = set(s.get("assets", []))
        self.errors = s.get("errors", [])
        self.frontier = collections.deque(tuple(x) for x in s.get("frontier", []))
        self.log(f"      tiếp tục: {len(self.done)} trang đã tải, "
                 f"{len(self.frontier)} url còn trong hàng đợi")
        return True

    # -- vòng chạy ----------------------------------------------------------
    def run(self):
        # LUÔN nạp state nếu file có, không chỉ khi --resume. Trước đây chạy
        # --from-file mà quên --resume thì state khởi tạo rỗng, rồi save_state
        # cuối mẻ ghi đè lên file — xoá sạch hàng đợi 4.220 url đã dựng công phu.
        # --resume giờ chỉ quyết định có gieo lại hạt giống hay không.
        loaded = self.load_state() if self.state_path.exists() else False
        if self.a.seed_file or self.a.from_file:
            # gác hàng đợi cũ sang một bên chứ không xoá, nếu không lần --resume
            # sau sẽ mất sạch những url đã phát hiện được
            self._parked = list(self.frontier)
            self.frontier.clear()
            self.seed()
        elif not loaded:
            self.seed()

        if self.a.only == "listing":
            # hàng đợi cũ phần lớn là bài; gác chúng sang một bên chứ đừng vứt,
            # nếu không lần chạy sau mất sạch danh mục đã phát hiện được
            keep = [x for x in self.frontier if kind_of(x[0]) != "article"]
            self._parked += [x for x in self.frontier if kind_of(x[0]) == "article"]
            self.frontier = collections.deque(keep)
            self.log(f"      --only listing: đi {len(keep)} trang danh sách, "
                     f"gác lại {len(self._parked)} url khác")

        self.log(f"[2/3] Bò theo link (nhịp khởi điểm {self.a.delay}s x {self.a.workers} luồng, "
                 f"tự dò lại theo 429, trần {self.a.max_pages or '∞'} trang)…")
        t0 = self.t0 = time.time()
        self.n_start = len(self.done)     # trang của phiên trước, không tính vào tốc độ
        budget = self.a.max_pages or float("inf")

        active = [0]          # số luồng đang tải dở; hàng đợi rỗng chưa chắc là hết việc

        def worker():
            while not self.stop.is_set():
                item = self.pop()
                if item is None:
                    with self.lock:
                        busy = active[0]
                    if not busy:                         # rỗng và không ai đang tải -> hết thật
                        return
                    time.sleep(0.3)                      # chờ luồng khác nạp thêm link
                    continue
                url, depth, via = item
                if url in self.done:
                    continue
                with self.lock:
                    active[0] += 1
                try:
                    self.visit(url, depth, via)
                except Exception as e:                   # một trang hỏng không được giết cả mẻ
                    with self.lock:
                        self.errors.append({"url": url, "error": f"{type(e).__name__}: {e}"})
                finally:
                    with self.lock:
                        active[0] -= 1
                if len(self.done) >= budget:
                    self.stop.set()
                if self.store.n_total % self.a.checkpoint == 0:
                    self.save_state()

        try:
            with ThreadPoolExecutor(max_workers=self.a.workers) as pool:
                for fut in [pool.submit(worker) for _ in range(self.a.workers)]:
                    fut.result()
        except KeyboardInterrupt:
            self.log("\n    ! Ctrl-C — đóng shard và ghi state, chạy lại với --resume")
            self.stop.set()

        self.store.close()
        self.save_state()
        dur = time.time() - t0

        self.log("[3/3] Tổng kết")
        by_kind = collections.Counter(kind_of(u) for u in self.done)
        manifest = {
            "site": BASE,
            "finished_at": dt.datetime.now().isoformat(timespec="seconds"),
            "duration_sec": round(dur),
            "pages": self.store.n_total,
            "raw_bytes": self.store.bytes_raw,
            "by_kind": dict(by_kind),
            "distinct_articles": len(self.by_key),
            "alias_urls": sum(len(v) for v in self.aliases.values()),
            "assets_seen": len(self.assets),
            "throttle_429": self.n_429,
            "rendered_pages": self.n_rendered,
            "final_delay_sec": round(self.delay, 2),
            "pages_per_min": round(self.store.n_total / max(dur / 60, 1e-9), 1),
            "errors": len(self.errors),
            "frontier_left": len(self.frontier),
            "shards": self.store.shards,
        }
        (RAW / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1),
                                          encoding="utf-8")
        # GỘP, đừng đè: mẻ chạy --seed-file không bóc link nên self.assets rỗng,
        # ghi đè sẽ xoá sạch danh sách read_raw.py --assets đã dựng từ cả kho
        ap = RAW / "assets.txt"
        old = set(ap.read_text(encoding="utf-8").split()) if ap.exists() else set()
        ap.write_text("\n".join(sorted(old | self.assets)), encoding="utf-8")
        self.log(json.dumps(manifest, ensure_ascii=False, indent=1))
        self.log(f"\nXong {self.store.n_total} trang / {self.store.bytes_raw / 1e6:.0f} MB HTML thô "
                 f"trong {dur / 60:.1f} phút -> {RAW}")
        if self.frontier:
            site = "" if HOST == "hust.edu.vn" else f" --site {HOST}"
            self.log(f"Còn {len(self.frontier)} url chưa đi. Chạy tiếp: "
                     f"python crawl_all.py --resume{site}")


def main():
    ap = argparse.ArgumentParser(description="Crawl toàn site hust.edu.vn ra HTML base64",
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("Ví dụ:")[-1])
    ap.add_argument("--site", default="hust.edu.vn",
                    help="host cần crawl, vd sinhvien.hust.edu.vn. Mỗi site một kho riêng "
                         "data/raw-<host>; kiểu phân trang tự nhận, không cần sửa code")
    ap.add_argument("--seed-url", nargs="*", help="url hạt giống thêm, khi site không có sitemap")
    ap.add_argument("--from-file", help="crawl đúng danh sách url trong file này (mỗi dòng một url)")
    ap.add_argument("--follow", action="store_true",
                    help="với --from-file: bò tiếp theo link tìm thấy, mặc định chỉ tải đúng danh sách")
    ap.add_argument("--allow-domain",
                    help="nhận mọi host thuộc tên miền này, vd hust.edu.vn cho cả subdomain")
    ap.add_argument("--render", choices=["never", "auto", "always"], default="never",
                    help="tải lại bằng trình duyệt thật: auto khi trang có vẻ bị chặn "
                         "hoặc rỗng do JS, always cho mọi trang")
    ap.add_argument("--render-timeout", type=int, default=30, help="giây, cho mỗi trang render")
    ap.add_argument("--insecure", action="store_true",
                    help="bỏ kiểm chứng chỉ TLS. Vài subdomain của trường không gửi kèm "
                         "chứng chỉ trung gian nên requests từ chối; chỉ bật khi đã biết "
                         "site đó là site thật, và biết là mất bảo đảm chống giả mạo")
    ap.add_argument("--max-pages", type=int, default=0, help="trần số trang, 0 = không giới hạn")
    ap.add_argument("--max-depth", type=int, default=6)
    ap.add_argument("--max-pages-per-cat", type=int, default=400,
                    help="trần số trang phân trang nở ra cho mỗi chuyên mục")
    ap.add_argument("--lang", choices=["vi", "en", "all"], default="all")
    ap.add_argument("--only", choices=["all", "listing"], default="all",
                    help="'listing' chỉ tải trang danh sách để chốt danh mục url; "
                         "bài vẫn được ghi nhận nhưng không tải nội dung")
    ap.add_argument("--prefer", choices=["listing", "article"], default="listing",
                    help="đi trang danh sách trước (phủ chuyên mục) hay bài viết trước "
                         "(dừng sớm vẫn có bài đọc được)")
    ap.add_argument("--delay", type=float, default=2.5,
                    help="nhịp nhanh nhất cho phép, giây/request, chung mọi luồng "
                         "(site chặn quanh 20-25 req/phút nên đừng hạ dưới 2)")
    ap.add_argument("--max-delay", type=float, default=30.0, help="trần khi bị chặn liên tục")
    ap.add_argument("--max-429", type=int, default=8, help="số lần chịu 429 cho một URL")
    ap.add_argument("--workers", type=int, default=2,
                    help="nhịp đã bị khoá chung nên nhiều luồng không nhanh hơn, chỉ để giấu độ trễ")
    ap.add_argument("--retries", type=int, default=3)
    ap.add_argument("--timeout", type=int, default=25)
    ap.add_argument("--shard-size", type=int, default=200, help="số trang mỗi file shard")
    ap.add_argument("--no-gzip", action="store_true", help="shard để .jsonl trần thay vì .jsonl.gz")
    ap.add_argument("--checkpoint", type=int, default=50, help="cứ bấy nhiêu trang thì ghi state")
    ap.add_argument("--resume", action="store_true", help="đi tiếp từ state.json")
    ap.add_argument("--seed-file", help="chế độ vá: chỉ tải đúng danh sách url trong file này "
                                        "(dùng với read_raw.py --check)")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()
    set_site(args.site)             # phải gọi TRƯỚC khi dựng Crawler: RAW đổi theo site
    if args.allow_domain:
        globals()["ALLOW_SUFFIX"] = args.allow_domain.lower().strip(". ")
    if args.render != "never":
        ok, why = render.available()
        if not ok:
            print(f"! --render {args.render} nhưng {why}", file=sys.stderr)
            print("  vẫn chạy tiếp, chỉ là không render được trang nào", file=sys.stderr)

    if args.workers > 8:
        print("! workers > 8 là quá tay với web trường, hạ xuống 8", file=sys.stderr)
        args.workers = 8

    c = Crawler(args)

    # kill mềm (pkill, tắt máy) cũng phải kịp đóng shard và ghi state
    def bye(signum, frame):
        print(f"\n! nhận tín hiệu {signum} — đóng shard, ghi state rồi thoát", file=sys.stderr)
        c.stop.set()
    for sig in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, bye)

    c.run()


if __name__ == "__main__":
    main()
