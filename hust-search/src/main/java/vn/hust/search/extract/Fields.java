package vn.hust.search.extract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Bóc các trường của trang: tiêu đề, ngày đăng, tác giả, nguồn trích dẫn — bản port của
 * {@code truong.py}. Mỗi trường là một chuỗi ưu tiên và trả kèm tên nguồn để đo xem trường nào
 * đang lấy được từ đâu, host nào hay rỗng.
 */
public final class Fields {
    private Fields() {}

    /** Giá trị kèm tên nguồn lấy ra nó. */
    public record Sourced(String v, String src) {
        static final Sourced EMPTY = new Sourced("", "");
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> GENERIC_AUTHORS = Set.of("admin", "administrator", "webmaster", "super user", "superuser");
    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;
    private static final int I = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | U;
    static final Pattern RE_DATE_VN = Pattern.compile("(?<!\\d)(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})(?!\\d)", U);
    private static final Pattern RE_DATE_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})", U);
    private static final Pattern RE_AUTHOR = Pattern.compile(
            "^(?:Tác giả|Người viết|Tin,? ?ảnh|Bài,? ?ảnh|Tin,? ?bài|Bài viết|Ảnh)\\s*[:：]\\s*(.{2,80})$", I);
    private static final Pattern RE_SOURCE = Pattern.compile("^(?:Nguồn\\s*[:：]\\s*(.{2,120})|Theo\\s+(.{2,80}))$", I);

    static String clean(String s) {
        return HtmlUtil.cleanSpace(s);
    }

    // ------------------------------------------------------------------ tra cứu như bs4
    /** Phần tử đầu tiên trong {@code goc} (không tính chính nó) có thuộc tính {@code k} đúng bằng {@code v}. */
    static Element byAttr(Element root, String k, String v) {
        for (Element e : HtmlUtil.descendants(root)) if (e.hasAttr(k) && e.attr(k).equals(v)) return e;
        return null;
    }

    static Element byTag(Element root, String nameEl) {
        for (Element e : HtmlUtil.descendants(root)) if (e.normalName().equals(nameEl)) return e;
        return null;
    }

    // ------------------------------------------------------------------ JSON-LD
    /** Mọi đối tượng JSON-LD (phải gọi TRƯỚC khi dọn cây vì nằm trong script). */
    public static List<JsonNode> jsonLd(Document doc) {
        List<JsonNode> out = new ArrayList<>();
        for (Element s : doc.getAllElements()) {
            if (!s.normalName().equals("script") || !s.attr("type").equals("application/ld+json")) continue;
            JsonNode data;
            try {
                data = JSON.readTree(s.data());
            } catch (Exception e) {
                continue;
            }
            List<JsonNode> stack = new ArrayList<>();
            if (data.isArray()) data.forEach(stack::add);
            else stack.add(data);
            while (!stack.isEmpty()) {
                JsonNode d = stack.remove(stack.size() - 1);          // pop() lấy từ cuối, như Python
                if (d.isObject()) {
                    out.add(d);
                    JsonNode g = d.get("@graph");
                    if (g != null && g.isArray()) g.forEach(stack::add);
                }
            }
        }
        return out;
    }

    /** Giá trị "thật" theo nghĩa của Python: không null, không rỗng, không 0, không false. */
    static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return false;
        if (n.isTextual()) return !n.asText().isEmpty();
        if (n.isContainerNode()) return n.size() > 0;
        if (n.isNumber()) return n.doubleValue() != 0;
        if (n.isBoolean()) return n.asBoolean();
        return true;
    }

    static JsonNode ldGet(List<JsonNode> ld, String key) {
        for (JsonNode d : ld) if (truthy(d.get(key))) return d.get(key);
        return null;
    }

    private static String prop(Document doc, String name) {
        Element t = byAttr(doc, "itemprop", name);
        if (t == null) return "";
        String c = t.attr("content");
        return clean(c.isEmpty() ? HtmlUtil.textBs4(t) : c);
    }

    static String meta(Document doc, String attr, String val) {
        for (Element e : HtmlUtil.descendants(doc))
            if (e.normalName().equals("meta") && e.hasAttr(attr) && e.attr(attr).equals(val)) return clean(e.attr("content"));
        return "";
    }

    public static String section(Document doc) {
        return meta(doc, "property", "article:section");
    }

    // ------------------------------------------------------------------ tiêu đề
    /** Cắt hậu tố tên site ở title ("Bài A - ĐH Bách khoa"); heuristic, có thể cắt nhầm. */
    static String cutSuffix(String title) {
        for (String sep : new String[]{" | ", " - ", " – ", " — "}) {
            int i = title.lastIndexOf(sep);
            if (i >= 0) {
                String prefix = title.substring(0, i), suffix = title.substring(i + sep.length());
                if (HtmlUtil.len(suffix) <= 40 && HtmlUtil.len(prefix) >= 10) return HtmlUtil.strip(prefix);
            }
        }
        return title;
    }

    public static Sourced title(Document doc, List<JsonNode> ld, Element blockNode) {
        String v = prop(doc, "headline");
        if (!v.isEmpty()) return new Sourced(v, "headline");
        v = meta(doc, "property", "og:title");
        if (!v.isEmpty()) return new Sourced(v, "og:title");
        JsonNode h = ldGet(ld, "headline");
        if (h != null && h.isTextual() && !clean(h.asText()).isEmpty()) return new Sourced(clean(h.asText()), "json-ld");
        Element h1 = null, n = blockNode;
        for (int i = 0; i < 4; i++) {                       // h1 nằm trong khối hoặc vài cấp cha của nó
            if (n == null) break;
            h1 = byTag(n, "h1");
            if (h1 != null) break;
            n = n.parent();
        }
        if (h1 == null) h1 = byTag(doc, "h1");
        if (h1 != null && !clean(HtmlUtil.text(h1, " ", false)).isEmpty())
            return new Sourced(clean(HtmlUtil.text(h1, " ", false)), "h1");
        Element t = byTag(doc, "title");
        if (t != null && !clean(HtmlUtil.text(t, "", false)).isEmpty())
            return new Sourced(cutSuffix(clean(HtmlUtil.text(t, "", false))), "title");
        return Sourced.EMPTY;
    }

    // ------------------------------------------------------------------ ngày
    private static String iso(int y, int m, int d) {
        if (y < 1) return "";
        try {
            LocalDate.of(y, m, d);
        } catch (DateTimeException e) {
            return "";
        }
        return String.format("%04d-%02d-%02d", y, m, d);
    }

    /** Chuỗi ngày bất kỳ (ISO hoặc dd/mm/yyyy) -> YYYY-MM-DD, hoặc rỗng. */
    public static String normalizeDate(String value) {
        value = HtmlUtil.strip(value == null ? "" : value);
        Matcher m = RE_DATE_ISO.matcher(value);
        if (m.lookingAt()) return iso(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        m = RE_DATE_VN.matcher(value);
        if (m.find()) return iso(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        return "";
    }

    private static boolean hasDateClass(Element e) {
        String c = String.join(" ", ContentBlock.cssClasses(e));
        return c.contains("date") || c.contains("time") || c.contains("posted") || c.contains("meta");
    }

    public static Sourced publishedDate(Document doc, List<JsonNode> ld, Element blockNode) {
        JsonNode ldDate = ldGet(ld, "datePublished");
        String[][] sources = {{prop(doc, "datePublished"), "datePublished"},
                {meta(doc, "property", "article:published_time"), "article:published_time"},
                {ldDate != null && ldDate.isTextual() ? ldDate.asText() : "", "json-ld"}};
        for (String[] u : sources) {
            String d = normalizeDate(u[0]);
            if (!d.isEmpty()) return new Sourced(d, u[1]);
        }
        for (Element e : HtmlUtil.descendants(doc)) {
            if (e.normalName().equals("time") && e.hasAttr("datetime")) {
                String d = normalizeDate(e.attr("datetime"));
                if (!d.isEmpty()) return new Sourced(d, "time");
                break;                                       // chỉ xét thẻ time đầu tiên, như find()
            }
        }
        List<String> cands = new ArrayList<>();
        for (Element e : HtmlUtil.descendants(doc)) {            // [class*=date],[class*=time],[class*=posted],[class*=meta], 6 phần tử đầu
            if (cands.size() >= 6) break;
            if (hasDateClass(e)) cands.add(clean(HtmlUtil.text(e, " ", false)));
        }
        if (blockNode != null) {
            String txt = clean(HtmlUtil.text(blockNode, " ", false));
            cands.add(HtmlUtil.head(txt, 300));
            cands.add(HtmlUtil.tail(txt, 200));
        }
        for (String c : cands) {
            String d = RE_DATE_VN.matcher(c).find() ? normalizeDate(c) : "";
            if (!d.isEmpty()) return new Sourced(d, "regex");
        }
        return Sourced.EMPTY;
    }

    // ------------------------------------------------------------------ tác giả
    private static String authorName(JsonNode v) {
        if (v == null) return "";
        if (v.isArray()) v = v.size() > 0 ? v.get(0) : null;
        if (v != null && v.isObject()) v = v.get("name");
        return v != null && v.isTextual() ? clean(v.asText()) : "";
    }

    private static boolean isRealAuthor(String v) {
        return !v.isEmpty() && !GENERIC_AUTHORS.contains(v.toLowerCase(Locale.ROOT));
    }

    public static Sourced authorMeta(Document doc, List<JsonNode> ld) {
        Element t = byAttr(doc, "itemprop", "author");
        if (t != null) {
            Element nameEl = byAttr(t, "itemprop", "name");
            if (nameEl == null) nameEl = t;
            String c = nameEl.attr("content");
            String v = clean(c.isEmpty() ? HtmlUtil.textBs4(nameEl) : c);
            if (isRealAuthor(v)) return new Sourced(v, "microdata");
        }
        String v = meta(doc, "name", "author");
        if (isRealAuthor(v)) return new Sourced(v, "meta");
        v = authorName(ldGet(ld, "author"));
        if (isRealAuthor(v)) return new Sourced(v, "json-ld");
        return Sourced.EMPTY;
    }

    private static final Set<String> SHORT_TAGS = Set.of("p", "div", "li", "span", "strong", "em", "b", "i");
    private static final Set<String> BLOCK_LIKE_TAGS = Set.of("p", "div", "li");

    /** Các phần tử ngắn ở cuối khối, mới nhất trước — nơi hay có dòng tác giả/nguồn. */
    private static List<Element> shortLines(Element blockNode) {
        List<Element> els = new ArrayList<>();
        for (Element e : HtmlUtil.descendants(blockNode)) {
            if (!SHORT_TAGS.contains(e.normalName())) continue;
            boolean hasChild = false;
            for (Element c : HtmlUtil.descendants(e)) if (BLOCK_LIKE_TAGS.contains(c.normalName())) { hasChild = true; break; }
            int n = HtmlUtil.len(clean(HtmlUtil.text(e, " ", false)));
            if (!hasChild && n >= 2 && n <= 130) els.add(e);
        }
        java.util.Collections.reverse(els);
        return els.subList(0, Math.min(8, els.size()));
    }

    /** {@code <p><strong>Tác giả: X</strong></p>} -> chọn p: bỏ cả dòng, không sót thẻ rỗng. */
    private static Element climb(Element e) {
        String txt = clean(HtmlUtil.text(e, " ", false));
        while (e.parent() != null && BLOCK_LIKE_TAGS.contains(e.parent().normalName())
                && clean(HtmlUtil.text(e.parent(), " ", false)).equals(txt) && !BLOCK_LIKE_TAGS.contains(e.normalName()))
            e = e.parent();
        return e;
    }

    /** Dòng tác giả + nguồn trích dẫn tìm được ở cuối khối. */
    public record AuthorSource(Sourced author, String source) {}

    /** Tìm dòng "Tác giả:" và "Nguồn:" ở cuối khối và CẮT chúng khỏi khối (sửa cây tại chỗ). */
    public static AuthorSource authorAndSourceLines(Element blockNode) {
        Sourced author = Sourced.EMPTY;
        String source = "";
        if (blockNode == null) return new AuthorSource(author, source);
        for (Element e : shortLines(blockNode)) {
            if (!HtmlUtil.isAttached(e)) continue;
            String txt = clean(HtmlUtil.text(e, " ", false));
            Matcher m = RE_AUTHOR.matcher(txt);
            if (m.find() && author.v().isEmpty()) {
                author = new Sourced(clean(m.group(1)), "text-line");
                climb(e).remove();
                continue;
            }
            m = RE_SOURCE.matcher(txt);
            if (m.find() && source.isEmpty()) {
                source = clean(m.group(1) != null ? m.group(1) : m.group(2));
                climb(e).remove();
            }
        }
        return new AuthorSource(author, source);
    }
}
