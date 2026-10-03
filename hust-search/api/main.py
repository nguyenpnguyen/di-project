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
import urllib.parse
from typing import Iterator

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator, model_validator

from boc_tach import boc_tach, lien_ket
import trich
from routes_bt import router as bt_router
from boc_tach.html_sach import clean_space, don_html, join_http

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


def tim_ban_ghi(urls: set[str]) -> dict | None:
    """Bản ghi kho thô đầu tiên có url (đã norm) thuộc `urls`. Lọc thô bằng chuỗi
    đường dẫn trước khi json.loads — kho hàng trăm MB, giải mã mọi dòng thì chậm."""
    dich = {trich.crawl_all.norm(u) or u for u in urls}
    khoa = set()
    for u in dich:
        path = urllib.parse.urlsplit(u).path or "/"
        khoa.update({path, json.dumps(path)[1:-1]})
    for d in kho_dirs():
        for p in sorted(list(d.glob("pages-*.jsonl.gz")) + list(d.glob("pages-*.jsonl"))):
            op = gzip.open if p.suffix == ".gz" else open
            try:
                with op(p, "rt", encoding="utf-8") as fh:
                    for line in fh:
                        if not any(k in line for k in khoa):
                            continue
                        try:
                            rec = json.loads(line)
                        except json.JSONDecodeError:
                            break
                        if (trich.crawl_all.norm(rec.get("url", "")) or rec.get("url")) in dich:
                            return rec
            except (EOFError, OSError, gzip.BadGzipFile):
                continue
    return None


_join_http = join_http
_clean_space = clean_space


def outgoing_links(body, base_url: str) -> list[dict]:
    """Liên kết href trong phần thân, chuẩn hoá qua norm() và khử trùng theo url."""
    if body is None:
        return []
    noi_dung, _ = lien_ket.chia(lien_ket.thu_thap(body, base_url), body)
    return lien_ket.canh_ra_cong_khai(noi_dung)


def _date_or_empty(value: str | None) -> str:
    value = (value or "").strip()[:10]
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        return ""
    try:
        time.strptime(value, "%Y-%m-%d")
    except ValueError:
        return ""
    return value


def extract(rec: dict, khuon: set[str] | None = None) -> dict | None:
    """Bản ghi thô -> tài liệu bóc tách. None nếu không phải trang đọc được."""
    if not rec.get("html_b64") or (rec.get("status") or 0) >= 400:
        return None
    html = base64.b64decode(rec["html_b64"]).decode(rec.get("encoding") or "utf-8", "replace")
    return boc_tach(html, rec["url"], khuon)


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


class OutgoingLink(BaseModel):
    url: str
    text: str = ""

    @field_validator("url")
    @classmethod
    def http_only(cls, value: str) -> str:
        parsed = urllib.parse.urlsplit(value.strip())
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
            raise ValueError("url phải dùng HTTP hoặc HTTPS")
        return value.strip()


class PublicDocument(BaseModel):
    url: str
    title: str = ""
    content: str = ""
    host: str = ""
    section: str = ""
    published_at: str = ""
    author: str = ""
    kind: str = "page"
    outgoing_links: list[OutgoingLink] = Field(default_factory=list)

    @field_validator("url")
    @classmethod
    def valid_url(cls, value: str) -> str:
        value = value.strip()
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
            raise ValueError("url phải là HTTP hoặc HTTPS đầy đủ")
        return value

    @field_validator("kind")
    @classmethod
    def valid_kind(cls, value: str) -> str:
        if value not in {"page", "document"}:
            raise ValueError("kind phải là page hoặc document")
        return value

    @field_validator("title", mode="before")
    @classmethod
    def cap_title(cls, value: str | None) -> str:
        if value is None:
            return ""
        if not isinstance(value, str):
            raise ValueError("title phải là chuỗi")
        return value[:500]

    @field_validator("content", mode="before")
    @classmethod
    def cap_content(cls, value: str | None) -> str:
        if value is None:
            return ""
        if not isinstance(value, str):
            raise ValueError("content phải là chuỗi")
        return value[:200_000]

    @field_validator("published_at", mode="before")
    @classmethod
    def cap_date(cls, value: str | None) -> str:
        if value is None:
            return ""
        if not isinstance(value, str):
            raise ValueError("published_at phải là chuỗi")
        return value.strip()[:10]

    @field_validator("published_at")
    @classmethod
    def valid_date(cls, value: str) -> str:
        if value:
            try:
                time.strptime(value, "%Y-%m-%d")
            except ValueError as e:
                raise ValueError("published_at phải có dạng YYYY-MM-DD") from e
        return value

    @model_validator(mode="after")
    def has_text(self):
        if not self.title.strip() and not self.content.strip():
            raise ValueError("cần ít nhất title hoặc content không rỗng")
        return self


class LayReq(BaseModel):
    url: str
    render: bool = False


class IndexReq(BaseModel):
    limit: int = Field(default=0, ge=0)  # 0 = không giới hạn
    # mongo: đọc kết quả đã bóc tách; raw: bóc lại từ kho thô (cách cũ, không cần Mongo);
    # auto: mongo nếu collection pages có dữ liệu, không thì raw.
    source: str = "auto"
    batch: int = Field(default=200, ge=1, le=1000)
    reset: bool = False


class DocumentsReq(BaseModel):
    reset: bool = False
    batch: int = Field(default=200, ge=1, le=1000)
    documents: list[PublicDocument] = Field(max_length=5000)


def public_document(d: dict) -> dict:
    """Ánh xạ tên nội bộ sang schema dùng chung ngoài API."""
    return PublicDocument(
        url=d["url"], title=d.get("title", ""), content=d.get("text", ""),
        host=d.get("host", ""), section=d.get("section", ""),
        published_at=_date_or_empty(d.get("date")), author=d.get("author", "")[:200],
        outgoing_links=d.get("outgoing_links", []),
    ).model_dump()


def lucene_document(d: PublicDocument | dict) -> dict:
    if isinstance(d, PublicDocument):
        host = d.host.strip().lower() or (urllib.parse.urlsplit(d.url).hostname or "").lower()
        return {"url": d.url, "title": d.title, "text": d.content,
                "host": host, "section": d.section, "date": d.published_at,
                "author": d.author, "kind": d.kind}
    return {"url": d["url"], "title": d.get("title", ""), "text": d.get("text", ""),
            "host": d.get("host", ""), "section": d.get("section", ""),
            "date": d.get("date", ""), "html": d.get("html", ""),
            "author": d.get("author", ""), "kind": d.get("kind", "page")}


# ------------------------------------------------------------------- crawl API
def _mongo():
    from routes_bt import mdb
    return mdb()


def _alive() -> subprocess.Popen | None:
    p = _job.get("proc")
    return p if p and p.poll() is None else None


@app.post("/api/crawl/start")
def crawl_start(req: CrawlReq):
    with _job_lock:
        if _alive():
            raise HTTPException(409, "đang có mẻ chạy, dừng trước đã")
        from routes_bt import _job as _bt_job
        if _bt_job["running"] and _bt_job["what"] == "files":
            raise HTTPException(409, "đang tải tệp tài liệu, đợi xong rồi hãy crawl")
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
def _nguon_index(source: str):
    """Chọn nơi lấy tài liệu để index. Trả (tên nguồn, iterator các dict cho Lucene)."""
    if source not in {"auto", "mongo", "raw"}:
        raise HTTPException(400, "source phải là auto, mongo hoặc raw")
    d = None
    try:
        d = _mongo()
    except HTTPException:
        if source == "mongo":
            raise
    if d is not None and (source == "mongo" or (source == "auto" and d.pages.count_documents({}, limit=1))):
        return "mongo", trich.lucene_tu_mongo(d)

    def tu_kho():
        for rec in tat_ca_ban_ghi():
            doc = extract(rec)
            if doc:
                yield lucene_document(doc)
        # Tệp tài liệu không có chữ trong kho crawl — thiếu bước này thì "dựng lại từ đầu"
        # bằng kho thô (hoặc tự chọn khi Mongo chưa có trang) xoá mất mọi pdf/docx khỏi chỉ mục.
        if d is not None:
            yield from trich.tep_tu_mongo(d)
    return "raw", tu_kho()


@app.post("/api/index/run")
def index_run(req: IndexReq):
    nguon, docs = _nguon_index(req.source)
    with httpx.Client(base_url=LUCENE, timeout=120) as cli:
        if req.reset:
            cli.post("/reset").raise_for_status()
        sent = 0
        batch: list[dict] = []
        for doc in docs:
            batch.append(doc)
            if len(batch) >= req.batch:
                cli.post("/bulk", json=batch).raise_for_status()
                sent += len(batch)
                batch = []
            if req.limit and sent + len(batch) >= req.limit:
                break
        if batch:
            cli.post("/bulk", json=batch).raise_for_status()
            sent += len(batch)
        total = cli.get("/stats")
        total.raise_for_status()
    return {"indexed": sent, "source": nguon, "index": total.json()}


@app.post("/api/index/documents")
def index_documents(req: DocumentsReq):
    """Nhận corpus theo schema public rồi chia nhỏ sang Lucene."""
    unique: dict[str, PublicDocument] = {}
    duplicates = 0
    for doc in req.documents:
        if doc.url in unique:
            duplicates += 1
        unique[doc.url] = doc

    with httpx.Client(base_url=LUCENE, timeout=120) as cli:
        try:
            if req.reset:
                cli.post("/reset").raise_for_status()
            docs = [lucene_document(d) for d in unique.values()]
            for start in range(0, len(docs), req.batch):
                cli.post("/bulk", json=docs[start:start + req.batch]).raise_for_status()
            total = cli.get("/stats")
            total.raise_for_status()
        except httpx.HTTPError as e:
            raise HTTPException(502, f"Lucene không sẵn sàng: {e}") from e
    return {"indexed": len(docs), "duplicates_in_request": duplicates,
            "index": total.json()}


@app.get("/api/index/stats")
def index_stats():
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        return cli.get("/stats").json()


@app.get("/api/search")
def search(q: str, from_: int = 0, size: int = 10, host: str | None = None,
           date_from: str | None = None, date_to: str | None = None,
           sort: str = "score", ranking: str = "tfidf", kind: str | None = None):
    """Chuyển thẳng sang Lucene. Tầng này không tự lọc gì: lọc ở Lucene thì bộ
    đếm tổng và việc chia trang mới khớp nhau."""
    ranking = ranking.strip().lower()
    if ranking not in {"tfidf", "enhanced"}:
        raise HTTPException(400, "ranking phải là tfidf hoặc enhanced")
    params: dict = {"q": q, "from": from_, "size": size, "ranking": ranking}
    for k, v in (("host", host), ("date_from", date_from), ("date_to", date_to), ("kind", kind)):
        if v:
            params[k] = v
    if sort == "date":
        params["sort"] = "date"
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        r = cli.get("/search", params=params)
        return JSONResponse(r.json(), status_code=r.status_code)


@app.get("/api/index/list")
def index_list(from_: int = 0, size: int = 20, host: str | None = None, sort: str = "date",
               kind: str | None = None):
    """Liệt kê toàn bộ tài liệu đã index, không cần từ khoá — cho tab Duyệt tất cả,
    để hình dung tổng thể kho (bao nhiêu bài, thuộc site nào, thời gian nào) thay vì
    chỉ xem con số tổng như /api/index/stats."""
    params: dict = {"from": from_, "size": size}
    if host:
        params["host"] = host
    if kind:
        params["kind"] = kind
    if sort == "url":
        params["sort"] = "url"
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        r = cli.get("/list", params=params)
        return JSONResponse(r.json(), status_code=r.status_code)


@app.get("/api/index/dict")
def index_dict(field: str = "text", after: str | None = None, limit: int = 50):
    """Duyệt từ điển chỉ mục ngược (term dictionary) của một field — cho tab
    "Chỉ mục ngược": xem trực tiếp cấu trúc từ -> (docFreq, totalTermFreq)."""
    params: dict = {"field": field, "limit": limit}
    if after:
        params["after"] = after
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        r = cli.get("/dict", params=params)
        return JSONResponse(r.json(), status_code=r.status_code)


@app.get("/api/index/posting")
def index_posting(field: str = "text", term: str = "", limit: int = 50):
    """Posting list đầy đủ của một từ: docFreq/totalTermFreq và danh sách tài
    liệu chứa từ đó kèm tần suất + vị trí token."""
    if not term.strip():
        raise HTTPException(400, "thiếu tham số term")
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        r = cli.get("/posting", params={"field": field, "term": term, "limit": limit})
        return JSONResponse(r.json(), status_code=r.status_code)


# --------------------------------------------------- tải lẻ một url và index ngay
ADHOC = DATA / "raw-adhoc"
_lan_tai = {"luc": 0.0}
_tai_lock = threading.Lock()
NHIP_TOI_THIEU = 3.0        # giây giữa hai lần tải lẻ, cùng nhịp với --delay


def _ghi_kho(rec: dict) -> None:
    """Ghi vào kho riêng raw-adhoc, không đụng vào kho của crawler.

    Cố ý tách kho: crawl_all.py đang chạy nền giữ state.json của từng host mở
    suốt mẻ, ghi chen vào đó thì hai bên đạp lên nhau ngay. raw-adhoc có shard
    riêng, và vì kho_dirs() quét mọi thư mục raw* nên lần index đầy đủ sau này
    vẫn gom được.
    """
    ADHOC.mkdir(parents=True, exist_ok=True)
    p = ADHOC / "pages-0001.jsonl.gz"
    with gzip.open(p, "at", encoding="utf-8") as fh:
        fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
        fh.flush()          # flush từng dòng: cùng lý do như crawl_all.py


def _ghi_mongo(doc: dict, rec: dict) -> None:
    """Ghi trang tải lẻ vào Mongo; Mongo chưa lên thì bỏ qua, tải lẻ vẫn thành công."""
    try:
        d = _mongo()
        html = base64.b64decode(rec["html_b64"]).decode(rec.get("encoding") or "utf-8", "replace")
        trich.ghi_mot_trang(d, doc["url"], doc, rec, html)
    except Exception as e:
        print(f"[mongo] không ghi được trang tải lẻ: {e}", flush=True)


UA_TRINH_DUYET = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")


def tai_ve(url: str) -> httpx.Response:
    """Tải một url theo nhịp chung của mọi lần tải lẻ (/api/fetch, /api/extract/url).

    Chặn nhịp ở phía máy chủ chứ không tin vào giao diện: bấm nhanh tay hay mở
    hai tab là thành bắn liên tiếp, đúng kiểu ăn 429.
    """
    with _tai_lock:
        cho = NHIP_TOI_THIEU - (time.time() - _lan_tai["luc"])
        if cho > 0:
            time.sleep(cho)
        _lan_tai["luc"] = time.time()
    try:
        with httpx.Client(timeout=30, follow_redirects=True,
                          headers={"User-Agent": UA_TRINH_DUYET}) as cli:
            r = cli.get(url)
    except Exception as e:
        raise HTTPException(502, f"không tải được: {e}")
    if r.status_code == 429:
        raise HTTPException(429, "site đang chặn nhịp, đợi rồi thử lại")
    if r.status_code >= 400:
        raise HTTPException(502, f"site trả HTTP {r.status_code}")
    return r


@app.post("/api/fetch")
def fetch_one(req: LayReq):
    """Tải đúng một url, lưu kho, bóc chữ rồi đẩy thẳng vào Lucene.

    Trả về luôn kết quả bóc được để giao diện hiện ra ngay, không phải đợi một
    mẻ index nào cả — tải xong là tìm được.
    """
    url = (req.url or "").strip()
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
        raise HTTPException(422, "url phải là HTTP hoặc HTTPS đầy đủ")

    r = tai_ve(url)

    rec = {
        "url": str(r.url),
        "status": r.status_code,
        "encoding": r.encoding or "utf-8",
        "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "html_b64": base64.b64encode(r.content).decode("ascii"),
        "kind": "adhoc",
    }
    doc = extract(rec)
    if not doc:
        raise HTTPException(422, "tải được nhưng không bóc ra chữ nào — trang rỗng hoặc dựng bằng JS")

    try:
        with httpx.Client(base_url=LUCENE, timeout=60) as cli:
            cli.post("/bulk", json=[lucene_document(doc)]).raise_for_status()
            tong = cli.get("/stats")
            tong.raise_for_status()
            tong = tong.json().get("docs", 0)
    except httpx.HTTPError as e:
        raise HTTPException(502, f"Lucene không sẵn sàng: {e}") from e
    _ghi_kho(rec)
    _ghi_mongo(doc, rec)
    document = public_document(doc)

    return {
        "ok": True,
        "url": doc["url"],
        "title": doc["title"],
        "host": doc["host"],
        "date": doc["date"],
        "chars": len(doc["text"]),
        "bytes": len(r.content),
        "status": r.status_code,
        "indexed": True,
        "index_docs": tong,
        "preview": doc["html"][:4000],
        "document": document,
    }


@app.get("/api/preview")
def preview(url: str):
    """HTML đã dọn của một trang đã index, để nhúng vào khung xem trước.

    Lấy từ index của mình, không gọi lại hust.edu.vn — nên mở bao nhiêu lần
    cũng không tốn một request nào của site và không bao giờ bị chặn.
    """
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        r = cli.get("/doc", params={"url": url})
    if r.status_code != 200:
        raise HTTPException(r.status_code, "chưa có trang này trong index")
    return r.json()


@app.get("/api/health")
def health():
    try:
        with httpx.Client(base_url=LUCENE, timeout=5) as cli:
            lucene_ok = cli.get("/health").status_code == 200
    except Exception:
        lucene_ok = False
    return {"api": True, "lucene": lucene_ok, "crawler_dir": str(CRAWLER), "data_dir": str(DATA)}


def tat_ca_ban_ghi() -> Iterator[dict]:
    """Mọi bản ghi của mọi kho, bỏ url trùng."""
    seen: set[str] = set()
    for d in kho_dirs():
        for rec in records(d):
            if rec["url"] not in seen:
                seen.add(rec["url"])
                yield rec


# --------------------------------------------------------------------- giao diện
@app.get("/")
def home():
    return FileResponse(STATIC / "index.html")


app.include_router(bt_router)


@app.on_event("startup")
def _khoi_tao_mongo():
    """Tạo lược đồ Mongo nếu kết nối được; Mongo chưa lên thì API vẫn chạy (tìm kiếm
    không phụ thuộc Mongo), các route bóc tách sẽ trả 503."""
    try:
        import db
        db.init(db.get_db())
    except Exception as e:
        print(f"[mongo] chưa khởi tạo được lược đồ: {e}", flush=True)


app.mount("/static", StaticFiles(directory=STATIC), name="static")
