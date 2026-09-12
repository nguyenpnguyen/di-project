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
from bs4 import BeautifulSoup
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator, model_validator

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


def _join_http(base_url: str, href: str) -> str:
    """Ghép url và chỉ trả về link HTTP(S), bỏ fragment."""
    href = (href or "").strip()
    if not href:
        return ""
    url = urllib.parse.urldefrag(urllib.parse.urljoin(base_url, href))[0]
    parsed = urllib.parse.urlsplit(url)
    return url if parsed.scheme.lower() in {"http", "https"} and parsed.netloc else ""


def _clean_space(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def outgoing_links(body, base_url: str) -> list[dict]:
    """Bóc các liên kết trong phần thân, chuẩn hóa và khử trùng theo URL."""
    out: list[dict] = []
    seen: dict[str, int] = {}
    if body is None:
        return out
    for anchor in body.select("a[href]"):
        url = _join_http(base_url, anchor.get("href") or "")
        if not url:
            continue
        text = _clean_space(anchor.get_text(" ", strip=True))
        if not text:
            text = _clean_space(anchor.get("title") or anchor.get("aria-label") or "")
        if url in seen:
            i = seen[url]
            if not out[i]["text"] and text:
                out[i]["text"] = text
            continue
        seen[url] = len(out)
        out.append({"url": url, "text": text})
    return out


def _date_or_empty(value: str | None) -> str:
    value = (value or "").strip()[:10]
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        return ""
    try:
        time.strptime(value, "%Y-%m-%d")
    except ValueError:
        return ""
    return value


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
    parsed = urllib.parse.urlsplit(rec["url"])
    return {
        "url": rec["url"],
        "title": title[:500],
        "text": text[:200_000],
        "html": don_html(body, rec["url"]),
        "host": (parsed.hostname or "").lower(),
        "section": meta(property="article:section") or "",
        "date": (prop("datePublished") or rec.get("lastmod") or "")[:10],
        "outgoing_links": outgoing_links(body, rec["url"]),
    }


# thẻ giữ lại khi dọn: đủ để trang xem trước còn ra hình hài bài báo
THE_GIU = {"p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
           "blockquote", "figure", "figcaption", "table", "thead", "tbody", "tr",
           "th", "td", "strong", "b", "em", "i", "u", "sub", "sup", "img", "a",
           "div", "span", "section", "article"}
THUOC_TINH_GIU = {"a": ["href"], "img": ["src", "alt"]}


def don_html(body, goc: str) -> str:
    """HTML rút gọn để xem trước, dựng từ bản đã tải chứ không gọi lại site.

    Bỏ script/style/form/iframe và toàn bộ thuộc tính trừ href/src/alt: giữ
    nguyên class của trang gốc thì phải kéo cả CSS của họ về mới ra hình, mà
    kéo CSS nghĩa là mỗi lần xem trước lại bắn thêm chục request sang
    hust.edu.vn — đúng cái đang bị chặn. Bỏ hết rồi tự tô bằng CSS của mình thì
    trang xem trước không phát sinh request nào ngoài ảnh.
    """
    if body is None:
        return ""
    ban = BeautifulSoup(str(body), "lxml")
    for tag in ban(["script", "style", "form", "iframe", "noscript", "svg",
                    "button", "input", "select", "nav", "footer", "header"]):
        tag.decompose()
    for tag in ban.find_all(True):
        if tag.name not in THE_GIU:
            tag.unwrap()
            continue
        giu = THUOC_TINH_GIU.get(tag.name, [])
        tag.attrs = {k: v for k, v in tag.attrs.items() if k in giu}
        if tag.name == "img":
            src = _join_http(goc, tag.get("src") or "")
            if not src:
                tag.decompose()
                continue
            tag["src"] = src
            tag["loading"] = "lazy"
        elif tag.name == "a":
            href = _join_http(goc, tag.get("href") or "")
            if not href:
                tag.unwrap()
                continue
            tag["href"] = href
            tag["target"] = "_blank"
            tag["rel"] = "noopener"
    out = str(ban)
    # Chặn ở 40 KB: trang xem trước chỉ cần đủ nhận ra bài, mà 2.800 tài liệu
    # nhân vài chục KB là index phình lên hàng trăm MB cho một thứ chỉ dùng cho
    # năm kết quả đầu mỗi lượt tìm.
    return out[:40_000]


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
    outgoing_links: list[OutgoingLink] = Field(default_factory=list)

    @field_validator("url")
    @classmethod
    def valid_url(cls, value: str) -> str:
        value = value.strip()
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
            raise ValueError("url phải là HTTP hoặc HTTPS đầy đủ")
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
        published_at=_date_or_empty(d.get("date")),
        outgoing_links=d.get("outgoing_links", []),
    ).model_dump()


def lucene_document(d: PublicDocument | dict) -> dict:
    if isinstance(d, PublicDocument):
        host = d.host.strip().lower() or (urllib.parse.urlsplit(d.url).hostname or "").lower()
        return {"url": d.url, "title": d.title, "text": d.content,
                "host": host, "section": d.section, "date": d.published_at}
    return {"url": d["url"], "title": d.get("title", ""), "text": d.get("text", ""),
            "host": d.get("host", ""), "section": d.get("section", ""),
            "date": d.get("date", ""), "html": d.get("html", "")}


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
            cli.post("/reset").raise_for_status()
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
                batch.append(lucene_document(doc))
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
        total = cli.get("/stats")
        total.raise_for_status()
    return {"indexed": sent, "skipped_non_html": skipped, "index": total.json()}


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
           sort: str = "score", ranking: str = "tfidf"):
    """Chuyển thẳng sang Lucene. Tầng này không tự lọc gì: lọc ở Lucene thì bộ
    đếm tổng và việc chia trang mới khớp nhau."""
    ranking = ranking.strip().lower()
    if ranking not in {"tfidf", "enhanced"}:
        raise HTTPException(400, "ranking phải là tfidf hoặc enhanced")
    params: dict = {"q": q, "from": from_, "size": size, "ranking": ranking}
    for k, v in (("host", host), ("date_from", date_from), ("date_to", date_to)):
        if v:
            params[k] = v
    if sort == "date":
        params["sort"] = "date"
    with httpx.Client(base_url=LUCENE, timeout=30) as cli:
        r = cli.get("/search", params=params)
        return JSONResponse(r.json(), status_code=r.status_code)


@app.get("/api/index/list")
def index_list(from_: int = 0, size: int = 20, host: str | None = None, sort: str = "date"):
    """Liệt kê toàn bộ tài liệu đã index, không cần từ khoá — cho tab Duyệt tất cả,
    để hình dung tổng thể kho (bao nhiêu bài, thuộc site nào, thời gian nào) thay vì
    chỉ xem con số tổng như /api/index/stats."""
    params: dict = {"from": from_, "size": size}
    if host:
        params["host"] = host
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

    # Chặn nhịp ở phía máy chủ chứ không tin vào giao diện: bấm nhanh tay hay mở
    # hai tab là thành bắn liên tiếp, đúng kiểu ăn 429.
    with _tai_lock:
        cho = NHIP_TOI_THIEU - (time.time() - _lan_tai["luc"])
        if cho > 0:
            time.sleep(cho)
        _lan_tai["luc"] = time.time()

    ua = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
    try:
        with httpx.Client(timeout=30, follow_redirects=True,
                          headers={"User-Agent": ua}) as cli:
            r = cli.get(url)
    except Exception as e:
        raise HTTPException(502, f"không tải được: {e}")

    if r.status_code == 429:
        raise HTTPException(429, "site đang chặn nhịp, đợi rồi thử lại")
    if r.status_code >= 400:
        raise HTTPException(502, f"site trả HTTP {r.status_code}")

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


# --------------------------------------------------------------------- giao diện
@app.get("/")
def home():
    return FileResponse(STATIC / "index.html")


app.mount("/static", StaticFiles(directory=STATIC), name="static")
