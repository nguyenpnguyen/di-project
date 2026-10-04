package vn.hust.search.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Port của BongChuTest (tests/test_tep.py). Tệp mẫu sinh một lần bằng Python rồi commit ở resources/tep. */
class DocumentTextTest {
    static byte[] sample(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/tep", name));
    }

    @Test
    void pdfWithTextLayer() throws Exception {
        var r = DocumentText.extractText(sample("co-chu.pdf"), "pdf");
        assertEquals("ok", r.status());
        assertFalse(r.needsOcr());
        assertEquals(1, r.nPages());
        assertTrue(r.text().contains("hoc bong"), r.text());
    }

    @Test
    void pdfWithoutTextIsFlaggedNeedsOcrAndNotOcrd() throws Exception {
        var r = DocumentText.extractText(sample("khong-chu.pdf"), "pdf");
        assertEquals("ok", r.status());
        assertTrue(r.needsOcr());
        assertEquals("", r.text());
    }

    @Test
    void docxXlsxPptx() throws Exception {
        String d = DocumentText.extractText(sample("mau.docx"), "docx").text().replaceAll("\\s+", " ");
        assertTrue(d.contains("học bổng"), d);
        assertTrue(d.contains("Mã ngành") && d.contains("IT1"), d);
        var x = DocumentText.extractText(sample("mau.xlsx"), "xlsx");
        assertTrue(x.text().contains("Điểm chuẩn") || x.text().contains("Ngành"), x.text());
        assertTrue(x.text().contains("27.5"), x.text());
        assertEquals(1, x.nPages());                              // một sheet
        assertTrue(DocumentText.extractText(sample("mau.pptx"), "pptx").text().contains("khai giảng"));
    }

    @Test
    void legacyFormatsReadableViaPoi() throws Exception {            // Q2: doc/xls/ppt không còn unsupported
        var xls = new ByteArrayOutputStream();
        try (var wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
            var row = wb.createSheet("Học bổng").createRow(0);
            row.createCell(0).setCellValue("Khuyến khích học tập");
            row.createCell(1).setCellValue(27.5);
            wb.write(xls);
        }
        var r = DocumentText.extractText(xls.toByteArray(), "xls");
        assertEquals("ok", r.status(), r.error());
        assertTrue(r.text().contains("Khuyến khích học tập"), r.text());

        var ppt = new ByteArrayOutputStream();
        try (var show = new org.apache.poi.hslf.usermodel.HSLFSlideShow()) {
            var box = show.createSlide().createTextBox();
            box.setText("Lễ khai giảng cũ");
            show.write(ppt);
        }
        r = DocumentText.extractText(ppt.toByteArray(), "ppt");
        assertEquals("ok", r.status(), r.error());
        assertTrue(r.text().contains("khai giảng cũ"), r.text());
    }

    @Test
    void unknownExtensionAndCorruptFileBecomeErrorWithoutThrowing() {
        assertEquals("unsupported", DocumentText.extractText("x".getBytes(), "xyz").status());
        var r = DocumentText.extractText("khong phai docx".getBytes(), "docx");
        assertEquals("error", r.status());
        assertFalse(r.error().isEmpty());
    }

    @Test
    void suspectEncodingTcvn3() {
        assertTrue(DocumentText.suspectEncoding("Th«ng b¸o tuyÓn sinh n¨m häc míi ®¹i häc ".repeat(12)));
        assertFalse(DocumentText.suspectEncoding("Thông báo tuyển sinh năm học mới của trường đại học ".repeat(8)));
    }

    @Test
    void extensionFromUrl() {
        assertEquals("pdf", DocumentText.extensionFromUrl("https://hust.edu.vn/uploads/A.PDF?x=1.doc"));
        assertEquals("", DocumentText.extensionFromUrl("https://hust.edu.vn/tai"));
    }
}
