"""
Tầng điều khiển: bọc crawler và Lucene lại thành một API cho giao diện gọi.

Vì sao bóc chữ ở đây mà không ở Java: kho lưu HTML thô base64, muốn index phải
bóc tiêu đề/nội dung ra trước. BeautifulSoup đã có sẵn ở tầng crawler và bộ
selector cho hust.edu.vn cũng đã kiểm chứng ở đó, nên Java chỉ cần lo đúng
việc của Lucene là index và tìm kiếm.
"""
from __future__ import annotations

import base64
import gzip
import json
import os
import pathlib
import re
import signal
import subprocess
import threading
import time
from typing import Iterator

import httpx
from bs4 import BeautifulSoup
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

CRAWLER = pathlib.Path(os.getenv("CRAWLER_DIR", "/crawler"))
DATA = pathlib.Path(os.getenv("DATA_DIR", "/crawler/data"))
LUCENE = os.getenv("LUCENE_URL", "http://lucene:8081")
STATIC = pathlib.Path(__file__).parent / "static"
PIDFILE = DATA / "crawler.pid"
LOG = DATA / "crawl_all.log"

app = FastAPI(title="HUST Crawl & Search")

# một mẻ crawl tại một thời điểm; nhiều mẻ song song sẽ đạp nhau ở state.json
_job_lock = threading.Lock()
_job: dict = {"proc": None, "cmd": "", "started": 0.0}


# ------------------------------------------------------------------ kho dữ liệu
def kho_dirs() -> list[pathlib.Path]:
    return sorted(p for p in DATA.glob("raw*") if p.is_dir())


def host_of(d: pathlib.Path) -> str:
    return "hust.edu.vn" if d.name == "raw" else d.name[4:]


def records(d: pathlib.Path) -> Iterator[dict]:
    """Đọc lười từng bản ghi; shard cuối đang ghi dở thì đọc tới đâu trả tới đó."""
    shards = sorted(list(d.glob("pages-*.jsonl.gz")) + list(d.glob("pages-*.jsonl")))
    for p in shards:
        op = gzip.open if p.suffix == ".gz" else open
        try:
            with op(p, "rt", encoding="utf-8") as fh:
                for line in fh:
                    if not line.strip():
                        continue
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError:
                        break
        except (EOFError, OSError, gzip.BadGzipFile):
            continue


def extract(rec: dict) -> dict | None:
    """Bản ghi thô -> tài liệu để index. None nếu không phải trang đọc được."""
    if not rec.get("html_b64") or (rec.get("status") or 0) >= 400:
        return None
    html = base64.b64decode(rec["html_b64"]).decode(rec.get("encoding") or "utf-8", "replace")
    soup = BeautifulSoup(html, "lxml")

    def prop(name):
        t = soup.select_one(f"[itemprop='{name}']")
        return (t.get("content") or t.get_text(" ", strip=True)).strip() if t else ""

    def meta(**kw):
        t = soup.find("meta", kw)
        return (t.get("content") or "").strip() if t else ""

    title = (prop("headline") or meta(property="og:title")
             or (soup.title.get_text(strip=True) if soup.title else ""))
    body = soup.select_one(".bodytext") or soup.select_one("main") or soup.body
    if body:
        for tag in body(["script", "style", "nav", "footer"]):
            tag.decompose()
    text = body.get_text(" ", strip=True) if body else ""
    text = re.sub(r"\s+", " ", text)
    if not title and not text:
        return None
    return {
        "url": rec["url"],
        "title": title[:500],
        "text": text[:200_000],
        "host": re.sub(r"^https?://", "", rec["url"]).split("/")[0].lower(),
        "section": meta(property="article:section") or "",
        "date": (prop("datePublished") or rec.get("lastmod") or "")[:10],
    }


# --------------------------------------------------------------------- mô hình
class CrawlReq(BaseModel):
    mode: str = "resume"            # resume | listing | file | site
    site: str | None = None
    from_file: str | None = None
    max_pages: int = 0
    delay: float = 2.5
    render: str = "never"
    allow_domain: str | None = None
    insecure: bool = False


class IndexReq(BaseModel):
    limit: int = 0                  # 0 = không giới hạn
    batch: int = 200
    reset: bool = False


# ------------------------------------------------------------------- crawl API
def _alive() -> subprocess.Popen | None:
    p = _job.get("proc")
    return p if p and p.poll() is None else None


@app.post("/api/crawl/start")
def crawl_start(req: CrawlReq):
    with _job_lock:
        if _alive():
            raise HTTPException(409, "đang có mẻ chạy, dừng trước đã")
        cmd = ["python", "crawl_all.py"]
        if req.mode in ("resume", "listing"):
            cmd.append("--resume")
        if req.mode == "listing":
            cmd += ["--only", "listing"]
        if req.mode == "file":
            if not req.from_file:
                raise HTTPException(400, "mode=file thì phải có from_file")
            cmd += ["--from-file", req.from_file]
        if req.site:
            cmd += ["--site", req.site]
        if req.max_pages:
            cmd += ["--max-pages", str(req.max_pages)]
        if req.allow_domain:
            cmd += ["--allow-domain", req.allow_domain]
        if req.render != "never":
            cmd += ["--render", req.render]
        if req.insecure:
            cmd.append("--insecure")
        cmd += ["--delay", str(req.delay)]

        LOG.parent.mkdir(parents=True, exist_ok=True)
        fh = LOG.open("a", encoding="utf-8")
        proc = subprocess.Popen(cmd, cwd=CRAWLER, stdout=fh, stderr=fh)
        _job.update(proc=proc, cmd=" ".join(cmd), started=time.time())
        PIDFILE.write_text(str(proc.pid), encoding="utf-8")
        return {"ok": True, "pid": proc.pid, "cmd": _job["cmd"]}


@app.post("/api/crawl/stop")
def crawl_stop():
    with _job_lock:
        p = _alive()
        if not p:
            return {"ok": True, "note": "không có mẻ nào đang chạy"}
        # SIGTERM: crawler bắt tín hiệu này để đóng shard và ghi state trước khi thoát
        p.send_signal(signal.SIGTERM)
        for _ in range(30):
            if p.poll() is not None:
                break
            time.sleep(1)
        killed = p.poll() is None
        if killed:
            p.kill()
        PIDFILE.unlink(missing_ok=True)
        return {"ok": True, "graceful": not killed}


@app.get("/api/crawl/status")
def crawl_status():
    p = _alive()
    tail = ""
    if LOG.exists():
        lines = LOG.read_text(encoding="utf-8", errors="replace").splitlines()
        tail = "\n".join(lines[-12:])
    return {
        "running": bool(p),
        "pid": p.pid if p else None,
        "cmd": _job["cmd"] if p else "",
        "elapsed_sec": round(time.time() - _job["started"]) if p else 0,
        "log_tail": tail,
    }


@app.get("/api/stats")
def stats():
    sites = []
    total_pages = total_bytes = 0
    for d in kho_dirs():
        st = d / "state.json"
        s = json.loads(st.read_text(encoding="utf-8")) if st.exists() else {}
        n_shard = len(list(d.glob("pages-*.jsonl*")))
        size = sum(f.stat().st_size for f in d.glob("pages-*.jsonl*"))
        total_pages += len(s.get("done", {}))
        total_bytes += size
        sites.append({
            "host": host_of(d),
            "done": len(s.get("done", {})),
            "queued": len(s.get("frontier", [])),
            "articles": len(s.get("by_key", {})),
            "shards": n_shard,
            "bytes": size,
        })
    sites.sort(key=lambda x: -x["done"])
    links = 0
    lf = next((p for p in sorted(DATA.glob("*links*")) if p.is_file() and not p.suffix), None)
    if lf:
        links = sum(1 for line in lf.read_text(encoding="utf-8").splitlines() if line.strip())
    return {"sites": sites, "total_pages": total_pages, "total_bytes": total_bytes,
            "links_file": lf.name if lf else None, "links": links}


# ------------------------------------------------------------------- index API
@app.post("/api/index/run")
def index_run(req: IndexReq):
    with httpx.Client(base_url=LUCENE, timeout=120) as cli:
        if req.reset:
            cli.post("/reset")
        sent = skipped = 0
        batch: list[dict] = []
        seen: set[str] = set()
        for d in kho_dirs():
            for rec in records(d):
                if rec["url"] in seen:
                    continue
                seen.add(rec["url"])
                doc = extract(rec)
                if not doc:
                    skipped += 1
                    continue
                batch.append(doc)
                if len(batch) >= req.batch:
                    cli.post("/bulk", json=batch).raise_for_status()
                    sent += len(batch)
                    batch = []
                if req.limit and sent >= req.limit:
                    break
            if req.limit and sent >= req.limit:
                break
        if batch:
            cli.post("/bulk", json=batch).raise_for_status()
            sent += len(batch)
        total = cli.get("/stats").json()
    return {"indexed": sent, "skipped_non_html": skipped, "index": total}


@app.get("/api/index/stats")
def index_stats():
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        return cli.get("/stats").json()


@app.get("/api/search")
def search(q: str, from_: int = 0, size: int = 10, host: str | None = None):
    params = {"q": q, "from": from_, "size": size}
    if host:
        params["host"] = host
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        r = cli.get("/search", params=params)
        return JSONResponse(r.json(), status_code=r.status_code)


@app.get("/api/health")
def health():
    try:
        with httpx.Client(base_url=LUCENE, timeout=5) as cli:
            lucene_ok = cli.get("/health").status_code == 200
    except Exception:
        lucene_ok = False
    return {"api": True, "lucene": lucene_ok, "crawler_dir": str(CRAWLER), "data_dir": str(DATA)}


# --------------------------------------------------------------------- giao diện
@app.get("/")
def home():
    return FileResponse(STATIC / "index.html")


app.mount("/static", StaticFiles(directory=STATIC), name="static")
