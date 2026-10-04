package vn.hust.search.extract;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Thuật toán tìm khối nội dung chính của một trang HTML — bản port của {@code khoi.py}.
 *
 * <p>Ba lớp: (1) dọn cây, (2) khử khuôn theo host ({@link Template}, tuỳ chọn), (3) chấm điểm nút theo
 * mật độ chữ / mật độ link rồi đi từ gốc xuống. Hằng số ALPHA/BETA/GAMMA/DELTA giữ nguyên bản Python.
 * Họ ý tưởng: CETD (Sun, Song, Liao — SIGIR 2011), Boilerpipe (Kohlschütter và cs. — WSDM 2010).
 */
public record ContentBlock(Element node, String path, double score, String method) {
    /** Selector đã kiểm chứng theo host. */
    public static final Map<String, String> SELECTOR_BY_HOST = Map.of("hust.edu.vn", ".bodytext");

    private static final Set<String> DROP_TAGS = Set.of("script", "style", "noscript", "iframe", "form", "svg", "button",
            "input", "select", "nav", "footer", "aside", "template");
    private static final Set<String> CANDIDATE_TAGS = Set.of("div", "section", "article", "main", "td", "table", "tbody",
            "tr", "body", "form");
    static final double ALPHA = 2.0, BETA = 30.0, GAMMA = 1.0, DELTA = 0.65;
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern PENALTY = Pattern.compile("nav|menu|footer|header|sidebar|comment|share|related|"
            + "breadcrumb|banner|widget|social|advert|popup|modal|pagination|tag", F);
    private static final Pattern BONUS = Pattern.compile(
            "content|article|post|entry|detail|bodytext|main|news-body|noi-?dung", F);
    private static final Pattern PUNCTUATION = Pattern.compile("[.,;:?!…]");

    /** Lớp 1. Bỏ thẻ không mang nội dung, thẻ ẩn và comment. */
    public static void cleanTree(Document doc) {
        List<Comment> comments = new ArrayList<>();
        doc.traverse((n, d) -> {
            if (n instanceof Comment c) comments.add(c);
        });
        comments.forEach(Node::remove);
        for (Element t : doc.getAllElements()) if (DROP_TAGS.contains(t.normalName())) t.remove();
        for (Element t : doc.getAllElements()) {
            if (t == doc || !HtmlUtil.isAttached(t)) continue;
            String style = t.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
            if (t.hasAttr("hidden") || style.contains("display:none")) t.remove();
        }
    }

    /** Các lớp CSS theo thứ tự và kể cả trùng, như {@code node["class"]} của bs4. */
    static List<String> cssClasses(Element e) {
        List<String> out = new ArrayList<>();
        for (String s : HtmlUtil.WS.split(e.attr("class"))) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Đường dẫn CSS rút gọn tới nút, để ghi lại khối nào đã được chọn. */
    public static String cssPath(Element node) {
        List<String> parts = new ArrayList<>();
        for (Element n = node; n != null && !(n instanceof Document); n = n.parent()) {
            String p = n.normalName();
            List<String> cssClasses = cssClasses(n);
            if (!n.attr("id").isEmpty()) p += "#" + n.attr("id");
            else if (!cssClasses.isEmpty()) p += "." + String.join(".", cssClasses);
            parts.add(p);
        }
        java.util.Collections.reverse(parts);
        return String.join(" > ", parts);
    }

    /** {nút: [C, LC, P, Q]} tính từ lá lên gốc, một lượt. C chữ, LC chữ trong link, P đoạn văn thật, Q dấu câu. */
    static IdentityHashMap<Element, int[]> stats(Document doc) {
        var stats = new IdentityHashMap<Element, int[]>();
        List<Element> all = new ArrayList<>(doc.getAllElements());
        for (int i = all.size() - 1; i >= 1; i--) {           // bỏ Document ở vị trí 0
            Element t = all.get(i);
            int c = 0, lc = 0, p = 0, q = 0;
            boolean isLink = t.normalName().equals("a");
            for (Node ch : t.childNodes()) {
                if (ch instanceof Element ce) {
                    int[] s = stats.get(ce);
                    if (s != null) {
                        c += s[0];
                        lc += s[1];
                        p += s[2];
                        q += s[3];
                    }
                } else if (ch instanceof TextNode textNode) {
                    String txt = HtmlUtil.cleanSpace(textNode.getWholeText());
                    if (!txt.isEmpty()) {
                        int n = HtmlUtil.len(txt);
                        c += n;
                        if (isLink) lc += n;
                        var m = PUNCTUATION.matcher(txt);
                        while (m.find()) q++;
                    }
                }
            }
            if (t.normalName().equals("p") && c >= 25 && q >= 1) p++;
            stats.put(t, new int[]{c, lc, p, q});
        }
        return stats;
    }

    static double nodeScore(int[] s) {
        double dens = (double) s[1] / Math.max(s[0], 1);
        return (s[0] - s[1]) * Math.pow(1 - dens, ALPHA) + BETA * s[2] + GAMMA * s[3];
    }

    static double weight(Element t) {
        String cssClasses = String.join(" ", cssClasses(t)) + " " + t.attr("id");
        if (BONUS.matcher(cssClasses).find()) return 1.3;
        if (PENALTY.matcher(cssClasses).find()) return 0.3;
        return 1.0;
    }

    /** Số ký tự chữ hiển thị (bỏ chữ trong script/style/noscript/template), khoảng trắng gộp. */
    public static int charCount(Element node) {
        if (node == null) return 0;
        int[] n = {0};
        node.traverse((x, d) -> {
            if (x instanceof TextNode t && x.parentNode() instanceof Element p
                    && !Set.of("script", "style", "noscript", "template").contains(p.normalName())) {
                n[0] += HtmlUtil.len(HtmlUtil.cleanSpace(t.getWholeText()));
            }
        });
        return n[0];
    }

    /** Nhãn ngắn của một nút: tag#id hoặc tag.lop-dau. */
    static String label(Element t) {
        if (!t.attr("id").isEmpty()) return t.normalName() + "#" + t.attr("id");
        List<String> cssClasses = cssClasses(t);
        return cssClasses.isEmpty() ? t.normalName() : t.normalName() + "." + cssClasses.get(0);
    }

    /** Phần tử đầu tiên (theo thứ tự tài liệu) có lớp CSS đúng bằng {@code lop}, phân biệt hoa thường. */
    static Element byClass(Document doc, String cssClasses) {
        for (Element e : doc.getAllElements()) if (cssClasses(e).contains(cssClasses)) return e;
        return null;
    }

    /**
     * Chọn khối nội dung. Cây bị sửa tại chỗ (dọn cây, bỏ khuôn).
     *
     * @param vet tuỳ chọn: được điền số chữ sau từng lớp và các bậc đi xuống cây, để giao diện
     *            vẽ lại thuật toán đã chọn thế nào. Truyền null thì không tốn gì thêm.
     */
    public static ContentBlock findBlock(Document doc, String host, Set<String> templateFps, Map<String, Object> trace) {
        cleanTree(doc);
        Element body = doc.body();
        if (trace != null) trace.put("sau_don", charCount(body));
        int removedTemplateBlocks = Template.removeTemplate(doc, templateFps == null ? Set.of() : templateFps);
        if (trace != null) {
            trace.put("sau_khuon", charCount(body));
            trace.put("khoi_khuon_bo", removedTemplateBlocks);
            trace.put("bac", new ArrayList<Map<String, Object>>());
        }

        String selector = SELECTOR_BY_HOST.get(host);
        if (selector != null) {
            Element node = byClass(doc, selector.substring(1));      // chỉ hỗ trợ selector dạng ".lop"
            if (node != null && !HtmlUtil.text(node, "", true).isEmpty())
                return new ContentBlock(node, cssPath(node), 0.0, "selector");
        }

        var stats = stats(doc);
        Element node = body;
        if (stats.getOrDefault(node, new int[]{0})[0] == 0) return new ContentBlock(body, cssPath(body), 0.0, "fallback");
        while (true) {
            List<Element> children = new ArrayList<>();
            for (Element c : node.children()) if (CANDIDATE_TAGS.contains(c.normalName()) && stats.containsKey(c)) children.add(c);
            if (children.isEmpty()) break;
            Element winner = children.get(0);
            double best = nodeScore(stats.get(winner)) * weight(winner);
            for (Element c : children) {                       // max() của Python giữ phần tử đầu khi hoà
                double d = nodeScore(stats.get(c)) * weight(c);
                if (d > best) {
                    best = d;
                    winner = c;
                }
            }
            boolean stop = nodeScore(stats.get(winner)) * weight(winner) < DELTA * nodeScore(stats.get(node));
            if (trace != null) {
                @SuppressWarnings("unchecked")
                var step = (List<Map<String, Object>>) trace.get("bac");
                step.add(recordStep(node, children, winner, stats, stop));
            }
            if (stop) break;
            node = winner;
        }
        if (node == body && stats.get(node)[0] == 0) return new ContentBlock(body, "body", 0.0, "fallback");
        return new ContentBlock(node, cssPath(node), nodeScore(stats.get(node)), "heuristic");
    }

    static double round(double x, int digits) {
        double k = Math.pow(10, digits);
        return Math.round(x * k) / k;
    }

    /** Một bậc đi xuống: nút cha, các con ứng viên (điểm cao nhất trước) và con thắng. */
    private static Map<String, Object> recordStep(Element parent, List<Element> children, Element winner,
                                              IdentityHashMap<Element, int[]> stats, boolean stop) {
        final int maxCandidates = 6;
        List<Element> ranked = new ArrayList<>(children);
        ranked.sort((a, b) -> Double.compare(nodeScore(stats.get(b)) * weight(b), nodeScore(stats.get(a)) * weight(a)));   // ổn định, như sorted()
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Element c : ranked.subList(0, Math.min(maxCandidates, ranked.size()))) {
            int[] s = stats.get(c);
            var m = new LinkedHashMap<String, Object>();
            m.put("nhan", label(c));
            m.put("chu", s[0]);
            m.put("chu_link", s[1]);
            m.put("doan", s[2]);
            m.put("he_so", weight(c));
            m.put("diem", round(nodeScore(s) * weight(c), 1));
            m.put("thang", c == winner);
            candidates.add(m);
        }
        var b = new LinkedHashMap<String, Object>();
        b.put("cha", label(parent));
        b.put("diem_cha", round(nodeScore(stats.get(parent)), 1));
        b.put("nguong", round(DELTA * nodeScore(stats.get(parent)), 1));
        b.put("ung_vien", candidates);
        b.put("con_lai", Math.max(0, ranked.size() - maxCandidates));
        b.put("dung", stop);
        return b;
    }
}
