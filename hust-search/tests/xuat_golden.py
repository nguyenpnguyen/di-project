"""Chụp mốc từ bản Python để bản Java so khớp (KE-HOACH-PORT-JAVA.md, giai đoạn 0).

Script dùng một lần, xoá ở giai đoạn 9. Chạy trong container `api` đang sống (có bs4, lxml,
pdfminer… và kho ở /crawler/data), ghi ra thư mục tạm rồi `docker cp` ra máy:

    docker exec -i -w /app hust-search-api-1 python - kho   /tmp/golden < tests/xuat_golden.py
    docker exec -i -w /app hust-search-api-1 python - tep   /tmp/golden < tests/xuat_golden.py
    docker exec -i -w /app hust-search-api-1 python - robots /tmp/golden < tests/xuat_golden.py
    docker exec -i -w /app hust-search-api-1 python - api   /tmp/golden < tests/xuat_golden.py

Bốn bước độc lập: `kho` (url + bóc tách + khuôn), `tep`, `robots`, `api`.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import pathlib
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, "/app")
sys.path.insert(0, "/crawler")

DATA = pathlib.Path("/crawler/data")
OUT = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "/tmp/golden")
OUT.mkdir(parents=True, exist_ok=True)


def dump_gz(path: pathlib.Path, rows) -> int:
    n = 0
    with gzip.open(path, "wt", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
            n += 1
    return n


# ----------------------------------------------------------------------- kho
CA_BIEN_URL = [
    # (input, base)
    ("javascript:void(0)", None), ("mailto:a@hust.edu.vn", None), ("tel:0123", None), ("#top", None),
    ("", None), ("   ", None), ("//hust.edu.vn/vi/a-1.html", None), ("//www.hust.edu.vn/vi/a-1.html", None),
    ("?page=2", "https://hust.edu.vn/vi/news/"), ("#", "https://hust.edu.vn/vi/news/"),
    ("  /vi/news/a-1.html  ", None), ("/vi/news/a b-1.html", None), ("/vi/tin-tức/bài-1.html", None),
    ("/vi/a|b-1.html", None), ("http://www.hust.edu.vn/vi/", None), ("HTTP://HUST.EDU.VN/Vi/A-1.html", None),
    ("https://www.hust.edu.vn:8443/vi/", None), ("https://hust.edu.vn//vi///news//a-1.html", None),
    ("https://hust.edu.vn/vi/a-1.html?utm_source=x&id=3&fbclid=abc&PHPSESSID=z&utm_campaign=c", None),
    ("https://hust.edu.vn/vi/a-1.html?gidzl=1&utm_medium=m", None),
    ("https://hust.edu.vn/vi/a-1.html?a=1&&b=2&", None), ("https://hust.edu.vn/vi/a-1.html#frag", None),
    ("https://www.sub.www.hust.edu.vn/x", None), ("https://wwwhust.edu.vn/x", None),
    ("https://hust.edu.vn/vi/news/page-12/", None), ("https://hust.edu.vn/vi/news/trang-3/", None),
    ("https://hust.edu.vn/vi/news/p4/", None), ("https://hust.edu.vn/vi/news/?page=5", None),
    ("https://hust.edu.vn/vi/news/?paged=6", None), ("https://hust.edu.vn/vi/news/page/7/", None),
    ("https://hust.edu.vn/vi/news/", None), ("https://hust.edu.vn/vi/news/x-654601.html", None),
    ("https://hust.edu.vn/vi/a/x-654601.html/", None), ("https://hust.edu.vn/uploads/f.pdf", None),
    ("ftp://hust.edu.vn/a", None), ("../b-2.html", "https://hust.edu.vn/vi/news/c/a-1.html"),
    ("./b-2.html", "https://hust.edu.vn/vi/news/c/a-1.html"), ("b-2.html", "https://svbk.hust.edu.vn/x/y"),
    ("/z/", "https://www.svbk.hust.edu.vn/x/y"), ("https://hust.edu.vn", None), ("hust.edu.vn/vi/", None),
]


def buoc_kho():
    import crawl_all
    from boc_tach import VERSION, boc_tach, khuon
    from boc_tach.html_sach import crawl_all as _ca  # noqa: F401 (bảo đảm cùng module)
    from bs4 import BeautifulSoup
    import main as api_main
    import trich

    t0 = time.time()
    # --- đọc kho, bỏ url trùng sau norm (đúng như dung_templates / chay_extract)
    trang: list[tuple[str, str]] = []
    seen: set[str] = set()
    cap: set[tuple[str, str | None]] = set(CA_BIEN_URL)
    for rec in api_main.tat_ca_ban_ghi():
        url = crawl_all.norm(rec["url"]) or rec["url"]
        if url in seen:
            continue
        seen.add(url)
        html = trich._giai_ma(rec)
        if html is None:
            continue
        trang.append((url, html))
    print(f"{len(trang)} trang có HTML ({time.time() - t0:.0f}s)", flush=True)

    # --- url.jsonl.gz: href thật trong HTML + N1-links + ca biên
    for url, html in trang:
        for a in BeautifulSoup(html, "lxml").find_all("a", href=True):
            h = a["href"]
            tuyet_doi = h.strip().lower().startswith(("http://", "https://"))
            cap.add((h, None if tuyet_doi else url))
    for f in DATA.glob("raw*/N1-links"):
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                cap.add((line.strip(), None))
    rows = []
    for inp, base in sorted(cap, key=lambda x: (x[0], x[1] or "")):
        n = crawl_all.norm(inp, base) if base else crawl_all.norm(inp)
        rows.append([inp, base, n,
                     crawl_all.dedup_key(n) if n else None,
                     crawl_all.kind_of(n) if n else None,
                     list(crawl_all.page_of(n)) if n and crawl_all.page_of(n) else None])
    print("url:", dump_gz(OUT / "url.jsonl.gz", rows), "dòng", flush=True)

    # --- khuon.json: dựng đúng như trich.dung_templates
    theo_host: dict[str, list[set]] = {}
    for url, html in trang:
        host = (urllib.parse.urlsplit(url).hostname or "").lower()
        theo_host.setdefault(host, []).append(khuon.van_tay_trang(BeautifulSoup(html, "lxml")))
    bang_host = {}
    for host, tap in theo_host.items():
        bang = khuon.dem_host(tap)
        giu = max(trich.TOI_THIEU_DEM, trich.NGUONG_GIU * bang["n_pages"])
        bang["blocks"] = {v: c for v, c in bang["blocks"].items() if c >= giu}
        bang["tap_khuon"] = sorted(khuon.tap_khuon(bang))
        bang_host[host] = bang
    (OUT / "khuon.json").write_text(json.dumps(bang_host, ensure_ascii=False), encoding="utf-8")
    print("khuôn:", {h: (b["n_pages"], len(b["tap_khuon"])) for h, b in bang_host.items()}, flush=True)

    # --- boc_tach.jsonl.gz
    def gon(d):
        if d is None:
            return None
        text = d["text"]
        return {
            "title": d["title"], "title_src": d["title_src"], "date": d["date"], "date_src": d["date_src"],
            "author": d["author"], "author_src": d["author_src"], "cited_source": d["cited_source"],
            "section": d["section"], "block": d["block"],
            "text_sha1": hashlib.sha1(text.encode("utf-8")).hexdigest(), "text": text,
            "html_sha1": hashlib.sha1(d["html"].encode("utf-8")).hexdigest(), "html_len": len(d["html"]),
            "edges": sorted([e["dst"], e["type"], e["text"], e["dst_kind"], e["count"]] for e in d["links"]),
            "nav_edges": len(d["nav_links"]),
            "out": sorted(d["outgoing_links"], key=json.dumps) if d["outgoing_links"] else [],
        }

    def gen():
        for url, html in trang:
            host = (urllib.parse.urlsplit(url).hostname or "").lower()
            kh = set(bang_host.get(host, {}).get("tap_khuon", [])) if bang_host.get(host, {}).get("n_pages", 0) >= khuon.TOI_THIEU_TRANG else set()
            yield {"url": url, "host": host, "version": VERSION,
                   "khong_khuon": gon(boc_tach(html, url, None)),
                   "co_khuon": gon(boc_tach(html, url, kh)) if kh else "giong"}
    print("bóc tách:", dump_gz(OUT / "boc_tach.jsonl.gz", gen()), "trang", f"({time.time() - t0:.0f}s)")


# ----------------------------------------------------------------------- tệp
def buoc_tep():
    from boc_tach import tep
    rows = []
    for f in sorted((DATA / "files").iterdir()):
        sha, _, ext = f.name.partition(".")
        data = f.read_bytes()
        t0 = time.time()
        kq = tep.bong_chu(data, ext)
        rows.append({"sha1": sha, "ext": ext, "size": len(data), "status": kq["status"],
                     "n_pages": kq["n_pages"], "needs_ocr": kq["needs_ocr"],
                     "encoding_suspect": kq["encoding_suspect"], "len": len(kq["text"]),
                     "head": kq["text"][:300], "error": kq["error"], "sec": round(time.time() - t0, 2)})
        print(len(rows), f.name, kq["status"], len(kq["text"]), flush=True)
    dump_gz(OUT / "tep.jsonl.gz", rows)


# -------------------------------------------------------------------- robots
def buoc_robots():
    import httpx
    import pymongo
    from urllib.robotparser import RobotFileParser
    import crawl_all
    db = pymongo.MongoClient("mongodb://mongo:27017")["hust"]
    urls = [d["_id"] for d in db.documents.find({}, {"_id": 1})]
    hosts = sorted({urllib.parse.urlsplit(u).netloc for u in urls})
    (OUT / "robots").mkdir(exist_ok=True)
    rps = {}
    with httpx.Client(timeout=30, follow_redirects=True, headers={"User-Agent": crawl_all.UA}) as cli:
        for h in hosts:
            rp = RobotFileParser()
            try:
                r = cli.get(f"https://{h}/robots.txt")
                body = r.text if r.status_code == 200 else ""
                (OUT / "robots" / f"{h}.txt").write_text(body, encoding="utf-8")
                (OUT / "robots" / f"{h}.status").write_text(str(r.status_code))
            except httpx.HTTPError as e:
                body = ""
                (OUT / "robots" / f"{h}.status").write_text(f"loi {type(e).__name__}")
            rp.parse(body.splitlines())
            rps[h] = rp
            time.sleep(3)
    rows = [[u, rps[urllib.parse.urlsplit(u).netloc].can_fetch(crawl_all.UA, u)] for u in urls]
    dump_gz(OUT / "robots" / "ket-qua.jsonl.gz", rows)
    print(len(hosts), "host,", sum(1 for _, ok in rows if not ok), "url bị cấm /", len(rows))


# ----------------------------------------------------------------------- api
def buoc_api():
    import so_sanh
    base = "http://localhost:8000"
    (OUT / "api").mkdir(exist_ok=True)

    def get(path):
        try:
            with urllib.request.urlopen(base + path, timeout=120) as r:
                return {"path": path, "status": r.status, "body": json.loads(r.read())}
        except urllib.error.HTTPError as e:
            return {"path": path, "status": e.code, "body": json.loads(e.read() or b"null")}

    def luu(ten, doc):
        (OUT / "api" / f"{ten}.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")

    q = urllib.parse.quote
    mau = get("/api/index/list?size=1&sort=url")["body"]
    url_mau = mau["hits"][0]["url"] if mau.get("hits") else "https://hust.edu.vn/"
    for ten, path in {
        "stats": "/api/stats", "index-stats": "/api/index/stats", "health": "/api/health",
        "index-list": "/api/index/list?size=5&sort=url", "index-dict": "/api/index/dict?field=text&limit=10",
        "index-posting": "/api/index/posting?field=text&term=" + q("tuyển") + "&limit=5",
        "preview": "/api/preview?url=" + q(url_mau),
        "extract-coverage": "/api/extract/coverage", "extract-overview": "/api/extract/overview",
        "extract-status": "/api/extract/status", "extract-explain": "/api/extract/explain?url=" + q(url_mau),
        "graph-stats": "/api/graph/stats?top=10", "graph-out": "/api/graph/out?url=" + q(url_mau) + "&limit=20",
        "referrers": "/api/referrers?url=" + q(url_mau) + "&limit=20",
        "files": "/api/files?limit=10", "files-ok": "/api/files?status=ok&limit=10",
        "images": "/api/images?limit=10", "images-template": "/api/images?template=true&limit=10",
        "loi-search-thieu-q": "/api/search", "loi-search-ranking": "/api/search?q=a&ranking=xyz",
        "loi-posting-thieu-term": "/api/index/posting?field=text",
    }.items():
        luu(ten, get(path))
    luu("search", [{"q": tv, "ranking": rk,
                    **get(f"/api/search?q={q(tv)}&size=10&ranking={rk}")}
                   for tv in so_sanh.TRUY_VAN_MAC_DINH for rk in ("tfidf", "enhanced")])
    print("api: đã ghi", len(list((OUT / "api").iterdir())), "tệp")


if __name__ == "__main__":
    {"kho": buoc_kho, "tep": buoc_tep, "robots": buoc_robots, "api": buoc_api}[sys.argv[1]]()
