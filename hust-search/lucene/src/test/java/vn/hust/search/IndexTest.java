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

    // ------------------------------------------------ các cải tiến xếp hạng

    @Test void goKhongDauVanTimRa() throws Exception {
        doc("https://hust.edu.vn/k-11.html", "Điểm chuẩn Đại học Bách khoa Hà Nội năm 2026",
            "Nhà trường công bố điểm chuẩn xét tuyển vào 68 chương trình đào tạo.");
        idx.commit();

        Index.Result r = idx.search("diem chuan", 0, 10, null);
        assertEquals(1, r.total(), "gõ không dấu phải ra bài có dấu");
        assertTrue(r.hits().get(0).fragments().stream().anyMatch(f -> f.contains("<mark>")),
                "đoạn trích vẫn phải tô sáng, thực tế: " + r.hits().get(0).fragments());
        assertTrue(r.hits().get(0).fragments().toString().contains("điểm"),
                "đoạn trích lấy từ bản gốc nên phải còn dấu");
    }

    @Test void goDuDauXepTrenGoKhongDau() throws Exception {
        doc("https://hust.edu.vn/l-12.html", "Tuyển sinh đại học",
            "thông tin tuyển sinh đại học chính quy năm nay của nhà trường");
        doc("https://hust.edu.vn/m-13.html", "Tuyen sinh dai hoc",
            "ban tin khong dau ve tuyen sinh dai hoc chinh quy nam nay");
        idx.commit();

        Index.Result r = idx.search("tuyển sinh", 0, 10, null);
        assertEquals(2, r.total(), "cả hai bản đều phải ra");
        assertEquals("https://hust.edu.vn/l-12.html", r.hits().get(0).url(),
                "gõ đủ dấu thì bản có dấu phải xếp trên");
    }

    @Test void amTietLienNhauXepTrenAmTietRaiRac() throws Exception {
        doc("https://hust.edu.vn/n-14.html", "Ngành Kỹ thuật máy tính",
            "giới thiệu chương trình đào tạo ngành kỹ thuật máy tính của trường");
        doc("https://hust.edu.vn/o-15.html", "Tin tổng hợp",
            "hội thao kỹ năng mềm cho sinh viên, phần thuật lại buổi lễ ở cuối bài");
        idx.commit();

        Index.Result r = idx.search("kỹ thuật", 0, 10, null);
        assertEquals(2, r.total(), "cả hai bài đều chứa đủ hai âm tiết");
        assertEquals("https://hust.edu.vn/n-14.html", r.hits().get(0).url(),
                "hai âm tiết đứng liền nhau phải thắng hai âm tiết rải rác");
    }

    @Test void baiVietXepTrenTrangMucLuc() throws Exception {
        doc("https://hust.edu.vn/vi/news/hoc-bong-tran-dai-nghia-123.html",
            "Học bổng Trần Đại Nghĩa",
            "Nhà trường trao học bổng Trần Đại Nghĩa cho sinh viên vượt khó. ".repeat(30));
        doc("https://hust.edu.vn/vi/news/tin-tuc/page-9/", "Tin tức - Trang 9",
            "Học bổng Trần Đại Nghĩa. Học bổng Odon Vallet. Học bổng Vingroup.");
        idx.commit();

        Index.Result r = idx.search("học bổng", 0, 10, null);
        assertEquals(2, r.total());
        assertTrue(r.hits().get(0).url().endsWith("-123.html"),
                "bài viết phải vượt trang mục lục, thực tế đứng đầu: " + r.hits().get(0).url());
    }

    @Test void thuaMotTuVanConVet() throws Exception {
        doc("https://hust.edu.vn/p-16.html", "Điểm chuẩn năm 2026",
            "công bố điểm chuẩn các ngành");
        idx.commit();

        // "nghìn tỉ" không có trong bài, AND thuần sẽ trả rỗng
        Index.Result r = idx.search("điểm chuẩn nghìn tỉ", 0, 10, null);
        assertEquals(1, r.total(), "thừa một từ thì vét lại theo quá nửa số âm tiết, đừng trả rỗng");
    }

    @Test void demTheoHostKhongDemBanDaXoa() throws Exception {
        doc("https://hust.edu.vn/q-17.html", "Bản đầu", "nội dung ban đầu");
        idx.commit();
        doc("https://hust.edu.vn/q-17.html", "Bản sửa", "nội dung đã sửa");
        idx.commit();

        assertEquals(1, idx.byHost().get("hust.edu.vn"),
                "bản cũ đã bị ghi đè thì không được đếm nữa");
        assertEquals(idx.numDocs(), idx.byHost().values().stream().mapToInt(Integer::intValue).sum(),
                "tổng theo host phải khớp numDocs");
    }

    @Test void tungTuDoiLienNhauDuocCong() throws Exception {
        doc("https://hust.edu.vn/r-18.html", "Ngành Kỹ thuật máy tính",
            "chương trình đào tạo kỹ thuật máy tính");
        doc("https://hust.edu.vn/s-19.html", "Vật lý kỹ thuật và máy đo",
            "ngành vật lý kỹ thuật, thiết bị máy đo và tính toán mô phỏng");
        idx.commit();

        Index.Result r = idx.search("kỹ thuật máy tính", 0, 10, null);
        assertEquals("https://hust.edu.vn/r-18.html", r.hits().get(0).url(),
                "bài có cả hai cặp 'kỹ thuật' và 'máy tính' phải xếp trên bài chỉ có một cặp");
    }

    @Test void goKhongDauVanToSangDuHaiChu() throws Exception {
        doc("https://hust.edu.vn/t-20.html", "Điểm chuẩn Đại học Bách khoa Hà Nội năm 2026",
            "công bố điểm chuẩn năm 2026");
        idx.commit();

        Index.Result r = idx.search("diem chuan 2026", 0, 10, null);
        String frag = String.join(" ", r.hits().get(0).fragments());
        assertTrue(frag.contains("<mark>Điểm</mark>") || frag.contains("<mark>điểm</mark>"),
                "chữ gõ không dấu cũng phải được tô, không chỉ mỗi con số: " + frag);
    }

    @Test void thuongCumKhongDuocNoiRongKetQua() throws Exception {
        doc("https://hust.edu.vn/u-21.html", "Ngành Kỹ thuật máy tính", "đào tạo kỹ thuật máy tính");
        doc("https://hust.edu.vn/v-22.html", "Phòng máy tính", "lịch mở phòng máy tính cho sinh viên");
        idx.commit();

        Index.Result r = idx.search("kỹ thuật máy tính", 0, 10, null);
        assertEquals(1, r.total(),
                "bài chỉ có cụm 'máy tính' mà thiếu 'kỹ thuật' không được lọt vào");
        assertEquals("https://hust.edu.vn/u-21.html", r.hits().get(0).url());
    }

    // ---------------------------------------- gộp trùng, lọc ngày, chia trang

    private void docNgay(String url, String title, String text, String ngay) throws Exception {
        idx.put(Map.of("url", url, "title", title, "text", text,
                "host", "hust.edu.vn", "section", "Tin tức", "date", ngay));
    }

    /**
     * Thân bài dài cỡ bài thật, và phần riêng phải chiếm phần lớn thân bài.
     *
     * Hai điều kiện đều cần: vân tay chỉ đáng tin từ khoảng 100 cụm 3 từ trở
     * lên, mà nếu nhồi cùng một đoạn mẫu dài vào mọi bài thì chúng thành hàng
     * xóm của nhau thật — bản nháp trước của test này bị gộp mất một nửa số
     * bài, và Sig gộp đúng chứ không sai.
     */
    private static String than(String rieng) {
        return (rieng + ". ").repeat(14);
    }

    @Test void haiUrlCungMotBaiThiGopLai() throws Exception {
        String noiDung = than("Trao học bổng Trần Đại Nghĩa cho sinh viên vượt khó.");
        doc("https://hust.edu.vn/vi/news/tin-tuc/hoc-bong-1.html", "Học bổng Trần Đại Nghĩa", noiDung);
        doc("https://hust.edu.vn/vi/news/savefile/tin-tuc/hoc-bong-1.html",
            "Học bổng Trần Đại Nghĩa", noiDung + " Lượt xem: 214.");
        idx.commit();

        Index.Result r = idx.search("học bổng", 0, 10, null);
        assertEquals(1, r.hits().size(), "hai bản cùng bài chỉ được chiếm một dòng");
        assertEquals(1, r.hits().get(0).duplicates().size(),
                "bản kia phải nằm trong danh sách trùng, không biến mất");
        assertTrue(r.hits().get(0).duplicates().get(0).contains("/savefile/"));
    }

    @Test void baiKhacNhauThiKhongBiGopNham() throws Exception {
        doc("https://hust.edu.vn/w-23.html", "Học bổng Trần Đại Nghĩa",
            than("Trao học bổng Trần Đại Nghĩa cho sinh viên vượt khó ngành cơ khí."));
        doc("https://hust.edu.vn/x-24.html", "Học bổng Odon Vallet",
            than("Quỹ Odon Vallet trao thưởng cho học sinh giỏi quốc gia môn toán và lý."));
        idx.commit();

        Index.Result r = idx.search("học bổng", 0, 10, null);
        assertEquals(2, r.hits().size(), "hai bài khác nội dung phải giữ hai dòng riêng");
    }

    @Test void locTheoKhoangNgay() throws Exception {
        docNgay("https://hust.edu.vn/y-25.html", "Tuyển sinh 2024", than("kỳ tuyển sinh"), "2024-06-01");
        docNgay("https://hust.edu.vn/z-26.html", "Tuyển sinh 2026", than("kỳ tuyển sinh"), "2026-06-01");
        idx.commit();

        Index.Result r = idx.search(new Index.Truy("tuyển sinh", 0, 10, null,
                "2026-01-01", null, false));
        assertEquals(1, r.hits().size(), "chỉ bài trong khoảng mới được ra");
        assertEquals("https://hust.edu.vn/z-26.html", r.hits().get(0).url());
    }

    @Test void baiKhongRoNgayBiLoaiKhiLocNgay() throws Exception {
        // put() thẳng, không qua doc(): doc() luôn gắn sẵn một ngày
        idx.put(Map.of("url", "https://hust.edu.vn/aa-27.html", "title", "Tuyển sinh không ngày",
                "text", than("kỳ tuyển sinh"), "host", "hust.edu.vn"));
        idx.put(Map.of("url", "https://hust.edu.vn/bb-28.html", "title", "Tuyển sinh 2026",
                "text", than("kỳ tuyển sinh"), "host", "hust.edu.vn", "date", "2026-06-01"));
        idx.commit();

        Index.Result r = idx.search(new Index.Truy("tuyển sinh", 0, 10, null,
                "2020-01-01", "2030-12-31", false));
        assertEquals(1, r.hits().size(), "không rõ ngày thì đứng ngoài bộ lọc ngày");
        assertEquals("https://hust.edu.vn/bb-28.html", r.hits().get(0).url());
    }

    @Test void sapTheoNgayMoiTruoc() throws Exception {
        docNgay("https://hust.edu.vn/cc-29.html", "Tuyển sinh bản cũ",
                than("tuyển sinh ngành kỹ thuật cơ khí động lực và ô tô"), "2019-03-05");
        docNgay("https://hust.edu.vn/dd-30.html", "Tuyển sinh bản mới",
                than("tuyển sinh ngành công nghệ sinh học thực phẩm và môi trường"), "2026-03-05");
        idx.commit();

        Index.Result r = idx.search(new Index.Truy("tuyển sinh", 0, 10, null, null, null, true));
        assertEquals("2026-03-05", r.hits().get(0).date(), "sắp theo ngày thì bài mới đứng đầu");
        assertEquals("2019-03-05", r.hits().get(1).date());
    }

    @Test void chiaTrangKhongTraTrungDong() throws Exception {
        String[] nganh = {"cơ khí động lực", "điện tử viễn thông", "hoá dược phẩm",
                "dệt may thời trang", "toán tin ứng dụng", "vật lý kỹ thuật hạt nhân",
                "công nghệ sinh học", "kỹ thuật môi trường nước", "quản trị kinh doanh",
                "tiếng Anh khoa học", "cơ điện tử thông minh", "nhiệt lạnh công nghiệp"};
        for (int i = 0; i < nganh.length; i++) {
            doc("https://hust.edu.vn/tin-" + i + ".html", "Tuyển sinh ngành " + nganh[i],
                than("chương trình tuyển sinh ngành " + nganh[i] + " tại Bách khoa Hà Nội"));
        }
        idx.commit();

        List<String> t1 = idx.search("tuyển sinh", 0, 5, null).hits().stream().map(Index.Hit::url).toList();
        List<String> t2 = idx.search("tuyển sinh", 5, 5, null).hits().stream().map(Index.Hit::url).toList();
        assertEquals(5, t1.size());
        assertEquals(5, t2.size());
        assertTrue(java.util.Collections.disjoint(t1, t2),
                "trang 2 không được lặp lại dòng của trang 1");
    }

    @Test void ngaySoDoiDungVaBoNgayRac() {
        assertEquals(20260809L, Index.ngaySo("2026-08-09"));
        assertEquals(0L, Index.ngaySo(""));
        assertEquals(0L, Index.ngaySo("2026-13-40"));
        assertEquals(0L, Index.ngaySo("hôm qua"));
    }
}
