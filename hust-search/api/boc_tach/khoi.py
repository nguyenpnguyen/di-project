"""Thuật toán tìm khối nội dung chính của một trang HTML.

Ba lớp (xem KE-HOACH-BOC-TACH.md mục 4):
  1. dọn cây: bỏ script/style/form/nav/footer... (`don_cay`)
  2. khử khuôn theo host (`khuon.py`, tuỳ chọn — cần bảng đếm của cả host)
  3. chấm điểm nút theo mật độ chữ / mật độ link rồi đi từ gốc xuống (`tim_khoi`)

Họ ý tưởng: mật độ chữ và mật độ link trên DOM (CETD — Sun, Song, Liao, SIGIR
2011; Boilerpipe — Kohlschütter và cs., WSDM 2010). Tên bài ghi theo trí nhớ.
Các hằng số ALPHA/BETA/GAMMA/DELTA là khởi điểm, chưa được dò trên dữ liệu thật.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from bs4 import Comment, NavigableString, Tag

from . import khuon as _khuon

# selector đã kiểm chứng theo host (chỉ hust.edu.vn chính đã kiểm chứng ở tầng crawler)
SELECTOR_THEO_HOST = {"hust.edu.vn": ".bodytext"}

BO_HAN = ["script", "style", "noscript", "iframe", "form", "svg", "button",
          "input", "select", "nav", "footer", "aside", "template"]
UNG_VIEN = {"div", "section", "article", "main", "td", "table", "tbody", "tr", "body", "form"}

ALPHA, BETA, GAMMA, DELTA = 2.0, 30.0, 1.0, 0.65
PHAT = re.compile(r"nav|menu|footer|header|sidebar|comment|share|related|breadcrumb|"
                  r"banner|widget|social|advert|popup|modal|pagination|tag", re.I)
THUONG = re.compile(r"content|article|post|entry|detail|bodytext|main|news-body|noi-?dung", re.I)
DAU_CAU = re.compile(r"[.,;:?!…]")


@dataclass
class Khoi:
    node: Tag | None
    path: str
    score: float
    method: str          # selector | heuristic | fallback

    @property
    def text(self) -> str:
        return re.sub(r"\s+", " ", self.node.get_text(" ", strip=True)) if self.node else ""


def don_cay(soup) -> None:
    """Lớp 1. Bỏ thẻ không mang nội dung, thẻ ẩn và comment."""
    for c in soup.find_all(string=lambda s: isinstance(s, Comment)):
        c.extract()
    for t in soup(BO_HAN):
        t.decompose()
    for t in soup.find_all(True):
        if t.decomposed:
            continue
        style = (t.get("style") or "").replace(" ", "").lower()
        if t.has_attr("hidden") or "display:none" in style:
            t.decompose()


def duong_dan(node: Tag) -> str:
    """Đường dẫn CSS rút gọn tới nút, để ghi lại khối nào đã được chọn."""
    parts = []
    while node is not None and node.name and node.name != "[document]":
        p = node.name
        if node.get("id"):
            p += "#" + node["id"]
        elif node.get("class"):
            p += "." + ".".join(node["class"])
        parts.append(p)
        node = node.parent
    return " > ".join(reversed(parts))


def _thong_ke(soup) -> dict:
    """{id(nút): (C, LC, P, Q)} tính từ lá lên gốc, một lượt."""
    tk: dict[int, list] = {}
    for t in reversed(soup.find_all(True)):
        c = lc = p = q = 0
        la_link = t.name == "a"
        for ch in t.children:
            if isinstance(ch, Tag):
                s = tk.get(id(ch))
                if s:
                    c += s[0]; lc += s[1]; p += s[2]; q += s[3]
            elif isinstance(ch, NavigableString) and not isinstance(ch, Comment):
                txt = re.sub(r"\s+", " ", str(ch)).strip()
                if txt:
                    c += len(txt)
                    if la_link:
                        lc += len(txt)
                    q += len(DAU_CAU.findall(txt))
        if t.name == "p" and c >= 25 and q >= 1:
            p += 1
        tk[id(t)] = [c, lc, p, q]
    return tk


def _diem(s) -> float:
    c, lc, p, q = s
    dens = lc / max(c, 1)
    return (c - lc) * (1 - dens) ** ALPHA + BETA * p + GAMMA * q


def _he_so(t: Tag) -> float:
    lop = " ".join(t.get("class") or []) + " " + (t.get("id") or "")
    if THUONG.search(lop):
        return 1.3
    if PHAT.search(lop):
        return 0.3
    return 1.0


def so_chu(node) -> int:
    """Số ký tự chữ hiển thị (bỏ chữ trong script/style), khoảng trắng gộp."""
    if node is None:
        return 0
    n = 0
    for s in node.find_all(string=True):
        if isinstance(s, Comment) or s.parent.name in ("script", "style", "noscript", "template"):
            continue
        t = re.sub(r"\s+", " ", str(s)).strip()
        n += len(t)
    return n


def nhan(t: Tag) -> str:
    """Nhãn ngắn của một nút: tag#id hoặc tag.lop-dau."""
    if t.get("id"):
        return f"{t.name}#{t['id']}"
    if t.get("class"):
        return f"{t.name}.{t['class'][0]}"
    return t.name


def tim_khoi(soup, host: str = "", khuon: set[str] | None = None, vet: dict | None = None) -> Khoi:
    """Chọn khối nội dung. Soup bị sửa tại chỗ (dọn cây, bỏ khuôn).

    `vet` (tuỳ chọn): dict được điền số chữ sau từng lớp và các bậc đi xuống cây,
    để giao diện vẽ lại thuật toán đã chọn thế nào. Không truyền thì không tốn gì thêm."""
    don_cay(soup)
    if vet is not None:
        vet["sau_don"] = so_chu(soup.body or soup)
    n_khuon = _khuon.bo_khuon(soup, khuon or set())
    body = soup.body or soup
    if vet is not None:
        vet.update(sau_khuon=so_chu(body), khoi_khuon_bo=n_khuon, bac=[])

    sel = SELECTOR_THEO_HOST.get(host)
    if sel:
        node = soup.select_one(sel)
        if node is not None and node.get_text(strip=True):
            return Khoi(node, duong_dan(node), 0.0, "selector")

    tk = _thong_ke(soup)
    node = body
    if tk.get(id(node), [0])[0] == 0:
        return Khoi(body, duong_dan(body) if body is not soup else "", 0.0, "fallback")
    while True:
        con = [c for c in node.children if isinstance(c, Tag) and c.name in UNG_VIEN
               and id(c) in tk]
        if not con:
            break
        tot = max(con, key=lambda c: _diem(tk[id(c)]) * _he_so(c))
        dung = _diem(tk[id(tot)]) * _he_so(tot) < DELTA * _diem(tk[id(node)])
        if vet is not None:
            vet["bac"].append(_ghi_bac(node, con, tot, tk, dung))
        if dung:
            break
        node = tot
    if node is body and (node.name == "body") and tk[id(node)][0] == 0:
        return Khoi(body, "body", 0.0, "fallback")
    return Khoi(node, duong_dan(node), _diem(tk[id(node)]), "heuristic")


def _ghi_bac(cha: Tag, con: list[Tag], tot: Tag, tk: dict, dung: bool, toi_da: int = 6) -> dict:
    """Một bậc đi xuống: nút cha, các con ứng viên (điểm cao nhất trước) và con thắng."""
    xep = sorted(con, key=lambda c: _diem(tk[id(c)]) * _he_so(c), reverse=True)
    return {
        "cha": nhan(cha), "diem_cha": round(_diem(tk[id(cha)]), 1),
        "nguong": round(DELTA * _diem(tk[id(cha)]), 1),
        "ung_vien": [{"nhan": nhan(c), "chu": tk[id(c)][0], "chu_link": tk[id(c)][1],
                      "doan": tk[id(c)][2], "he_so": _he_so(c),
                      "diem": round(_diem(tk[id(c)]) * _he_so(c), 1), "thang": c is tot}
                     for c in xep[:toi_da]],
        "con_lai": max(0, len(xep) - toi_da),
        "dung": dung,
    }
