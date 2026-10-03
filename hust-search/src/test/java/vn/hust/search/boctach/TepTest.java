package vn.hust.search.boctach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Port của BongChuTest (tests/test_tep.py). Tệp mẫu sinh một lần bằng Python rồi commit ở resources/tep. */
class TepTest {
    static byte[] mau(String ten) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/tep", ten));
    }

    @Test
    void pdfCoLopChu() throws Exception {
        var r = Tep.bocChu(mau("co-chu.pdf"), "pdf");
        assertEquals("ok", r.status());
        assertFalse(r.needsOcr());
        assertEquals(1, r.nPages());
        assertTrue(r.text().contains("hoc bong"), r.text());
    }

    @Test
    void pdfKhongCoChuBiGanNeedsOcrKhongOcr() throws Exception {
        var r = Tep.bocChu(mau("khong-chu.pdf"), "pdf");
        assertEquals("ok", r.status());
        assertTrue(r.needsOcr());
        assertEquals("", r.text());
    }

    @Test
    void docxXlsxPptx() throws Exception {
        String d = Tep.bocChu(mau("mau.docx"), "docx").text().replaceAll("\\s+", " ");
        assertTrue(d.contains("học bổng"), d);
        assertTrue(d.contains("Mã ngành") && d.contains("IT1"), d);
        var x = Tep.bocChu(mau("mau.xlsx"), "xlsx");
        assertTrue(x.text().contains("Điểm chuẩn") || x.text().contains("Ngành"), x.text());
        assertTrue(x.text().contains("27.5"), x.text());
        assertEquals(1, x.nPages());                              // một sheet
        assertTrue(Tep.bocChu(mau("mau.pptx"), "pptx").text().contains("khai giảng"));
    }

    @Test
    void dinhDangCuDocDuocNhoPoi() throws Exception {            // Q2: doc/xls/ppt không còn unsupported
        var xls = new ByteArrayOutputStream();
        try (var wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
            var row = wb.createSheet("Học bổng").createRow(0);
            row.createCell(0).setCellValue("Khuyến khích học tập");
            row.createCell(1).setCellValue(27.5);
            wb.write(xls);
        }
        var r = Tep.bocChu(xls.toByteArray(), "xls");
        assertEquals("ok", r.status(), r.error());
        assertTrue(r.text().contains("Khuyến khích học tập"), r.text());

        var ppt = new ByteArrayOutputStream();
        try (var show = new org.apache.poi.hslf.usermodel.HSLFSlideShow()) {
            var box = show.createSlide().createTextBox();
            box.setText("Lễ khai giảng cũ");
            show.write(ppt);
        }
        r = Tep.bocChu(ppt.toByteArray(), "ppt");
        assertEquals("ok", r.status(), r.error());
        assertTrue(r.text().contains("khai giảng cũ"), r.text());
    }

    @Test
    void duoiLaVaTepHongThanhErrorKhongNemLoi() {
        assertEquals("unsupported", Tep.bocChu("x".getBytes(), "xyz").status());
        var r = Tep.bocChu("khong phai docx".getBytes(), "docx");
        assertEquals("error", r.status());
        assertFalse(r.error().isEmpty());
    }

    @Test
    void nghiSaiBangMaTcvn3() {
        assertTrue(Tep.nghiSaiBangMa("Th«ng b¸o tuyÓn sinh n¨m häc míi ®¹i häc ".repeat(12)));
        assertFalse(Tep.nghiSaiBangMa("Thông báo tuyển sinh năm học mới của trường đại học ".repeat(8)));
    }

    @Test
    void duoiTuUrl() {
        assertEquals("pdf", Tep.duoiTuUrl("https://hust.edu.vn/uploads/A.PDF?x=1.doc"));
        assertEquals("", Tep.duoiTuUrl("https://hust.edu.vn/tai"));
    }
}
