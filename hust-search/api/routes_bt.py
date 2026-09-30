"""Các route bóc tách và đồ thị liên kết (MongoDB). Gắn vào app trong main.py."""
from __future__ import annotations

import csv
import io
import threading
import time
import urllib.parse

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

import db as _db
import trich
from boc_tach.html_sach import norm

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
    return {"url": canon, "edges": [{k: e[k] for k in ("dst", "text", "type", "dst_kind", "count")}
                                    for e in edges]}


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
