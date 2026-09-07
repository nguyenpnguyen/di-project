package vn.hust.search;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexTest {

    Path dir;
    Index idx;

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("lucene-test");
        idx = new Index(dir);
    }

    @AfterEach void tearDown() throws Exception { idx.close(); }

    private void doc(String url, String title, String text) throws Exception {
        idx.put(Map.of("url", url, "title", title, "text", text,
                "host", "hust.edu.vn", "section", "Tin tức", "date", "2026-08-09"));
    }

    @Test void indexRoiTimThayLai() throws Exception {
        doc("https://hust.edu.vn/a-1.html", "Điểm chuẩn Đại học Bách khoa Hà Nội năm 2026",
            "Chiều nay Đại học Bách khoa Hà Nội công bố điểm chuẩn xét tuyển vào 68 chương trình.");
        idx.commit();

        Index.Result r = idx.search("điểm chuẩn", 0, 10, null);
        assertEquals(1, r.total(), "phải tìm ra đúng một tài liệu");
        assertEquals("https://hust.edu.vn/a-1.html", r.hits().get(0).url());
    }

    @Test void toSangPhanKhopBangTheMark() throws Exception {
        doc("https://hust.edu.vn/b-2.html", "Thông báo tuyển sinh",
            "Trường công bố điểm chuẩn xét tuyển sớm năm 2026 cho các ngành.");
        idx.commit();

        Index.Result r = idx.search("điểm chuẩn", 0, 10, null);
        List<String> frags = r.hits().get(0).fragments();
        assertFalse(frags.isEmpty(), "phải có đoạn trích");
        assertTrue(frags.stream().anyMatch(f -> f.contains("<mark>")),
                "đoạn trích phải bọc <mark> quanh từ khớp, thực tế: " + frags);
    }

    @Test void indexLaiCungUrlThiKhongDeTrung() throws Exception {
        doc("https://hust.edu.vn/c-3.html", "Bản đầu", "nội dung ban đầu");
        idx.commit();
        doc("https://hust.edu.vn/c-3.html", "Bản sửa", "nội dung đã sửa");
        idx.commit();

        assertEquals(1, idx.numDocs(), "cùng url thì ghi đè, không thêm bản mới");
        Index.Result r = idx.search("nội dung", 0, 10, null);
        assertEquals("Bản sửa", r.hits().get(0).title(), "phải giữ bản mới nhất");
    }

    @Test void tieuDeDuocUuTienHonNoiDung() throws Exception {
        doc("https://hust.edu.vn/d-4.html", "Học bổng Trần Đại Nghĩa", "chuyện khác hẳn");
        doc("https://hust.edu.vn/e-5.html", "Tin vắn", "bài này chỉ nhắc học bổng ở giữa thân bài");
        idx.commit();

        Index.Result r = idx.search("học bổng", 0, 10, null);
        assertEquals(2, r.total());
        assertEquals("Học bổng Trần Đại Nghĩa", r.hits().get(0).title(),
                "khớp ở tiêu đề phải xếp trên khớp ở thân bài");
    }

    @Test void locTheoHost() throws Exception {
        doc("https://hust.edu.vn/f-6.html", "Thư viện", "giờ mở cửa thư viện");
        idx.put(Map.of("url", "https://library.hust.edu.vn/x", "title", "Thư viện Tạ Quang Bửu",
                "text", "giờ mở cửa thư viện", "host", "library.hust.edu.vn"));
        idx.commit();

        assertEquals(2, idx.search("thư viện", 0, 10, null).total());
        assertEquals(1, idx.search("thư viện", 0, 10, "library.hust.edu.vn").total(),
                "lọc host phải thu hẹp đúng một site");
    }

    @Test void demTheoHost() throws Exception {
        doc("https://hust.edu.vn/g-7.html", "A", "x");
        doc("https://hust.edu.vn/h-8.html", "B", "y");
        idx.put(Map.of("url", "https://svbk.hust.edu.vn/z", "title", "C", "text", "z",
                "host", "svbk.hust.edu.vn"));
        idx.commit();

        Map<String, Integer> byHost = idx.byHost();
        assertEquals(2, byHost.get("hust.edu.vn"));
        assertEquals(1, byHost.get("svbk.hust.edu.vn"));
    }

    @Test void truyVanSaiCuPhapThiNemLoi() throws Exception {
        doc("https://hust.edu.vn/i-9.html", "A", "x");
        idx.commit();
        assertThrows(Exception.class, () -> idx.search("điểm AND AND", 0, 10, null),
                "cú pháp hỏng phải báo lỗi để tầng trên trả 400, không phải 500");
    }

    @Test void resetXoaSach() throws Exception {
        doc("https://hust.edu.vn/j-10.html", "A", "x");
        idx.commit();
        assertEquals(1, idx.numDocs());
        idx.reset();
        assertEquals(0, idx.numDocs());
    }
}
