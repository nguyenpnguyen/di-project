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
