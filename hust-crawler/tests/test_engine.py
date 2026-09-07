"""
Test cho tầng crawl. Mỗi test ứng với một lỗi đã từng gặp thật, không phải
test cho có — tên test nói rõ lỗi đó là gì để lần sau ai sửa code còn biết
mình đang phá cái gì.

    cd hust-crawler && .venv/bin/python -m pytest tests -q
"""
import importlib.util
import pathlib
import sys

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def _load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    argv, sys.argv = sys.argv, [name]
    sys.modules[name] = mod
    try:
        spec.loader.exec_module(mod)
    finally:
        sys.argv = argv
    return mod


ca = _load("crawl_all")
rr = _load("read_raw")


@pytest.fixture(autouse=True)
def _reset_site():
    """Mỗi test bắt đầu từ hust.edu.vn, không dính cấu hình của test trước."""
    ca.set_site("hust.edu.vn")
    ca.ALLOW_SUFFIX = None
    yield
    ca.set_site("hust.edu.vn")
    ca.ALLOW_SUFFIX = None


# ------------------------------------------------------------------ chuẩn hoá
class TestNorm:
    def test_bo_fragment_va_tham_so_theo_doi(self):
        u = ca.norm("https://hust.edu.vn/a.html?fbclid=xyz&utm_source=fb#doan-2")
        assert u == "https://hust.edu.vn/a.html"

    def test_giu_tham_so_that_su_phan_biet_noi_dung(self):
        assert ca.norm("https://hust.edu.vn/vi/index?page=5") == "https://hust.edu.vn/vi/index?page=5"

    def test_ep_https_va_bo_www(self):
        assert ca.norm("http://www.hust.edu.vn/x.pdf") == "https://hust.edu.vn/x.pdf"

    def test_bo_javascript_va_mailto(self):
        assert ca.norm("javascript:void(0)") is None
        assert ca.norm("mailto:ai@hust.edu.vn") is None

    def test_base_doc_luc_goi_khong_phai_luc_dinh_nghia(self):
        """--site đổi BASE sau khi module đã nạp; norm phải theo host mới."""
        ca.set_site("svbk.hust.edu.vn")
        assert ca.norm("/tin-tuc/") == "https://svbk.hust.edu.vn/tin-tuc/"


# ----------------------------------------------------------------- phạm vi
class TestScope:
    def test_mac_dinh_chi_mot_host(self):
        assert ca.in_scope("https://hust.edu.vn/vi/news/")
        assert not ca.in_scope("https://library.hust.edu.vn/vi/index")

    def test_allow_domain_mo_ra_ca_subdomain(self):
        ca.ALLOW_SUFFIX = "hust.edu.vn"
        assert ca.in_scope("https://library.hust.edu.vn/vi/index")
        assert ca.in_scope("https://hust.edu.vn/vi/news/")
        assert not ca.in_scope("https://vnexpress.net/x")

    def test_loai_file_nhi_phan_va_endpoint_xuat_file(self):
        assert not ca.in_scope("https://hust.edu.vn/uploads/a.pdf")
        assert not ca.in_scope("https://hust.edu.vn/vi/lich-lam-viec/export/?report_week=1&submit=1")

    def test_loai_duong_dan_sinh_url_vo_han(self):
        for p in ("/vi/feeds/", "/vi/rss/", "/vi/seek/", "/vi/print/"):
            assert not ca.in_scope("https://hust.edu.vn" + p), p


# ------------------------------------------------------------- khoá khử trùng
class TestDedup:
    """Lỗi đắt nhất của dự án: con số cuối url KHÔNG duy nhất."""

    A = "https://hust.edu.vn/vi/su-kien-noi-bat/to-chuc/thong-bao-tuyen-dung-nam-654601.html"
    B = "https://hust.edu.vn/vi/su-kien-noi-bat/thong-bao-chung/thong-bao-tuyen-dung-nam-654601.html"
    C = "https://hust.edu.vn/vi/news/khcn/sahep-cung-bach-khoa-nang-cao-654601.html"

    def test_cung_slug_khac_chuyen_muc_la_mot_bai(self):
        assert ca.dedup_key(self.A) == ca.dedup_key(self.B)

    def test_cung_so_khac_slug_la_hai_bai_khac_nhau(self):
        # đo thật trên site: ba bài khác hẳn nhau cùng đuôi -654601.html
        assert ca.dedup_key(self.A) != ca.dedup_key(self.C)
        assert ca.art_id(self.A) == ca.art_id(self.C) == "654601"

    def test_url_khong_phai_bai_thi_khong_co_khoa(self):
        assert ca.dedup_key("https://hust.edu.vn/vi/news/") is None


# ------------------------------------------------------------------ phân trang
class TestPagination:
    @pytest.mark.parametrize("url,n", [
        ("https://a.vn/news/cat/page-90/", 90),      # NukeViet
        ("https://a.vn/blog/page/7/", 7),            # WordPress đường dẫn
        ("https://a.vn/list?page=3", 3),             # query
        ("https://a.vn/list?paged=4", 4),
        ("https://a.vn/tin/trang-12/", 12),
        ("https://a.vn/x/p5/", 5),
    ])
    def test_nhan_dien_sau_kieu_phan_trang(self, url, n):
        got = ca.page_of(url)
        assert got is not None, url
        assert got[1] == n

    def test_khuon_dung_lai_dung_url_goc(self):
        tpl, n, stem = ca.page_of("https://a.vn/news/cat/page-90/")
        assert tpl.replace("{n}", "2") == "https://a.vn/news/cat/page-2/"
        assert stem == "https://a.vn/news/cat/"

    def test_hai_chuyen_muc_khac_nhau_thi_goc_khac_nhau(self):
        """Chống nở nhầm phân trang của chuyên mục hàng xóm ở sidebar."""
        a = ca.page_of("https://a.vn/news/x/page-2/")[2]
        b = ca.page_of("https://a.vn/news/y/page-2/")[2]
        assert a != b

    def test_trang_thuong_khong_bi_coi_la_phan_trang(self):
        assert ca.page_of("https://a.vn/news/bai-viet-656013.html") is None


# ---------------------------------------------------------------- phân loại
class TestKind:
    @pytest.mark.parametrize("url,kind", [
        ("https://hust.edu.vn/vi/news/tin-tuc/bai-656013.html", "article"),
        ("https://hust.edu.vn/vi/news/tin-tuc/page-4/", "listing-page"),
        ("https://hust.edu.vn/vi/news/tin-tuc/", "listing"),
        ("https://hust.edu.vn/vi/about", "other"),
    ])
    def test_phan_loai_url(self, url, kind):
        assert ca.kind_of(url) == kind

    def test_phan_trang_uu_tien_hon_nhan_dien_bai(self):
        """?page=2 phải là listing-page, dù phần trước có dạng giống bài."""
        assert ca.kind_of("https://a.vn/x?page=2") == "listing-page"


# ------------------------------------------------------------------ read_raw
class TestReadRaw:
    def test_chi_lay_href_khong_lay_src(self):
        """Gộp src vào thì ảnh nhúng làm danh sách link phình 14k lên 24k dòng."""
        html = '<a href="/a">x</a><img src="/anh.jpg"><script src="/x.js"></script>'
        assert rr.hrefs_in(html) == ["/a"]
        assert set(rr.hrefs_in(html, with_src=True)) == {"/a", "/anh.jpg", "/x.js"}

    def test_tim_file_link_bo_qua_file_co_duoi(self, tmp_path):
        """File danh sách link cố ý không có đuôi; đừng nhầm sang links_missing.txt."""
        (tmp_path / "links_missing.txt").write_text("x")
        (tmp_path / "N1-links").write_text("y")
        assert rr.link_file(tmp_path).name == "N1-links"

    def test_khong_co_file_nao_thi_tra_none(self, tmp_path):
        assert rr.link_file(tmp_path) is None


# --------------------------------------------------------------- from-file
class TestFromFile:
    def test_bo_dong_trong_va_dong_chu_thich(self, tmp_path):
        f = tmp_path / "links.txt"
        f.write_text("https://hust.edu.vn/a.html\n\n# ghi chú\nhttps://hust.edu.vn/b.html\n",
                     encoding="utf-8")
        urls = [u.strip() for u in f.read_text(encoding="utf-8").splitlines()
                if u.strip() and not u.startswith("#")]
        assert urls == ["https://hust.edu.vn/a.html", "https://hust.edu.vn/b.html"]


# ------------------------------------------------------------------- render
class TestRender:
    def test_nhan_dien_trang_bi_chan(self):
        rd = _load("render")
        assert rd.looks_blocked(403, "<html>ok</html>")
        assert rd.looks_blocked(200, "<html>Just a moment... checking your browser</html>")
        assert rd.looks_blocked(200, "<html><body>trang rỗng</body></html>")   # SPA

    def test_trang_binh_thuong_thi_khong_can_render(self):
        rd = _load("render")
        html = "<html>" + '<a href="/x">link</a>' * 30 + " ".join(["chữ"] * 300) + "</html>"
        assert not rd.looks_blocked(200, html)
