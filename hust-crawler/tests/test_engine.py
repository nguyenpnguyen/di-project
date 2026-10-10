"""
Test cho tầng crawl. Mỗi test ứng với một lỗi đã từng gặp thật, không phải
test cho có — tên test nói rõ lỗi đó là gì để lần sau ai sửa code còn biết
mình đang phá cái gì.

    cd hust-crawler && .venv/bin/python -m pytest tests -q
"""
import argparse
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


# --------------------------------------------------------- chế độ 4: ngày đăng
class TestParseDateVn:
    """parse_date_vn: bản tối giản của Fields.normalizeDate() (Java) — chỉ cần
    đọc dd/mm/yyyy in cạnh mỗi mục trên trang danh sách (mục 2.3.1)."""

    def test_bam_duoc_ngay_lan_trong_doan_text_dai(self):
        assert ca.parse_date_vn("THÔNG BÁO TUYỂN DỤNG ĐỢT 3 NĂM 2026\n30/09/2026") == "2026-09-30"

    def test_mot_chu_so_cho_ngay_va_thang(self):
        assert ca.parse_date_vn("đăng 2/3/2026") == "2026-03-02"

    def test_ngay_khong_hop_le_tra_rong(self):
        assert ca.parse_date_vn("ngày 31/02/2026 không có thật") == ""

    def test_khong_co_ngay_tra_rong(self):
        assert ca.parse_date_vn("xem thêm 1790757300") == ""
        assert ca.parse_date_vn("") == ""

    def test_khong_dinh_vao_so_khac_vd_id_bai_hoac_nam_dai_hon(self):
        """(?<!\\d)...(?!\\d) phải chặn số dính liền ở hai đầu, không chỉ ở giữa."""
        assert ca.parse_date_vn("bài viết 123/04/2026999 không phải ngày") == ""


class TestRecentWalkListing:
    """run_recent/_recent_walk_listing: duyệt listing theo ngày giảm dần, dừng
    sớm khi gặp ngày < since — không tải mạng thật, mock Crawler.fetch()."""

    def _crawler(self, tmp_path, monkeypatch, **extra_args):
        monkeypatch.setattr(ca, "RAW", tmp_path)
        # test chỉ cần MỘT danh mục mới nhất để không phải mock nhiều URL khác
        # nhau — RECENT_LISTINGS thật của hust.edu.vn có 2, không liên quan tới
        # đúng/sai của thuật toán duyệt-một-listing đang được test ở đây.
        monkeypatch.setitem(ca.RECENT_LISTINGS, "hust.edu.vn", ("/vi/su-kien-noi-bat/",))
        args = argparse.Namespace(
            site="hust.edu.vn", seed_url=None, from_file=None, follow=False,
            allow_domain=None, render="never", render_timeout=30, insecure=False,
            max_pages=0, max_depth=6, max_pages_per_cat=400, lang="all",
            only="all", prefer="listing", delay=2.5, max_delay=30.0, max_429=8,
            workers=2, retries=3, timeout=25, shard_size=200, no_gzip=True,
            checkpoint=50, resume=False, seed_file=None, quiet=True,
            since=None, until=None,
        )
        for k, v in extra_args.items():
            setattr(args, k, v)
        c = ca.Crawler(args)
        return c

    @staticmethod
    def _page(items, next_href=None):
        """items: list[(href, text_kem_ngay)]. Sinh HTML danh sách tối giản."""
        li = "".join(f'<li><a href="{h}">bài</a><span>{t}</span></li>' for h, t in items)
        nxt = f'<a href="{next_href}">Trang sau</a>' if next_href else ""
        return f"<html><body><ul>{li}</ul>{nxt}</body></html>"

    def test_mo_moi_bai_trong_khoang_duoc_day_vao_hang_doi(self, tmp_path, monkeypatch):
        c = self._crawler(tmp_path, monkeypatch)
        html = self._page([
            ("/vi/su-kien-noi-bat/a-654801.html", "10/10/2026"),
            ("/vi/su-kien-noi-bat/b-654800.html", "09/10/2026"),
            ("/vi/su-kien-noi-bat/c-654799.html", "08/10/2026"),
        ])

        class FakeResp:
            text = html

        monkeypatch.setattr(c, "fetch", lambda url: (FakeResp(), ""))
        manifest = c.run_recent("2026-10-08", "2026-10-10")
        assert manifest["matched"] == 3
        assert manifest["pushed_to_queue"] == 3
        pushed = {u for u, _, _ in c.frontier}
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/a-654801.html" in pushed
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/c-654799.html" in pushed

    def test_bai_cu_hon_since_bi_cat_khong_day_vao_hang_doi(self, tmp_path, monkeypatch):
        """Danh sách giảm dần: gặp 05/10 < since=08/10 thì dừng, không xét tiếp."""
        c = self._crawler(tmp_path, monkeypatch)
        html = self._page([
            ("/vi/su-kien-noi-bat/a-1.html", "10/10/2026"),
            ("/vi/su-kien-noi-bat/b-2.html", "05/10/2026"),    # < since -> dừng ở đây
            ("/vi/su-kien-noi-bat/c-3.html", "01/10/2026"),    # không bao giờ được xét
        ])

        class FakeResp:
            text = html

        monkeypatch.setattr(c, "fetch", lambda url: (FakeResp(), ""))
        manifest = c.run_recent("2026-10-08", "2026-10-10")
        assert manifest["matched"] == 1          # chỉ "a"
        urls = {u for u, _, _ in c.frontier}
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/a-1.html" in urls
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/c-3.html" not in urls

    def test_ngay_tuong_lai_bi_bo_qua_khong_lam_dung_duyet(self, tmp_path, monkeypatch):
        """Mục có ngày > until (lệch giờ máy chủ) chỉ bị loại, KHÔNG dừng cả trang."""
        c = self._crawler(tmp_path, monkeypatch)
        html = self._page([
            ("/vi/su-kien-noi-bat/future-1.html", "11/10/2026"),   # > until, bỏ qua
            ("/vi/su-kien-noi-bat/ok-2.html", "09/10/2026"),       # trong khoảng
        ])

        class FakeResp:
            text = html

        monkeypatch.setattr(c, "fetch", lambda url: (FakeResp(), ""))
        manifest = c.run_recent("2026-10-08", "2026-10-10")
        assert manifest["skipped_future"] == 1
        assert manifest["matched"] == 1
        urls = {u for u, _, _ in c.frontier}
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/ok-2.html" in urls
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/future-1.html" not in urls

    def test_listing_khong_giam_dan_thi_khong_cat_oan(self, tmp_path, monkeypatch):
        """Bài bị ghim lên đầu (ngày cũ hơn mục sau) -> không coi là giảm dần,
        duyệt hết trang thay vì dừng oan ở mục ghim."""
        c = self._crawler(tmp_path, monkeypatch)
        html = self._page([
            ("/vi/su-kien-noi-bat/pinned-1.html", "01/09/2026"),   # ghim, cũ hơn since
            ("/vi/su-kien-noi-bat/new-2.html", "10/10/2026"),      # mục sau lại mới hơn -> không giảm dần
            ("/vi/su-kien-noi-bat/new-3.html", "09/10/2026"),
        ])

        class FakeResp:
            text = html

        monkeypatch.setattr(c, "fetch", lambda url: (FakeResp(), ""))
        manifest = c.run_recent("2026-10-08", "2026-10-10")
        # không giảm dần -> duyệt hết trang; chỉ các mục THỰC SỰ trong khoảng mới match
        urls = {u for u, _, _ in c.frontier}
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/new-2.html" in urls
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/new-3.html" in urls
        assert "https://hust.edu.vn/vi/su-kien-noi-bat/pinned-1.html" not in urls

    def test_site_chua_khai_recent_listings_tra_ket_qua_rong(self, tmp_path, monkeypatch):
        c = self._crawler(tmp_path, monkeypatch)
        ca.set_site("library.hust.edu.vn")     # không có trong RECENT_LISTINGS
        c2 = self._crawler(tmp_path, monkeypatch)
        manifest = c2.run_recent("2026-10-08", "2026-10-10")
        assert manifest["matched"] == 0
        assert manifest["pages"] == 0
