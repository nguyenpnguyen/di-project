import json
import pathlib
import sys
import unittest

from bs4 import BeautifulSoup

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "api"))
from boc_tach import boc_tach, khoi, khuon, lien_ket, truong  # noqa: E402

FX = pathlib.Path(__file__).parent / "fixtures" / "html"


def fixture(host, i):
    f = FX / host / f"{i:02d}.html"
    gold = json.loads(f.with_suffix(".gold.json").read_text(encoding="utf-8"))
    return f.read_text(encoding="utf-8"), gold


class KhoiTest(unittest.TestCase):
    def test_chon_bodytext_khi_tat_selector_tren_fixture_nukeviet(self):
        html, gold = fixture("nukeviet.test", 0)
        k = khoi.tim_khoi(BeautifulSoup(html, "lxml"), host="")
        self.assertEqual(k.method, "heuristic")
        self.assertIn("bodytext", k.path)

    def test_selector_hust_dung_bodytext(self):
        html, _ = fixture("nukeviet.test", 1)
        k = khoi.tim_khoi(BeautifulSoup(html, "lxml"), host="hust.edu.vn")
        self.assertEqual(k.method, "selector")

    def test_khong_chon_nham_menu_tren_bo_cuc_khong_ten(self):
        for i in range(5):
            html, gold = fixture("khongten.test", i)
            text = khoi.tim_khoi(BeautifulSoup(html, "lxml")).text
            self.assertIn(gold["content"][0], text)
            self.assertNotIn("Cựu sinh viên", text)      # mục menu
            self.assertNotIn("Sơ đồ trang", text)

    def test_bang_long_nhau_chon_o_noi_dung(self):
        html, gold = fixture("bang.test", 2)
        text = khoi.tim_khoi(BeautifulSoup(html, "lxml")).text
        self.assertIn(gold["content"][-1], text)
        self.assertNotIn("Sơ đồ trang", text)

    def test_trang_rong_hoac_chi_menu_thi_fallback_body(self):
        k = khoi.tim_khoi(BeautifulSoup("<html><body><div></div></body></html>", "lxml"))
        self.assertEqual(k.method, "fallback")

    def test_bo_the_an_va_comment(self):
        s = BeautifulSoup('<body><main><p>Thật. Đúng.</p><div style="display: none">ẩn</div>'
                          '<!-- ghi chú --><script>var x=1</script></main></body>', "lxml")
        self.assertEqual(khoi.tim_khoi(s).text, "Thật. Đúng.")


class KhuonTest(unittest.TestCase):
    def pages(self, n, chung="Liên hệ 0123 ABC"):
        return [BeautifulSoup(f"<body><ul><li>{chung}</li></ul><p>Bài viết về {chr(97+i)*5} riêng biệt</p></body>", "lxml")
                for i in range(n)]

    def test_host_it_hon_20_trang_khong_ket_luan(self):
        bang = khuon.dem_host([khuon.van_tay_trang(s) for s in self.pages(10)])
        self.assertEqual(khuon.tap_khuon(bang), set())

    def test_khoi_lap_tren_moi_trang_la_khuon_con_bai_thi_khong(self):
        ps = self.pages(25)
        kh = khuon.tap_khuon(khuon.dem_host([khuon.van_tay_trang(s) for s in ps]))
        self.assertIn(khuon.van_tay("Liên hệ 0123 ABC"), kh)
        s = self.pages(1)[0]
        khuon.bo_khuon(s, kh)
        self.assertNotIn("Liên hệ", s.get_text())
        self.assertIn("riêng biệt", s.get_text())

    def test_van_tay_bo_qua_con_so(self):
        self.assertEqual(khuon.van_tay("Hôm nay 12 tin"), khuon.van_tay("Hôm nay 99 tin"))


class TruongTest(unittest.TestCase):
    def test_ngay_dd_mm_yyyy_sang_iso(self):
        self.assertEqual(truong.chuan_ngay("Đăng ngày 05/09/2026 10:30"), "2026-09-05")
        self.assertEqual(truong.chuan_ngay("2026-08-22T10:00:00+07:00"), "2026-08-22")
        self.assertEqual(truong.chuan_ngay("31/02/2026"), "")     # ngày không tồn tại

    def test_ngay_uu_tien_meta_truoc_regex(self):
        html = ('<html><head><meta property="article:published_time" content="2026-03-04T00:00:00Z">'
                '</head><body><main><p>Đăng 01/01/2020. Nội dung dài đủ chữ ở đây.</p></main></body></html>')
        d = boc_tach(html, "https://x.hust.edu.vn/a.html")
        self.assertEqual((d["date"], d["date_src"]), ("2026-03-04", "article:published_time"))

    def test_dong_tac_gia_bi_cat_khoi_content(self):
        html = ("<html><body><main><p>Đoạn một của bài viết, khá dài.</p>"
                "<p><strong>Tác giả: Trần Thị B</strong></p></main></body></html>")
        d = boc_tach(html, "https://x.hust.edu.vn/a.html")
        self.assertEqual((d["author"], d["author_src"]), ("Trần Thị B", "text-line"))
        self.assertNotIn("Tác giả", d["text"])
        self.assertNotIn("<strong>", d["html"])

    def test_tac_gia_meta_bo_qua_admin(self):
        html = ('<html><head><meta name="author" content="admin"></head>'
                "<body><main><p>Nội dung.</p><p>Bài, ảnh: Lê Văn C</p></main></body></html>")
        d = boc_tach(html, "https://x.hust.edu.vn/a.html")
        self.assertEqual(d["author"], "Lê Văn C")

    def test_nguon_trich_dan_la_truong_phu(self):
        html = "<html><body><main><p>Nội dung bài.</p><p>Nguồn: Báo Giáo dục</p></main></body></html>"
        d = boc_tach(html, "https://x.hust.edu.vn/a.html")
        self.assertEqual(d["cited_source"], "Báo Giáo dục")
        self.assertNotIn("Nguồn", d["text"])

    def test_theo_trong_cau_van_khong_bi_nhan_la_nguon(self):
        html = ("<html><body><main><p>Theo kế hoạch năm nay, nhà trường tổ chức nhiều hoạt động "
                "cho sinh viên trong suốt học kỳ và sau đó nữa để đảm bảo chất lượng.</p></main></body></html>")
        d = boc_tach(html, "https://x.hust.edu.vn/a.html")
        self.assertEqual(d["cited_source"], "")

    def test_tieu_de_json_ld_va_cat_hau_to(self):
        html = ('<html><head><title>Thông báo tuyển sinh 2026 - ĐH Bách khoa</title>'
                '<script type="application/ld+json">{"@type":"NewsArticle","headline":"Tiêu đề JSON",'
                '"author":{"name":"Phạm D"},"datePublished":"2026-05-06"}</script></head>'
                "<body><main><p>Nội dung.</p></main></body></html>")
        d = boc_tach(html, "https://x.hust.edu.vn/a.html")
        self.assertEqual((d["title"], d["title_src"]), ("Tiêu đề JSON", "json-ld"))
        self.assertEqual((d["author"], d["author_src"]), ("Phạm D", "json-ld"))
        self.assertEqual(d["date"], "2026-05-06")
        self.assertEqual(truong.cat_hau_to("Thông báo tuyển sinh 2026 - ĐH Bách khoa"),
                         "Thông báo tuyển sinh 2026")
        self.assertEqual(truong.cat_hau_to("A - B"), "A - B")     # phần đầu quá ngắn: giữ nguyên


class LienKetTest(unittest.TestCase):
    HTML = """<html><body>
      <nav><a href="/menu">Menu chính</a></nav>
      <main><p>Bài viết có tệp đính kèm và ảnh minh hoạ cho sinh viên năm nhất.</p>
        <a href="http://hust.edu.vn/uploads/diem-chuan.pdf#x">Xem chi tiết tại đây</a>
        <a href="https://hust.edu.vn/uploads/diem-chuan.pdf">Xem chi tiết tại đây</a>
        <a href="https://drive.google.com/file/d/1">Bản trên Drive</a>
        <figure><img src="/uploads/anh1.jpg" alt="Lễ khai giảng"></figure>
        <img src="/themes/hust/icon.png" alt="icon">
        <a href="/big.jpg"><img src="/big-thumb.jpg" alt="Ảnh lớn"></a>
      </main>
      <footer><a href="/lien-he">Liên hệ</a></footer></body></html>"""

    def setUp(self):
        self.d = boc_tach(self.HTML, "https://hust.edu.vn/vi/news/bai-1-1.html")
        self.nd = {(e["dst"], e["type"]): e for e in self.d["links"]}
        self.kh = {e["dst"] for e in self.d["nav_links"]}

    def test_http_va_https_khong_tach_doi_qua_norm(self):
        e = self.nd[("https://hust.edu.vn/uploads/diem-chuan.pdf", "href")]
        self.assertEqual((e["count"], e["dst_kind"], e["text"]), (2, "document", "Xem chi tiết tại đây"))

    def test_phan_loai_dich(self):
        self.assertEqual(lien_ket.loai_dich("https://drive.google.com/file/d/1"), "external")
        self.assertEqual(lien_ket.loai_dich("https://hust.edu.vn/a.jpg"), "image")
        self.assertEqual(lien_ket.loai_dich("https://svbk.hust.edu.vn/f?download=1"), "document")
        self.assertEqual(lien_ket.loai_dich("https://hust.edu.vn/vi/"), "page")
        self.assertEqual(lien_ket.loai_dich("https://nothust.edu.vn/a.pdf"), "external")

    def test_menu_va_footer_la_canh_khuon_khong_phai_noi_dung(self):
        self.assertIn("https://hust.edu.vn/menu", self.kh)
        self.assertIn("https://hust.edu.vn/lien-he", self.kh)
        self.assertNotIn(("https://hust.edu.vn/menu", "href"), self.nd)

    def test_anh_trong_bai_la_embed_con_icon_theme_la_khuon(self):
        self.assertEqual(self.nd[("https://hust.edu.vn/uploads/anh1.jpg", "embed")]["text"], "Lễ khai giảng")
        self.assertIn("https://hust.edu.vn/themes/hust/icon.png", self.kh)

    def test_ngoai_ho_hust_ghi_canh_khong_tai(self):
        e = self.nd[("https://drive.google.com/file/d/1", "href")]
        self.assertEqual(e["dst_kind"], "external")

    def test_chu_a_rong_lay_alt_anh_con(self):
        self.assertEqual(self.nd[("https://hust.edu.vn/big.jpg", "href")]["text"], "Ảnh lớn")

    def test_canh_ra_cong_khai_chi_gom_href(self):
        urls = [x["url"] for x in self.d["outgoing_links"]]
        self.assertIn("https://hust.edu.vn/uploads/diem-chuan.pdf", urls)
        self.assertNotIn("https://hust.edu.vn/uploads/anh1.jpg", urls)
        self.assertEqual(len(urls), len(set(urls)))


if __name__ == "__main__":
    unittest.main()
