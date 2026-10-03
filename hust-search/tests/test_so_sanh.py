import csv
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "api"))
import so_sanh as ss  # noqa: E402


class DoLuongTest(unittest.TestCase):
    def test_fold_giu_do_dai_de_cat_doan_trich_theo_vi_tri(self):
        s = "Điểm chuẩn Đại học Bách khoa"
        self.assertEqual(ss.fold(s), "diem chuan dai hoc bach khoa")
        self.assertEqual(len(ss.fold(s)), len(s))

    def test_tokens_bo_dau_va_chu_hoa(self):
        self.assertEqual(ss.tokens("Học BỔNG, năm 2026"), {"hoc", "bong", "nam", "2026"})

    def test_overlap_at_k(self):
        self.assertEqual(ss.overlap_at_k(["a", "b"], ["b", "c"]), 0.5)
        self.assertEqual(ss.overlap_at_k([], []), 1.0)
        self.assertEqual(ss.overlap_at_k(["a"], []), 0.0)

    def test_precision_mrr_chi_tinh_tren_ket_qua_da_gan_nhan(self):
        p, mrr, chua = ss.precision_mrr(["a", "b", "c", "d"], {"a": 0, "b": 1, "c": 1})
        self.assertEqual((p, mrr, chua), (2 / 3, 0.5, 1))
        self.assertEqual(ss.precision_mrr(["x"], {}), (None, None, 1))

    def test_doc_nhan_bo_dong_chua_dien(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "n.csv"
            with open(f, "w", encoding="utf-8-sig", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(["query", "url", "title", "in_cu", "in_moi", "relevant"])
                w.writerow(["q", "u1", "", 1, 1, "1"])
                w.writerow(["q", "u2", "", 1, 0, ""])
                w.writerow(["q", "u3", "", 0, 1, "x"])
            self.assertEqual(ss.doc_nhan(f), {"q": {"u1": 1}})


class BocCuTest(unittest.TestCase):
    def test_extract_cu_giu_hanh_vi_cu_lay_ca_body_khi_khong_co_main(self):
        html = ("<html><head><title>T</title></head><body><nav>Menu</nav><div>Bài</div>"
                "<footer>Chân</footer></body></html>")
        d = ss.extract_cu({"url": "https://a.hust.edu.vn/x"}, html)
        self.assertEqual(d["text"], "Bài")          # nav/footer bị bỏ, phần còn lại của body giữ nguyên
        d2 = ss.extract_cu({"url": "https://a.hust.edu.vn/x"},
                           "<body><div>Menu riêng</div><main>Thân</main></body>")
        self.assertEqual(d2["text"], "Thân")


if __name__ == "__main__":
    unittest.main()
