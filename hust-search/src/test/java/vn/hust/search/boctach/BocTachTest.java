package vn.hust.search.boctach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import vn.hust.search.boctach.LienKet.Canh;

/** Port của tests/test_boc_tach.py (25 test). */
class BocTachTest {
    static final Path FX = Path.of("src/test/resources/html");
    static final String X = "https://x.hust.edu.vn/a.html";

    record Fx(String html, JsonNode gold) {}

    static Fx fixture(String host, int i) throws Exception {
        Path f = FX.resolve(host).resolve(String.format("%02d.html", i));
        return new Fx(Files.readString(f), new ObjectMapper().readTree(
                Files.readString(f.resolveSibling(String.format("%02d.gold.json", i)))));
    }

    static Khoi khoi(String html, String host, Set<String> kh, Map<String, Object> vet) {
        return Khoi.timKhoi(BocTach.parse(html), host, kh, vet);
    }

    static String text(Khoi k) {
        return k.node() == null ? "" : HtmlSach.WS.matcher(HtmlSach.textBs4(k.node())).replaceAll(" ");
    }

    // ------------------------------------------------------------------ Khoi
    @Test
    void chonBodytextKhiTatSelectorTrenFixtureNukeviet() throws Exception {
        var k = khoi(fixture("nukeviet.test", 0).html(), "", null, null);
        assertEquals("heuristic", k.method());
        assertTrue(k.path().contains("bodytext"), k.path());
    }

    @Test
    void selectorHustDungBodytext() throws Exception {
        assertEquals("selector", khoi(fixture("nukeviet.test", 1).html(), "hust.edu.vn", null, null).method());
    }

    @Test
    void khongChonNhamMenuTrenBoCucKhongTen() throws Exception {
        for (int i = 0; i < 5; i++) {
            Fx f = fixture("khongten.test", i);
            String t = text(khoi(f.html(), "", null, null));
            assertTrue(t.contains(f.gold().get("content").get(0).asText()));
            assertFalse(t.contains("Cựu sinh viên"));                 // mục menu
            assertFalse(t.contains("Sơ đồ trang"));
        }
    }

    @Test
    void bangLongNhauChonONoiDung() throws Exception {
        Fx f = fixture("bang.test", 2);
        String t = text(khoi(f.html(), "", null, null));
        var c = f.gold().get("content");
        assertTrue(t.contains(c.get(c.size() - 1).asText()));
        assertFalse(t.contains("Sơ đồ trang"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void vetKhongDoiKetQuaVaGhiDuBac() throws Exception {
        String html = fixture("nukeviet.test", 0).html();
        var k1 = khoi(html, "", null, null);
        Map<String, Object> vet = new java.util.LinkedHashMap<>();
        var k2 = khoi(html, "", null, vet);
        assertEquals(k1.path(), k2.path());
        var bac = (List<Map<String, Object>>) vet.get("bac");
        // mỗi bậc đi xuống đúng một con thắng; nút cuối cùng là khối được chọn
        for (var b : bac)
            assertEquals(1, ((List<Map<String, Object>>) b.get("ung_vien")).stream().filter(u -> (boolean) u.get("thang")).count());
        var diXuong = bac.stream().filter(b -> !(boolean) b.get("dung")).toList();
        var cuoi = (List<Map<String, Object>>) diXuong.get(diXuong.size() - 1).get("ung_vien");
        String thang = (String) cuoi.stream().filter(u -> (boolean) u.get("thang")).findFirst().get().get("nhan");
        assertTrue(k2.path().endsWith(thang));
    }

    @Test
    @SuppressWarnings("unchecked")
    void giaiThichPheuGiamDanVaChiaCanh() throws Exception {
        String html = fixture("wordpress.test", 0).html();
        var d = BocTach.giaiThich(html, "https://abc.hust.edu.vn/vi/x.html", null);
        var pheu = (List<Map<String, Object>>) d.get("pheu");
        List<Integer> chu = pheu.stream().map(b -> (Integer) b.get("chu")).toList();
        assertEquals(chu.stream().sorted(java.util.Comparator.reverseOrder()).toList(), chu);
        assertTrue(chu.get(1) < chu.get(0));                          // dọn cây bỏ được nav/footer
        assertEquals(khoi(html, "", null, null).path(), ((Map<String, Object>) d.get("block")).get("path"));
        assertTrue((int) ((Map<String, Object>) d.get("canh")).get("khuon") > 0);
    }

    @Test
    void trangRongHoacChiMenuThiFallbackBody() {
        assertEquals("fallback", khoi("<html><body><div></div></body></html>", "", null, null).method());
    }

    @Test
    void boTheAnVaComment() {
        var k = khoi("<body><main><p>Thật. Đúng.</p><div style=\"display: none\">ẩn</div>"
                + "<!-- ghi chú --><script>var x=1</script></main></body>", "", null, null);
        assertEquals("Thật. Đúng.", text(k));
    }

    // ------------------------------------------------------------------ Khuon
    static List<Set<String>> trang(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> Khuon.vanTayTrang(page(i))).toList();
    }

    static org.jsoup.nodes.Document page(int i) {
        return BocTach.parse("<body><ul><li>Liên hệ 0123 ABC</li></ul><p>Bài viết về "
                + String.valueOf((char) (97 + i)).repeat(5) + " riêng biệt</p></body>");
    }

    @Test
    void hostItHon20TrangKhongKetLuan() {
        assertTrue(Khuon.tapKhuon(Khuon.demHost(trang(10))).isEmpty());
    }

    @Test
    void khoiLapTrenMoiTrangLaKhuonConBaiThiKhong() {
        var kh = Khuon.tapKhuon(Khuon.demHost(trang(25)));
        assertTrue(kh.contains(Khuon.vanTay("Liên hệ 0123 ABC")));
        var s = page(0);
        Khuon.boKhuon(s, kh);
        String t = HtmlSach.textBs4(s.body());
        assertFalse(t.contains("Liên hệ"));
        assertTrue(t.contains("riêng biệt"));
    }

    @Test
    void vanTayBoQuaConSo() {
        assertEquals(Khuon.vanTay("Hôm nay 12 tin"), Khuon.vanTay("Hôm nay 99 tin"));
    }

    // ------------------------------------------------------------------ Truong
    static BocTach.Ket bt(String html) {
        return BocTach.bocTach(html, X, null);
    }

    @Test
    void ngayDdMmYyyySangIso() {
        assertEquals("2026-09-05", Truong.chuanNgay("Đăng ngày 05/09/2026 10:30"));
        assertEquals("2026-08-22", Truong.chuanNgay("2026-08-22T10:00:00+07:00"));
        assertEquals("", Truong.chuanNgay("31/02/2026"));             // ngày không tồn tại
    }

    @Test
    void ngayUuTienMetaTruocRegex() {
        var d = bt("<html><head><meta property=\"article:published_time\" content=\"2026-03-04T00:00:00Z\">"
                + "</head><body><main><p>Đăng 01/01/2020. Nội dung dài đủ chữ ở đây.</p></main></body></html>");
        assertEquals("2026-03-04", d.date());
        assertEquals("article:published_time", d.dateSrc());
    }

    @Test
    void dongTacGiaBiCatKhoiContent() {
        var d = bt("<html><body><main><p>Đoạn một của bài viết, khá dài.</p>"
                + "<p><strong>Tác giả: Trần Thị B</strong></p></main></body></html>");
        assertEquals("Trần Thị B", d.author());
        assertEquals("text-line", d.authorSrc());
        assertFalse(d.text().contains("Tác giả"));
        assertFalse(d.html().contains("<strong>"));
    }

    @Test
    void tacGiaMetaBoQuaAdmin() {
        var d = bt("<html><head><meta name=\"author\" content=\"admin\"></head>"
                + "<body><main><p>Nội dung.</p><p>Bài, ảnh: Lê Văn C</p></main></body></html>");
        assertEquals("Lê Văn C", d.author());
    }

    @Test
    void nguonTrichDanLaTruongPhu() {
        var d = bt("<html><body><main><p>Nội dung bài.</p><p>Nguồn: Báo Giáo dục</p></main></body></html>");
        assertEquals("Báo Giáo dục", d.citedSource());
        assertFalse(d.text().contains("Nguồn"));
    }

    @Test
    void theoTrongCauVanKhongBiNhanLaNguon() {
        var d = bt("<html><body><main><p>Theo kế hoạch năm nay, nhà trường tổ chức nhiều hoạt động "
                + "cho sinh viên trong suốt học kỳ và sau đó nữa để đảm bảo chất lượng.</p></main></body></html>");
        assertEquals("", d.citedSource());
    }

    @Test
    void tieuDeJsonLdVaCatHauTo() {
        var d = bt("<html><head><title>Thông báo tuyển sinh 2026 - ĐH Bách khoa</title>"
                + "<script type=\"application/ld+json\">{\"@type\":\"NewsArticle\",\"headline\":\"Tiêu đề JSON\","
                + "\"author\":{\"name\":\"Phạm D\"},\"datePublished\":\"2026-05-06\"}</script></head>"
                + "<body><main><p>Nội dung.</p></main></body></html>");
        assertEquals("Tiêu đề JSON", d.title());
        assertEquals("json-ld", d.titleSrc());
        assertEquals("Phạm D", d.author());
        assertEquals("json-ld", d.authorSrc());
        assertEquals("2026-05-06", d.date());
        assertEquals("Thông báo tuyển sinh 2026", Truong.catHauTo("Thông báo tuyển sinh 2026 - ĐH Bách khoa"));
        assertEquals("A - B", Truong.catHauTo("A - B"));              // phần đầu quá ngắn: giữ nguyên
    }

    // ------------------------------------------------------------------ LienKet
    static final String LK = """
            <html><body>
              <nav><a href="/menu">Menu chính</a></nav>
              <main><p>Bài viết có tệp đính kèm và ảnh minh hoạ cho sinh viên năm nhất.</p>
                <a href="http://hust.edu.vn/uploads/diem-chuan.pdf#x">Xem chi tiết tại đây</a>
                <a href="https://hust.edu.vn/uploads/diem-chuan.pdf">Xem chi tiết tại đây</a>
                <a href="https://drive.google.com/file/d/1">Bản trên Drive</a>
                <figure><img src="/uploads/anh1.jpg" alt="Lễ khai giảng"></figure>
                <img src="/themes/hust/icon.png" alt="icon">
                <a href="/big.jpg"><img src="/big-thumb.jpg" alt="Ảnh lớn"></a>
              </main>
              <footer><a href="/lien-he">Liên hệ</a></footer></body></html>""";

    BocTach.Ket lk = BocTach.bocTach(LK, "https://hust.edu.vn/vi/news/bai-1-1.html", null);

    Canh nd(String dst, String type) {
        return lk.links().stream().filter(e -> e.dst.equals(dst) && e.type.equals(type)).findFirst().orElse(null);
    }

    Set<String> kh() {
        return lk.navLinks().stream().map(e -> e.dst).collect(Collectors.toSet());
    }

    @Test
    void httpVaHttpsKhongTachDoiQuaNorm() {
        var e = nd("https://hust.edu.vn/uploads/diem-chuan.pdf", "href");
        assertNotNull(e);
        assertEquals(2, e.count);
        assertEquals("document", e.dstKind);
        assertEquals("Xem chi tiết tại đây", e.text);
    }

    @Test
    void phanLoaiDich() {
        assertEquals("external", LienKet.loaiDich("https://drive.google.com/file/d/1"));
        assertEquals("image", LienKet.loaiDich("https://hust.edu.vn/a.jpg"));
        assertEquals("document", LienKet.loaiDich("https://svbk.hust.edu.vn/f?download=1"));
        assertEquals("page", LienKet.loaiDich("https://hust.edu.vn/vi/"));
        assertEquals("external", LienKet.loaiDich("https://nothust.edu.vn/a.pdf"));
    }

    @Test
    void menuVaFooterLaCanhKhuonKhongPhaiNoiDung() {
        assertTrue(kh().contains("https://hust.edu.vn/menu"));
        assertTrue(kh().contains("https://hust.edu.vn/lien-he"));
        assertEquals(null, nd("https://hust.edu.vn/menu", "href"));
    }

    @Test
    void anhTrongBaiLaEmbedConIconThemeLaKhuon() {
        assertEquals("Lễ khai giảng", nd("https://hust.edu.vn/uploads/anh1.jpg", "embed").text);
        assertTrue(kh().contains("https://hust.edu.vn/themes/hust/icon.png"));
    }

    @Test
    void ngoaiHoHustGhiCanhKhongTai() {
        assertEquals("external", nd("https://drive.google.com/file/d/1", "href").dstKind);
    }

    @Test
    void chuARongLayAltAnhCon() {
        assertEquals("Ảnh lớn", nd("https://hust.edu.vn/big.jpg", "href").text);
    }

    @Test
    void canhRaCongKhaiChiGomHref() {
        List<String> urls = lk.outgoingLinks().stream().map(x -> x.url).toList();
        assertTrue(urls.contains("https://hust.edu.vn/uploads/diem-chuan.pdf"));
        assertFalse(urls.contains("https://hust.edu.vn/uploads/anh1.jpg"));
        assertEquals(urls.size(), new HashSet<>(urls).size());
    }
}
