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


class IndexTuMongoTest(unittest.TestCase):
    def setUp(self):
        self.db = mongomock.MongoClient().db
        html = ("<html><head><meta name='author' content='Lê Văn C'></head><body><main>"
                "<p>Nội dung học bổng của trường dành cho sinh viên năm cuối.</p></main></body></html>")
        trich.chay_extract(self.db, [rec("https://hust.edu.vn/vi/a-1.html", html)])
        self.db.documents.insert_many([
            {"_id": "https://hust.edu.vn/uploads/thong-bao%20hoc-bong.pdf", "host": "hust.edu.vn",
             "ext": "pdf", "status": "ok", "text": "Thông báo học bổng"},
            {"_id": "https://hust.edu.vn/uploads/scan.pdf", "host": "hust.edu.vn", "ext": "pdf",
             "status": "ok", "text": "", "needs_ocr": True}])

    def test_lucene_tu_mongo_co_trang_va_tep_co_chu_khong_lay_tep_scan(self):
        docs = {d["url"]: d for d in trich.lucene_tu_mongo(self.db)}
        self.assertEqual(len(docs), 2)
        trang = docs["https://hust.edu.vn/vi/a-1.html"]
        self.assertEqual((trang["kind"], trang["author"]), ("page", "Lê Văn C"))
        tep = docs["https://hust.edu.vn/uploads/thong-bao%20hoc-bong.pdf"]
        self.assertEqual((tep["kind"], tep["title"]), ("document", "thong-bao hoc-bong.pdf"))

    def _index(self, source):
        sent = []

        class Client:
            def __init__(self, *a, **k): pass
            def __enter__(self): return self
            def __exit__(self, *a): return False
            def post(self, url, **k):
                if url == "/bulk":
                    sent.extend(k["json"])
                return type("R", (), {"raise_for_status": lambda s: None})()
            def get(self, url, **k):
                return type("R", (), {"raise_for_status": lambda s: None, "json": lambda s: {"docs": len(sent)}})()

        with patch.object(main.httpx, "Client", Client), patch.object(main, "_mongo", lambda: self.db):
            return main.index_run(main.IndexReq(source=source)), sent

    def test_index_run_source_mongo_day_author_va_kind_sang_lucene(self):
        r, sent = self._index("mongo")
        self.assertEqual((r["source"], r["indexed"]), ("mongo", 2))
        self.assertEqual({d["kind"] for d in sent}, {"page", "document"})

    def test_index_run_auto_chon_mongo_khi_co_du_lieu_va_raw_khi_rong(self):
        self.assertEqual(self._index("auto")[0]["source"], "mongo")
        self.db.pages.delete_many({})
        with patch.object(main, "tat_ca_ban_ghi", lambda: iter([])):
            self.assertEqual(self._index("auto")[0]["source"], "raw")

    def test_dung_lai_bang_kho_tho_van_giu_tep_tu_mongo(self):
        # trang lấy từ kho thô, tệp lấy từ Mongo: trước đây nguồn raw làm mất mọi pdf
        self.db.pages.delete_many({})
        with patch.object(main, "tat_ca_ban_ghi", lambda: iter([])):
            for source in ("raw", "auto"):
                r, sent = self._index(source)
                self.assertEqual(r["source"], "raw")
                self.assertEqual([d["url"] for d in sent],
                                 ["https://hust.edu.vn/uploads/thong-bao%20hoc-bong.pdf"], source)
                self.assertEqual(sent[0]["kind"], "document")

    def test_source_khong_hop_le_tra_400(self):
        with self.assertRaises(HTTPException) as c:
            main.index_run(main.IndexReq(source="xyz"))
        self.assertEqual(c.exception.status_code, 400)

    def test_public_document_kind_hop_le(self):
        with self.assertRaises(Exception):
            main.PublicDocument(url="https://a.b/c", title="x", kind="video")
        d = main.PublicDocument(url="https://a.b/c", title="x", author="Y")
        self.assertEqual(main.lucene_document(d)["author"], "Y")
        self.assertEqual(main.lucene_document(d)["kind"], "page")


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

    def test_explain_doc_kho_qua_bi_danh_va_404_khi_khong_co(self):
        u = "https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html"
        with patch.object(main, "tim_ban_ghi", lambda urls: rec(u, BAI) if u in urls else None):
            r = routes_bt.extract_explain("https://hust.edu.vn/vi/khac/diem-chuan-654601.html")
            self.assertEqual(r["block"]["path"], "html > body > main")
            self.assertEqual(r["canh"]["noi_dung"], 2)          # tệp pdf + ảnh trong bài
            self.assertEqual(r["canh"]["khuon"], 1)             # menu
            with self.assertRaises(HTTPException) as c:
                routes_bt.extract_explain("https://hust.edu.vn/vi/khong-co.html")
            self.assertEqual(c.exception.status_code, 404)

    def test_tim_ban_ghi_trong_kho_that(self):
        import gzip, json, tempfile
        with tempfile.TemporaryDirectory() as t:
            kho = pathlib.Path(t) / "raw"
            kho.mkdir()
            with gzip.open(kho / "pages-0001.jsonl.gz", "wt", encoding="utf-8") as fh:
                for u in ("http://hust.edu.vn/vi/a.html", "https://hust.edu.vn/vi/b.html"):
                    fh.write(json.dumps(rec(u, BAI)) + "\n")
            with patch.object(main, "DATA", pathlib.Path(t)):
                self.assertEqual(main.tim_ban_ghi({"https://hust.edu.vn/vi/a.html"})["url"],
                                 "http://hust.edu.vn/vi/a.html")   # khớp qua norm()
                self.assertIsNone(main.tim_ban_ghi({"https://hust.edu.vn/vi/c.html"}))

    def test_overview_dem_tung_buoc(self):
        o = routes_bt.extract_overview()
        self.assertEqual((o["pages"], o["links"], o["nav_links"]), (1, 2, 1))
        self.assertEqual(o["templates"], 0)

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
