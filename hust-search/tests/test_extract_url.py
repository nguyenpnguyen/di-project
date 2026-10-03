"""POST /api/extract/url: bóc tách một url bất kỳ, kể cả url chưa có trong kho."""
import base64
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

import httpx
import mongomock
from fastapi import HTTPException

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "api"))
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import main  # noqa: E402
import routes_bt  # noqa: E402
from test_tep import pdf_bytes  # noqa: E402

BAI = ("<html><head><title>Thông báo học bổng 2026 - ĐHBK</title></head><body>"
       "<nav><a href='/tuyen-sinh/'>Tuyển sinh</a></nav><main>"
       "<p>Nhà trường thông báo học bổng năm 2026 cho sinh viên, hạn nộp hồ sơ ngày 05/09/2026.</p>"
       "<a href='/uploads/hb.pdf'>Tải mẫu đơn</a><img src='/uploads/anh.jpg' alt='Lễ trao học bổng'>"
       "<p>Tác giả: Nguyễn Văn A</p></main></body></html>")


def resp(url, content, ctype="text/html; charset=utf-8", status=200):
    return httpx.Response(status, content=content, headers={"content-type": ctype},
                          request=httpx.Request("GET", url))


class ExtractUrlTest(unittest.TestCase):
    def setUp(self):
        self.db = mongomock.MongoClient().db
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.bulk, self.kho, self.tai = [], [], []
        ps = [patch.object(routes_bt, "mdb", lambda: self.db),
              patch.object(routes_bt, "FILES_DIR", str(self.tmp)),
              patch.object(routes_bt, "_index_lucene", lambda doc: self.bulk.append(doc) or ""),
              patch.object(main, "_ghi_kho", lambda rec: self.kho.append(rec)),
              patch.object(main, "tim_ban_ghi", lambda urls: None)]
        for p in ps:
            p.start()
            self.addCleanup(p.stop)

    def web(self, content, ctype="text/html; charset=utf-8"):
        def tai(url):
            self.tai.append(url)
            return resp(url, content, ctype)
        return patch.object(main, "tai_ve", tai)

    def test_url_chua_co_trong_kho_thi_tai_tu_web_boc_va_luu_mongo_lucene(self):
        with self.web(BAI.encode()):
            r = routes_bt.extract_url(routes_bt.UrlReq(url="http://www.svbk.hust.edu.vn/tin/hb-1.html"))
        url = "https://svbk.hust.edu.vn/tin/hb-1.html"                # qua norm()
        self.assertEqual((r["nguon"], r["loai"], r["url"]), ("web", "page", url))
        self.assertEqual(r["truong"]["author"], "Nguyễn Văn A")
        self.assertEqual(r["truong"]["date"], "2026-09-05")
        self.assertTrue(r["luu"]["mongo"] and r["luu"]["index"])
        self.assertIn("Nhà trường thông báo học bổng", r["noi_dung"]["text"])
        self.assertIn("<p>", r["noi_dung"]["html"])                   # HTML đã dọn để trình bày
        self.assertNotIn("Tuyển sinh", r["noi_dung"]["text"])         # menu không lọt vào nội dung
        self.assertEqual({(e["dst"], e["text"]) for e in r["lien_ket"]},
                         {("https://svbk.hust.edu.vn/uploads/hb.pdf", "Tải mẫu đơn"),
                          ("https://svbk.hust.edu.vn/uploads/anh.jpg", "Lễ trao học bổng")})
        self.assertEqual(r["so_canh_khuon"], 1)
        p = self.db.pages.find_one({"_id": url})
        self.assertEqual(p["author"], "Nguyễn Văn A")
        self.assertEqual({e["dst"] for e in self.db.links.find({"src": url})},
                         {"https://svbk.hust.edu.vn/uploads/hb.pdf", "https://svbk.hust.edu.vn/uploads/anh.jpg"})
        self.assertEqual(self.db.documents.find_one({"_id": "https://svbk.hust.edu.vn/uploads/hb.pdf"})["status"], "pending")
        self.assertEqual(self.db.images.find_one({"_id": "https://svbk.hust.edu.vn/uploads/anh.jpg"})["alts"], ["Lễ trao học bổng"])
        self.assertEqual(self.bulk[0]["author"], "Nguyễn Văn A")
        self.assertEqual(len(self.kho), 1)                            # ghi raw-adhoc
        self.assertEqual(self.db.nav_links.count_documents({}), 0)   # nav không ghi lẻ (tránh đếm đôi)

    def test_tai_bang_url_goc_khong_ep_https(self):
        with self.web(BAI.encode()):
            r = routes_bt.extract_url(routes_bt.UrlReq(url="http://site-chi-co-http.example/a.html"))
        self.assertEqual(self.tai, ["http://site-chi-co-http.example/a.html"])   # tải đúng http
        self.assertEqual(r["url"], "https://site-chi-co-http.example/a.html")    # khoá lưu qua norm()

    def test_https_loi_ket_noi_thi_thu_lai_http_con_loi_http_thi_khong(self):
        goi = []

        def tai(url):
            goi.append(url)
            if url.startswith("https://"):
                raise HTTPException(502, "không tải được: SSL wrong version number")
            return resp(url, BAI.encode())
        with patch.object(main, "tai_ve", tai):
            routes_bt.extract_url(routes_bt.UrlReq(url="https://site-chi-co-http.example/a.html"))
        self.assertEqual(goi, ["https://site-chi-co-http.example/a.html", "http://site-chi-co-http.example/a.html"])

        def tai_404(url):
            goi.append(url)
            raise HTTPException(502, "site trả HTTP 404")
        goi.clear()
        with patch.object(main, "tai_ve", tai_404), self.assertRaises(HTTPException):
            routes_bt.extract_url(routes_bt.UrlReq(url="https://x.example/khong-co"))
        self.assertEqual(len(goi), 1)                    # 404 thật thì không thử lại

    def test_url_co_trong_kho_thi_khong_goi_web(self):
        rec = {"url": "https://hust.edu.vn/vi/a-1.html", "status": 200, "encoding": "utf-8",
               "html_b64": base64.b64encode(BAI.encode()).decode()}
        with patch.object(main, "tim_ban_ghi", lambda urls: rec), self.web(b""):
            r = routes_bt.extract_url(routes_bt.UrlReq(url="https://hust.edu.vn/vi/a-1.html"))
        self.assertEqual((r["nguon"], self.tai), ("kho", []))
        self.assertEqual(self.kho, [])

    def test_tai_lai_bo_qua_kho(self):
        rec = {"url": "https://hust.edu.vn/vi/a-1.html", "status": 200, "html_b64": base64.b64encode(b"<p>cu</p>").decode()}
        with patch.object(main, "tim_ban_ghi", lambda urls: rec), self.web(BAI.encode()):
            r = routes_bt.extract_url(routes_bt.UrlReq(url="https://hust.edu.vn/vi/a-1.html", tai_lai=True))
        self.assertEqual(r["nguon"], "web")

    def test_chay_hai_lan_khong_nhan_doi(self):
        for _ in range(2):
            with self.web(BAI.encode()):
                routes_bt.extract_url(routes_bt.UrlReq(url="https://svbk.hust.edu.vn/tin/hb-1.html"))
        self.assertEqual(self.db.pages.count_documents({}), 1)
        self.assertEqual(self.db.links.count_documents({}), 2)
        self.assertEqual(self.db.images.find_one({})["alts"], ["Lễ trao học bổng"])

    def test_khong_luu_khong_index_khi_tat(self):
        with self.web(BAI.encode()):
            r = routes_bt.extract_url(routes_bt.UrlReq(url="https://x.hust.edu.vn/a", luu=False, index=False))
        self.assertEqual((r["luu"]["mongo"], r["luu"]["index"]), (False, False))
        self.assertEqual((self.db.pages.count_documents({}), self.bulk), (0, []))

    def test_mongo_tat_van_boc_duoc_va_bao_loi_luu(self):
        def hong():
            raise HTTPException(503, "x")
        with patch.object(routes_bt, "mdb", hong), self.web(BAI.encode()):
            r = routes_bt.extract_url(routes_bt.UrlReq(url="https://x.hust.edu.vn/a"))
        self.assertFalse(r["co_mongo"])
        self.assertEqual(r["luu"]["loi_mongo"], "MongoDB không sẵn sàng")
        self.assertTrue(r["luu"]["index"])

    def test_url_la_tep_pdf_thi_boc_chu_va_ghi_documents(self):
        with self.web(pdf_bytes("Thong bao hoc bong nam 2026 danh cho sinh vien"), "application/pdf"):
            r = routes_bt.extract_url(routes_bt.UrlReq(url="https://hust.edu.vn/uploads/hb.pdf"))
        self.assertEqual((r["loai"], r["tep"]["status"]), ("document", "ok"))
        doc = self.db.documents.find_one({"_id": "https://hust.edu.vn/uploads/hb.pdf"})
        self.assertIn("hoc bong", doc["text"])
        self.assertIn("hoc bong", r["noi_dung"]["text"])
        self.assertEqual(len(list(self.tmp.iterdir())), 1)
        self.assertEqual(self.bulk[0]["kind"], "document")
        self.assertEqual(self.bulk[0]["ftype"], "pdf")
        self.assertIsNone(self.kho[0]["html_b64"])                   # kho thô không nhét byte pdf vào html_b64

    def test_anh_va_url_sai_bi_tu_choi(self):
        with self.web(b"\x89PNG", "image/png"), self.assertRaises(HTTPException) as c:
            routes_bt.extract_url(routes_bt.UrlReq(url="https://hust.edu.vn/a.png"))
        self.assertEqual(c.exception.status_code, 415)
        with self.assertRaises(HTTPException) as c:
            routes_bt.extract_url(routes_bt.UrlReq(url="ftp://x/y"))
        self.assertEqual(c.exception.status_code, 422)

    def test_graph_out_bao_url_chua_co_trang(self):
        r = routes_bt.graph_out("https://x.hust.edu.vn/chua-co")
        self.assertFalse(r["co_trang"])
        with self.web(BAI.encode()):
            routes_bt.extract_url(routes_bt.UrlReq(url="https://x.hust.edu.vn/chua-co"))
        self.assertTrue(routes_bt.graph_out("https://x.hust.edu.vn/chua-co")["co_trang"])


if __name__ == "__main__":
    unittest.main()
