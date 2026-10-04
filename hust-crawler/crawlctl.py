"""
Điều khiển crawler qua HTTP, cho service `crawler` trong docker compose.

Java (`hust-search`) chuyển tiếp /api/crawl/start|stop|status sang đây. Chỉ nghe trong mạng docker,
không có xác thực, đừng mở cổng ra ngoài. Chỉ dùng stdlib. Một mẻ crawl tại một thời điểm: nhiều mẻ
song song sẽ đạp nhau ở state.json. Lỗi luôn là {"detail": "..."} để giao diện đọc như mọi route khác.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import signal
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = pathlib.Path(__file__).resolve().parent
DATA = HERE / "data"
PIDFILE = DATA / "crawler.pid"
LOG = DATA / "crawl_all.log"

_lock = threading.Lock()
_job: dict = {"proc": None, "cmd": "", "started": 0.0}


class Loi(Exception):
    def __init__(self, code: int, detail: str):
        self.code, self.detail = code, detail


def _alive() -> subprocess.Popen | None:
    p = _job["proc"]
    return p if p and p.poll() is None else None


def crawl_start(req: dict) -> dict:
    with _lock:
        if _alive():
            raise Loi(409, "đang có mẻ chạy, dừng trước đã")
        mode = req.get("mode", "resume")          # resume | listing | file | site
        cmd = ["python", "crawl_all.py"]
        if mode in ("resume", "listing"):
            cmd.append("--resume")
        if mode == "listing":
            cmd += ["--only", "listing"]
        if mode == "file":
            if not req.get("from_file"):
                raise Loi(400, "mode=file thì phải có from_file")
            cmd += ["--from-file", str(req["from_file"])]
        if req.get("site"):
            cmd += ["--site", str(req["site"])]
        if req.get("max_pages"):
            cmd += ["--max-pages", str(int(req["max_pages"]))]
        if req.get("allow_domain"):
            cmd += ["--allow-domain", str(req["allow_domain"])]
        if req.get("insecure"):
            cmd.append("--insecure")
        cmd += ["--delay", str(float(req.get("delay", 2.5)))]

        LOG.parent.mkdir(parents=True, exist_ok=True)
        fh = LOG.open("a", encoding="utf-8")
        proc = subprocess.Popen(cmd, cwd=HERE, stdout=fh, stderr=fh)
        _job.update(proc=proc, cmd=" ".join(cmd), started=time.time())
        PIDFILE.write_text(str(proc.pid), encoding="utf-8")
        return {"ok": True, "pid": proc.pid, "cmd": _job["cmd"]}


def crawl_stop() -> dict:
    with _lock:
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


def crawl_status() -> dict:
    p = _alive()
    tail = ""
    if LOG.exists():
        tail = "\n".join(LOG.read_text(encoding="utf-8", errors="replace").splitlines()[-12:])
    return {"running": bool(p), "pid": p.pid if p else None, "cmd": _job["cmd"] if p else "",
            "elapsed_sec": round(time.time() - _job["started"]) if p else 0, "log_tail": tail}


class Handler(BaseHTTPRequestHandler):
    def _gui(self, code: int, body: dict) -> None:
        b = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _xu_ly(self, method: str) -> None:
        try:
            if (method, self.path) == ("GET", "/status"):
                out = crawl_status()
            elif (method, self.path) == ("POST", "/stop"):
                out = crawl_stop()
            elif (method, self.path) == ("POST", "/start"):
                n = int(self.headers.get("Content-Length") or 0)
                try:
                    req = json.loads(self.rfile.read(n) or b"{}")
                except json.JSONDecodeError as e:
                    raise Loi(422, f"JSON không hợp lệ: {e}")
                if not isinstance(req, dict):
                    raise Loi(422, "body phải là một đối tượng JSON")
                out = crawl_start(req)
            else:
                raise Loi(404, "Not Found")
            self._gui(200, out)
        except Loi as e:
            self._gui(e.code, {"detail": e.detail})
        except (ValueError, TypeError) as e:       # max_pages / delay sai kiểu
            self._gui(422, {"detail": str(e)})

    def do_GET(self):
        self._xu_ly("GET")

    def do_POST(self):
        self._xu_ly("POST")

    def log_message(self, *a):                     # status được hỏi liên tục, đừng làm ngập log
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--host", default="0.0.0.0")
    a = ap.parse_args()
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
