#!/usr/bin/env python3
"""
Crawler cho hust.edu.vn — nguồn web có cấu trúc (NukeViet 4).

Trang này thân thiện với wrapper hơn hẳn các site tuyển dụng:
  * có sitemap index -> sitemap con theo từng chuyên mục, kèm <lastmod>;
  * trang bài viết nhúng sẵn microdata schema.org (headline, author,
    datePublished, dateModified, image) nên không phải đoán selector;
  * trang chuyên mục phân trang kiểu /vi/news/<cat>/page-N/.

Hai chế độ lấy URL bài viết:
  --mode sitemap  (mặc định) đọc sitemap, rẻ và đầy đủ, có lastmod để lọc theo ngày.
  --mode category duyệt trang chuyên mục, dùng khi cần đúng thứ tự hiển thị
                  hoặc khi sitemap chưa kịp cập nhật.

Ba nguyên tắc giữ cho crawl "có kỷ luật":
  1. Có ngân sách  — mỗi lần chạy chỉ đi tối đa `--limit` bài, `--pages` trang.
  2. Dừng sớm      — bài đã có trong state.json hoặc cũ hơn `--since` thì bỏ qua/dừng.
  3. Chịu được chặn — 403/429/5xx thì lùi theo cấp số nhân, hết lượt thì ném Blocked
                     kèm phần đã lấy được; dữ liệu tốt vẫn được ghi ra đĩa.

Ví dụ:
    python crawl_hust.py --limit 30
    python crawl_hust.py --sections news tuyen-sinh --since 2026-01-01 --limit 200
    python crawl_hust.py --mode category --category tin-tuc-su-kien --pages 3
    python crawl_hust.py --list-sitemaps
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import pathlib
import random
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser

import requests
from bs4 import BeautifulSoup

BASE = "https://hust.edu.vn"
UA = "hust-research-crawler/1.0 (nghien cuu mon IT5420; lien he: student@sis.hust.edu.vn)"
HEADERS = {"User-Agent": UA, "Accept-Language": "vi,en;q=0.8"}

ROOT = pathlib.Path(__file__).resolve().parent
CACHE = ROOT / "data" / "html"
OUT = ROOT / "data"

ART_ID = re.compile(r"-(\d+)\.html$")          # .../diem-chuan-...-656013.html
VN_DATE = re.compile(r"(\d{1,2})/(\d{1,2})/(\d{4})(?:\s+(\d{1,2}):(\d{2}))?")


class Blocked(Exception):
    """Nguồn chặn / lỗi mạng. Mang theo phần dữ liệu đã lấy được."""

    def __init__(self, msg, partial=None, retry_after=1800):
        super().__init__(msg)
        self.partial = partial or []
        self.retry_after = retry_after


# ---------------------------------------------------------------- tầng fetch
class Fetcher:
    """Một cửa duy nhất ra Internet: robots.txt, nhịp chậm, retry, cache đĩa."""

    def __init__(self, delay=1.5, timeout=20, retries=3, use_cache=True, verbose=True):
        self.delay, self.timeout, self.retries = delay, timeout, retries
        self.use_cache, self.verbose = use_cache, verbose
        self.session = requests.Session()
        self.session.headers.update(HEADERS)
        self._lock = threading.Lock()          # giữ nhịp chung cho mọi worker
        self._next_at = 0.0
        self._robots: RobotFileParser | None = None
        self.stats = {"hit": 0, "fetch": 0, "retry": 0, "fail": 0, "bytes": 0}

    # -- robots.txt ---------------------------------------------------------
    def allowed(self, url: str) -> bool:
        if self._robots is None:
            rp = RobotFileParser()
            rp.set_url(BASE + "/robots.txt")
            try:
                rp.read()
            except Exception:
                rp = False                     # không đọc được thì cho qua, vẫn đi chậm
            self._robots = rp
        return True if self._robots is False else self._robots.can_fetch(UA, url)

    # -- nhịp ---------------------------------------------------------------
    def _wait_turn(self):
        with self._lock:
            now = time.monotonic()
            if now < self._next_at:
                time.sleep(self._next_at - now)
            self._next_at = max(now, self._next_at) + self.delay * random.uniform(0.8, 1.3)

    def _cache_key(self, url: str) -> pathlib.Path:
        # cắt bớt cho tên file dễ đọc, nhưng gắn hash URL đầy đủ để không đụng key
        name = re.sub(r"\W+", "_", url.replace(BASE, ""))[-100:].strip("_") or "index"
        return CACHE / f"{name}.{hashlib.sha1(url.encode()).hexdigest()[:8]}.html"

    def get(self, url: str, force=False) -> str:
        key = self._cache_key(url)
        if self.use_cache and not force and key.exists():
            self.stats["hit"] += 1
            return key.read_text(encoding="utf-8")

        if not self.allowed(url):
            raise Blocked(f"robots.txt không cho phép: {url}", retry_after=86400)

        last = ""
        for attempt in range(self.retries):
            self._wait_turn()
            try:
                r = self.session.get(url, timeout=self.timeout)
            except requests.RequestException as e:
                last = f"lỗi mạng: {e}"
            else:
                if r.status_code == 200:
                    self.stats["fetch"] += 1
                    self.stats["bytes"] += len(r.content)
                    r.encoding = r.encoding or "utf-8"
                    key.parent.mkdir(parents=True, exist_ok=True)
                    key.write_text(r.text, encoding="utf-8")
                    return r.text
                if r.status_code in (403, 404, 410):
                    self.stats["fail"] += 1
                    raise Blocked(f"HTTP {r.status_code}: {url}", retry_after=86400)
                last = f"HTTP {r.status_code}"
                if r.status_code == 429:
                    time.sleep(int(r.headers.get("Retry-After", 30)))
            self.stats["retry"] += 1
            back = 2 ** attempt * 3
            self.log(f"    ! {last} — thử lại sau {back}s ({attempt + 1}/{self.retries})")
            time.sleep(back)

        self.stats["fail"] += 1
        raise Blocked(f"bỏ cuộc sau {self.retries} lần: {last} — {url}")

    def log(self, *a):
        if self.verbose:
            print(*a, file=sys.stderr, flush=True)


# ------------------------------------------------------------- tầng discovery
def sitemap_index(f: Fetcher) -> list[str]:
    """Danh sách sitemap con từ /sitemap.xml."""
    xml = f.get(BASE + "/sitemap.xml")
    return re.findall(r"<loc>\s*(.*?)\s*</loc>", xml)


def sitemap_urls(f: Fetcher, sections: list[str], lang="vi") -> list[dict]:
    """URL bài viết + lastmod, lọc theo chuyên mục (news, tuyen-sinh, ...)."""
    picked = [s for s in sitemap_index(f)
              if f"sitemap-{lang}." in s and any(f".{sec}.xml" in s for sec in sections)]
    if not picked:
        raise SystemExit(f"Không có sitemap nào khớp {sections}. Chạy --list-sitemaps để xem danh sách.")

    out, seen = [], set()
    for sm in picked:
        xml = f.get(sm)
        blocks = re.findall(r"<url>(.*?)</url>", xml, re.S)
        # vài sitemap con của trường đang rỗng (vd. van-ban) — báo rõ để khỏi tưởng lỗi
        f.log(f"  · sitemap {sm.rsplit('/', 1)[-1]}: {len(blocks)} url"
              + ("  (rỗng — chuyên mục này phải dùng --mode category)" if not blocks else ""))
        for block in blocks:
            loc = re.search(r"<loc>\s*(.*?)\s*</loc>", block)
            mod = re.search(r"<lastmod>\s*(.*?)\s*</lastmod>", block)
            if not loc or loc.group(1) in seen:
                continue
            seen.add(loc.group(1))
            out.append({"url": loc.group(1), "lastmod": mod.group(1) if mod else None})
    # mới nhất trước, để --limit luôn cắt phần đuôi cũ
    out.sort(key=lambda r: r["lastmod"] or "", reverse=True)
    return out


def category_urls(f: Fetcher, category: str, pages: int, lang="vi") -> list[dict]:
    """Duyệt /vi/news/<category>/page-N/ và bóc thẻ bài viết trên trang danh sách."""
    out, seen = [], set()
    for p in range(1, pages + 1):
        url = f"{BASE}/{lang}/news/{category}/" if p == 1 else f"{BASE}/{lang}/news/{category}/page-{p}/"
        f.log(f"  · trang {p}: {url}")
        try:
            soup = BeautifulSoup(f.get(url), "lxml")
        except Blocked as e:
            f.log(f"    ! dừng duyệt chuyên mục: {e}")
            break

        cards = soup.select("div.news_column div.panel-body")
        if not cards:
            f.log("    ! trang không còn bài, dừng")
            break

        for c in cards:
            a = c.select_one("h2 a") or c.select_one("a[href$='.html']")
            if not a or not a.get("href"):
                continue
            link = urljoin(BASE, a["href"])
            if link in seen:
                continue
            seen.add(link)
            meta = " ".join(x.get_text(" ", strip=True) for x in c.select(".text-muted"))
            img = c.select_one("img")
            out.append({
                "url": link,
                "lastmod": None,
                "list_title": a.get("title") or a.get_text(" ", strip=True),
                "list_date": _vn_date(meta),
                "list_views": _int(re.search(r"Đã xem:\s*([\d.,]+)", meta)),
                "list_thumb": urljoin(BASE, img["src"]) if img and img.get("src") else None,
            })
    return out


# ----------------------------------------------------------------- tầng parse
def art_id(url: str) -> str:
    """/vi/news/<bat-ky>/<slug>-656013.html -> '656013'.

    Cùng một bài có thể nằm ở nhiều chuyên mục nên đường dẫn khác nhau; con số
    cuối mới là khoá thật của bài viết. Đây chính là external_id để khử trùng.
    """
    m = ART_ID.search(urlparse(url).path)
    return m.group(1) if m else url


def _txt(node) -> str:
    return node.get_text(" ", strip=True) if node else ""


def _int(m):
    return int(re.sub(r"\D", "", m.group(1))) if m else None


def _vn_date(s: str) -> str | None:
    """'Chủ nhật - 09/08/2026 13:40' hoặc '18/08/2026 16:30:00' -> ISO."""
    m = VN_DATE.search(s or "")
    if not m:
        return None
    d, mo, y, hh, mm = m.groups()
    try:
        return dt.datetime(int(y), int(mo), int(d), int(hh or 0), int(mm or 0)).isoformat()
    except ValueError:
        return None


def _prop(soup, name: str) -> str:
    """Ưu tiên microdata schema.org, đây là phần ổn định nhất của trang."""
    t = soup.select_one(f"[itemprop='{name}']")
    if not t:
        return ""
    return (t.get("content") or t.get_text(" ", strip=True)).strip()


def _meta(soup, **attrs) -> str:
    t = soup.find("meta", attrs)
    return (t.get("content") or "").strip() if t else ""


def parse_article(html: str, url: str) -> dict:
    """HTML bài viết -> bản ghi phẳng ở schema gốc của nguồn."""
    soup = BeautifulSoup(html, "lxml")
    body = soup.select_one(".bodytext")
    lead = _txt(soup.select_one(".hometext"))

    # dòng "Tác giả: X" nằm cuối phần nội dung, tách ra khỏi body cho sạch
    author = _prop(soup, "author") or ""
    if body:
        for p in body.select("p"):
            if p.get_text(strip=True).startswith("Tác giả:"):
                author = author or p.get_text(strip=True)[len("Tác giả:"):].strip()
                p.decompose()

    text = _txt(body)
    breadcrumb = [_txt(x) for x in soup.select("[itemprop='itemListElement'] [itemprop='name']")]

    return {
        "id": art_id(url),
        "url": url,
        "lang": "en" if "/en/" in url else "vi",
        "title": _prop(soup, "headline") or _meta(soup, property="og:title") or _txt(soup.select_one("h1")),
        "section": _meta(soup, property="article:section") or (breadcrumb[-1] if breadcrumb else None),
        "breadcrumb": breadcrumb,
        "author": author or None,
        "published_at": _prop(soup, "datePublished") or _vn_date(_txt(soup.select_one("span.h5"))),
        "modified_at": _prop(soup, "dateModified") or None,
        "summary": lead or (text[:300] + "…" if len(text) > 300 else text),
        "content": text,
        "word_count": len(text.split()),
        "thumbnail": _prop(soup, "image") or _meta(soup, property="og:image") or None,
        "images": [urljoin(BASE, i["src"]) for i in (body.select("img[src]") if body else [])],
        "attachments": [urljoin(BASE, a["href"]) for a in (body.select("a[href]") if body else [])
                        if re.search(r"\.(pdf|docx?|xlsx?|pptx?|zip)$", a["href"], re.I)],
        "crawled_at": dt.datetime.now().isoformat(timespec="seconds"),
        "source": "hust.edu.vn",
    }


# ------------------------------------------------------------------ tầng ghi
def load_state(path: pathlib.Path) -> dict:
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"seen": {}, "runs": []}


def save_state(path: pathlib.Path, state: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")


def write_out(rows: list[dict], outdir: pathlib.Path, stem: str, fmt: str):
    outdir.mkdir(parents=True, exist_ok=True)
    written = []
    if fmt in ("jsonl", "both"):
        p = outdir / f"{stem}.jsonl"
        with p.open("w", encoding="utf-8") as fh:
            for r in rows:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        written.append(p)
    if fmt in ("csv", "both"):
        p = outdir / f"{stem}.csv"
        cols = ["id", "url", "lang", "title", "section", "author", "published_at",
                "modified_at", "summary", "word_count", "views", "thumbnail", "crawled_at"]
        with p.open("w", encoding="utf-8-sig", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
        written.append(p)
    return written


# --------------------------------------------------------------------- chạy
def crawl(args) -> list[dict]:
    f = Fetcher(delay=args.delay, timeout=args.timeout, retries=args.retries,
                use_cache=not args.no_cache, verbose=not args.quiet)
    state_path = OUT / "state.json"
    state = load_state(state_path) if args.incremental else {"seen": {}, "runs": []}
    since = dt.date.fromisoformat(args.since) if args.since else None

    f.log(f"[1/3] Tìm URL bài viết ({args.mode})…")
    if args.mode == "sitemap":
        cands = sitemap_urls(f, args.sections, args.lang)
    else:
        cands = category_urls(f, args.category, args.pages, args.lang)
    f.log(f"      {len(cands)} URL ứng viên")

    # lọc trước khi tải: trùng bài, đã crawl rồi, hoặc cũ hơn mốc --since
    todo, dup, skipped_seen, skipped_old = [], 0, 0, 0
    picked_ids = set()
    for c in cands:
        aid = art_id(c["url"])
        if aid in picked_ids:       # cùng bài đăng ở nhiều chuyên mục -> nhiều URL
            dup += 1
            continue
        picked_ids.add(aid)
        if args.incremental and aid in state["seen"] and state["seen"][aid] == (c["lastmod"] or ""):
            skipped_seen += 1
            continue
        if since and c["lastmod"]:
            try:
                if dt.date.fromisoformat(c["lastmod"][:10]) < since:
                    skipped_old += 1
                    continue
            except ValueError:
                pass
        todo.append(c)
        if len(todo) >= args.limit:
            break
    f.log(f"      bỏ qua {dup} URL trùng bài, {skipped_seen} bài đã có, "
          f"{skipped_old} bài cũ hơn {since} -> tải {len(todo)} bài")

    f.log(f"[2/3] Tải & bóc tách (delay {args.delay}s, {args.workers} luồng)…")
    rows: list[dict] = []
    lock = threading.Lock()

    def work(c):
        try:
            rec = parse_article(f.get(c["url"]), c["url"])
        except Blocked as e:
            f.log(f"    ! bỏ {c['url'][-60:]}: {e}")
            return
        # trang danh sách có thứ trang chi tiết không có (lượt xem) -> vá vào chỗ trống
        for src, dst in (("list_title", "title"), ("list_date", "published_at"),
                         ("list_views", "views"), ("list_thumb", "thumbnail")):
            if c.get(src) and not rec.get(dst):
                rec[dst] = c[src]
        rec["lastmod"] = c.get("lastmod")
        with lock:
            rows.append(rec)
            state["seen"][rec["id"]] = c.get("lastmod") or ""
            f.log(f"    [{len(rows):>4}/{len(todo)}] {(rec['title'] or '?')[:66]}")

    try:
        if args.workers > 1:
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                list(pool.map(work, todo))
        else:
            for c in todo:
                work(c)
    except KeyboardInterrupt:
        f.log("\n    ! Ctrl-C — ghi lại phần đã lấy được")

    f.log("[3/3] Ghi kết quả…")
    rows.sort(key=lambda r: r.get("published_at") or "", reverse=True)
    stem = args.out or f"hust_{args.mode}_{dt.date.today():%Y%m%d}"
    files = write_out(rows, OUT, stem, args.format)
    if args.incremental:
        state["runs"].append({"at": dt.datetime.now().isoformat(timespec="seconds"),
                              "got": len(rows), "mode": args.mode})
        save_state(state_path, state)

    f.log(f"\nXong: {len(rows)} bài | cache-hit {f.stats['hit']}, tải {f.stats['fetch']}, "
          f"retry {f.stats['retry']}, lỗi {f.stats['fail']}, "
          f"{f.stats['bytes'] / 1e6:.1f} MB")
    for p in files:
        f.log(f"  -> {p}")
    return rows


def main():
    ap = argparse.ArgumentParser(description="Crawler hust.edu.vn",
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("Ví dụ:")[-1])
    ap.add_argument("--mode", choices=["sitemap", "category"], default="sitemap")
    ap.add_argument("--sections", nargs="+", default=["news"],
                    help="tên sitemap con: news, tuyen-sinh, dao-tao, nghien-cuu, sinh-vien…")
    ap.add_argument("--category", default="tin-tuc-su-kien", help="slug chuyên mục cho --mode category")
    ap.add_argument("--pages", type=int, default=3, help="số trang duyệt ở --mode category")
    ap.add_argument("--lang", choices=["vi", "en"], default="vi")
    ap.add_argument("--limit", type=int, default=50, help="trần số bài mỗi lần chạy")
    ap.add_argument("--since", help="chỉ lấy bài lastmod >= ngày này (YYYY-MM-DD)")
    ap.add_argument("--incremental", action="store_true", help="bỏ qua bài đã crawl (data/state.json)")
    ap.add_argument("--delay", type=float, default=1.5, help="giãn cách giữa 2 request, giây")
    ap.add_argument("--workers", type=int, default=1, help="số luồng tải (nhịp vẫn dùng chung)")
    ap.add_argument("--retries", type=int, default=3)
    ap.add_argument("--timeout", type=int, default=20)
    ap.add_argument("--no-cache", action="store_true", help="không đọc HTML đã lưu ở data/html")
    ap.add_argument("--format", choices=["jsonl", "csv", "both"], default="both")
    ap.add_argument("--out", help="tên file kết quả, không kèm đuôi")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--list-sitemaps", action="store_true", help="in danh sách sitemap con rồi thoát")
    args = ap.parse_args()

    if args.list_sitemaps:
        f = Fetcher(delay=args.delay, verbose=False)
        for s in sitemap_index(f):
            print(s.rsplit("/", 1)[-1].replace("sitemap-", "").replace(".xml", ""), "\t", s)
        return

    if args.workers > 4:
        print("! workers > 4 là quá tay với một site trường học, hạ xuống 4", file=sys.stderr)
        args.workers = 4

    try:
        crawl(args)
    except Blocked as e:
        print(f"Dừng: {e} (thử lại sau ~{e.retry_after}s)", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
