"""Tệp tài liệu: danh mục -> tải -> bóc chữ, ghi vào collection `documents`.

Chỉ tải host trong họ hust.edu.vn. Host ngoài (Google Drive...) chỉ có cạnh trong
`links` với dst_kind=external, không vào `documents`.
"""
from __future__ import annotations

import hashlib
import pathlib
import time
import urllib.parse
from typing import Callable, Iterable

import httpx

from boc_tach import VERSION, lien_ket, tep
from boc_tach.html_sach import crawl_all

MAX_BYTES = 50 * 1024 * 1024
NHIP = 2.5                       # giây giữa hai request, như crawler
UA = crawl_all.UA


def _ext_khong_ro(url: str) -> str:
    return tep.duoi_tu_url(url)


def danh_muc(db, records: Iterable[dict] = ()) -> dict:
    """Thêm vào `documents` mọi tệp mà một cạnh nội dung trỏ tới, cộng các bản ghi kho thô
    không phải HTML mà content-type là pdf/office. Tệp đã có thì không đụng."""
    moi = 0

    def them(url: str, mime: str = ""):
        nonlocal moi
        url = crawl_all.norm(url) or url
        host = (urllib.parse.urlsplit(url).hostname or "").lower()
        if not lien_ket.trong_ho_hust(host):
            return
        ext = tep.duoi_tu_url(url) or tep.MIME_SANG_DUOI.get(mime.split(";")[0].strip().lower(), "")
        doc = {"host": host, "ext": ext, "mime": mime, "status": "pending", "extractor_version": VERSION}
        if ext in tep.CU:
            doc["status"] = "unsupported"
        r = db.documents.update_one({"_id": url}, {"$setOnInsert": doc}, upsert=True)
        moi += 1 if r.upserted_id is not None else 0

    for dst in db.links.distinct("dst", {"dst_kind": "document"}):
        them(dst)
    for rec in records:
        ct = (rec.get("content_type") or "").split(";")[0].strip().lower()
        if not rec.get("html_b64") and ct in tep.MIME_SANG_DUOI and (rec.get("status") or 0) < 400:
            them(rec["url"], ct)
    return {"new": moi, "total": db.documents.count_documents({})}


def _robots_ok(cache: dict, cli: httpx.Client, url: str) -> bool:
    from urllib.robotparser import RobotFileParser
    p = urllib.parse.urlsplit(url)
    rp = cache.get(p.netloc)
    if rp is None:
        rp = RobotFileParser()
        try:
            r = cli.get(f"https://{p.netloc}/robots.txt")
            rp.parse(r.text.splitlines() if r.status_code == 200 else [])
        except httpx.HTTPError:
            rp.parse([])
        cache[p.netloc] = rp
    return rp.can_fetch(UA, url)


def tai(db, thu_muc: pathlib.Path, on_progress: Callable[[int], None] = lambda n: None,
        client: httpx.Client | None = None, nhip: float = NHIP, max_bytes: int = MAX_BYTES,
        limit: int = 0) -> dict:
    """Tải các tệp `pending` chưa có byte (sha1 rỗng). Gọi lại thì bỏ qua tệp đã tải."""
    thu_muc.mkdir(parents=True, exist_ok=True)
    cli = client or httpx.Client(timeout=60, follow_redirects=True, headers={"User-Agent": UA})
    robots: dict = {}
    n = ok = loi = lon = 0
    for d in list(db.documents.find({"status": "pending", "sha1": {"$exists": False}})):
        if limit and n >= limit:
            break
        url = d["_id"]
        n += 1
        upd: dict = {}
        try:
            if not _robots_ok(robots, cli, url):
                upd = {"status": "error", "error": "robots.txt cấm"}
            else:
                time.sleep(nhip)
                r = cli.get(url)
                if r.status_code == 429:
                    time.sleep(float(r.headers.get("Retry-After", "30")))
                    r = cli.get(url)
                if r.status_code >= 400:
                    upd = {"status": "error", "error": f"HTTP {r.status_code}"}
                elif len(r.content) > max_bytes:
                    upd = {"status": "skipped_too_large", "size": len(r.content)}
                else:
                    mime = r.headers.get("content-type", "").split(";")[0].strip().lower()
                    ext = d.get("ext") or tep.MIME_SANG_DUOI.get(mime, "")
                    sha = hashlib.sha1(r.content).hexdigest()
                    (thu_muc / f"{sha}.{ext or 'bin'}").write_bytes(r.content)
                    upd = {"sha1": sha, "size": len(r.content), "mime": mime, "ext": ext,
                           "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
                    if ext in tep.CU or ext not in tep.HO_TRO:
                        upd["status"] = "unsupported"
        except httpx.HTTPError as e:
            upd = {"status": "error", "error": f"{type(e).__name__}: {e}"[:300]}
        db.documents.update_one({"_id": url}, {"$set": upd})
        st = upd.get("status", "pending")
        ok += st == "pending" or st == "unsupported"
        loi += st == "error"
        lon += st == "skipped_too_large"
        on_progress(n)
    return {"tried": n, "downloaded": ok, "errors": loi, "too_large": lon}


def boc_chu(db, thu_muc: pathlib.Path, on_progress: Callable[[int], None] = lambda n: None) -> dict:
    """Bóc chữ các tệp đã tải mà chưa bóc (`pending` có sha1)."""
    n = ok = ocr = loi = 0
    for d in list(db.documents.find({"status": "pending", "sha1": {"$exists": True}})):
        f = thu_muc / f"{d['sha1']}.{d.get('ext') or 'bin'}"
        if not f.exists():
            db.documents.update_one({"_id": d["_id"]}, {"$set": {"status": "error", "error": "mất tệp đã tải"}})
            loi += 1
            continue
        kq = tep.bong_chu(f.read_bytes(), d.get("ext", ""))
        db.documents.update_one({"_id": d["_id"]}, {"$set": {**kq, "extractor_version": VERSION}})
        n += 1
        ok += kq["status"] == "ok"
        ocr += kq["needs_ocr"]
        loi += kq["status"] == "error"
        on_progress(n)
    return {"extracted": n, "ok": ok, "needs_ocr": ocr, "errors": loi}
