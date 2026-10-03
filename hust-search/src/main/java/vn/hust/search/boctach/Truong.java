package vn.hust.search.boctach;

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
public final class Truong {
    private Truong() {}

    /** Giá trị kèm tên nguồn lấy ra nó. */
    public record Cap(String v, String src) {
        static final Cap RONG = new Cap("", "");
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> TAC_GIA_CHUNG = Set.of("admin", "administrator", "webmaster", "super user", "superuser");
    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;
    private static final int I = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | U;
    static final Pattern RE_NGAY_VN = Pattern.compile("(?<!\\d)(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})(?!\\d)", U);
    private static final Pattern RE_NGAY_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})", U);
    private static final Pattern RE_TAC_GIA = Pattern.compile(
            "^(?:Tác giả|Người viết|Tin,? ?ảnh|Bài,? ?ảnh|Tin,? ?bài|Bài viết|Ảnh)\\s*[:：]\\s*(.{2,80})$", I);
    private static final Pattern RE_NGUON = Pattern.compile("^(?:Nguồn\\s*[:：]\\s*(.{2,120})|Theo\\s+(.{2,80}))$", I);

    static String clean(String s) {
        return HtmlSach.cleanSpace(s);
    }

    // ------------------------------------------------------------------ tra cứu như bs4
    /** Phần tử đầu tiên trong {@code goc} (không tính chính nó) có thuộc tính {@code k} đúng bằng {@code v}. */
    static Element theoAttr(Element goc, String k, String v) {
        for (Element e : HtmlSach.conChau(goc)) if (e.hasAttr(k) && e.attr(k).equals(v)) return e;
        return null;
    }

    static Element theoThe(Element goc, String ten) {
        for (Element e : HtmlSach.conChau(goc)) if (e.normalName().equals(ten)) return e;
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
        Element t = theoAttr(doc, "itemprop", name);
        if (t == null) return "";
        String c = t.attr("content");
        return clean(c.isEmpty() ? HtmlSach.textBs4(t) : c);
    }

    static String meta(Document doc, String attr, String val) {
        for (Element e : HtmlSach.conChau(doc))
            if (e.normalName().equals("meta") && e.hasAttr(attr) && e.attr(attr).equals(val)) return clean(e.attr("content"));
        return "";
    }

    public static String section(Document doc) {
        return meta(doc, "property", "article:section");
    }

    // ------------------------------------------------------------------ tiêu đề
    /** Cắt hậu tố tên site ở title ("Bài A - ĐH Bách khoa"); heuristic, có thể cắt nhầm. */
    static String catHauTo(String title) {
        for (String sep : new String[]{" | ", " - ", " – ", " — "}) {
            int i = title.lastIndexOf(sep);
            if (i >= 0) {
                String dau = title.substring(0, i), cuoi = title.substring(i + sep.length());
                if (HtmlSach.len(cuoi) <= 40 && HtmlSach.len(dau) >= 10) return HtmlSach.strip(dau);
            }
        }
        return title;
    }

    public static Cap tieuDe(Document doc, List<JsonNode> ld, Element khoiNode) {
        String v = prop(doc, "headline");
        if (!v.isEmpty()) return new Cap(v, "headline");
        v = meta(doc, "property", "og:title");
        if (!v.isEmpty()) return new Cap(v, "og:title");
        JsonNode h = ldGet(ld, "headline");
        if (h != null && h.isTextual() && !clean(h.asText()).isEmpty()) return new Cap(clean(h.asText()), "json-ld");
        Element h1 = null, n = khoiNode;
        for (int i = 0; i < 4; i++) {                       // h1 nằm trong khối hoặc vài cấp cha của nó
            if (n == null) break;
            h1 = theoThe(n, "h1");
            if (h1 != null) break;
            n = n.parent();
        }
        if (h1 == null) h1 = theoThe(doc, "h1");
        if (h1 != null && !clean(HtmlSach.text(h1, " ", false)).isEmpty())
            return new Cap(clean(HtmlSach.text(h1, " ", false)), "h1");
        Element t = theoThe(doc, "title");
        if (t != null && !clean(HtmlSach.text(t, "", false)).isEmpty())
            return new Cap(catHauTo(clean(HtmlSach.text(t, "", false))), "title");
        return Cap.RONG;
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
    public static String chuanNgay(String value) {
        value = HtmlSach.strip(value == null ? "" : value);
        Matcher m = RE_NGAY_ISO.matcher(value);
        if (m.lookingAt()) return iso(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        m = RE_NGAY_VN.matcher(value);
        if (m.find()) return iso(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        return "";
    }

    private static boolean lopChua(Element e) {
        String c = String.join(" ", Khoi.lop(e));
        return c.contains("date") || c.contains("time") || c.contains("posted") || c.contains("meta");
    }

    public static Cap ngayDang(Document doc, List<JsonNode> ld, Element khoiNode) {
        JsonNode lv = ldGet(ld, "datePublished");
        String[][] ung = {{prop(doc, "datePublished"), "datePublished"},
                {meta(doc, "property", "article:published_time"), "article:published_time"},
                {lv != null && lv.isTextual() ? lv.asText() : "", "json-ld"}};
        for (String[] u : ung) {
            String d = chuanNgay(u[0]);
            if (!d.isEmpty()) return new Cap(d, u[1]);
        }
        for (Element e : HtmlSach.conChau(doc)) {
            if (e.normalName().equals("time") && e.hasAttr("datetime")) {
                String d = chuanNgay(e.attr("datetime"));
                if (!d.isEmpty()) return new Cap(d, "time");
                break;                                       // chỉ xét thẻ time đầu tiên, như find()
            }
        }
        List<String> cands = new ArrayList<>();
        for (Element e : HtmlSach.conChau(doc)) {            // [class*=date],[class*=time],[class*=posted],[class*=meta], 6 phần tử đầu
            if (cands.size() >= 6) break;
            if (lopChua(e)) cands.add(clean(HtmlSach.text(e, " ", false)));
        }
        if (khoiNode != null) {
            String txt = clean(HtmlSach.text(khoiNode, " ", false));
            cands.add(HtmlSach.head(txt, 300));
            cands.add(HtmlSach.tail(txt, 200));
        }
        for (String c : cands) {
            String d = RE_NGAY_VN.matcher(c).find() ? chuanNgay(c) : "";
            if (!d.isEmpty()) return new Cap(d, "regex");
        }
        return Cap.RONG;
    }

    // ------------------------------------------------------------------ tác giả
    private static String tenTacGia(JsonNode v) {
        if (v == null) return "";
        if (v.isArray()) v = v.size() > 0 ? v.get(0) : null;
        if (v != null && v.isObject()) v = v.get("name");
        return v != null && v.isTextual() ? clean(v.asText()) : "";
    }

    private static boolean tacGiaThat(String v) {
        return !v.isEmpty() && !TAC_GIA_CHUNG.contains(v.toLowerCase(Locale.ROOT));
    }

    public static Cap tacGiaMeta(Document doc, List<JsonNode> ld) {
        Element t = theoAttr(doc, "itemprop", "author");
        if (t != null) {
            Element ten = theoAttr(t, "itemprop", "name");
            if (ten == null) ten = t;
            String c = ten.attr("content");
            String v = clean(c.isEmpty() ? HtmlSach.textBs4(ten) : c);
            if (tacGiaThat(v)) return new Cap(v, "microdata");
        }
        String v = meta(doc, "name", "author");
        if (tacGiaThat(v)) return new Cap(v, "meta");
        v = tenTacGia(ldGet(ld, "author"));
        if (tacGiaThat(v)) return new Cap(v, "json-ld");
        return Cap.RONG;
    }

    private static final Set<String> NGAN = Set.of("p", "div", "li", "span", "strong", "em", "b", "i");
    private static final Set<String> KHOI_DONG = Set.of("p", "div", "li");

    /** Các phần tử ngắn ở cuối khối, mới nhất trước — nơi hay có dòng tác giả/nguồn. */
    private static List<Element> dongNgan(Element khoiNode) {
        List<Element> els = new ArrayList<>();
        for (Element e : HtmlSach.conChau(khoiNode)) {
            if (!NGAN.contains(e.normalName())) continue;
            boolean coCon = false;
            for (Element c : HtmlSach.conChau(e)) if (KHOI_DONG.contains(c.normalName())) { coCon = true; break; }
            int n = HtmlSach.len(clean(HtmlSach.text(e, " ", false)));
            if (!coCon && n >= 2 && n <= 130) els.add(e);
        }
        java.util.Collections.reverse(els);
        return els.subList(0, Math.min(8, els.size()));
    }

    /** {@code <p><strong>Tác giả: X</strong></p>} -> chọn p: bỏ cả dòng, không sót thẻ rỗng. */
    private static Element leoLen(Element e) {
        String txt = clean(HtmlSach.text(e, " ", false));
        while (e.parent() != null && KHOI_DONG.contains(e.parent().normalName())
                && clean(HtmlSach.text(e.parent(), " ", false)).equals(txt) && !KHOI_DONG.contains(e.normalName()))
            e = e.parent();
        return e;
    }

    /** Dòng tác giả + nguồn trích dẫn tìm được ở cuối khối. */
    public record TgNguon(Cap tacGia, String nguon) {}

    /** Tìm dòng "Tác giả:" và "Nguồn:" ở cuối khối và CẮT chúng khỏi khối (sửa cây tại chỗ). */
    public static TgNguon dongTacGiaNguon(Element khoiNode) {
        Cap tg = Cap.RONG;
        String nguon = "";
        if (khoiNode == null) return new TgNguon(tg, nguon);
        for (Element e : dongNgan(khoiNode)) {
            if (!HtmlSach.conGan(e)) continue;
            String txt = clean(HtmlSach.text(e, " ", false));
            Matcher m = RE_TAC_GIA.matcher(txt);
            if (m.find() && tg.v().isEmpty()) {
                tg = new Cap(clean(m.group(1)), "text-line");
                leoLen(e).remove();
                continue;
            }
            m = RE_NGUON.matcher(txt);
            if (m.find() && nguon.isEmpty()) {
                nguon = clean(m.group(1) != null ? m.group(1) : m.group(2));
                leoLen(e).remove();
            }
        }
        return new TgNguon(tg, nguon);
    }
}
