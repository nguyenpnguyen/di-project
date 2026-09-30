"""Tiện ích HTML dùng chung: ghép url, dọn HTML để xem trước, chuẩn hoá url."""
from __future__ import annotations

import os
import pathlib
import re
import sys
import urllib.parse

from bs4 import BeautifulSoup


def _nap_crawl_all():
    """Import crawl_all để dùng norm(). Trong container PYTHONPATH đã trỏ /crawler;
    chạy test ngoài container thì tự tìm thư mục hust-crawler cạnh hust-search."""
    try:
        import crawl_all
        return crawl_all
    except ImportError:
        for p in (os.getenv("CRAWLER_DIR"), pathlib.Path(__file__).parents[3] / "hust-crawler"):
            if p and pathlib.Path(p, "crawl_all.py").exists():
                sys.path.insert(0, str(p))
                import crawl_all
                return crawl_all
        raise


crawl_all = _nap_crawl_all()
norm = crawl_all.norm


def join_http(base_url: str, href: str) -> str:
    """Ghép url, chỉ nhận HTTP(S), qua crawl_all.norm() như mọi nguồn url khác."""
    href = (href or "").strip()
    if not href:
        return ""
    return norm(href, base_url) or ""


def clean_space(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


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
            src = join_http(goc, tag.get("src") or "")
            if not src:
                tag.decompose()
                continue
            tag["src"] = src
            tag["loading"] = "lazy"
        elif tag.name == "a":
            href = join_http(goc, tag.get("href") or "")
            if not href:
                tag.unwrap()
                continue
            tag["href"] = href
            tag["target"] = "_blank"
            tag["rel"] = "noopener"
    # Chặn ở 40 KB: trang xem trước chỉ cần đủ nhận ra bài, mà 2.800 tài liệu
    # nhân vài chục KB là index phình lên hàng trăm MB cho một thứ chỉ dùng cho
    # năm kết quả đầu mỗi lượt tìm.
    return str(ban)[:40_000]
