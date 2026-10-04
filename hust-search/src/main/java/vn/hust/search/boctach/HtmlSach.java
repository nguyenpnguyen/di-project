package vn.hust.search.boctach;

import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import vn.hust.search.kho.Url;

/**
 * Tiện ích HTML dùng chung — bản port của {@code boc_tach/html_sach.py}, cộng các hàm làm jsoup
 * cư xử giống BeautifulSoup + lxml (KE-HOACH-PORT-JAVA.md mục 4). Trong bóc tách KHÔNG dùng
 * {@code Element.text()}: nó chuẩn hoá khoảng trắng và không chèn dấu cách giữa các mảnh chữ.
 */
public final class HtmlSach {
    private HtmlSach() {}

    /** {@code \s} của Python 3 (Unicode): thêm \x1c-\x1f mà Java không coi là khoảng trắng. */
    public static final Pattern WS = Pattern.compile("[\\s\\x1c-\\x1f]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Thẻ chứa chữ mà bs4 tách khỏi get_text() (string_containers của HTMLTreeBuilder). */
    private static final Set<String> KHONG_LAY_CHU = Set.of("script", "style", "template", "rt", "rp");

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
        boolean[] dau = {true};
        el.traverse((node, depth) -> {
            if (node instanceof TextNode t && !trongHopKhongChu(t, el)) {
                String s = t.getWholeText();
                if (strip) {
                    s = strip(s);
                    if (s.isEmpty()) return;
                }
                if (!dau[0]) sb.append(sep);
                dau[0] = false;
                sb.append(s);
            }
        });
        return sb.toString();
    }

    public static String textBs4(Element el) {
        return text(el, " ", true);
    }

    private static boolean trongHopKhongChu(Node n, Element goc) {
        for (Node p = n.parentNode(); p != null; p = p.parentNode()) {
            if (p instanceof Element e && KHONG_LAY_CHU.contains(e.normalName()) && e != goc) return true;
            if (p == goc) return false;
        }
        return false;
    }

    /** Còn gắn vào cây của tài liệu? (bs4: {@code not t.decomposed}). */
    public static boolean conGan(Node n) {
        return n.ownerDocument() != null;
    }

    /** Mọi phần tử con cháu theo thứ tự tài liệu, không gồm chính nó (bs4 {@code find_all(True)}). */
    public static java.util.List<Element> conChau(Element el) {
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
    private static final Set<String> THE_GIU = Set.of("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "blockquote", "figure", "figcaption", "table", "thead", "tbody", "tr", "th", "td", "strong", "b", "em",
            "i", "u", "sub", "sup", "img", "a", "div", "span", "section", "article");
    private static final Set<String> BO_HAN_PREVIEW = Set.of("script", "style", "form", "iframe", "noscript", "svg",
            "button", "input", "select", "nav", "footer", "header");

    /**
     * HTML rút gọn để xem trước: bỏ script/form/nav..., mọi thuộc tính trừ href/src/alt, thẻ ngoài
     * danh sách giữ thì gỡ vỏ giữ ruột. Dựng thẳng chuỗi từ cây (bản Python parse lại {@code str(body)}
     * rồi unwrap; không cần parse lần hai), theo định dạng của bs4: {@code <br/>}, thoát & < >.
     * Cắt ở 40.000 ký tự.
     */
    public static String donHtml(Element node, String goc) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        ghi(node, goc, sb, false);
        return head(sb.toString(), 40_000);
    }

    private static final Set<String> GIU_KHOANG_TRANG = Set.of("pre", "textarea");

    /** Con của {@code e}. Các TextNode liền nhau gộp thành một chuỗi (bs4 parse lại str() nên thấy chúng là một). */
    private static void ghiCon(Element e, String goc, StringBuilder sb, boolean giuTrang) {
        giuTrang |= GIU_KHOANG_TRANG.contains(e.normalName());
        StringBuilder chu = new StringBuilder();
        for (Node c : e.childNodes()) {
            if (c instanceof TextNode t) {
                chu.append(t.getWholeText());
                continue;
            }
            xaChu(chu, sb, giuTrang);
            if (c instanceof Element ce) ghi(ce, goc, sb, giuTrang);
        }
        xaChu(chu, sb, giuTrang);
    }

    /** bs4: chuỗi chỉ gồm khoảng trắng ASCII thì thành một "\n" (nếu có xuống dòng) hoặc một dấu cách. */
    private static void xaChu(StringBuilder chu, StringBuilder sb, boolean giuTrang) {
        if (chu.length() == 0) return;
        String s = chu.toString();
        chu.setLength(0);
        if (!giuTrang && s.chars().allMatch(c -> c == ' ' || c == '\n' || c == '\t' || c == '\f' || c == '\r'))
            s = s.indexOf('\n') >= 0 ? "\n" : " ";
        sb.append(thoat(s));
    }

    private static void ghi(Element e, String goc, StringBuilder sb, boolean giuTrang) {
        String ten = e.normalName();
        if (BO_HAN_PREVIEW.contains(ten)) return;
        if (!THE_GIU.contains(ten)) {
            ghiCon(e, goc, sb, giuTrang);                              // gỡ vỏ
            return;
        }
        StringBuilder tag = new StringBuilder("<").append(ten);
        // bs4 xuất thuộc tính theo thứ tự chữ cái
        switch (ten) {
            case "img" -> {
                String src = joinHttp(goc, e.attr("src"));
                if (src.isEmpty()) return;
                if (e.hasAttr("alt")) tag.append(' ').append(thuocTinh("alt", e.attr("alt")));
                tag.append(' ').append(thuocTinh("loading", "lazy")).append(' ').append(thuocTinh("src", src));
            }
            case "a" -> {
                String href = joinHttp(goc, e.attr("href"));
                if (href.isEmpty()) {
                    ghiCon(e, goc, sb, giuTrang);
                    return;
                }
                tag.append(' ').append(thuocTinh("href", href)).append(' ').append(thuocTinh("rel", "noopener"))
                        .append(' ').append(thuocTinh("target", "_blank"));
            }
            default -> {}
        }
        if (ten.equals("br") || ten.equals("img")) {
            sb.append(tag).append("/>");
            return;
        }
        sb.append(tag).append('>');
        ghiCon(e, goc, sb, giuTrang);
        sb.append("</").append(ten).append('>');
    }

    private static String thoat(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Thuộc tính theo bs4: giá trị có " thì bọc bằng ' (nếu không có ' nữa), không thì thoát thành &quot;. */
    private static String thuocTinh(String k, String v) {
        v = thoat(v);
        if (v.contains("\"")) {
            if (v.contains("'")) return k + "=\"" + v.replace("\"", "&quot;") + "\"";
            return k + "='" + v + "'";
        }
        return k + "=\"" + v + "\"";
    }
}
