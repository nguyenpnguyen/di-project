"""Các route bóc tách và đồ thị liên kết (MongoDB). Gắn vào app trong main.py."""
from __future__ import annotations

import base64
import csv
import hashlib
import os
import pathlib
import io
import threading
import time
import urllib.parse

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

import db as _db
import tep_job
import trich
from boc_tach import boc_tach, giai_thich, tep
from boc_tach.html_sach import norm
from boc_tach.khuon import TOI_THIEU_TRANG

router = APIRouter()
_job = {"running": False, "what": "", "done": 0, "started": 0.0, "result": None, "error": None}
_lock = threading.Lock()


def mdb():
    """Kết nối Mongo; 503 nếu chưa lên."""
    try:
        d = _db.get_db()
        d.command("ping")
        return d
    except Exception as e:                       # pymongo có nhiều lớp lỗi kết nối
        raise HTTPException(503, f"MongoDB không sẵn sàng: {e}") from e


def _chay_nen(what: str, fn):
    with _lock:
        if _job["running"]:
            raise HTTPException(409, f"đang chạy: {_job['what']}")
        _job.update(running=True, what=what, done=0, started=time.time(), result=None, error=None)

    def run():
        try:
            _job["result"] = fn(lambda n: _job.__setitem__("done", n))
        except Exception as e:
            _job["error"] = f"{type(e).__name__}: {e}"
        finally:
            _job["running"] = False

    threading.Thread(target=run, daemon=True).start()
    return {"ok": True, "started": what}


@router.post("/api/extract/templates")
def extract_templates():
    """Dựng bảng khối lặp theo host (lớp 2 của thuật toán khối nội dung)."""
    import main
    d = mdb()
    return _chay_nen("templates", lambda cb: trich.dung_templates(d, main.tat_ca_ban_ghi(), cb))


@router.post("/api/extract/run")
def extract_run(limit: int = 0):
    """Kho thô -> boc_tach -> Mongo (pages, links, nav_links, images)."""
    import main
    d = mdb()
    _db.init(d)
    return _chay_nen("extract", lambda cb: trich.chay_extract(d, main.tat_ca_ban_ghi(), limit, cb))


@router.get("/api/extract/status")
def extract_status():
    return {**_job, "elapsed_sec": round(time.time() - _job["started"]) if _job["started"] else 0}


@router.get("/api/extract/coverage")
def extract_coverage():
    return trich.coverage(mdb())


@router.get("/api/extract/explain")
def extract_explain(url: str):
    """Chạy lại bước chọn khối trên HTML thô trong kho và trả về từng bước (phễu số
    chữ, các bậc đi xuống cây, cạnh trong/ngoài khối) để giao diện vẽ. Không cần Mongo;
    có Mongo thì dùng thêm bảng khuôn của host và bí danh của trang."""
    import main
    if urllib.parse.urlsplit(url).scheme not in ("http", "https"):
        raise HTTPException(422, "url phải là HTTP hoặc HTTPS đầy đủ")
    urls, khuon_host, co_mongo = {url}, set(), True
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    try:
        d = mdb()
        urls.update(_canon(d, url)[1])
        khuon_host = trich._khuon_theo_host(d).get(host, set())
    except HTTPException:
        co_mongo = False
    rec = main.tim_ban_ghi(urls)
    html = trich._giai_ma(rec) if rec else None
    if not html:
        raise HTTPException(404, "không có HTML của url này trong kho (chưa crawl hoặc là tệp)")
    out = giai_thich(html, rec["url"], khuon_host)
    return {**out, "co_mongo": co_mongo, "khuon_host": len(khuon_host)}


TOI_DA_VAN_BAN = 50_000       # ký tự văn bản trả về giao diện; bản lưu Mongo/Lucene không bị cắt ở đây


class UrlReq(BaseModel):
    url: str
    tai_lai: bool = False      # True: tải lại từ web dù kho đã có
    luu: bool = True           # ghi vào MongoDB
    index: bool = True         # đẩy vào Lucene để tìm được ngay


def _loai_noi_dung(ctype: str, url: str) -> str:
    """html | document | image | other, theo content-type rồi tới đuôi url."""
    ct = (ctype or "").split(";")[0].strip().lower()
    if "html" in ct:
        return "html"
    if ct in tep.MIME_SANG_DUOI or tep.duoi_tu_url(url) in tep.HO_TRO | tep.CU:
        return "document"
    if ct.startswith("image/"):
        return "image"
    return "other" if ct else "html"


def _index_lucene(doc: dict) -> str:
    """Đẩy một tài liệu sang Lucene; trả chuỗi lỗi, rỗng nếu thành công."""
    import httpx
    import main
    try:
        with httpx.Client(base_url=main.LUCENE, timeout=60) as cli:
            cli.post("/bulk", json=[doc]).raise_for_status()
        return ""
    except httpx.HTTPError as e:
        return f"Lucene không sẵn sàng: {e}"


@router.post("/api/extract/url")
def extract_url(req: UrlReq):
    """Bóc tách MỘT url bất kỳ: lấy HTML trong kho nếu có (hoặc tải mới từ web), chạy
    thuật toán chọn khối + bóc trường + chia cạnh, ghi Mongo và index Lucene.

    Trả về các trường, nội dung đã bóc (HTML đã dọn + văn bản), liên kết trong bài và trạng
    thái lưu. Từng bậc của thuật toán chọn khối xem ở /api/extract/explain. Url là tệp tài
    liệu thì bóc chữ tệp và ghi `documents`.
    """
    import main
    url = (req.url or "").strip()
    sp = urllib.parse.urlsplit(url)
    if sp.scheme.lower() not in ("http", "https") or not sp.netloc:
        raise HTTPException(422, "url phải là HTTP hoặc HTTPS đầy đủ")
    url_tai = url                    # tải bằng url gốc: norm() ép https, site chỉ có http sẽ hỏng
    url = norm(url) or url           # còn khoá lưu thì luôn qua norm()

    d = None
    try:
        d = mdb()
    except HTTPException:
        pass
    khuon_host: set[str] = set()
    urls = {url}
    if d is not None:
        urls.update(_canon(d, url)[1])
        khuon_host = trich._khuon_theo_host(d).get(sp.hostname.lower(), set())

    # 1. lấy nội dung: kho trước, web sau
    rec, nguon, data = None, "kho", b""
    if not req.tai_lai:
        rec = main.tim_ban_ghi(urls)
        if rec is not None and not rec.get("html_b64"):
            rec = None                       # kho chỉ ghi nhận tệp, không có byte: tải mới
    if rec is None:
        try:
            r = main.tai_ve(url_tai)
        except HTTPException as e:
            # url lấy từ đồ thị đã bị norm() ép https; site chỉ có http thì lỗi ngay ở bắt tay
            # TLS/kết nối (không phải HTTP 4xx/5xx) — thử lại một lần bằng http.
            if not (e.status_code == 502 and str(e.detail).startswith("không tải được")
                    and url_tai.lower().startswith("https://")):
                raise
            r = main.tai_ve("http://" + url_tai[len("https://"):])
        nguon, data = "web", r.content
        ctype = r.headers.get("content-type", "")
        loai = _loai_noi_dung(ctype, str(r.url))
        rec = {"url": norm(str(r.url)) or str(r.url), "status": r.status_code, "content_type": ctype,
               "encoding": r.encoding or "utf-8", "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
               "size": len(data), "sha1": hashlib.sha1(data).hexdigest(), "kind": "adhoc",
               "html_b64": base64.b64encode(data).decode("ascii") if loai == "html" else None}
        if loai in ("html", "document"):
            main._ghi_kho(rec)               # vào raw-adhoc: lần bóc tách cả kho sau cũng gom được
        if loai == "document":
            return _boc_tep_url(d, rec, data, req)
        if loai != "html":
            raise HTTPException(415, f"url trả về {ctype or 'không rõ loại'} — không phải trang HTML "
                                     "hay tệp tài liệu; ảnh chỉ có nguồn giới thiệu, xem ở tab Đồ thị")

    html = trich._giai_ma(rec)
    if not html:
        raise HTTPException(422, "trang trả lỗi hoặc không có HTML")
    page_url = norm(rec["url"]) or rec["url"]
    kq = boc_tach(html, page_url, khuon_host)
    if not kq:
        raise HTTPException(422, "tải được nhưng không bóc ra chữ nào — trang rỗng hoặc dựng bằng JS")

    # 2. lưu
    luu = {"mongo": False, "loi_mongo": "", "index": False, "loi_index": ""}
    if req.luu:
        if d is None:
            luu["loi_mongo"] = "MongoDB không sẵn sàng"
        else:
            trich.ghi_mot_trang(d, page_url, kq, rec, html)
            luu.update(trich.ghi_phu_mot_trang(d, kq), mongo=True)
    if req.index:
        luu["loi_index"] = _index_lucene({**main.lucene_document(kq), "author": kq["author"], "kind": "page"})
        luu["index"] = not luu["loi_index"]
    return {"loai": "page", "url": page_url, "host": kq["host"], "nguon": nguon,
            "fetched_at": rec.get("fetched_at", ""), "co_mongo": d is not None,
            "khuon_host": len(khuon_host), "luu": luu, "block": kq["block"],
            "truong": {k: kq[k] for k in ("title", "title_src", "date", "date_src", "author",
                                          "author_src", "cited_source", "section")},
            "so_tu": len(kq["text"].split()),
            # nội dung đã bóc: HTML đã dọn (≤ 40 KB, để trình bày) và văn bản thuần (đưa vào chỉ mục)
            "noi_dung": {"html": kq["html"], "text": kq["text"][:TOI_DA_VAN_BAN],
                         "bi_cat": len(kq["text"]) > TOI_DA_VAN_BAN},
            "lien_ket": [{k: e[k] for k in ("dst", "type", "text", "dst_kind", "count")} for e in kq["links"]],
            "so_canh_khuon": len(kq["nav_links"])}


def _boc_tep_url(d, rec: dict, data: bytes, req: UrlReq) -> dict:
    """Nhánh tệp tài liệu của /api/extract/url: bóc chữ, ghi documents + byte, index kind=document."""
    url = rec["url"]
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    mime = (rec.get("content_type") or "").split(";")[0].strip().lower()
    ext = tep.duoi_tu_url(url) or tep.MIME_SANG_DUOI.get(mime, "")
    kq = tep.bong_chu(data, ext)
    luu = {"mongo": False, "loi_mongo": "", "index": False, "loi_index": ""}
    if req.luu:
        if d is None:
            luu["loi_mongo"] = "MongoDB không sẵn sàng"
        else:
            p = pathlib.Path(FILES_DIR)
            p.mkdir(parents=True, exist_ok=True)
            (p / f"{rec['sha1']}.{ext or 'bin'}").write_bytes(data)
            d.documents.update_one({"_id": url}, {"$set": {
                "host": host, "ext": ext, "mime": mime, "size": len(data), "sha1": rec["sha1"],
                "fetched_at": rec["fetched_at"], "extractor_version": trich.VERSION, **kq}}, upsert=True)
            luu["mongo"] = True
    if req.index and kq["status"] == "ok" and kq["text"]:
        ten = urllib.parse.unquote(url.rsplit("/", 1)[-1].split("?")[0]) or url
        luu["loi_index"] = _index_lucene({"url": url, "title": ten, "text": kq["text"], "host": host,
                                          "section": "", "date": "", "author": "", "kind": "document"})
        luu["index"] = not luu["loi_index"]
    ref = d.links.count_documents({"dst": url}) if d is not None else 0
    return {"loai": "document", "url": url, "host": host, "nguon": "web", "ext": ext, "size": len(data),
            "co_mongo": d is not None, "luu": luu, "referrers": ref,
            "tep": {k: kq[k] for k in ("status", "n_pages", "needs_ocr", "encoding_suspect", "error")},
            "noi_dung": {"text": kq["text"][:TOI_DA_VAN_BAN], "bi_cat": len(kq["text"]) > TOI_DA_VAN_BAN},
            "so_ky_tu": len(kq["text"])}


@router.get("/api/extract/overview")
def extract_overview():
    """Số liệu từng bước của dây chuyền khuôn -> bóc tách -> đồ thị -> tệp."""
    d = mdb()
    tpl = list(d.templates.find({}, {"n_pages": 1}))
    return {"templates": len(tpl),
            "templates_active": sum(1 for t in tpl if t.get("n_pages", 0) >= TOI_THIEU_TRANG),
            "pages": d.pages.count_documents({}), "links": d.links.count_documents({}),
            "nav_links": d.nav_links.count_documents({}), "images": d.images.count_documents({}),
            "documents": {r["_id"]: r["n"] for r in d.documents.aggregate(
                [{"$group": {"_id": "$status", "n": {"$sum": 1}}}])},
            "documents_text": d.documents.count_documents({"text": {"$nin": [None, ""]}}),
            "job": {k: _job[k] for k in ("running", "what", "done")}}


FILES_DIR = str(pathlib.Path(os.getenv("DATA_DIR", "/crawler/data")) / "files")


@router.post("/api/files/fetch")
def files_fetch(limit: int = 0):
    """Lập danh mục tệp từ đồ thị rồi tải, chạy nền. 409 khi crawler đang chạy: hai
    tiến trình mỗi bên 2,5 s là ~48 request/phút, gấp đôi ngưỡng site chặn."""
    import main
    if main._alive():
        raise HTTPException(409, "crawler đang chạy, dừng trước rồi hãy tải tệp")
    d = mdb()
    _db.init(d)

    def job(cb):
        r = tep_job.danh_muc(d, main.tat_ca_ban_ghi())
        return {**r, **tep_job.tai(d, pathlib.Path(FILES_DIR), cb, limit=limit)}
    return _chay_nen("files", job)


@router.post("/api/files/extract")
def files_extract():
    """Bóc chữ các tệp đã tải -> documents.text."""
    d = mdb()
    return _chay_nen("files-extract", lambda cb: tep_job.boc_chu(d, pathlib.Path(FILES_DIR), cb))


@router.get("/api/files")
def files_list(status: str | None = None, host: str | None = None, skip: int = 0, limit: int = 50):
    """Danh sách tệp, mỗi tệp kèm số trang giới thiệu nó (cạnh nội dung đi vào)."""
    d = mdb()
    q = {k: v for k, v in (("status", status), ("host", host)) if v}
    rows = list(d.documents.find(q, {"text": 0}).sort("_id", 1).skip(skip).limit(min(limit, 200)))
    return {"total": d.documents.count_documents(q),
            "by_status": {r["_id"]: r["n"] for r in d.documents.aggregate(
                [{"$group": {"_id": "$status", "n": {"$sum": 1}}}])},
            "items": [{**{k: v for k, v in r.items() if k != "_id"}, "url": r["_id"],
                       "referrers": d.links.count_documents({"dst": r["_id"]})} for r in rows]}


@router.get("/api/images")
def images_list(template: bool | None = None, skip: int = 0, limit: int = 50):
    d = mdb()
    q = {} if template is None else {"is_template": template}
    rows = list(d.images.find(q).sort("_id", 1).skip(skip).limit(min(limit, 200)))
    return {"total": d.images.count_documents(q),
            "items": [{"url": r["_id"], "host": r["host"], "alts": r["alts"],
                       "is_template": r["is_template"],
                       "referrers": d.links.count_documents({"dst": r["_id"]})} for r in rows]}


def _canon(d, url: str) -> tuple[str, list[str]]:
    """url -> (_id trang chính, mọi url cùng bài). Url không có trong pages thì giữ nguyên."""
    u = norm(url) or url
    p = d.pages.find_one({"$or": [{"_id": u}, {"aliases": u}]}, {"aliases": 1})
    if not p:
        return u, [u]
    return p["_id"], [p["_id"], *p.get("aliases", [])]


@router.get("/api/referrers")
def referrers(url: str, limit: int = 100):
    """Nguồn giới thiệu của một trang / tệp / ảnh: các cạnh đi VÀO url này."""
    if urllib.parse.urlsplit(url).scheme not in ("http", "https"):
        raise HTTPException(422, "url phải là HTTP hoặc HTTPS đầy đủ")
    d = mdb()
    canon, tat_ca = _canon(d, url)
    q = {"dst": {"$in": tat_ca}}
    noi_dung = list(d.links.find(q, {"src": 1, "text": 1, "type": 1, "count": 1}).limit(limit))
    khuon = list(d.nav_links.find(q, {"host": 1, "text": 1, "n_pages": 1, "sample_src": 1})
                 .sort("n_pages", -1).limit(limit))
    return {"url": canon,
            "content": [{"src": x["src"], "text": x["text"], "type": x["type"], "count": x["count"]}
                        for x in noi_dung],
            "nav": [{"host": x["host"], "text": x["text"], "n_pages": x["n_pages"],
                     "sample_src": x["sample_src"]} for x in khuon],
            "total_content": d.links.count_documents(q),
            "total_nav": d.nav_links.count_documents(q)}


@router.get("/api/graph/out")
def graph_out(url: str, limit: int = 200):
    """Cạnh nội dung đi RA từ một trang."""
    d = mdb()
    canon, _ = _canon(d, url)
    edges = list(d.links.find({"src": canon}, {"dst": 1, "text": 1, "type": 1, "dst_kind": 1,
                                                "count": 1}).limit(limit))
    return {"url": canon, "co_trang": d.pages.count_documents({"_id": canon}, limit=1) > 0,
            "edges": [{k: e[k] for k in ("dst", "text", "type", "dst_kind", "count")} for e in edges]}


@router.get("/api/graph/stats")
def graph_stats(top: int = 10):
    d = mdb()
    top_vao = list(d.links.aggregate([
        {"$group": {"_id": "$dst", "n": {"$sum": 1}}}, {"$sort": {"n": -1}}, {"$limit": top}]))
    theo_kind = {r["_id"]: r["n"] for r in d.links.aggregate(
        [{"$group": {"_id": "$dst_kind", "n": {"$sum": 1}}}])}
    return {"pages": d.pages.count_documents({}), "content_edges": d.links.count_documents({}),
            "nav_edges": d.nav_links.count_documents({}), "images": d.images.count_documents({}),
            "content_edges_by_dst_kind": theo_kind,
            "top_in_degree": [{"url": r["_id"], "in_content_edges": r["n"]} for r in top_vao]}


@router.get("/api/graph/edges.csv")
def graph_edges_csv():
    """Xuất cạnh nội dung: source,target,text (cho Gephi / networkx)."""
    d = mdb()

    def gen():
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["source", "target", "text"])
        yield buf.getvalue()
        for e in d.links.find({}, {"src": 1, "dst": 1, "text": 1}):
            buf.seek(0); buf.truncate()
            w.writerow([e["src"], e["dst"], e["text"]])
            yield buf.getvalue()

    return StreamingResponse(gen(), media_type="text/csv; charset=utf-8")
