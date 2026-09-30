"""Sinh trang mẫu TỔNG HỢP cho test và đánh giá thuật toán khối nội dung.

Đây KHÔNG phải trang thật của hust.edu.vn: container viết ra nó không vào được
site và không có kho `data/`. Trang dựng theo năm kiểu bố cục CMS phổ biến, nội
dung là câu ghép ngẫu nhiên có seed cố định, khối vàng được ghi cạnh mỗi trang.
Số đo trên bộ này chỉ cho biết thuật toán xử lý được các bố cục đó, không nói gì
về độ chính xác trên dữ liệu thật — muốn vậy phải thêm trang thật rồi gán nhãn tay.

    python tests/sinh_mau.py     # ghi tests/fixtures/html/<host>/NN.html + NN.gold.txt
"""
from __future__ import annotations

import json
import pathlib
import random

TU = ("sinh viên trường đại học bách khoa hà nội thông báo kế hoạch đào tạo học kỳ "
      "tuyển sinh nghiên cứu khoa học hội thảo quốc tế hợp tác doanh nghiệp học bổng "
      "tốt nghiệp giảng viên phòng thí nghiệm dự án công nghệ chương trình cử nhân "
      "kỹ sư thạc sĩ tiến sĩ đăng ký xét tuyển điểm chuẩn lễ khai giảng câu lạc bộ").split()
MENU = ["Giới thiệu", "Tuyển sinh", "Đào tạo", "Nghiên cứu", "Hợp tác", "Tin tức", "Liên hệ",
        "Sinh viên", "Cựu sinh viên", "Thư viện", "Tuyển dụng", "Sơ đồ trang"]
N_TRANG = 25


def cau(r, n=None):
    n = n or r.randint(9, 18)
    return " ".join(r.choice(TU) for _ in range(n)).capitalize() + "."


def doan(r, k):
    return [cau(r) for _ in range(k)]


def menu_html(ten_lop="menu"):
    return f'<ul class="{ten_lop}">' + "".join(
        f'<li><a href="/muc/{i}/">{t}</a></li>' for i, t in enumerate(MENU)) + "</ul>"


def bo_cuc_nukeviet(r, i, ps, tieu_de):
    return f"""<html><head><title>{tieu_de} - Đại học Bách khoa Hà Nội</title></head><body>
<div id="header"><div class="logo"><img src="/themes/hust/logo.png" width="120"></div>{menu_html()}</div>
<div id="wrap"><div class="sidebar"><h3>Tin mới</h3><ul>{"".join(f'<li><a href="/vi/news/tin-{j}-{100+j}.html">{cau(r,6)}</a></li>' for j in range(8))}</ul></div>
<div class="main"><h1>{tieu_de}</h1><span class="date">{r.randint(1,28):02d}/{r.randint(1,12):02d}/2026</span>
<div class="bodytext">{"".join(f"<p>{p}</p>" for p in ps)}<p>Tác giả: Nguyễn Văn {chr(65+i%26)}</p></div></div></div>
<div id="footer">{menu_html("footer-menu")}<p>Copyright © Đại học Bách khoa Hà Nội</p></div></body></html>"""


def bo_cuc_wordpress(r, i, ps, tieu_de):
    return f"""<html><head><title>{tieu_de} | Khoa CNTT</title>
<meta property="article:published_time" content="2026-0{r.randint(1,9)}-1{r.randint(0,9)}T08:00:00+07:00"></head><body>
<header class="site-header">{menu_html("nav-menu")}</header>
<div class="container"><article class="post type-post"><h1 class="entry-title">{tieu_de}</h1>
<div class="entry-content">{"".join(f"<p>{p}</p>" for p in ps)}</div></article>
<div class="widget-area"><div class="widget"><h4>Bài liên quan</h4>{"".join(f'<a href="/p/{j}">{cau(r,5)}</a><br>' for j in range(10))}</div></div></div>
<footer>{menu_html("fmenu")}</footer></body></html>"""


def bo_cuc_bang(r, i, ps, tieu_de):
    return f"""<html><head><title>{tieu_de}</title></head><body>
<table width="100%"><tr><td colspan="2">{menu_html("top")}</td></tr>
<tr><td width="20%" valign="top">{menu_html("left")}<p><a href="/lh">Liên hệ</a> | <a href="/gt">Giới thiệu</a></p></td>
<td valign="top"><table><tr><td><b>{tieu_de}</b></td></tr><tr><td>{"<br>".join(ps)}</td></tr></table></td></tr>
<tr><td colspan="2"><a href="/">Trang chủ</a> - <a href="/lh">Liên hệ</a></td></tr></table></body></html>"""


def bo_cuc_khong_ten(r, i, ps, tieu_de):
    """Không class/id có nghĩa: thuật toán phải dựa vào mật độ chữ/link thuần tuý."""
    return f"""<html><head><title>{tieu_de}</title></head><body>
<div id="a1">{menu_html("x1")}</div>
<div id="a2"><div id="a3"><h2>{tieu_de}</h2>{"".join(f"<p>{p}</p>" for p in ps)}</div>
<div id="a4">{"".join(f'<div><a href="/z/{j}">{cau(r,7)}</a></div>' for j in range(12))}</div></div>
<div id="a5">{menu_html("x2")}</div></body></html>"""


GIOI_THIEU_CO_DINH = [cau(random.Random(f"gt{k}")) for k in range(3)]


def bo_cuc_sidebar_dai(r, i, ps, tieu_de, lap=False):
    """Ca khó: sidebar có nhiều chữ thường (không phải link). lap=False: chữ đổi theo
    từng trang (ít gặp, lớp 2 không giúp được); lap=True: cùng đoạn giới thiệu trên mọi
    trang (kiểu boilerplate thật, lớp 2 bỏ được)."""
    gt = GIOI_THIEU_CO_DINH if lap else [cau(r) for _ in range(3)]
    return f"""<html><head><title>{tieu_de}</title></head><body><div class="wrapper">
<div class="left">{menu_html("m")}<div class="about"><h3>Giới thiệu</h3>{"".join(f"<p>{x}</p>" for x in gt)}</div></div>
<div class="center"><h1>{tieu_de}</h1>{"".join(f"<p>{p}</p>" for p in ps)}</div></div></body></html>"""


BO_CUC = {"nukeviet.test": bo_cuc_nukeviet, "wordpress.test": bo_cuc_wordpress,
          "bang.test": bo_cuc_bang, "khongten.test": bo_cuc_khong_ten,
          "sidebar.test": bo_cuc_sidebar_dai,
          "sidebar-lap.test": lambda *a: bo_cuc_sidebar_dai(*a, lap=True)}


def sinh(goc: pathlib.Path):
    for host, fn in BO_CUC.items():
        d = goc / host
        d.mkdir(parents=True, exist_ok=True)
        for i in range(N_TRANG):
            r = random.Random(f"{host}-{i}")
            ps = doan(r, r.randint(4, 9))
            tieu_de = cau(r, 7)[:-1]
            (d / f"{i:02d}.html").write_text(fn(r, i, ps, tieu_de), encoding="utf-8")
            (d / f"{i:02d}.gold.json").write_text(
                json.dumps({"url": f"https://{host}/bai/{i}.html", "content": ps, "title": tieu_de},
                           ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    sinh(pathlib.Path(__file__).parent / "fixtures" / "html")
