package vn.hust.search.extract;

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
import vn.hust.search.extract.Links.Edge;

/** Port của tests/test_boc_tach.py (25 test). */
class ExtractorTest {
    static final Path FIXTURES = Path.of("src/test/resources/html");
    static final String X = "https://x.hust.edu.vn/a.html";

    record Fixture(String html, JsonNode gold) {}

    static Fixture fixture(String host, int i) throws Exception {
        Path f = FIXTURES.resolve(host).resolve(String.format("%02d.html", i));
        return new Fixture(Files.readString(f), new ObjectMapper().readTree(
                Files.readString(f.resolveSibling(String.format("%02d.gold.json", i)))));
    }

    static ContentBlock block(String html, String host, Set<String> templateFps, Map<String, Object> trace) {
        return ContentBlock.findBlock(Extractor.parse(html), host, templateFps, trace);
    }

    static String text(ContentBlock k) {
        return k.node() == null ? "" : HtmlUtil.WS.matcher(HtmlUtil.textBs4(k.node())).replaceAll(" ");
    }

    // ------------------------------------------------------------------ Khoi
    @Test
    void picksBodytextWhenSelectorDisabledOnNukevietFixture() throws Exception {
        var k = block(fixture("nukeviet.test", 0).html(), "", null, null);
        assertEquals("heuristic", k.method());
        assertTrue(k.path().contains("bodytext"), k.path());
    }

    @Test
    void hustSelectorUsesBodytext() throws Exception {
        assertEquals("selector", block(fixture("nukeviet.test", 1).html(), "hust.edu.vn", null, null).method());
    }

    @Test
    void doesNotPickMenuOnUnnamedLayout() throws Exception {
        for (int i = 0; i < 5; i++) {
            Fixture f = fixture("khongten.test", i);
            String t = text(block(f.html(), "", null, null));
            assertTrue(t.contains(f.gold().get("content").get(0).asText()));
            assertFalse(t.contains("Cựu sinh viên"));                 // mục menu
            assertFalse(t.contains("Sơ đồ trang"));
        }
    }

    @Test
    void nestedTablesPickContentCell() throws Exception {
        Fixture f = fixture("bang.test", 2);
        String t = text(block(f.html(), "", null, null));
        var c = f.gold().get("content");
        assertTrue(t.contains(c.get(c.size() - 1).asText()));
        assertFalse(t.contains("Sơ đồ trang"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceDoesNotChangeResultAndRecordsAllSteps() throws Exception {
        String html = fixture("nukeviet.test", 0).html();
        var k1 = block(html, "", null, null);
        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        var k2 = block(html, "", null, trace);
        assertEquals(k1.path(), k2.path());
        var step = (List<Map<String, Object>>) trace.get("bac");
        // mỗi bậc đi xuống đúng một con thắng; nút cuối cùng là khối được chọn
        for (var b : step)
            assertEquals(1, ((List<Map<String, Object>>) b.get("ung_vien")).stream().filter(u -> (boolean) u.get("thang")).count());
        var descending = step.stream().filter(b -> !(boolean) b.get("dung")).toList();
        var lastStep = (List<Map<String, Object>>) descending.get(descending.size() - 1).get("ung_vien");
        String winner = (String) lastStep.stream().filter(u -> (boolean) u.get("thang")).findFirst().get().get("nhan");
        assertTrue(k2.path().endsWith(winner));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explainFunnelDecreasesAndSplitsEdges() throws Exception {
        String html = fixture("wordpress.test", 0).html();
        var d = Extractor.explain(html, "https://abc.hust.edu.vn/vi/x.html", null);
        var funnel = (List<Map<String, Object>>) d.get("pheu");
        List<Integer> chars = funnel.stream().map(b -> (Integer) b.get("chu")).toList();
        assertEquals(chars.stream().sorted(java.util.Comparator.reverseOrder()).toList(), chars);
        assertTrue(chars.get(1) < chars.get(0));                          // dọn cây bỏ được nav/footer
        assertEquals(block(html, "", null, null).path(), ((Map<String, Object>) d.get("block")).get("path"));
        assertTrue((int) ((Map<String, Object>) d.get("canh")).get("khuon") > 0);
    }

    @Test
    void emptyPageOrMenuOnlyFallsBackToBody() {
        assertEquals("fallback", block("<html><body><div></div></body></html>", "", null, null).method());
    }

    @Test
    void hiddenTagsAndCommentsRemoved() {
        var k = block("<body><main><p>Thật. Đúng.</p><div style=\"display: none\">ẩn</div>"
                + "<!-- ghi chú --><script>var x=1</script></main></body>", "", null, null);
        assertEquals("Thật. Đúng.", text(k));
    }

    // ------------------------------------------------------------------ Khuon
    static List<Set<String>> pageFps(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> Template.pageFingerprints(page(i))).toList();
    }

    static org.jsoup.nodes.Document page(int i) {
        return Extractor.parse("<body><ul><li>Liên hệ 0123 ABC</li></ul><p>Bài viết về "
                + String.valueOf((char) (97 + i)).repeat(5) + " riêng biệt</p></body>");
    }

    @Test
    void hostWithFewerThan20PagesNoConclusion() {
        assertTrue(Template.templateSet(Template.countByHost(pageFps(10))).isEmpty());
    }

    @Test
    void blockRepeatedOnEveryPageIsTemplateButArticleIsNot() {
        var templateFps = Template.templateSet(Template.countByHost(pageFps(25)));
        assertTrue(templateFps.contains(Template.fingerprint("Liên hệ 0123 ABC")));
        var s = page(0);
        Template.removeTemplate(s, templateFps);
        String t = HtmlUtil.textBs4(s.body());
        assertFalse(t.contains("Liên hệ"));
        assertTrue(t.contains("riêng biệt"));
    }

    @Test
    void fingerprintIgnoresNumbers() {
        assertEquals(Template.fingerprint("Hôm nay 12 tin"), Template.fingerprint("Hôm nay 99 tin"));
    }

    // ------------------------------------------------------------------ Truong
    static Extractor.Extraction extractHtml(String html) {
        return Extractor.extract(html, X, null);
    }

    @Test
    void dateDdMmYyyyToIso() {
        assertEquals("2026-09-05", Fields.normalizeDate("Đăng ngày 05/09/2026 10:30"));
        assertEquals("2026-08-22", Fields.normalizeDate("2026-08-22T10:00:00+07:00"));
        assertEquals("", Fields.normalizeDate("31/02/2026"));             // ngày không tồn tại
    }

    @Test
    void dateMetaPreferredOverRegex() {
        var d = extractHtml("<html><head><meta property=\"article:published_time\" content=\"2026-03-04T00:00:00Z\">"
                + "</head><body><main><p>Đăng 01/01/2020. Nội dung dài đủ chữ ở đây.</p></main></body></html>");
        assertEquals("2026-03-04", d.date());
        assertEquals("article:published_time", d.dateSrc());
    }

    @Test
    void authorLineCutFromContent() {
        var d = extractHtml("<html><body><main><p>Đoạn một của bài viết, khá dài.</p>"
                + "<p><strong>Tác giả: Trần Thị B</strong></p></main></body></html>");
        assertEquals("Trần Thị B", d.author());
        assertEquals("text-line", d.authorSrc());
        assertFalse(d.text().contains("Tác giả"));
        assertFalse(d.html().contains("<strong>"));
    }

    @Test
    void authorMetaIgnoresAdmin() {
        var d = extractHtml("<html><head><meta name=\"author\" content=\"admin\"></head>"
                + "<body><main><p>Nội dung.</p><p>Bài, ảnh: Lê Văn C</p></main></body></html>");
        assertEquals("Lê Văn C", d.author());
    }

    @Test
    void citedSourceIsSecondaryField() {
        var d = extractHtml("<html><body><main><p>Nội dung bài.</p><p>Nguồn: Báo Giáo dục</p></main></body></html>");
        assertEquals("Báo Giáo dục", d.citedSource());
        assertFalse(d.text().contains("Nguồn"));
    }

    @Test
    void accordingToInsideSentenceIsNotTakenAsSource() {
        var d = extractHtml("<html><body><main><p>Theo kế hoạch năm nay, nhà trường tổ chức nhiều hoạt động "
                + "cho sinh viên trong suốt học kỳ và sau đó nữa để đảm bảo chất lượng.</p></main></body></html>");
        assertEquals("", d.citedSource());
    }

    @Test
    void titleFromJsonLdAndSuffixCut() {
        var d = extractHtml("<html><head><title>Thông báo tuyển sinh 2026 - ĐH Bách khoa</title>"
                + "<script type=\"application/ld+json\">{\"@type\":\"NewsArticle\",\"headline\":\"Tiêu đề JSON\","
                + "\"author\":{\"name\":\"Phạm D\"},\"datePublished\":\"2026-05-06\"}</script></head>"
                + "<body><main><p>Nội dung.</p></main></body></html>");
        assertEquals("Tiêu đề JSON", d.title());
        assertEquals("json-ld", d.titleSrc());
        assertEquals("Phạm D", d.author());
        assertEquals("json-ld", d.authorSrc());
        assertEquals("2026-05-06", d.date());
        assertEquals("Thông báo tuyển sinh 2026", Fields.cutSuffix("Thông báo tuyển sinh 2026 - ĐH Bách khoa"));
        assertEquals("A - B", Fields.cutSuffix("A - B"));              // phần đầu quá ngắn: giữ nguyên
    }

    // ------------------------------------------------------------------ LienKet
    static final String LINKS_HTML = """
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

    Extractor.Extraction linkResult = Extractor.extract(LINKS_HTML, "https://hust.edu.vn/vi/news/bai-1-1.html", null);

    Edge contentEdge(String dst, String type) {
        return linkResult.links().stream().filter(e -> e.dst.equals(dst) && e.type.equals(type)).findFirst().orElse(null);
    }

    Set<String> templateFps() {
        return linkResult.navLinks().stream().map(e -> e.dst).collect(Collectors.toSet());
    }

    @Test
    void httpAndHttpsNotSplitByNorm() {
        var e = contentEdge("https://hust.edu.vn/uploads/diem-chuan.pdf", "href");
        assertNotNull(e);
        assertEquals(2, e.count);
        assertEquals("document", e.dstKind);
        assertEquals("Xem chi tiết tại đây", e.text);
    }

    @Test
    void classifyDestination() {
        assertEquals("external", Links.destKind("https://drive.google.com/file/d/1"));
        assertEquals("image", Links.destKind("https://hust.edu.vn/a.jpg"));
        assertEquals("document", Links.destKind("https://svbk.hust.edu.vn/f?download=1"));
        assertEquals("page", Links.destKind("https://hust.edu.vn/vi/"));
        assertEquals("external", Links.destKind("https://nothust.edu.vn/a.pdf"));
    }

    @Test
    void menuAndFooterAreTemplateEdgesNotContent() {
        assertTrue(templateFps().contains("https://hust.edu.vn/menu"));
        assertTrue(templateFps().contains("https://hust.edu.vn/lien-he"));
        assertEquals(null, contentEdge("https://hust.edu.vn/menu", "href"));
    }

    @Test
    void inArticleImageIsEmbedAndThemeIconIsTemplate() {
        assertEquals("Lễ khai giảng", contentEdge("https://hust.edu.vn/uploads/anh1.jpg", "embed").text);
        assertTrue(templateFps().contains("https://hust.edu.vn/themes/hust/icon.png"));
    }

    @Test
    void outsideHustFamilyRecordsEdgeButNoDownload() {
        assertEquals("external", contentEdge("https://drive.google.com/file/d/1", "href").dstKind);
    }

    @Test
    void emptyLinkTextUsesChildImageAlt() {
        assertEquals("Ảnh lớn", contentEdge("https://hust.edu.vn/big.jpg", "href").text);
    }

    @Test
    void publicLinksOnlyIncludeHref() {
        List<String> urls = linkResult.outgoingLinks().stream().map(x -> x.url).toList();
        assertTrue(urls.contains("https://hust.edu.vn/uploads/diem-chuan.pdf"));
        assertFalse(urls.contains("https://hust.edu.vn/uploads/anh1.jpg"));
        assertEquals(urls.size(), new HashSet<>(urls).size());
    }
}
