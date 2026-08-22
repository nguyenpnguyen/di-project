#!/usr/bin/env python3
"""
Đọc lại kho HTML thô do crawl_all.py tạo ra (data/raw/pages-*.jsonl[.gz]).

Kho lưu base64 nên không grep thẳng được; đây là cái mở nắp:

    python read_raw.py --stats                    # kho có gì
    python read_raw.py --list --kind article      # liệt kê url đã tải
    python read_raw.py --get 656013               # in HTML của một bài ra stdout
    python read_raw.py --grep "điểm chuẩn"        # tìm chuỗi trong nội dung đã giải mã
    python read_raw.py --extract data/html_files  # bung tất cả ra file .html rời
"""
from __future__ import annotations

import argparse
import base64
import collections
import gzip
import json
import pathlib
import re
import sys

RAW = pathlib.Path(__file__).resolve().parent / "data" / "raw"


def shards(d: pathlib.Path):
    return sorted(list(d.glob("pages-*.jsonl.gz")) + list(d.glob("pages-*.jsonl")))


def records(d: pathlib.Path, quiet=False):
    """Đọc lười từng dòng: kho vài GB vẫn chạy được trên máy ít RAM.

    Shard cuối có thể đang được crawler ghi dở (gzip chưa đóng stream, dòng cuối
    đứt giữa chừng). Đọc tới đâu trả tới đó thay vì hỏng cả mẻ — nhờ vậy xem
    được kho ngay trong lúc crawl vẫn đang chạy.
    """
    for p in shards(d):
        op = gzip.open if p.suffix == ".gz" else open
        n = 0
        try:
            with op(p, "rt", encoding="utf-8") as fh:
                for line in fh:
                    if not line.strip():
                        continue
                    try:
                        rec = json.loads(line)
                    except json.JSONDecodeError:
                        break                       # dòng cuối đứt: shard đang ghi dở
                    n += 1
                    yield rec
        except (EOFError, OSError, gzip.BadGzipFile):
            if not quiet:
                print(f"  (…{p.name} đang được ghi dở, đọc được {n} dòng)", file=sys.stderr)


def html_of(rec: dict) -> bytes:
    return base64.b64decode(rec["html_b64"]) if rec.get("html_b64") else b""


def main():
    ap = argparse.ArgumentParser(description="Đọc kho HTML thô base64")
    ap.add_argument("--dir", default=str(RAW))
    ap.add_argument("--stats", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--kind", help="lọc theo loại: article / listing / listing-page / other")
    ap.add_argument("--get", help="id bài (656013) hoặc một mẩu URL")
    ap.add_argument("--grep", help="tìm chuỗi trong HTML đã giải mã")
    ap.add_argument("--extract", help="bung ra thư mục, mỗi trang một file .html")
    ap.add_argument("--check", action="store_true",
                    help="đối chiếu state.json với shard, ghi url khuyết ra missing.txt")
    ap.add_argument("--assets", action="store_true",
                    help="bóc url file đính kèm (pdf/doc/xls…) từ HTML đã lưu, ghi ra assets.txt")
    ap.add_argument("--fix-roots", action="store_true",
                    help="suy chuyên mục gốc từ kho, trả những cái biến mất khỏi frontier về hàng đợi")
    ap.add_argument("--links", action="store_true",
                    help="xuất MỌI url đã biết ra file 'links' (không đuôi), mỗi dòng một link")
    ap.add_argument("--out", help="đường dẫn file kết quả cho --links "
                                  "(mặc định: ghi đè file *links* đang có trong data/raw)")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    d = pathlib.Path(args.dir)

    if not shards(d):
        sys.exit(f"Không thấy shard nào trong {d}. Chạy crawl_all.py trước.")

    if args.fix_roots:
        # Chuyên mục có thể biến mất khỏi kế hoạch crawl (không done, không
        # frontier, có khi không cả queued) — khi đó cả nhánh đó vô hình vĩnh
        # viễn với --resume. Mọi url bài đều ngụ ý chuyên mục chứa nó, nên suy
        # ngược từ kho ra danh sách chuyên mục rồi trả những cái thiếu về hàng đợi.
        sp = d / "state.json"
        st = json.loads(sp.read_text(encoding="utf-8"))
        done, front = set(st["done"]), {u for u, _, _ in st["frontier"]}

        roots = set()
        for r in records(d, quiet=True):
            u = r["url"]
            if re.search(r"-\d+\.html$", u):
                roots.add(u.rsplit("/", 1)[0] + "/")
            m = re.match(r"(.*/)page-\d+/$", u)
            if m:
                roots.add(m.group(1))
        for u in list(st.get("by_key", {}).values()) + list(front) + list(done):
            if re.search(r"-\d+\.html$", u):
                roots.add(u.rsplit("/", 1)[0] + "/")

        missing = sorted(r for r in roots if r not in done and r not in front)
        print(f"suy ra {len(roots)} chuyên mục gốc từ kho")
        print(f"thiếu khỏi kế hoạch crawl: {len(missing)}")
        for r in missing[:15]:
            print(f"   {r}")
        if len(missing) > 15:
            print(f"   … còn {len(missing) - 15}")
        if missing:
            st["frontier"] += [[r, 1, "fix-roots"] for r in missing]
            st["queued"] = sorted(set(st["queued"]) | set(missing))
            sp.write_text(json.dumps(st, ensure_ascii=False), encoding="utf-8")
            print(f"-> đã trả về frontier, giờ có {len(st['frontier'])} url. "
                  f"Chạy: python crawl_all.py --resume")
        else:
            print("-> không thiếu chuyên mục nào.")
        return

    if args.links:
        # Gom MỌI url đã biết, không phân biệt đã tải hay chưa, không phân cấp.
        # Mỗi dòng một link, file không đuôi.
        st = json.loads((d / "state.json").read_text(encoding="utf-8"))
        out: set[str] = set()

        def take(it):
            for u in it:
                if u and str(u).startswith("http"):
                    out.add(u)

        take(st.get("done", {}))
        take(u for u, _, _ in st.get("frontier", []))
        take(st.get("queued", []))
        take(st.get("by_key", {}).values())
        take(st.get("origin", {}))
        take(st.get("aliases", {}))
        take(a for v in st.get("aliases", {}).values() for a in v)
        take(st.get("assets", []))
        for name in ("assets.txt", "sample_articles.txt", "lost_articles.txt",
                     "roots_todo.txt", "missing.txt"):
            f = d / name
            if f.exists():
                take(f.read_text(encoding="utf-8").split())
        take(r["url"] for r in records(d, quiet=True))

        # ghi đè đúng file cũ nếu đã đổi tên, để lần cập nhật sau không đẻ file mới
        p = pathlib.Path(args.out) if args.out else next(
            (x for x in sorted(d.glob("*links*")) if x.is_file()), d / "links")
        p.write_text("\n".join(sorted(out)) + "\n", encoding="utf-8")
        kinds = collections.Counter()
        for u in out:
            if re.search(r"-\d+\.html$", u):
                kinds["bài viết"] += 1
            elif re.search(r"/page-\d+/$", u):
                kinds["trang phân trang"] += 1
            elif re.search(r"\.(pdf|docx?|xlsx?|pptx?|rar|zip)$", u, re.I):
                kinds["file đính kèm"] += 1
            elif u.endswith("/"):
                kinds["trang danh sách"] += 1
            else:
                kinds["khác"] += 1
        print(f"{len(out)} link -> {p}  ({p.stat().st_size / 1024:.0f} KB)")
        for k, v in kinds.most_common():
            print(f"   {v:>5}  {k}")
        return

    if args.assets:
        # bóc từ HTML đã lưu, không phải ra mạng lại — đó là lợi ích của kho thô
        from urllib.parse import urljoin, urlparse
        pat = re.compile(r"\.(pdf|docx?|xlsx?|pptx?|zip|rar)$", re.I)
        seen: dict[str, str] = {}
        for r in records(d):
            if not r.get("html_b64"):
                continue
            html = html_of(r).decode(r.get("encoding") or "utf-8", "replace")
            for m in re.finditer(r'href=["\']([^"\']+)["\']', html):
                u = urljoin(r["url"], m.group(1))
                if urlparse(u).netloc == "hust.edu.vn" and pat.search(urlparse(u).path):
                    seen.setdefault(u, r["url"])
        p = d / "assets.txt"
        p.write_text("\n".join(sorted(seen)), encoding="utf-8")
        ext = collections.Counter(pathlib.Path(urlparse(u).path).suffix.lower() for u in seen)
        print(f"{len(seen)} file đính kèm trong {len(shards(d))} shard -> {p}")
        for e, n in ext.most_common():
            print(f"   {n:>5}  {e}")
        return

    if args.check:
        # state bảo đã tải, nhưng shard có thể mất đuôi do kill cứng -> liệt kê chỗ khuyết.
        # Tách riêng phần url về sau bị loại khỏi phạm vi (vd /rss/): những cái đó
        # khuyết là đúng, đừng báo động rồi bắt người ta tải lại thứ không cần.
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "crawl_all", pathlib.Path(__file__).resolve().parent / "crawl_all.py")
        ca = importlib.util.module_from_spec(spec)
        argv, sys.argv = sys.argv, ["crawl_all"]
        spec.loader.exec_module(ca)
        sys.argv = argv

        state = json.loads((d / "state.json").read_text(encoding="utf-8"))
        rec_of = {}
        lines = 0
        for r in records(d):
            lines += 1
            rec_of.setdefault(r["url"], r)
        claimed = set(state.get("done", {}))
        # "có bản ghi" mới là tiêu chí; trang không phải HTML (pdf/xml) vẫn được
        # lưu metadata, thiếu thân HTML là đúng thiết kế chứ không phải khuyết
        gone = claimed - set(rec_of)
        nobody = sum(1 for u in claimed & set(rec_of) if not rec_of[u].get("html_b64"))
        real = sorted(u for u in gone if ca.in_scope(u))
        stale = len(gone) - len(real)

        print(f"state bảo đã tải : {len(claimed)} url")
        print(f"shard thực có    : {len(rec_of)} url ({lines - len(rec_of)} dòng trùng do resume)")
        print(f"  trong đó không có thân HTML: {nobody} url (pdf/docx/xml — đúng thiết kế)")
        print(f"khuyết thật      : {len(real)} url")
        print(f"khuyết nhưng bỏ  : {stale} url (đã bị loại khỏi phạm vi, vd /rss/ — không cần vá)")
        if real:
            p = d / "missing.txt"
            p.write_text("\n".join(real), encoding="utf-8")
            print(f"-> đã ghi {p}. Vá bằng:\n"
                  f"   python crawl_all.py --resume --seed-file {p}")
        else:
            (d / "missing.txt").unlink(missing_ok=True)   # đừng để lại danh sách cũ gây hiểu nhầm
            print("-> kho khớp với state, không phải vá gì.")
        return

    if args.stats:
        # đếm theo url khác nhau: resume làm một số trang bị tải lại, đếm dòng sẽ thổi phồng
        kind = collections.Counter()
        status = collections.Counter()
        sec = collections.Counter()
        seen: dict[str, int] = {}
        lines = 0
        for r in records(d):
            lines += 1
            u = r["url"]
            if u in seen:
                continue
            seen[u] = r.get("size") or 0
            kind[r.get("kind")] += 1
            status[r.get("status")] += 1
            m = re.match(r"/(vi|en)/([^/]+)/", u.replace("https://hust.edu.vn", ""))
            sec[f"{m.group(1)}/{m.group(2)}" if m else "khác"] += 1
        size = sum(seen.values())
        print(f"{len(shards(d))} shard | {len(seen)} trang khác nhau | {size / 1e6:.0f} MB HTML thô"
              f" | {lines - len(seen)} dòng trùng do resume")
        print("theo loại  :", dict(kind))
        print("theo status:", dict(status))
        print("theo mục   :")
        for k, v in sec.most_common(30):
            print(f"   {v:>6}  {k}")
        return

    matched = 0
    emitted: set[str] = set()      # resume làm một số trang có hai bản ghi, in một lần thôi
    for r in records(d):
        if r["url"] in emitted:
            continue
        if args.kind and r.get("kind") != args.kind:
            continue
        if args.get and args.get not in r["url"] and args.get != (r.get("article_id") or ""):
            continue
        if args.grep:
            try:
                text = html_of(r).decode(r.get("encoding") or "utf-8", "replace")
            except Exception:
                continue
            if args.grep.lower() not in text.lower():
                continue

        matched += 1
        emitted.add(r["url"])
        if args.extract:
            out = pathlib.Path(args.extract)
            out.mkdir(parents=True, exist_ok=True)
            name = re.sub(r"\W+", "_", r["url"].replace("https://hust.edu.vn", ""))[-120:].strip("_")
            (out / f"{name or 'index'}.html").write_bytes(html_of(r))
        elif args.get and not args.list:
            sys.stdout.buffer.write(html_of(r))
            return
        else:
            print(f"{r.get('kind','?'):<13} {r.get('status')} {(r.get('size') or 0) // 1024:>5}KB  {r['url']}")
        if args.limit and matched >= args.limit:
            break

    if args.extract:
        print(f"Đã bung {matched} file ra {args.extract}")
    elif not matched:
        print("Không khớp bản ghi nào.")


if __name__ == "__main__":
    main()
