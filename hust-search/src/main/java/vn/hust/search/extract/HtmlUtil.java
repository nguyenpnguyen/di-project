package vn.hust.search.extract;

import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import vn.hust.search.store.Url;

/**
 * Tiện ích HTML dùng chung — bản port của {@code boc_tach/html_sach.py}, cộng các hàm làm jsoup
 * cư xử giống BeautifulSoup + lxml (KE-HOACH-PORT-JAVA.md mục 4). Trong bóc tách KHÔNG dùng
 * {@code Element.text()}: nó chuẩn hoá khoảng trắng và không chèn dấu cách giữa các mảnh chữ.
 */
public final class HtmlUtil {
    private HtmlUtil() {}

    /** {@code \s} của Python 3 (Unicode): thêm \x1c-\x1f mà Java không coi là khoảng trắng. */
    public static final Pattern WS = Pattern.compile("[\\s\\x1c-\\x1f]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Thẻ chứa chữ mà bs4 tách khỏi get_text() (string_containers của HTMLTreeBuilder). */
    private static final Set<String> SKIPPED_TEXT_TAGS = Set.of("script", "style", "template", "rt", "rp");

    public static String strip(String s) {
        return Url.pyStrip(s);
    }

    /** {@code re.sub(r"\s+", " ", s).strip()}. */
    public static String cleanSpace(String s) {
        return s == null ? "" : strip(WS.matcher(s).replaceAll(" "));
    }

    /** Số code point (Python {@code len}). */
    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** {@code s[:n]} theo code point, không chẻ cặp surrogate. */
    public static String head(String s, int n) {
        return s.length() <= n || len(s) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** {@code s[-n:]}. */
    public static String tail(String s, int n) {
        int l = len(s);
        return l <= n ? s : s.substring(s.offsetByCodePoints(0, l - n));
    }

    /**
     * {@code get_text(sep, strip=...)} của bs4: nối TỪNG mảnh chữ bằng {@code sep}
     * ({@code <b>a</b>b} -> "a b"). Bỏ chữ nằm trong script/style/template/rt/rp (bẫy 1, 4).
     * Comment không phải TextNode nên tự bị bỏ.
     */
    public static String text(Element el, String sep, boolean strip) {
        StringBuilder sb = new StringBuilder();
        boolean[] first = {true};
        el.traverse((node, depth) -> {
            if (node instanceof TextNode t && !insideSkippedTag(t, el)) {
                String s = t.getWholeText();
                if (strip) {
                    s = strip(s);
                    if (s.isEmpty()) return;
                }
                if (!first[0]) sb.append(sep);
                first[0] = false;
                sb.append(s);
            }
        });
        return sb.toString();
    }

    public static String textBs4(Element el) {
        return text(el, " ", true);
    }

    private static boolean insideSkippedTag(Node n, Element root) {
        for (Node p = n.parentNode(); p != null; p = p.parentNode()) {
            if (p instanceof Element e && SKIPPED_TEXT_TAGS.contains(e.normalName()) && e != root) return true;
            if (p == root) return false;
        }
        return false;
    }

    /** Còn gắn vào cây của tài liệu? (bs4: {@code not t.decomposed}). */
    public static boolean isAttached(Node n) {
        return n.ownerDocument() != null;
    }

    /** Mọi phần tử con cháu theo thứ tự tài liệu, không gồm chính nó (bs4 {@code find_all(True)}). */
    public static java.util.List<Element> descendants(Element el) {
        var all = el.getAllElements();
        return all.subList(1, all.size());
    }

    public static String joinHttp(String baseUrl, String href) {
        href = href == null ? "" : strip(href);
        if (href.isEmpty()) return "";
        String n = Url.norm(href, baseUrl);
        return n == null ? "" : n;
    }

    // ------------------------------------------------------------------ dọn HTML xem trước
    private static final Set<String> KEEP_TAGS = Set.of("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "blockquote", "figure", "figcaption", "table", "thead", "tbody", "tr", "th", "td", "strong", "b", "em",
            "i", "u", "sub", "sup", "img", "a", "div", "span", "section", "article");
    private static final Set<String> DROP_TAGS = Set.of("script", "style", "form", "iframe", "noscript", "svg",
            "button", "input", "select", "nav", "footer", "header");

    /**
     * HTML rút gọn để xem trước: bỏ script/form/nav..., mọi thuộc tính trừ href/src/alt, thẻ ngoài
     * danh sách giữ thì gỡ vỏ giữ ruột. Dựng thẳng chuỗi từ cây (bản Python parse lại {@code str(body)}
     * rồi unwrap; không cần parse lần hai), theo định dạng của bs4: {@code <br/>}, thoát & < >.
     * Cắt ở 40.000 ký tự.
     */
    public static String cleanHtml(Element node, String baseUrl) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        write(node, baseUrl, sb, false);
        return head(sb.toString(), 40_000);
    }

    private static final Set<String> PRESERVE_WS_TAGS = Set.of("pre", "textarea");

    /** Con của {@code e}. Các TextNode liền nhau gộp thành một chuỗi (bs4 parse lại str() nên thấy chúng là một). */
    private static void writeChildren(Element e, String baseUrl, StringBuilder sb, boolean preserveWs) {
        preserveWs |= PRESERVE_WS_TAGS.contains(e.normalName());
        StringBuilder pending = new StringBuilder();
        for (Node c : e.childNodes()) {
            if (c instanceof TextNode t) {
                pending.append(t.getWholeText());
                continue;
            }
            flushText(pending, sb, preserveWs);
            if (c instanceof Element ce) write(ce, baseUrl, sb, preserveWs);
        }
        flushText(pending, sb, preserveWs);
    }

    /** bs4: chuỗi chỉ gồm khoảng trắng ASCII thì thành một "\n" (nếu có xuống dòng) hoặc một dấu cách. */
    private static void flushText(StringBuilder pending, StringBuilder sb, boolean preserveWs) {
        if (pending.length() == 0) return;
        String s = pending.toString();
        pending.setLength(0);
        if (!preserveWs && s.chars().allMatch(c -> c == ' ' || c == '\n' || c == '\t' || c == '\f' || c == '\r'))
            s = s.indexOf('\n') >= 0 ? "\n" : " ";
        sb.append(escape(s));
    }

    private static void write(Element e, String baseUrl, StringBuilder sb, boolean preserveWs) {
        String name = e.normalName();
        if (DROP_TAGS.contains(name)) return;
        if (!KEEP_TAGS.contains(name)) {
            writeChildren(e, baseUrl, sb, preserveWs);                              // gỡ vỏ
            return;
        }
        StringBuilder tag = new StringBuilder("<").append(name);
        // bs4 xuất thuộc tính theo thứ tự chữ cái
        switch (name) {
            case "img" -> {
                String src = joinHttp(baseUrl, e.attr("src"));
                if (src.isEmpty()) return;
                if (e.hasAttr("alt")) tag.append(' ').append(attribute("alt", e.attr("alt")));
                tag.append(' ').append(attribute("loading", "lazy")).append(' ').append(attribute("src", src));
            }
            case "a" -> {
                String href = joinHttp(baseUrl, e.attr("href"));
                if (href.isEmpty()) {
                    writeChildren(e, baseUrl, sb, preserveWs);
                    return;
                }
                tag.append(' ').append(attribute("href", href)).append(' ').append(attribute("rel", "noopener"))
                        .append(' ').append(attribute("target", "_blank"));
            }
            default -> {}
        }
        if (name.equals("br") || name.equals("img")) {
            sb.append(tag).append("/>");
            return;
        }
        sb.append(tag).append('>');
        writeChildren(e, baseUrl, sb, preserveWs);
        sb.append("</").append(name).append('>');
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Thuộc tính theo bs4: giá trị có " thì bọc bằng ' (nếu không có ' nữa), không thì thoát thành &quot;. */
    private static String attribute(String k, String v) {
        v = escape(v);
        if (v.contains("\"")) {
            if (v.contains("'")) return k + "=\"" + v.replace("\"", "&quot;") + "\"";
            return k + "='" + v + "'";
        }
        return k + "=\"" + v + "\"";
    }
}
