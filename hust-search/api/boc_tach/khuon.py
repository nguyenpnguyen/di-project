"""Lớp 2 của thuật toán khối nội dung: khử khuôn mẫu theo cả host.

Menu, footer, banner lặp gần như nguyên xi trên mọi trang cùng site. Một khối
văn bản xuất hiện trên quá nhiều trang của một host thì là khuôn, không phải
nội dung bài. Ý tưởng theo họ "Site Style Tree" (Yi, Liu, Li — KDD 2003); đây là
bản đơn giản hoá dùng vân tay văn bản chứ không dựng cây kiểu.
"""
from __future__ import annotations

import hashlib
import re

# Khối "lá": thẻ khối không chứa thẻ khối nào khác bên trong.
THE_KHOI = {"p", "li", "td", "th", "div", "dt", "dd", "address", "h1", "h2",
            "h3", "h4", "h5", "h6", "blockquote", "figcaption"}
NGUONG_TRANG = 0.30     # θ₁: khối có mặt trên > 30% số trang thì là khuôn (dò lại)
TOI_THIEU_TRANG = 20    # host ít hơn số này thì không đủ mẫu để kết luận


def van_tay(text: str) -> str:
    t = re.sub(r"\d+", "0", re.sub(r"\s+", " ", text or "").strip().lower())
    return hashlib.sha1(t.encode("utf-8")).hexdigest()[:16]


def khoi_la(soup):
    """(thẻ, văn bản) của các khối lá có chữ."""
    khoi = soup.find_all(THE_KHOI)
    for t in khoi:
        if t.find(THE_KHOI):
            continue
        text = re.sub(r"\s+", " ", t.get_text(" ", strip=True))
        if len(text) >= 2:
            yield t, text


def van_tay_trang(soup) -> set[str]:
    return {van_tay(text) for _, text in khoi_la(soup)}


def dem_host(tap_van_tay: list[set[str]]) -> dict:
    """Gộp vân tay của các trang cùng host thành bảng đếm số trang."""
    dem: dict[str, int] = {}
    for s in tap_van_tay:
        for v in s:
            dem[v] = dem.get(v, 0) + 1
    return {"n_pages": len(tap_van_tay), "blocks": dem}


def tap_khuon(bang: dict | None, nguong: float = NGUONG_TRANG) -> set[str]:
    """Tập vân tay bị coi là khuôn; rỗng nếu host chưa đủ mẫu."""
    if not bang or bang.get("n_pages", 0) < TOI_THIEU_TRANG:
        return set()
    can = nguong * bang["n_pages"]
    return {v for v, n in bang["blocks"].items() if n > can}


def bo_khuon(soup, khuon: set[str]) -> int:
    """Xoá các khối lá thuộc khuôn khỏi cây. Trả về số khối đã xoá."""
    if not khuon:
        return 0
    n = 0
    for t, text in list(khoi_la(soup)):
        if van_tay(text) in khuon:
            t.decompose()
            n += 1
    return n
