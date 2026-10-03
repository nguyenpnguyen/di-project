#!/usr/bin/env python3
"""So sánh chỉ mục dựng bằng cách bóc CŨ và cách bóc MỚI (boc_tach) trên cùng kho thô.

Trả lời câu hỏi: bóc tách mới có làm tìm kiếm tốt hơn không, hay chỉ làm text khác đi?

    cũ : .bodytext -> main -> body (đúng hành vi extract() trước khi có boc_tach)
    mới: boc_tach (selector theo host, khử khuôn theo host, chấm điểm khối)

Hai arm được index vào HAI instance Lucene RIÊNG, không đụng index chính:

    docker compose --profile compare up -d --build lucene-cu lucene-moi
    docker compose exec api python so_sanh.py                  # bước 1
    # mở <data>/so_sanh/phan_loai.csv, điền cột relevant = 1 hoặc 0
    docker compose exec api python so_sanh.py --evaluate /crawler/data/so_sanh/phan_loai.csv   # bước 2

Bước 1 (không cần nhãn) cho biết HAI chỉ mục khác nhau thế nào: độ trùng top-K, từ nào
bị mất/thêm, từ nào có mặt ở gần mọi trang (rác khuôn), trang nào bị cắt quá tay.
Nó KHÔNG cho biết bên nào tốt hơn. Muốn kết luận phải có bước 2: bạn gán nhãn liên quan
cho các kết quả, script tính precision@K và MRR cho từng arm.

Chạy ngoài docker: đặt --old-url/--new-url (mặc định localhost:8091 / 8092) và
PYTHONPATH tới hust-crawler (hoặc để html_sach tự tìm thư mục cạnh hust-search).
"""
from __future__ import annotations

import argparse
import base64
import collections
import csv
import gzip
import json
import os
import pathlib
import re
import statistics
import sys
import time
import unicodedata
import urllib.parse

import httpx
from bs4 import BeautifulSoup

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from boc_tach import boc_tach, khuon as _khuon  # noqa: E402

# Truy vấn mặc định: đoán của tôi về chủ đề hay gặp trên site trường, CHƯA kiểm với kho thật.
# Vài từ cuối ("liên hệ"...) cố ý là từ hay nằm trong menu/footer để dò nhiễu khuôn.
# Thay bằng truy vấn người dùng thật của bạn qua --queries thì kết luận mới đáng tin.
TRUY_VAN_MAC_DINH = [
    "điểm chuẩn", "tuyển sinh", "học bổng", "thư viện", "học phí", "lịch thi",
    "tốt nghiệp", "đăng ký học phần", "kỹ thuật máy tính", "hợp tác quốc tế",
    "nghiên cứu khoa học", "tuyển dụng", "giảng viên", "lễ khai giảng",
    "chương trình đào tạo", "thạc sĩ", "tiến sĩ", "ký túc xá", "câu lạc bộ",
    "trao đổi sinh viên", "hội thảo", "đại học bách khoa hà nội",
    "liên hệ", "giới thiệu", "tin tức",
]
MIN_DOC_CHUA_CAT = 2000       # trang cũ dài từ chừng này trở lên mới xét "cắt quá tay"
TY_LE_CAT = 0.15              # bản mới ngắn hơn 15% bản cũ thì nghi cắt quá tay
VI_DU_TOI_DA = 5


# ----------------------------------------------------------------------- tiện ích
def fold(s: str) -> str:
    """Thường hoá + bỏ dấu, giữ độ dài (NFC trước) để cắt đoạn trích theo vị trí được."""
    s = unicodedata.normalize("NFC", s or "").lower()
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")
    return s.replace("đ", "d")


def tokens(s: str) -> set[str]:
    return set(re.findall(r"\w+", fold(s)))


def kho_dirs(data: pathlib.Path) -> list[pathlib.Path]:
    return sorted(p for p in data.glob("raw*") if p.is_dir())


def records(d: pathlib.Path):
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


def trang_html(data: pathlib.Path, limit: int = 0):
    """(rec, html) của mọi trang đọc được, bỏ url trùng — cùng luật với index/run cũ."""
    seen: set[str] = set()
    n = 0
    for d in kho_dirs(data):
        for rec in records(d):
            if rec["url"] in seen:
                continue
            seen.add(rec["url"])
            if not rec.get("html_b64") or (rec.get("status") or 0) >= 400:
                continue
            yield rec, base64.b64decode(rec["html_b64"]).decode(rec.get("encoding") or "utf-8", "replace")
            n += 1
            if limit and n >= limit:
                return


def extract_cu(rec: dict, html: str) -> dict | None:
    """Bản sao nguyên văn hành vi extract() TRƯỚC khi có boc_tach (commit 8b50a30)."""
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
    text = re.sub(r"\s+", " ", body.get_text(" ", strip=True) if body else "")
    if not title and not text:
        return None
    host = (urllib.parse.urlsplit(rec["url"]).hostname or "").lower()
    return {"url": rec["url"], "title": title[:500], "text": text[:200_000], "host": host,
            "section": meta(property="article:section") or "",
            "date": (prop("datePublished") or rec.get("lastmod") or "")[:10]}


def doc_lucene(d: dict) -> dict:
    return {"url": d["url"], "title": d["title"], "text": d["text"], "host": d["host"],
            "section": d.get("section", ""), "date": d.get("date", ""),
            "author": d.get("author", ""), "kind": "page"}


# ------------------------------------------------------------------- Lucene
class Arm:
    def __init__(self, ten: str, url: str, timeout: float = 120):
        self.ten, self.url = ten, url.rstrip("/")
        self.cli = httpx.Client(base_url=self.url, timeout=timeout)
        self.buf: list[dict] = []

    def stats(self) -> dict:
        r = self.cli.get("/stats")
        r.raise_for_status()
        return r.json()

    def chuan_bi(self, reset: bool):
        try:
            n = self.stats().get("docs", 0)
        except httpx.HTTPError as e:
            sys.exit(f"[{self.ten}] không nối được {self.url}: {e}\n"
                     "Đã bật profile compare chưa? docker compose --profile compare up -d lucene-cu lucene-moi")
        if n and not reset:
            sys.exit(f"[{self.ten}] {self.url} đang có {n} tài liệu. Chỉ dùng --reset nếu CHẮC đây là "
                     "instance riêng cho so sánh, không phải index chính (cổng 8081).")
        self.cli.post("/reset").raise_for_status()

    def them(self, d: dict, batch: int = 200):
        self.buf.append(doc_lucene(d))
        if len(self.buf) >= batch:
            self.xa()

    def xa(self):
        if self.buf:
            self.cli.post("/bulk", json=self.buf).raise_for_status()
            self.buf = []

    def tim(self, q: str, k: int, ranking: str) -> dict | None:
        r = self.cli.get("/search", params={"q": q, "size": k, "ranking": ranking})
        return r.json() if r.status_code == 200 else None


# ------------------------------------------------------------------- độ đo
def overlap_at_k(a: list[str], b: list[str]) -> float:
    """|A∩B| / max(|A|,|B|) trên top-K; hai danh sách rỗng coi là trùng hoàn toàn."""
    if not a and not b:
        return 1.0
    return len(set(a) & set(b)) / max(len(a), len(b))


def precision_mrr(urls: list[str], nhan: dict[str, int]) -> tuple[float | None, float | None, int]:
    """(P@K trên các kết quả ĐÃ gán nhãn, MRR, số kết quả chưa gán nhãn)."""
    da = [(u, nhan[u]) for u in urls if u in nhan]
    chua = len(urls) - len(da)
    p = sum(r for _, r in da) / len(da) if da else None
    mrr = 0.0
    for i, u in enumerate(urls, 1):
        if nhan.get(u) == 1:
            mrr = 1.0 / i
            break
    return p, (mrr if da else None), chua


def doc_nhan(path: pathlib.Path) -> dict[str, dict[str, int]]:
    """phan_loai.csv -> {query: {url: 0|1}}; dòng để trống cột relevant bị bỏ qua."""
    out: dict[str, dict[str, int]] = collections.defaultdict(dict)
    with open(path, encoding="utf-8-sig", newline="") as fh:
        for row in csv.DictReader(fh):
            v = (row.get("relevant") or "").strip()
            if v in ("0", "1"):
                out[row["query"]][row["url"]] = int(v)
    return out


def tb(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def f3(x):
    return "—" if x is None else f"{x:.3f}"


# ---------------------------------------------------------------- bước 1: dựng + so
def chay_day_du(a, args, queries, out: pathlib.Path):
    cu, moi = Arm("cũ", args.old_url), Arm("mới", args.new_url)
    if cu.url == moi.url:
        sys.exit("--old-url và --new-url trùng nhau: hai arm phải là hai instance khác nhau")
    cu.chuan_bi(args.reset)
    moi.chuan_bi(args.reset)
    data = pathlib.Path(args.data)

    # lượt 1: đếm khối lặp theo host (lớp 2) — cùng cách trich.dung_templates() làm
    kh: dict[str, set[str]] = {}
    if not args.no_khuon:
        t0 = time.time()
        dem: dict[str, collections.Counter] = {}
        so_trang: collections.Counter = collections.Counter()
        for rec, html in trang_html(data, args.limit):
            host = (urllib.parse.urlsplit(rec["url"]).hostname or "").lower()
            dem.setdefault(host, collections.Counter()).update(
                _khuon.van_tay_trang(BeautifulSoup(html, "lxml")))
            so_trang[host] += 1
        kh = {h: _khuon.tap_khuon({"n_pages": so_trang[h], "blocks": dict(c)}) for h, c in dem.items()}
        print(f"lượt 1 (khuôn): {sum(so_trang.values())} trang, {len(kh)} host, "
              f"{sum(1 for v in kh.values() if v)} host đủ mẫu để khử khuôn, {time.time() - t0:.0f}s")

    # lượt 2: bóc hai kiểu, index hai arm, gom thống kê văn bản
    qtok = {q: tokens(q) for q in queries}
    dem_q = {q: collections.Counter() for q in queries}      # both / only_cu / only_moi
    vi_du_mat = {q: [] for q in queries}                     # khớp ở cũ, mất ở mới
    df = {"cu": collections.Counter(), "moi": collections.Counter()}
    n_doc = {"cu": 0, "moi": 0}
    do_dai = {"cu": [], "moi": []}
    ty_le = []
    cat_qua_tay: list[dict] = []
    phuong_phap = collections.Counter()
    chi_mot_ben = {"cu": 0, "moi": 0}
    t0 = time.time()
    for i, (rec, html) in enumerate(trang_html(data, args.limit), 1):
        host = (urllib.parse.urlsplit(rec["url"]).hostname or "").lower()
        d_cu = extract_cu(rec, html)
        d_moi = boc_tach(html, rec["url"], kh.get(host))
        if d_cu:
            cu.them(d_cu)
            n_doc["cu"] += 1
        if d_moi:
            moi.them(d_moi)
            n_doc["moi"] += 1
            phuong_phap[d_moi["block"]["method"]] += 1
        if bool(d_cu) != bool(d_moi):
            chi_mot_ben["cu" if d_cu else "moi"] += 1
        t_cu = tokens(d_cu["text"]) if d_cu else set()
        t_moi = tokens(d_moi["text"]) if d_moi else set()
        df["cu"].update(t_cu)
        df["moi"].update(t_moi)
        if d_cu and d_moi:
            do_dai["cu"].append(len(d_cu["text"]))
            do_dai["moi"].append(len(d_moi["text"]))
            if d_cu["text"]:
                ty_le.append(len(d_moi["text"]) / len(d_cu["text"]))
            if (len(d_cu["text"]) >= MIN_DOC_CHUA_CAT
                    and len(d_moi["text"]) < TY_LE_CAT * len(d_cu["text"])
                    and len(cat_qua_tay) < 15):
                cat_qua_tay.append({"url": d_cu["url"], "cu": len(d_cu["text"]), "moi": len(d_moi["text"]),
                                    "method": d_moi["block"]["method"], "path": d_moi["block"]["path"]})
        for q, Q in qtok.items():
            if not Q:
                continue
            o, n = Q <= t_cu, Q <= t_moi
            if o and n:
                dem_q[q]["both"] += 1
            elif o:
                dem_q[q]["only_cu"] += 1
                if len(vi_du_mat[q]) < VI_DU_TOI_DA:
                    txt = unicodedata.normalize("NFC", d_cu["text"])
                    j = fold(txt).find(sorted(Q)[0])
                    vi_du_mat[q].append({"url": d_cu["url"], "snippet": txt[max(j - 60, 0): j + 80]})
            elif n:
                dem_q[q]["only_moi"] += 1
        if i % 500 == 0:
            print(f"  … {i} trang, {time.time() - t0:.0f}s", flush=True)
    cu.xa()
    moi.xa()
    print(f"lượt 2 (bóc + index): cũ {n_doc['cu']} tài liệu, mới {n_doc['moi']} tài liệu, {time.time() - t0:.0f}s")

    # truy vấn
    kq = []
    for q in queries:
        for rk in args.ranking:
            rc, rm = cu.tim(q, args.k, rk), moi.tim(q, args.k, rk)
            if rc is None or rm is None:
                kq.append({"q": q, "ranking": rk, "loi": True})
                continue
            uc, um = [h["url"] for h in rc["hits"]], [h["url"] for h in rm["hits"]]
            kq.append({"q": q, "ranking": rk, "total_cu": rc["total"], "total_moi": rm["total"],
                       "urls_cu": uc, "urls_moi": um,
                       "titles": {h["url"]: h["title"] for h in rc["hits"] + rm["hits"]},
                       "overlap": overlap_at_k(uc, um)})
    st = {"cu": cu.stats(), "moi": moi.stats()}
    ghi_bao_cao(out, args, queries, kq, dem_q, vi_du_mat, df, n_doc, do_dai, ty_le, cat_qua_tay,
                phuong_phap, chi_mot_ben, st, bool(kh), sum(1 for v in kh.values() if v))


def df_top(counter, n, top=25):
    return [(t, c / n) for t, c in counter.most_common(top)] if n else []


def ghi_bao_cao(out, args, queries, kq, dem_q, vi_du_mat, df, n_doc, do_dai, ty_le, cat_qua_tay,
                phuong_phap, chi_mot_ben, st, co_khuon, so_host_khuon):
    out.mkdir(parents=True, exist_ok=True)
    L = ["# So sánh chỉ mục: bóc cũ và bóc mới", "",
         f"Tạo lúc {time.strftime('%Y-%m-%d %H:%M:%S')} · K={args.k} · khử khuôn: "
         f"{'bật' if co_khuon else 'TẮT'}" + (f" ({so_host_khuon} host đủ mẫu)" if co_khuon else ""), "",
         "> Báo cáo này mô tả **hai chỉ mục khác nhau thế nào**. Nó **không** nói bên nào tốt hơn:",
         "> độ trùng thấp có thể do rác khuôn được loại (tốt) hoặc nội dung bị cắt (xấu).",
         "> Muốn kết luận: điền cột `relevant` trong `phan_loai.csv` rồi chạy `--evaluate`.", "",
         "## 1. Quy mô", "",
         "| | cũ | mới |", "|---|---:|---:|",
         f"| tài liệu index | {n_doc['cu']} | {n_doc['moi']} |",
         f"| dung lượng index | {st['cu'].get('index_bytes', 0) / 1e6:.1f} MB | {st['moi'].get('index_bytes', 0) / 1e6:.1f} MB |",
         f"| độ dài text trung vị (ký tự) | {statistics.median(do_dai['cu']) if do_dai['cu'] else 0:.0f} "
         f"| {statistics.median(do_dai['moi']) if do_dai['moi'] else 0:.0f} |", ""]
    if ty_le:
        q = statistics.quantiles(ty_le, n=10) if len(ty_le) >= 10 else [min(ty_le)] * 9
        L += [f"Tỉ lệ độ dài mới/cũ theo từng trang: trung vị {statistics.median(ty_le):.2f}, "
              f"phân vị 10% {q[0]:.2f}, phân vị 90% {q[-1]:.2f}. "
              f"Trang chỉ một arm bóc được: cũ-không-mới {chi_mot_ben['cu']}, mới-không-cũ {chi_mot_ben['moi']}.", ""]
    L += ["Cách chọn khối của arm mới: " + ", ".join(f"{k} {v}" for k, v in phuong_phap.most_common())
          + " (`fallback` = không tìm được khối, rơi về cả body).", ""]

    L += ["## 2. Từ có mặt ở nhiều trang nhất (dấu hiệu rác khuôn)", "",
          "Từ xuất hiện ở gần mọi trang gần như chắc chắn là menu/footer. Nếu arm mới giảm rõ các tỉ lệ này "
          "thì khối nội dung đã loại được khuôn; từ vốn phổ biến trong tiếng Việt (của, và, là…) sẽ giữ nguyên.", "",
          "| hạng | cũ: từ | % trang | mới: từ | % trang |", "|---:|---|---:|---|---:|"]
    a, b = df_top(df["cu"], n_doc["cu"]), df_top(df["moi"], n_doc["moi"])
    for i in range(max(len(a), len(b))):
        x = a[i] if i < len(a) else ("", 0)
        y = b[i] if i < len(b) else ("", 0)
        L.append(f"| {i + 1} | {x[0]} | {x[1] * 100:.0f} | {y[0]} | {y[1] * 100:.0f} |")
    L.append("")

    L += ["## 3. Trang nghi bị cắt quá tay", "",
          f"Trang cũ dài ≥ {MIN_DOC_CHUA_CAT} ký tự mà bản mới ngắn hơn {int(TY_LE_CAT * 100)}% "
          "(tối đa 15 ví dụ). Mở từng trang để xem khối được chọn có đúng thân bài không.", ""]
    if cat_qua_tay:
        L += ["| url | cũ | mới | cách chọn | khối |", "|---|---:|---:|---|---|"]
        L += [f"| {c['url']} | {c['cu']} | {c['moi']} | {c['method']} | `{c['path'][-70:]}` |" for c in cat_qua_tay]
    else:
        L.append("Không có trang nào.")
    L.append("")

    L += ["## 4. Theo từng truy vấn", "",
          "`mất` = trang mà text cũ chứa đủ từ khoá nhưng text mới không (xấp xỉ: so từ bỏ dấu, "
          "không phải truy vấn Lucene thật). `mất` có thể là khớp nhờ menu (loại đúng) hoặc nội dung bị cắt (loại sai) — "
          "xem ví dụ ở dưới. **Cột `tổng` là số Lucene trả về**, có thể gồm lượt vét (khi AND không ra gì, Lucene "
          "hạ xuống khớp quá nửa số âm tiết), nên có thể lớn hơn `cả hai + thêm`: nếu `cả hai + thêm` = 0 mà `tổng` > 0 "
          "thì arm đó không có trang nào chứa đủ từ khoá và các kết quả là khớp một phần.", "",
          "| truy vấn | ranking | tổng cũ | tổng mới | trùng@K | cả hai | mất | thêm |", "|---|---|---:|---:|---:|---:|---:|---:|"]
    for r in kq:
        if r.get("loi"):
            L.append(f"| {r['q']} | {r['ranking']} | lỗi cú pháp ||||||")
            continue
        c = dem_q[r["q"]]
        L.append(f"| {r['q']} | {r['ranking']} | {r['total_cu']} | {r['total_moi']} | {r['overlap']:.2f} "
                 f"| {c['both']} | {c['only_cu']} | {c['only_moi']} |")
    ok = [r for r in kq if not r.get("loi")]
    for rk in args.ranking:
        o = tb([r["overlap"] for r in ok if r["ranking"] == rk])
        L.append(f"\nTrùng@K trung bình ({rk}): **{f3(o)}** trên {sum(1 for r in ok if r['ranking'] == rk)} truy vấn.")
    L.append("")

    L += ["## 5. Chi tiết", ""]
    for q in queries:
        L.append(f"### {q}")
        c = dem_q[q]
        L.append(f"Khớp cả hai: {c['both']} · chỉ cũ (mất): {c['only_cu']} · chỉ mới (thêm): {c['only_moi']}\n")
        if vi_du_mat[q]:
            L.append("<details><summary>ví dụ trang bị mất (đoạn quanh từ khoá trong text cũ)</summary>\n")
            L += [f"- {v['url']}: …{v['snippet']}…" for v in vi_du_mat[q]]
            L.append("\n</details>\n")
        for r in [x for x in kq if x["q"] == q and not x.get("loi")]:
            L.append(f"**{r['ranking']}** (trùng@K {r['overlap']:.2f})\n")
            L += ["| # | cũ | mới |", "|---:|---|---|"]
            for i in range(args.k):
                def cell(urls, other):
                    if i >= len(urls):
                        return ""
                    u = urls[i]
                    t = (r["titles"].get(u) or u)[:60].replace("|", "/")
                    return f"{t}{'' if u in other else ' ⚑'}"
                L.append(f"| {i + 1} | {cell(r['urls_cu'], r['urls_moi'])} | {cell(r['urls_moi'], r['urls_cu'])} |")
            L.append("")
        L.append("⚑ = chỉ có ở arm đó trong top-K.\n")
    (out / "report.md").write_text("\n".join(L), encoding="utf-8")

    # file để gán nhãn: hợp top-K của hai arm, mọi ranking
    seen, rows = set(), []
    for r in ok:
        for u in dict.fromkeys(r["urls_cu"] + r["urls_moi"]):
            if (r["q"], u) in seen:
                continue
            seen.add((r["q"], u))
            rows.append({"query": r["q"], "url": u, "title": r["titles"].get(u, ""),
                         "in_cu": int(u in r["urls_cu"]), "in_moi": int(u in r["urls_moi"]), "relevant": ""})
    with open(out / "phan_loai.csv", "w", encoding="utf-8-sig", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["query", "url", "title", "in_cu", "in_moi", "relevant"])
        w.writeheader()
        w.writerows(rows)
    (out / "raw.json").write_text(json.dumps({"results": kq, "counts": {q: dict(c) for q, c in dem_q.items()}},
                                             ensure_ascii=False), encoding="utf-8")
    print(f"\nĐã ghi {out}/report.md, phan_loai.csv ({len(rows)} dòng cần gán nhãn), raw.json")


# ---------------------------------------------------------------- bước 2: chấm điểm
def danh_gia(args, queries, out: pathlib.Path):
    nhan = doc_nhan(pathlib.Path(args.evaluate))
    if not nhan:
        sys.exit("Không có dòng nào có relevant = 0/1 trong file nhãn.")
    cu, moi = Arm("cũ", args.old_url), Arm("mới", args.new_url)
    L = ["# Đánh giá theo nhãn", "",
         f"K={args.k}. P@K chỉ tính trên kết quả đã gán nhãn; cột `chưa nhãn` là số kết quả top-K chưa có nhãn "
         "(nhiều thì số P@K kém tin cậy). Truy vấn không có nhãn nào bị bỏ qua.", "",
         "| truy vấn | ranking | P@K cũ | P@K mới | MRR cũ | MRR mới | chưa nhãn cũ/mới |", "|---|---|---:|---:|---:|---:|---:|"]
    tong: dict[str, dict[str, list]] = {rk: {"pc": [], "pm": [], "mc": [], "mm": []} for rk in args.ranking}
    for q in queries:
        if q not in nhan:
            continue
        for rk in args.ranking:
            rc, rm = cu.tim(q, args.k, rk), moi.tim(q, args.k, rk)
            if rc is None or rm is None:
                continue
            pc, mc, xc = precision_mrr([h["url"] for h in rc["hits"]], nhan[q])
            pm, mm, xm = precision_mrr([h["url"] for h in rm["hits"]], nhan[q])
            for k_, v in (("pc", pc), ("pm", pm), ("mc", mc), ("mm", mm)):
                tong[rk][k_].append(v)
            L.append(f"| {q} | {rk} | {f3(pc)} | {f3(pm)} | {f3(mc)} | {f3(mm)} | {xc}/{xm} |")
    L += ["", "## Trung bình", "", "| ranking | số truy vấn | P@K cũ | P@K mới | MRR cũ | MRR mới |", "|---|---:|---:|---:|---:|---:|"]
    for rk, t in tong.items():
        L.append(f"| {rk} | {len([x for x in t['pc'] if x is not None])} | {f3(tb(t['pc']))} | {f3(tb(t['pm']))} "
                 f"| {f3(tb(t['mc']))} | {f3(tb(t['mm']))} |")
    L += ["", "Lưu ý khi đọc: số truy vấn nhỏ và nhãn do một người gán thì chênh lệch vài phần trăm không đáng kể. "
          "Nhãn lấy từ chính top-K của hai arm nên bỏ sót tài liệu liên quan mà cả hai đều không trả về (không đo được recall)."]
    out.mkdir(parents=True, exist_ok=True)
    (out / "danh_gia.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))
    print(f"\nĐã ghi {out}/danh_gia.md")


def main():
    in_docker = os.path.exists("/.dockerenv")
    data_mac_dinh = os.getenv("DATA_DIR") or str(pathlib.Path(__file__).parents[2] / "hust-crawler" / "data")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--old-url", default="http://lucene-cu:8081" if in_docker else "http://localhost:8091")
    ap.add_argument("--new-url", default="http://lucene-moi:8081" if in_docker else "http://localhost:8092")
    ap.add_argument("--data", default=data_mac_dinh, help="thư mục chứa raw*/ (mặc định DATA_DIR)")
    ap.add_argument("--out", help="thư mục ghi báo cáo (mặc định <data>/so_sanh)")
    ap.add_argument("--queries", help="file truy vấn, mỗi dòng một câu (mặc định: bộ đoán sẵn trong code)")
    ap.add_argument("-k", type=int, default=10, help="top-K để so (mặc định 10)")
    ap.add_argument("--ranking", default="tfidf,enhanced", help="tfidf, enhanced hoặc cả hai, cách nhau bằng dấu phẩy")
    ap.add_argument("--no-khuon", action="store_true", help="arm mới không dùng khử khuôn theo host (chỉ khối + selector)")
    ap.add_argument("--limit", type=int, default=0, help="chỉ lấy N trang đầu để chạy thử")
    ap.add_argument("--reset", action="store_true", help="cho phép xoá index của hai instance nếu chúng không rỗng")
    ap.add_argument("--evaluate", metavar="CSV", help="bước 2: chấm P@K/MRR theo file phan_loai.csv đã điền; không dựng lại index")
    args = ap.parse_args()
    args.ranking = [r.strip() for r in args.ranking.split(",") if r.strip()]
    if not set(args.ranking) <= {"tfidf", "enhanced"}:
        ap.error("--ranking chỉ nhận tfidf và/hoặc enhanced")
    queries = TRUY_VAN_MAC_DINH
    if args.queries:
        queries = [x.strip() for x in pathlib.Path(args.queries).read_text(encoding="utf-8").splitlines()
                   if x.strip() and not x.startswith("#")]
    out = pathlib.Path(args.out or pathlib.Path(args.data) / "so_sanh")
    if args.evaluate:
        danh_gia(args, queries, out)
    else:
        chay_day_du(None, args, queries, out)


if __name__ == "__main__":
    main()
