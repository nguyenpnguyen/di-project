"""Đo thuật toán khối nội dung: precision / recall / F1 trên túi âm tiết.

Ba mốc so:
  body   — cả <body> (đúng hành vi cũ của extract() khi không có .bodytext/main)
  lop3   — chỉ chấm điểm nút (không khử khuôn, không dùng selector)
  lop2+3 — khử khuôn theo host rồi chấm điểm (không dùng selector)

    python tests/danh_gia_khoi.py [thư mục fixtures/html]

CẢNH BÁO: bộ mặc định là trang TỔNG HỢP (xem sinh_mau.py). Kết quả chỉ chứng minh
thuật toán chạy đúng trên các bố cục đó, không phải số đo trên dữ liệu thật.
"""
from __future__ import annotations

import collections
import json
import pathlib
import re
import sys

from bs4 import BeautifulSoup

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "api"))
from boc_tach import khoi, khuon  # noqa: E402

MAC_DINH = pathlib.Path(__file__).parent / "fixtures" / "html"


def tui(text: str) -> collections.Counter:
    return collections.Counter(re.findall(r"\w+", text.lower()))


def prf(pred: str, gold: str) -> tuple[float, float, float]:
    p, g = tui(pred), tui(gold)
    trung = sum((p & g).values())
    pr = trung / max(sum(p.values()), 1)
    rc = trung / max(sum(g.values()), 1)
    return pr, rc, (2 * pr * rc / (pr + rc) if pr + rc else 0.0)


def chay(thu_muc: pathlib.Path) -> dict:
    ket = collections.defaultdict(lambda: collections.defaultdict(list))
    for host_dir in sorted(p for p in thu_muc.iterdir() if p.is_dir()):
        files = sorted(host_dir.glob("*.html"))
        html = {f: f.read_text(encoding="utf-8") for f in files}
        bang = khuon.dem_host([khuon.van_tay_trang(BeautifulSoup(h, "lxml")) for h in html.values()])
        kh = khuon.tap_khuon(bang)
        for f, h in html.items():
            gold = " ".join(json.loads(f.with_suffix(".gold.json").read_text(encoding="utf-8"))["content"])
            cach = {
                "body": lambda s: (s.body or s).get_text(" ", strip=True),
                "lop3": lambda s: khoi.tim_khoi(s, "").text,
                "lop2+3": lambda s: khoi.tim_khoi(s, "", kh).text,
            }
            for ten, fn in cach.items():
                ket[ten][host_dir.name].append(prf(fn(BeautifulSoup(h, "lxml")), gold))
    return ket


def main():
    thu_muc = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else MAC_DINH
    ket = chay(thu_muc)
    hosts = sorted(next(iter(ket.values())))
    print(f"{'mốc':8}" + "".join(f"{h[:14]:>16}" for h in hosts) + f"{'TB':>10}   (F1)")
    for ten, theo_host in ket.items():
        f1s = [sum(x[2] for x in theo_host[h]) / len(theo_host[h]) for h in hosts]
        print(f"{ten:8}" + "".join(f"{v:16.3f}" for v in f1s) + f"{sum(f1s)/len(f1s):10.3f}")
    print()
    for ten, theo_host in ket.items():
        allv = [x for v in theo_host.values() for x in v]
        n = len(allv)
        print(f"{ten:8} P={sum(x[0] for x in allv)/n:.3f} R={sum(x[1] for x in allv)/n:.3f} "
              f"F1={sum(x[2] for x in allv)/n:.3f}  ({n} trang)")


if __name__ == "__main__":
    main()
