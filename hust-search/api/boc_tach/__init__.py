"""Bóc tách trang HTML: khối nội dung, các trường, đồ thị liên kết.

`boc_tach(html, url)` là cửa vào duy nhất; các module con làm từng phần.
"""
from __future__ import annotations

import urllib.parse

from bs4 import BeautifulSoup

from . import khoi as _khoi
from . import lien_ket, truong
from .html_sach import clean_space, don_html, join_http

VERSION = "1"


def boc_tach(html: str, url: str, khuon: set[str] | None = None) -> dict | None:
    """HTML thô -> bản ghi đã bóc. None nếu không có cả tiêu đề lẫn chữ."""
    soup = BeautifulSoup(html, "lxml")
    host = (urllib.parse.urlsplit(url).hostname or "").lower()

    ld = truong.json_ld(soup)                 # trước khi dọn cây: JSON-LD nằm trong <script>
    raw = lien_ket.thu_thap(soup, url)        # cạnh trước khi dọn: menu/footer bị xoá mất
    section = truong._meta(soup, property="article:section")
    tg_meta = truong.tac_gia_meta(soup, ld)

    k = _khoi.tim_khoi(soup, host, khuon)
    tieu_de, tieu_de_src = truong.tieu_de(soup, ld, k.node)
    ngay, ngay_src = truong.ngay_dang(soup, ld, k.node)
    tg_dong, nguon = truong.dong_tac_gia_nguon(k.node)
    tac_gia, tac_gia_src = tg_meta if tg_meta[0] else tg_dong

    text = clean_space(k.node.get_text(" ", strip=True)) if k.node is not None else ""
    if not tieu_de and not text:
        return None
    noi_dung, khuon_canh = lien_ket.chia(raw, k.node)
    return {
        "url": url, "host": host, "section": section,
        "title": tieu_de[:500], "title_src": tieu_de_src,
        "text": text[:200_000], "html": don_html(k.node, url),
        "date": ngay, "date_src": ngay_src,
        "author": tac_gia, "author_src": tac_gia_src, "cited_source": nguon,
        "block": {"path": k.path, "score": round(k.score, 2), "method": k.method},
        "outgoing_links": lien_ket.canh_ra_cong_khai(noi_dung),
        "links": noi_dung, "nav_links": khuon_canh,
    }


def giai_thich(html: str, url: str, khuon: set[str] | None = None) -> dict:
    """Chạy lại bước chọn khối + chia cạnh của `boc_tach` và trả về từng bước để
    giao diện vẽ: phễu số chữ qua từng lớp, các bậc đi xuống cây, cạnh trong/ngoài khối."""
    soup = BeautifulSoup(html, "lxml")
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    raw = lien_ket.thu_thap(soup, url)
    toan_trang = _khoi.so_chu(soup.body or soup)
    vet: dict = {}
    k = _khoi.tim_khoi(soup, host, khuon, vet)
    noi_dung, khuon_canh = lien_ket.chia(raw, k.node)
    text = clean_space(k.node.get_text(" ", strip=True)) if k.node is not None else ""
    theo_loai: dict[str, int] = {}
    for e in noi_dung:
        theo_loai[e["dst_kind"]] = theo_loai.get(e["dst_kind"], 0) + 1
    return {
        "url": url, "host": host,
        "block": {"path": k.path, "score": round(k.score, 2), "method": k.method,
                  "selector": _khoi.SELECTOR_THEO_HOST.get(host, "")},
        "pheu": [
            {"buoc": "Toàn trang", "chu": toan_trang},
            {"buoc": "Sau dọn cây", "chu": vet.get("sau_don", 0)},
            {"buoc": "Sau khử khuôn", "chu": vet.get("sau_khuon", 0)},
            {"buoc": "Khối được chọn", "chu": _khoi.so_chu(k.node)},
        ],
        "khoi_khuon_bo": vet.get("khoi_khuon_bo", 0),
        "bac": vet.get("bac", []),
        "trich": text[:600],
        "canh": {"noi_dung": len(noi_dung), "khuon": len(khuon_canh), "noi_dung_theo_loai": theo_loai},
    }
