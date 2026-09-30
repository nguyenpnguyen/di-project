"""Bóc các trường của trang: tiêu đề, ngày đăng, tác giả, nguồn trích dẫn.

Mỗi trường là một chuỗi ưu tiên và trả kèm tên nguồn (`*_src`) để đo xem
trường nào đang lấy được từ đâu, host nào hay rỗng.
"""
from __future__ import annotations

import json
import re
import time

TAC_GIA_CHUNG = {"admin", "administrator", "webmaster", "super user", "superuser"}
RE_NGAY_VN = re.compile(r"(?<!\d)(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})(?!\d)")
RE_NGAY_ISO = re.compile(r"(\d{4})-(\d{2})-(\d{2})")
RE_TAC_GIA = re.compile(
    r"^(?:Tác giả|Người viết|Tin,? ?ảnh|Bài,? ?ảnh|Tin,? ?bài|Bài viết|Ảnh)\s*[:：]\s*(.{2,80})$",
    re.I)
RE_NGUON = re.compile(r"^(?:Nguồn\s*[:：]\s*(.{2,120})|Theo\s+(.{2,80}))$", re.I)


def _clean(s) -> str:
    return re.sub(r"\s+", " ", s or "").strip()


def json_ld(soup) -> list[dict]:
    """Mọi đối tượng JSON-LD (phải gọi TRƯỚC khi dọn cây vì nằm trong <script>)."""
    out = []
    for s in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(s.string or s.get_text() or "")
        except (ValueError, TypeError):
            continue
        stack = data if isinstance(data, list) else [data]
        while stack:
            d = stack.pop()
            if isinstance(d, dict):
                out.append(d)
                g = d.get("@graph")
                if isinstance(g, list):
                    stack.extend(g)
    return out


def _ld_get(ld: list[dict], key: str):
    for d in ld:
        if d.get(key):
            return d[key]
    return None


def _prop(soup, name: str) -> str:
    t = soup.select_one(f"[itemprop='{name}']")
    return _clean(t.get("content") or t.get_text(" ", strip=True)) if t else ""


def _meta(soup, **kw) -> str:
    t = soup.find("meta", kw)
    return _clean(t.get("content")) if t else ""


def cat_hau_to(title: str) -> str:
    """Cắt hậu tố tên site ở <title> ("Bài A - ĐH Bách khoa"). Chỉ cắt khi
    đoạn cuối ngắn (≤ 40 ký tự) và phần còn lại đủ dài — heuristic, có thể cắt nhầm."""
    for sep in (" | ", " - ", " – ", " — "):
        if sep in title:
            dau, cuoi = title.rsplit(sep, 1)
            if len(cuoi) <= 40 and len(dau) >= 10:
                return dau.strip()
    return title


def tieu_de(soup, ld: list[dict], khoi_node=None) -> tuple[str, str]:
    v = _prop(soup, "headline")
    if v:
        return v, "headline"
    v = _meta(soup, property="og:title")
    if v:
        return v, "og:title"
    v = _ld_get(ld, "headline")
    if isinstance(v, str) and _clean(v):
        return _clean(v), "json-ld"
    h1 = None
    n = khoi_node
    for _ in range(4):                      # h1 nằm trong khối hoặc vài cấp cha của nó
        if n is None or not getattr(n, "name", None):
            break
        h1 = n.find("h1")
        if h1:
            break
        n = n.parent
    h1 = h1 or soup.find("h1")
    if h1 and _clean(h1.get_text(" ")):
        return _clean(h1.get_text(" ")), "h1"
    if soup.title and _clean(soup.title.get_text()):
        return cat_hau_to(_clean(soup.title.get_text())), "title"
    return "", ""


def _iso(y: int, m: int, d: int) -> str:
    try:
        time.strptime(f"{y:04d}-{m:02d}-{d:02d}", "%Y-%m-%d")
    except ValueError:
        return ""
    return f"{y:04d}-{m:02d}-{d:02d}"


def chuan_ngay(value: str) -> str:
    """Chuỗi ngày bất kỳ (ISO hoặc dd/mm/yyyy) -> YYYY-MM-DD, hoặc rỗng."""
    value = (value or "").strip()
    m = RE_NGAY_ISO.match(value)
    if m:
        return _iso(*map(int, m.groups()))
    m = RE_NGAY_VN.search(value)
    if m:
        d, mo, y = map(int, m.groups())
        return _iso(y, mo, d)
    return ""


def ngay_dang(soup, ld: list[dict], khoi_node=None) -> tuple[str, str]:
    for v, src in ((_prop(soup, "datePublished"), "datePublished"),
                   (_meta(soup, property="article:published_time"), "article:published_time"),
                   (_ld_get(ld, "datePublished"), "json-ld")):
        d = chuan_ngay(v if isinstance(v, str) else "")
        if d:
            return d, src
    t = soup.find("time", attrs={"datetime": True})
    if t:
        d = chuan_ngay(t["datetime"])
        if d:
            return d, "time"
    cands = [_clean(e.get_text(" ")) for e in
             soup.select("[class*=date],[class*=time],[class*=posted],[class*=meta]")[:6]]
    if khoi_node is not None:
        txt = _clean(khoi_node.get_text(" "))
        cands += [txt[:300], txt[-200:]]
    for c in cands:
        d = chuan_ngay(c) if RE_NGAY_VN.search(c) else ""
        if d:
            return d, "regex"
    return "", ""


def _ten_tac_gia(v) -> str:
    if isinstance(v, list):
        v = v[0] if v else ""
    if isinstance(v, dict):
        v = v.get("name", "")
    return _clean(v) if isinstance(v, str) else ""


def tac_gia_meta(soup, ld: list[dict]) -> tuple[str, str]:
    t = soup.select_one("[itemprop='author']")
    if t:
        ten = t.select_one("[itemprop='name']") or t
        v = _clean(ten.get("content") or ten.get_text(" ", strip=True))
        if v and v.lower() not in TAC_GIA_CHUNG:
            return v, "microdata"
    v = _meta(soup, name="author")
    if v and v.lower() not in TAC_GIA_CHUNG:
        return v, "meta"
    v = _ten_tac_gia(_ld_get(ld, "author"))
    if v and v.lower() not in TAC_GIA_CHUNG:
        return v, "json-ld"
    return "", ""


def _dong_ngan(khoi_node):
    """Các phần tử ngắn ở cuối khối, mới nhất trước — nơi hay có dòng tác giả/nguồn."""
    els = [e for e in khoi_node.find_all(["p", "div", "li", "span", "strong", "em", "b", "i"])
           if not e.find(["p", "div", "li"]) and 2 <= len(_clean(e.get_text(" "))) <= 130]
    return list(reversed(els))[:8]


def _leo_len(e):
    """<p><strong>Tác giả: X</strong></p> -> chọn <p>: bỏ cả dòng, không sót thẻ rỗng."""
    txt = _clean(e.get_text(" "))
    while (e.parent is not None and e.parent.name in {"p", "div", "li"}
           and _clean(e.parent.get_text(" ")) == txt and e.name not in {"p", "div", "li"}):
        e = e.parent
    return e


def dong_tac_gia_nguon(khoi_node) -> tuple[tuple[str, str], str]:
    """Tìm dòng "Tác giả:" và "Nguồn:" ở cuối khối và cắt chúng khỏi khối.

    Trả ((tác giả, nguồn gốc), nguồn trích dẫn).
    """
    tg = ("", "")
    nguon = ""
    if khoi_node is None:
        return tg, nguon
    for e in _dong_ngan(khoi_node):
        if e.decomposed:
            continue
        txt = _clean(e.get_text(" "))
        m = RE_TAC_GIA.match(txt)
        if m and not tg[0]:
            tg = (_clean(m.group(1)), "text-line")
            _leo_len(e).decompose()
            continue
        m = RE_NGUON.match(txt)
        if m and not nguon:
            nguon = _clean(m.group(1) or m.group(2))
            _leo_len(e).decompose()
    return tg, nguon
