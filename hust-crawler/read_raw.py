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


def load_crawler():
    """Nạp crawl_all.py để dùng chung norm()/in_scope() — hai bên phải cùng
    một luật chuẩn hoá, nếu không so sánh sẽ lệch giả."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "crawl_all", pathlib.Path(__file__).resolve().parent / "crawl_all.py")
    mod = importlib.util.module_from_spec(spec)
    argv, sys.argv = sys.argv, ["crawl_all"]
    spec.loader.exec_module(mod)
    sys.argv = argv
    return mod


def link_file(d: pathlib.Path):
    """Tìm file danh sách link đang dùng. File này cố ý KHÔNG có đuôi, nên loại
    hết .txt để không nhầm sang links_missing.txt hay assets.txt."""
    cand = [x for x in sorted(d.glob("*links*")) if x.is_file() and not x.suffix]
    return cand[0] if cand else None


def hrefs_in(html: str, with_src=False):
    """Link trong trang.

    Mặc định chỉ lấy href — đó mới là "link" theo nghĩa người ta đi tới được.
    src là tài nguyên nhúng (ảnh, script); gộp vào làm danh sách link phình lên
    vì mỗi bài kéo theo cả chục ảnh /uploads/.
    """
    attrs = "href|src" if with_src else "href"
    return re.findall(rf'(?:{attrs})=["\']([^"\'>\s]+)["\']', html)


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
    ap.add_argument("--audit", action="store_true",
                    help="soát từng chuyên mục: phân trang nở tới đâu, tải thiếu trang nào")
    ap.add_argument("--fix-roots", action="store_true",
                    help="suy chuyên mục gốc từ kho, trả những cái biến mất khỏi frontier về hàng đợi")
    ap.add_argument("--verify-links", action="store_true",
                    help="soát độc lập: bóc lại mọi href từ HTML đã lưu, đối chiếu với file link")
    ap.add_argument("--links", action="store_true",
                    help="xuất MỌI url đã biết ra file 'links' (không đuôi), mỗi dòng một link")
    ap.add_argument("--domain", default="hust.edu.vn",
                    help="chỉ giữ link thuộc tên miền này và mọi subdomain của nó")
    ap.add_argument("--all-sites", action="store_true", default=True,
                    help="gom từ mọi kho data/raw* (mặc định bật)")
    ap.add_argument("--one-site", dest="all_sites", action="store_false",
                    help="chỉ gom từ kho được chỉ định bằng --dir")
    ap.add_argument("--subdomains", action="store_true",
                    help="liệt kê các subdomain của --domain xuất hiện trong kho")
    ap.add_argument("--out", help="đường dẫn file kết quả cho --links "
                                  "(mặc định: ghi đè file *links* đang có trong data/raw)")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    d = pathlib.Path(args.dir)

    if not shards(d):
        sys.exit(f"Không thấy shard nào trong {d}. Chạy crawl_all.py trước.")

    if args.subdomains:
        # Crawler chỉ đi trong MỘT host, nên subdomain không bao giờ vào hàng đợi
        # của nó. Muốn biết họ hust.edu.vn có những site nào thì phải bóc lại
        # href từ HTML đã lưu — đây là nguồn duy nhất có thông tin đó.
        from urllib.parse import urljoin, urlparse
        hosts = collections.Counter()
        for k in sorted(p for p in d.parent.glob("raw*") if p.is_dir()):
            for r in records(k, quiet=True):
                if not r.get("html_b64"):
                    continue
                html = html_of(r).decode(r.get("encoding") or "utf-8", "replace")
                for h in hrefs_in(html):
                    try:
                        n = urlparse(urljoin(r["url"], h)).netloc.lower().replace("www.", "")
                    except ValueError:
                        continue
                    if "@" not in n and n.endswith(args.domain):
                        hosts[n] += 1
        p = d / "subdomains.txt"
        p.write_text("\n".join(sorted(hosts)) + "\n", encoding="utf-8")
        print(f"{len(hosts)} host thuộc {args.domain} (số lần được trỏ tới):\n")
        for h, c in hosts.most_common():
            print(f"{c:>8}  {h}")
        print(f"\n-> {p}")
        return

    if args.audit:
        # Soát từng chuyên mục: trang gốc đã tải chưa, phân trang nở tới đâu,
        # đã tải bao nhiêu trang trong dải đó, gom được bao nhiêu bài.
        # Chuyên mục nào tải thiếu trang thì phần bài trên những trang ấy
        # chưa hề được nhìn thấy — đó chính là "sub link còn thiếu".
        st = json.loads((d / "state.json").read_text(encoding="utf-8"))
        done = set(st["done"])
        front = {u for u, _, _ in st["frontier"]}
        known = done | front | set(st["queued"])

        PGN = re.compile(r"^(.*/)page-(\d+)/$")
        cats: dict[str, dict] = collections.defaultdict(
            lambda: {"max": 1, "pages": set(), "fetched": set(), "arts": 0, "root": False})
        for u in known:
            p = u.replace("https://hust.edu.vn", "")
            m = PGN.match(p)
            if m:
                c = cats[m.group(1)]
                n = int(m.group(2))
                c["max"] = max(c["max"], n)
                c["pages"].add(n)
                if u in done:
                    c["fetched"].add(n)
            elif re.search(r"-\d+\.html$", p):
                cats[p.rsplit("/", 1)[0] + "/"]["arts"] += 1
            elif p.endswith("/"):
                cats[p]["root"] = u in done

        rows = []
        for c, v in cats.items():
            need = v["max"]                       # page-1 là chính trang gốc
            got = len(v["fetched"]) + (1 if v["root"] else 0)
            rows.append((need - got, need, got, v["arts"], v["root"], c))
        rows.sort(reverse=True)

        thieu = [r for r in rows if r[0] > 0]
        print(f"{len(cats)} chuyên mục | {len(thieu)} chuyên mục còn thiếu trang\n")
        print(f"{'thiếu':>6} {'tải/tổng':>10} {'bài':>6}  chuyên mục")
        for miss, need, got, arts, root, c in rows[:30]:
            flag = "" if root else "  [chưa tải trang gốc]"
            print(f"{miss:>6} {got:>4}/{need:<5} {arts:>6}  {c}{flag}")
        if len(rows) > 30:
            print(f"   … còn {len(rows) - 30} chuyên mục (đa số đã đủ)")
        tm = sum(r[0] for r in thieu)
        print(f"\nTổng còn thiếu {tm} trang danh sách "
              f"(~{tm * 6} bài chưa nhìn thấy, ước theo 6 bài/trang của module news)")
        if tm:
            print("Chạy tiếp: python crawl_all.py --resume --only listing")
        return

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

    if args.verify_links:
        # Soát ĐỘC LẬP: không tin state.json, mà bóc lại mọi href từ HTML đã lưu
        # rồi đối chiếu với file link. Nếu hai bên khớp thì file link đã đủ so với
        # những gì crawler thật sự nhìn thấy; lệch chỗ nào là chỗ đó bị bỏ sót.
        ca = load_crawler()
        f = pathlib.Path(args.out) if args.out else link_file(d)
        if not f or not f.exists():
            sys.exit("Chưa có file link. Chạy read_raw.py --links trước.")
        listed = {l for l in f.read_text(encoding="utf-8").split("\n") if l}

        seen: set[str] = set()
        assets: set[str] = set()
        pages = 0
        for r in records(d, quiet=True):
            if not r.get("html_b64"):
                continue
            pages += 1
            html = html_of(r).decode(r.get("encoding") or "utf-8", "replace")
            for h in hrefs_in(html):
                u = ca.norm(h, r["url"])
                if not u:
                    continue
                if ca.urlparse(u).netloc != ca.HOST:
                    continue                       # link ra ngoài, không thuộc phạm vi
                (assets if not ca.in_scope(u) else seen).add(u)

        missing = sorted(seen - listed)
        extra = len(listed - seen - assets)
        print(f"quét {pages} trang HTML trong kho")
        print(f"  href nội bộ trong phạm vi : {len(seen)}")
        print(f"  file/đường dẫn ngoài phạm vi: {len(assets)} (pdf, /rss/, /export/… — bỏ là đúng)")
        print(f"  file link đang liệt kê     : {len(listed)}")
        print(f"  CÓ trong HTML mà THIẾU ở file link: {len(missing)}")
        print(f"  có ở file link mà HTML chưa thấy  : {extra} (từ sitemap/hàng đợi, bình thường)")
        if missing:
            p = d / "links_missing.txt"
            p.write_text("\n".join(missing), encoding="utf-8")
            for u in missing[:12]:
                print(f"     {u}")
            if len(missing) > 12:
                print(f"     … còn {len(missing) - 12}, xem {p}")
            print(f"\n-> chạy lại: python read_raw.py --links")
        else:
            print("\n-> file link đã phủ hết mọi href crawler nhìn thấy.")
        return

    if args.links:
        # Gom MỌI url đã biết trong họ hust.edu.vn, không phân biệt đã tải hay
        # chưa, không phân cấp: hàng đợi + kho của MỌI site đã crawl + mọi href
        # bóc lại từ HTML (chỗ này mới ra được link của subdomain, vì crawler chỉ
        # đi trong một host nên subdomain không bao giờ vào hàng đợi của nó).
        from urllib.parse import urljoin, urlparse
        ca = load_crawler()          # dùng chung norm() với crawler, nếu không thì
        out: set[str] = set()        # --verify-links sẽ báo lệch giả vì hai bên
                                     # chuẩn hoá khác nhau (http/https, #fragment…)

        def take(it, base=None):
            for u in it:
                # chuẩn hoá MỌI nguồn, không chỉ href bóc từ HTML: url đọc từ
                # assets.txt vẫn còn dạng http:// và sẽ lọt ra thành dòng lạc loài
                u = ca.norm(str(u or ""), base)
                if not u or not u.startswith("http"):
                    continue
                h = urlparse(u).netloc.lower()
                # "@" trong netloc = mailto: bị urljoin nuốt nhầm, không phải host
                if "@" in h or not h.replace("www.", "").endswith(args.domain):
                    continue
                out.add(u)

        kho = sorted(p for p in d.parent.glob("raw*") if p.is_dir()) if args.all_sites else [d]
        pages = 0
        for k in kho:
            sp = k / "state.json"
            if sp.exists():
                st = json.loads(sp.read_text(encoding="utf-8"))
                take(st.get("done", {}))
                take(u for u, _, _ in st.get("frontier", []))
                take(st.get("queued", []))
                take(st.get("by_key", {}).values())
                take(st.get("origin", {}))
                take(st.get("aliases", {}))
                take(a for v in st.get("aliases", {}).values() for a in v)
                take(st.get("assets", []))
            for name in ("assets.txt", "sample_articles.txt", "lost_articles.txt",
                         "roots_todo.txt", "missing.txt", "links_missing.txt"):
                f = k / name
                if f.exists():
                    take(f.read_text(encoding="utf-8").split())
            for r in records(k, quiet=True):
                take([r["url"]])
                if not r.get("html_b64"):
                    continue
                pages += 1
                html = html_of(r).decode(r.get("encoding") or "utf-8", "replace")
                take(hrefs_in(html), base=r["url"])
        print(f"quét {len(kho)} kho, {pages} trang HTML")

        # ghi đè đúng file cũ nếu đã đổi tên, để lần cập nhật sau không đẻ file mới
        p = pathlib.Path(args.out) if args.out else (link_file(d) or d / "links")
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
