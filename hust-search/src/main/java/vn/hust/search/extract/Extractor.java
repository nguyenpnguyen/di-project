package vn.hust.search.extract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import vn.hust.search.extract.Links.Edge;
import vn.hust.search.extract.Links.PublicLink;
import vn.hust.search.store.Url;

/**
 * Bóc tách trang HTML: khối nội dung, các trường, đồ thị liên kết — bản port của
 * {@code boc_tach/__init__.py}. {@link #extract} là cửa vào duy nhất; các lớp con làm từng phần.
 */
public final class Extractor {
    private Extractor() {}

    /** Bản Python là "1"; đổi để {@code coverage} / {@code extractor_version} phân biệt được trang do bản nào bóc. */
    public static final String VERSION = "2";

    public record Block(String path, double score, String method) {}

    /** Bản ghi đã bóc. {@code links} là cạnh nội dung, {@code navLinks} là cạnh khuôn. */
    public record Extraction(String url, String host, String section, String title, String titleSrc, String text, String html,
                      String date, String dateSrc, String author, String authorSrc, String citedSource, Block block,
                      List<PublicLink> outgoingLinks, List<Edge> links, List<Edge> navLinks) {}

    public static Document parse(String html) {
        return Jsoup.parse(html);
    }

    /** HTML thô -> bản ghi đã bóc. null nếu không có cả tiêu đề lẫn chữ. */
    public static Extraction extract(String html, String url, Set<String> templateFps) {
        Document doc = parse(html);
        String host = Url.hostname(url);

        List<JsonNode> ld = Fields.jsonLd(doc);              // trước khi dọn cây: JSON-LD nằm trong <script>
        var raw = Links.collect(doc, url);                 // cạnh trước khi dọn: menu/footer bị xoá mất
        String section = Fields.section(doc);
        var authorMeta = Fields.authorMeta(doc, ld);

        ContentBlock k = ContentBlock.findBlock(doc, host, templateFps, null);
        var title = Fields.title(doc, ld, k.node());
        var date = Fields.publishedDate(doc, ld, k.node());
        var authorSource = Fields.authorAndSourceLines(k.node());
        var author = !authorMeta.v().isEmpty() ? authorMeta : authorSource.author();

        String text = k.node() != null ? HtmlUtil.cleanSpace(HtmlUtil.textBs4(k.node())) : "";
        if (title.v().isEmpty() && text.isEmpty()) return null;
        var split = Links.split(raw, k.node());
        List<Edge> contentEdges = split.get(0);
        return new Extraction(url, host, section, HtmlUtil.head(title.v(), 500), title.src(), HtmlUtil.head(text, 200_000),
                HtmlUtil.cleanHtml(k.node(), url), date.v(), date.src(), author.v(), author.src(), authorSource.source(),
                new Block(k.path(), ContentBlock.round(k.score(), 2), k.method()), Links.toPublicLinks(contentEdges),
                contentEdges, split.get(1));
    }

    /**
     * Chạy lại bước chọn khối + chia cạnh của {@link #extract} và trả về từng bước để giao diện vẽ:
     * phễu số chữ qua từng lớp, các bậc đi xuống cây, cạnh trong/ngoài khối.
     */
    public static Map<String, Object> explain(String html, String url, Set<String> templateFps) {
        Document doc = parse(html);
        String host = Url.hostname(url);
        var raw = Links.collect(doc, url);
        int wholePage = ContentBlock.charCount(doc.body());
        Map<String, Object> trace = new LinkedHashMap<>();
        ContentBlock k = ContentBlock.findBlock(doc, host, templateFps, trace);
        var split = Links.split(raw, k.node());
        String text = k.node() != null ? HtmlUtil.cleanSpace(HtmlUtil.textBs4(k.node())) : "";
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (Edge e : split.get(0)) byKind.merge(e.dstKind, 1, Integer::sum);

        var block = new LinkedHashMap<String, Object>();
        block.put("path", k.path());
        block.put("score", ContentBlock.round(k.score(), 2));
        block.put("method", k.method());
        block.put("selector", ContentBlock.SELECTOR_BY_HOST.getOrDefault(host, ""));
        var edgeSummary = new LinkedHashMap<String, Object>();
        edgeSummary.put("noi_dung", split.get(0).size());
        edgeSummary.put("khuon", split.get(1).size());
        edgeSummary.put("noi_dung_theo_loai", byKind);
        var out = new LinkedHashMap<String, Object>();
        out.put("url", url);
        out.put("host", host);
        out.put("block", block);
        out.put("pheu", List.of(
                Map.of("buoc", "Toàn trang", "chu", wholePage),
                Map.of("buoc", "Sau dọn cây", "chu", trace.getOrDefault("sau_don", 0)),
                Map.of("buoc", "Sau khử khuôn", "chu", trace.getOrDefault("sau_khuon", 0)),
                Map.of("buoc", "Khối được chọn", "chu", ContentBlock.charCount(k.node()))));
        out.put("khoi_khuon_bo", trace.getOrDefault("khoi_khuon_bo", 0));
        out.put("bac", trace.getOrDefault("bac", List.of()));
        out.put("trich", HtmlUtil.head(text, 600));
        out.put("canh", edgeSummary);
        return out;
    }
}
