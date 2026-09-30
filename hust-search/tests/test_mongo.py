"""Kiểm tra lớp Mongo bằng mongomock. mongomock KHÔNG thực thi $jsonSchema, nên
validator chỉ được kiểm ở integration.sh với MongoDB thật."""
import base64
import pathlib
import sys
import unittest
from unittest.mock import patch

import mongomock
from fastapi import HTTPException

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "api"))
import db as dbmod  # noqa: E402
import main  # noqa: E402
import routes_bt  # noqa: E402
import trich  # noqa: E402


def rec(url, html):
    return {"url": url, "status": 200, "encoding": "utf-8", "fetched_at": "2026-09-01T00:00:00",
            "html_b64": base64.b64encode(html.encode()).decode()}


BAI = """<html><body><nav><a href="/tuyen-sinh/">Tuyển sinh</a></nav><main>
<p>Điểm chuẩn năm nay đã được công bố cho toàn bộ các ngành đào tạo.</p>
<a href="/uploads/diem-chuan.pdf">Xem chi tiết tại đây</a>
<img src="/uploads/anh1.jpg" alt="Lễ khai giảng"></main></body></html>"""


class TrichTest(unittest.TestCase):
    def setUp(self):
        self.db = mongomock.MongoClient().db
        self.recs = [
            rec("https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html", BAI),
            rec("https://hust.edu.vn/vi/khac/diem-chuan-654601.html", BAI),   # cùng đoạn cuối: bản trùng
            rec("https://hust.edu.vn/vi/tin-tuc/bai-khac-654601.html", BAI.replace("Điểm chuẩn", "Học phí")),
            {"url": "https://hust.edu.vn/x.pdf", "status": 200, "html_b64": None},
        ]

    def chay(self):
        return trich.chay_extract(self.db, list(self.recs))

    def test_chay_hai_lan_khong_nhan_doi_ban_ghi(self):
        self.chay()
        dem1 = {c: self.db[c].count_documents({}) for c in ("pages", "links", "nav_links", "images")}
        self.chay()
        dem2 = {c: self.db[c].count_documents({}) for c in ("pages", "links", "nav_links", "images")}
        self.assertEqual(dem1, dem2)
        self.assertEqual(dem1["pages"], 2)

    def test_bai_trung_dedup_key_thanh_bi_danh_con_so_giong_khong_gop(self):
        self.chay()
        p = self.db.pages.find_one({"_id": "https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html"})
        self.assertEqual(p["aliases"], ["https://hust.edu.vn/vi/khac/diem-chuan-654601.html"])
        self.assertIsNotNone(self.db.pages.find_one({"_id": "https://hust.edu.vn/vi/tin-tuc/bai-khac-654601.html"}))

    def test_trang_ghi_du_truong_theo_luoc_do(self):
        self.chay()
        p = self.db.pages.find_one({"_id": "https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html"})
        for f in dbmod.SCHEMAS["pages"]["required"]:
            self.assertIn(f, p)
        self.assertIn(p["content"]["block"]["method"], {"selector", "heuristic", "fallback"})
        self.assertEqual(p["raw"]["fetched_at"], "2026-09-01T00:00:00")

    def test_nav_gop_theo_host_va_anh_khuon_danh_dau(self):
        self.chay()
        n = self.db.nav_links.find_one({"dst": "https://hust.edu.vn/tuyen-sinh/"})
        self.assertEqual((n["n_pages"], n["host"], n["text"]), (2, "hust.edu.vn", "Tuyển sinh"))
        self.assertEqual(self.db.images.find_one({"_id": "https://hust.edu.vn/uploads/anh1.jpg"})["is_template"], False)

    def test_limit_dung_som(self):
        r = trich.chay_extract(self.db, list(self.recs), limit=1)
        self.assertEqual(r["pages"], 1)

    def test_bang_templates_chi_ghi_khoi_lap_tu_3_trang(self):
        recs = [rec(f"https://a.hust.edu.vn/p/{i}.html",
                    f"<body><ul><li>Menu chung</li></ul><p>Bài viết duy nhất {chr(97 + i) * 6}</p></body>")
                for i in range(25)]
        out = trich.dung_templates(self.db, recs)
        self.assertEqual(out["a.hust.edu.vn"]["n_pages"], 25)
        self.assertGreaterEqual(out["a.hust.edu.vn"]["template_blocks"], 1)


class RoutesTest(unittest.TestCase):
    def setUp(self):
        self.db = mongomock.MongoClient().db
        trich.chay_extract(self.db, [
            rec("https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html", BAI),
            rec("https://hust.edu.vn/vi/khac/diem-chuan-654601.html", BAI)])
        p = patch.object(routes_bt, "mdb", lambda: self.db)
        p.start()
        self.addCleanup(p.stop)

    def test_referrers_tep_tra_ve_trang_nguon_va_chu_mo_ta(self):
        r = routes_bt.referrers("https://hust.edu.vn/uploads/diem-chuan.pdf")
        self.assertEqual(r["content"], [{"src": "https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html",
                                         "text": "Xem chi tiết tại đây", "type": "href", "count": 1}])

    def test_referrers_trang_co_ca_menu_gop_theo_host(self):
        r = routes_bt.referrers("http://www.hust.edu.vn/tuyen-sinh/")     # qua norm()
        self.assertEqual(r["content"], [])
        self.assertEqual(r["nav"][0]["text"], "Tuyển sinh")

    def test_referrers_bi_danh_tro_ve_trang_chinh(self):
        r = routes_bt.referrers("https://hust.edu.vn/vi/khac/diem-chuan-654601.html")
        self.assertEqual(r["url"], "https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html")

    def test_graph_out_va_edges_csv(self):
        o = routes_bt.graph_out("https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html")
        self.assertEqual({e["dst"] for e in o["edges"]},
                         {"https://hust.edu.vn/uploads/diem-chuan.pdf", "https://hust.edu.vn/uploads/anh1.jpg"})
        import asyncio

        async def doc():
            return "".join([c async for c in routes_bt.graph_edges_csv().body_iterator])
        body = asyncio.run(doc())
        self.assertTrue(body.startswith("source,target,text"))
        self.assertIn("Xem chi tiết tại đây", body)

    def test_coverage_theo_host(self):
        c = routes_bt.extract_coverage()
        self.assertEqual(c["hust.edu.vn"]["pages"], 1)
        self.assertEqual(c["hust.edu.vn"]["title_pct"], 0.0)   # fixture không có tiêu đề

    def test_referrers_tu_choi_url_khong_phai_http(self):
        with self.assertRaises(HTTPException) as c:
            routes_bt.referrers("ftp://x")
        self.assertEqual(c.exception.status_code, 422)

    def test_chay_nen_tu_choi_khi_dang_chay(self):
        routes_bt._job["running"] = True
        try:
            with self.assertRaises(HTTPException) as c:
                routes_bt._chay_nen("x", lambda cb: None)
            self.assertEqual(c.exception.status_code, 409)
        finally:
            routes_bt._job["running"] = False


if __name__ == "__main__":
    unittest.main()
