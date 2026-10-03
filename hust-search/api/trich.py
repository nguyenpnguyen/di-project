"""Kho thô -> boc_tach -> MongoDB.

Chạy lại bao nhiêu lần cũng không nhân đôi: `pages` ghi theo _id, `links` xoá
theo `src` rồi ghi lại, `nav_links`/`images` dựng lại từ đầu mỗi lần chạy.
"""
from __future__ import annotations

import base64
import hashlib
import time
import urllib.parse
from typing import Callable, Iterable

from boc_tach import VERSION, boc_tach, khuon
from boc_tach.html_sach import crawl_all

# Chỉ giữ vân tay có mặt trên ít nhất chừng này trang: khối chỉ xuất hiện 1-2 lần
# không thể là khuôn, mà bảng đầy đủ của 6.000 trang có thể vượt 16 MB của một
# document Mongo.
TOI_THIEU_DEM = 3
NGUONG_GIU = 0.05


def _sha1(*parts: str) -> str:
    return hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()


def _giai_ma(rec: dict) -> str | None:
    if not rec.get("html_b64") or (rec.get("status") or 0) >= 400:
        return None
    return base64.b64decode(rec["html_b64"]).decode(rec.get("encoding") or "utf-8", "replace")


def dung_templates(db, records: Iterable[dict], on_progress: Callable[[int], None] = lambda n: None) -> dict:
    """Lớp 2: đếm khối lặp theo host rồi ghi collection `templates`."""
    from bs4 import BeautifulSoup
    theo_host: dict[str, list[set]] = {}
    seen: set[str] = set()
    n = 0
    for rec in records:
        url = crawl_all.norm(rec["url"]) or rec["url"]
        if url in seen:
            continue
        seen.add(url)
        html = _giai_ma(rec)
        if html is None:
            continue
        host = (urllib.parse.urlsplit(url).hostname or "").lower()
        theo_host.setdefault(host, []).append(khuon.van_tay_trang(BeautifulSoup(html, "lxml")))
        n += 1
        if n % 100 == 0:
            on_progress(n)
    db.templates.delete_many({})
    out = {}
    for host, tap in theo_host.items():
        bang = khuon.dem_host(tap)
        giu = max(TOI_THIEU_DEM, NGUONG_GIU * bang["n_pages"])
        bang["blocks"] = {v: c for v, c in bang["blocks"].items() if c >= giu}
        db.templates.replace_one({"_id": host}, {"_id": host, **bang}, upsert=True)
        out[host] = {"n_pages": bang["n_pages"], "template_blocks": len(khuon.tap_khuon(bang))}
    return out


def _khuon_theo_host(db) -> dict[str, set[str]]:
    return {t["_id"]: khuon.tap_khuon(t) for t in db.templates.find()}


def dung_ban_ghi(url: str, host: str, d: dict, rec: dict, html: str) -> tuple[dict, list[dict]]:
    """Kết quả boc_tach -> (document `pages`, các document `links`)."""
    raw_html = html.encode("utf-8", "replace")
    page = {
        "_id": url, "aliases": [], "host": host,
        "lang": "en" if urllib.parse.urlsplit(url).path.startswith("/en/") else "vi",
        "kind": crawl_all.kind_of(url),
        "title": d["title"], "title_src": d["title_src"],
        "published_at": d["date"], "published_at_src": d["date_src"],
        "author": d["author"], "author_src": d["author_src"], "cited_source": d["cited_source"],
        "section": d["section"],
        "content": {"text": d["text"], "html": d["html"], "word_count": len(d["text"].split()),
                    "block": d["block"]},
        "raw": {"sha1": hashlib.sha1(raw_html).hexdigest(), "fetched_at": rec.get("fetched_at", "")},
        "extractor_version": VERSION, "extracted_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    links = [{
        "_id": _sha1(url, e["dst"], e["type"], e["text"]), "src": url, "dst": e["dst"],
        "type": e["type"], "text": e["text"], "dst_kind": e["dst_kind"], "count": e["count"],
        "src_host": host, "dst_host": (urllib.parse.urlsplit(e["dst"]).hostname or "").lower(),
    } for e in d["links"]]
    return page, links


def ghi_mot_trang(db, url: str, d: dict, rec: dict, html: str) -> None:
    """Ghi một trang tải lẻ (`/api/fetch`): pages + links của nó, không đụng nav_links
    và images (hai bảng đó là tổng hợp, dựng lại ở lần extract/run)."""
    url = crawl_all.norm(url) or url
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    page, links = dung_ban_ghi(url, host, d, rec, html)
    db.pages.replace_one({"_id": url}, page, upsert=True)
    db.links.delete_many({"src": url})
    if links:
        db.links.insert_many(links, ordered=False)


def ghi_phu_mot_trang(db, d: dict) -> dict:
    """Phần phụ khi ghi một trang lẻ: danh mục ảnh và danh mục tệp tài liệu mà trang trỏ tới.

    Chỉ upsert, không xoá: `images` vẫn được dựng lại đầy đủ ở lần extract/run kế tiếp
    (trang lẻ nằm trong raw-adhoc nên lần đó cũng gom được). `nav_links` cố ý KHÔNG ghi ở
    đây — nó đếm số trang theo host, ghi lẻ rồi ghi lại thì đếm đôi.
    """
    from boc_tach import lien_ket, tep
    n_anh = n_tep = 0
    for e in d["links"]:
        if e["dst_kind"] == "image":
            upd = {"$set": {"is_template": False},
                   "$setOnInsert": {"host": (urllib.parse.urlsplit(e["dst"]).hostname or "").lower()}}
            if e["text"]:
                upd["$addToSet"] = {"alts": e["text"]}
            else:
                upd["$setOnInsert"]["alts"] = []
            db.images.update_one({"_id": e["dst"]}, upd, upsert=True)
            n_anh += 1
        elif e["dst_kind"] == "document":
            host = (urllib.parse.urlsplit(e["dst"]).hostname or "").lower()
            if not lien_ket.trong_ho_hust(host):
                continue
            ext = tep.duoi_tu_url(e["dst"])
            r = db.documents.update_one({"_id": e["dst"]}, {"$setOnInsert": {
                "host": host, "ext": ext, "mime": "", "extractor_version": VERSION,
                "status": "unsupported" if ext in tep.CU else "pending"}}, upsert=True)
            n_tep += 1 if r.upserted_id is not None else 0
    for e in d["nav_links"]:
        if e["dst_kind"] == "image":
            db.images.update_one({"_id": e["dst"]}, {"$setOnInsert": {
                "host": (urllib.parse.urlsplit(e["dst"]).hostname or "").lower(),
                "alts": [], "is_template": True}}, upsert=True)
    return {"images": n_anh, "new_documents": n_tep}


def lucene_tu_mongo(db) -> Iterable[dict]:
    """Tài liệu để index, đọc từ Mongo: mọi trang, và tệp đã bóc được chữ."""
    for p in db.pages.find({}):
        yield {"url": p["_id"], "title": p["title"], "text": p["content"]["text"],
               "host": p["host"], "section": p.get("section", ""), "date": p.get("published_at", ""),
               "html": p["content"].get("html", ""), "author": p.get("author", ""), "kind": "page"}
    yield from tep_tu_mongo(db)


def tep_tu_mongo(db) -> Iterable[dict]:
    """Tệp tài liệu đã bóc được chữ. Chữ của tệp CHỈ có trong Mongo (kho crawl không lưu byte
    pdf/docx), nên mọi nguồn index — kể cả kho thô — đều phải lấy tệp từ đây."""
    for f in db.documents.find({"status": "ok", "text": {"$nin": [None, ""]}}):
        ten = urllib.parse.unquote(f["_id"].rsplit("/", 1)[-1])
        yield {"url": f["_id"], "title": f.get("title") or ten, "text": f["text"], "host": f["host"],
               "section": "", "date": "", "html": "", "author": "", "kind": "document",
               "ftype": f.get("ext") or ""}


def chay_extract(db, records: Iterable[dict], limit: int = 0,
                 on_progress: Callable[[int], None] = lambda n: None, batch: int = 200) -> dict:
    kh = _khuon_theo_host(db)
    da_thay: dict[str, str] = {}          # khoá khử trùng -> _id trang chính
    nav: dict[tuple, dict] = {}
    anh: dict[str, dict] = {}
    pages_buf: list[dict] = []
    links_buf: list[dict] = []
    src_buf: list[str] = []
    buf_idx: dict[str, dict] = {}
    n = skipped = n_links = 0

    def xa():
        nonlocal pages_buf, links_buf, src_buf
        buf_idx.clear()
        if pages_buf:
            db.links.delete_many({"src": {"$in": src_buf}})
            for p in pages_buf:
                db.pages.replace_one({"_id": p["_id"]}, p, upsert=True)
            if links_buf:
                db.links.insert_many(links_buf, ordered=False)
        pages_buf, links_buf, src_buf = [], [], []

    for rec in records:
        url = crawl_all.norm(rec["url"]) or rec["url"]
        key = crawl_all.dedup_key(url) or url
        if key in da_thay:                     # bản trùng: chỉ ghi thêm bí danh
            chinh = da_thay[key]
            if chinh != url:
                if chinh in buf_idx:            # trang chính còn trong bộ đệm, chưa ghi
                    if url not in buf_idx[chinh]["aliases"]:
                        buf_idx[chinh]["aliases"].append(url)
                else:
                    db.pages.update_one({"_id": chinh}, {"$addToSet": {"aliases": url}})
            continue
        html = _giai_ma(rec)
        host = (urllib.parse.urlsplit(url).hostname or "").lower()
        d = boc_tach(html, url, kh.get(host)) if html is not None else None
        if not d:
            skipped += 1
            continue
        da_thay[key] = url
        page, links = dung_ban_ghi(url, host, d, rec, html)
        pages_buf.append(page)
        src_buf.append(url)
        buf_idx[url] = page
        links_buf.extend(links)
        for e in d["links"]:
            if e["dst_kind"] == "image":
                a = anh.setdefault(e["dst"], {"alts": set(), "content": False})
                a["content"] = True
                if e["text"]:
                    a["alts"].add(e["text"])
        n_links += len(d["links"])
        for e in d["nav_links"]:
            k = (host, e["dst"], e["type"], e["text"])
            v = nav.setdefault(k, {"dst_kind": e["dst_kind"], "n_pages": 0, "sample_src": []})
            v["n_pages"] += 1
            if len(v["sample_src"]) < 3:
                v["sample_src"].append(url)
            if e["dst_kind"] == "image":
                anh.setdefault(e["dst"], {"alts": set(), "content": False})
        n += 1
        if len(pages_buf) >= batch:
            xa()
        if n % 100 == 0:
            on_progress(n)
        if limit and n >= limit:
            break
    xa()

    db.nav_links.delete_many({})
    docs = [{"_id": _sha1(*k), "host": k[0], "dst": k[1], "type": k[2], "text": k[3], **v}
            for k, v in nav.items()]
    for i in range(0, len(docs), 1000):
        db.nav_links.insert_many(docs[i:i + 1000], ordered=False)
    db.images.delete_many({})
    imgs = [{"_id": u, "host": (urllib.parse.urlsplit(u).hostname or "").lower(),
             "alts": sorted(a["alts"]), "is_template": not a["content"]} for u, a in anh.items()]
    for i in range(0, len(imgs), 1000):
        db.images.insert_many(imgs[i:i + 1000], ordered=False)
    return {"pages": n, "skipped": skipped, "links": n_links, "nav_links": len(docs), "images": len(imgs)}


def coverage(db) -> dict:
    """% trường khác rỗng theo host và tỉ lệ từng `method` chọn khối."""
    theo: dict[str, dict] = {}
    for p in db.pages.find({}, {"host": 1, "title": 1, "published_at": 1, "author": 1,
                                "content.block.method": 1, "content.word_count": 1}):
        h = theo.setdefault(p["host"], {"pages": 0, "title": 0, "published_at": 0, "author": 0,
                                        "methods": {}})
        h["pages"] += 1
        for f in ("title", "published_at", "author"):
            h[f] += 1 if p.get(f) else 0
        m = p["content"]["block"]["method"]
        h["methods"][m] = h["methods"].get(m, 0) + 1
    for h in theo.values():
        for f in ("title", "published_at", "author"):
            h[f + "_pct"] = round(100 * h[f] / h["pages"], 1)
    return theo
