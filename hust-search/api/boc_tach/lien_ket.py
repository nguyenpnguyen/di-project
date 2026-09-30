"""Đồ thị liên kết: cạnh `nguồn --> đích : văn bản mô tả`.

Cạnh nằm trong khối nội dung là cạnh nội dung (người viết chủ động giới thiệu);
cạnh ngoài khối là cạnh khuôn (menu, footer, sidebar) — lưu gộp theo host chứ
không theo từng trang (xem KE-HOACH-BOC-TACH.md mục 5.4).
"""
from __future__ import annotations

import urllib.parse

from .html_sach import clean_space, join_http

DUOI_TAI_LIEU = {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"}
DUOI_ANH = {"jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico"}
DUONG_DAN_KHUON = ("/themes/", "/templates/", "/assets/")
HOST_NHA = "hust.edu.vn"


def trong_ho_hust(host: str) -> bool:
    host = (host or "").lower()
    return host == HOST_NHA or host.endswith("." + HOST_NHA)


def loai_dich(url: str) -> str:
    """page | document | image | external. Host ngoài họ hust.edu.vn luôn là external."""
    p = urllib.parse.urlsplit(url)
    if not trong_ho_hust(p.hostname or ""):
        return "external"
    duoi = p.path.rsplit(".", 1)[-1].lower() if "." in p.path.rsplit("/", 1)[-1] else ""
    if duoi in DUOI_TAI_LIEU or "download=1" in p.query:
        return "document"
    if duoi in DUOI_ANH:
        return "image"
    return "page"


def _mo_ta_a(a) -> str:
    text = clean_space(a.get_text(" ", strip=True))
    if text:
        return text
    text = clean_space(a.get("title") or a.get("aria-label") or "")
    if text:
        return text
    img = a.find("img")
    return clean_space(img.get("alt") or "") if img else ""


def _mo_ta_img(img) -> str:
    text = clean_space(img.get("alt") or img.get("title") or "")
    if text:
        return text
    fig = img.find_parent("figure")
    cap = fig.find("figcaption") if fig else None
    return clean_space(cap.get_text(" ", strip=True)) if cap else ""


def _la_anh_khuon(img, dst: str) -> bool:
    if any(s in urllib.parse.urlsplit(dst).path for s in DUONG_DAN_KHUON):
        return True
    for k in ("width", "height"):
        v = (img.get(k) or "").strip().rstrip("px")
        if v.isdigit() and int(v) <= 16:
            return True
    return False


def thu_thap(soup, base: str) -> list[dict]:
    """Mọi cạnh trong trang, TRƯỚC khi dọn cây. Mỗi cạnh giữ tham chiếu `el` để
    sau này biết nó nằm trong hay ngoài khối nội dung (None = thuộc trang nói chung)."""
    raw = []
    self_url = join_http(base, base)
    for a in soup.find_all("a", href=True):
        dst = join_http(base, a["href"])
        if dst and dst != self_url:
            raw.append({"el": a, "dst": dst, "type": "href", "text": _mo_ta_a(a), "force_nav": False})
    for img in soup.find_all("img"):
        src = img.get("src") or img.get("data-src")
        dst = join_http(base, src or "")
        if dst:
            raw.append({"el": img, "dst": dst, "type": "embed", "text": _mo_ta_img(img),
                        "force_nav": _la_anh_khuon(img, dst)})
    for m in soup.find_all("meta", property="og:image"):
        dst = join_http(base, m.get("content") or "")
        if dst:
            raw.append({"el": None, "dst": dst, "type": "embed", "text": "", "force_nav": False})
    return raw


def chia(raw: list[dict], khoi_node) -> tuple[list[dict], list[dict]]:
    """Tách cạnh nội dung / cạnh khuôn. Trả về (nội dung, khuôn), đã khử trùng
    theo (dst, type, text) kèm `count`."""
    ben_trong = {id(x) for x in khoi_node.descendants} if khoi_node is not None else set()
    nd: dict[tuple, dict] = {}
    kh: dict[tuple, dict] = {}
    for e in raw:
        trong = e["el"] is None or id(e["el"]) in ben_trong
        dich = nd if (trong and not e["force_nav"]) else kh
        k = (e["dst"], e["type"], e["text"])
        if k in dich:
            dich[k]["count"] += 1
        else:
            dich[k] = {"dst": e["dst"], "type": e["type"], "text": e["text"],
                       "dst_kind": loai_dich(e["dst"]), "count": 1}
    return list(nd.values()), list(kh.values())


def canh_ra_cong_khai(canh_noi_dung: list[dict]) -> list[dict]:
    """`outgoing_links` của schema public: cạnh href, mỗi url một dòng; văn bản
    lấy từ lần đầu có chữ."""
    out: list[dict] = []
    seen: dict[str, int] = {}
    for e in canh_noi_dung:
        if e["type"] != "href":
            continue
        if e["dst"] in seen:
            i = seen[e["dst"]]
            if not out[i]["text"] and e["text"]:
                out[i]["text"] = e["text"]
            continue
        seen[e["dst"]] = len(out)
        out.append({"url": e["dst"], "text": e["text"]})
    return out
