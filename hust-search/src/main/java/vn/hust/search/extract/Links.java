package vn.hust.search.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import vn.hust.search.store.Url;

/**
 * Đồ thị liên kết: cạnh {@code nguồn --> đích : văn bản mô tả} — bản port của {@code lien_ket.py}.
 * Cạnh nằm trong khối nội dung là cạnh nội dung (người viết chủ động giới thiệu); cạnh ngoài khối
 * là cạnh khuôn (menu, footer, sidebar) — lưu gộp theo host chứ không theo từng trang.
 */
public final class Links {
    private Links() {}

    static final Set<String> DOCUMENT_EXTS = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");
    static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico");
    static final String[] TEMPLATE_PATHS = {"/themes/", "/templates/", "/assets/"};
    static final String HOME_HOST = "hust.edu.vn";

    /** Một cạnh đã khử trùng theo (dst, type, text). */
    public static final class Edge {
        public final String dst, type, text, dstKind;
        public int count;

        Edge(String dst, String type, String text, String dstKind) {
            this.dst = dst;
            this.type = type;
            this.text = text;
            this.dstKind = dstKind;
            this.count = 1;
        }
    }

    /** Cạnh thô, còn giữ phần tử HTML để biết nó nằm trong hay ngoài khối. {@code el} null = thuộc trang nói chung. */
    public record RawEdge(Element el, String dst, String type, String text, boolean forceNav) {}

    /** Url công khai của một cạnh href trong khối; {@code text} lấy từ lần đầu có chữ. */
    public static final class PublicLink {
        public final String url;
        public String text;

        PublicLink(String url, String text) {
            this.url = url;
            this.text = text;
        }
    }

    public static boolean inHustFamily(String host) {
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return host.equals(HOME_HOST) || host.endsWith("." + HOME_HOST);
    }

    /** page | document | image | external. Host ngoài họ hust.edu.vn luôn là external. */
    public static String destKind(String url) {
        if (!inHustFamily(Url.hostname(url))) return "external";
        String[] pq = Url.pathQuery(url);
        String path = pq[0];
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String ext = fileName.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (DOCUMENT_EXTS.contains(ext) || pq[1].contains("download=1")) return "document";
        if (IMAGE_EXTS.contains(ext)) return "image";
        return "page";
    }

    private static String describeAnchor(Element a) {
        String text = HtmlUtil.cleanSpace(HtmlUtil.textBs4(a));
        if (!text.isEmpty()) return text;
        String t = a.attr("title");
        text = HtmlUtil.cleanSpace(t.isEmpty() ? a.attr("aria-label") : t);
        if (!text.isEmpty()) return text;
        Element img = Fields.byTag(a, "img");
        return img == null ? "" : HtmlUtil.cleanSpace(img.attr("alt"));
    }

    private static String describeImage(Element img) {
        String t = img.attr("alt");
        if (t.isEmpty()) t = img.attr("title");
        String text = HtmlUtil.cleanSpace(t);
        if (!text.isEmpty()) return text;
        Element fig = null;
        for (Element p = img.parent(); p != null; p = p.parent()) if (p.normalName().equals("figure")) { fig = p; break; }
        Element cap = fig == null ? null : Fields.byTag(fig, "figcaption");
        return cap == null ? "" : HtmlUtil.cleanSpace(HtmlUtil.textBs4(cap));
    }

    private static boolean isTemplateImage(Element img, String dst) {
        String path = Url.pathQuery(dst)[0];
        for (String s : TEMPLATE_PATHS) if (path.contains(s)) return true;
        for (String k : new String[]{"width", "height"}) {
            String v = HtmlUtil.strip(img.attr(k));
            int e = v.length();
            while (e > 0 && (v.charAt(e - 1) == 'p' || v.charAt(e - 1) == 'x')) e--;      // rstrip("px")
            v = v.substring(0, e);
            if (!v.isEmpty() && v.chars().allMatch(Character::isDigit)) {
                try {
                    if (Integer.parseInt(v) <= 16) return true;
                } catch (NumberFormatException ex) {
                    // số quá lớn: không phải ảnh nhỏ
                }
            }
        }
        return false;
    }

    /** Mọi cạnh trong trang, TRƯỚC khi dọn cây (menu/footer còn nguyên). */
    public static List<RawEdge> collect(Document doc, String base) {
        List<RawEdge> raw = new ArrayList<>();
        String selfUrl = HtmlUtil.joinHttp(base, base);
        List<Element> all = HtmlUtil.descendants(doc);
        for (Element a : all) {
            if (!a.normalName().equals("a") || !a.hasAttr("href")) continue;
            String dst = HtmlUtil.joinHttp(base, a.attr("href"));
            if (!dst.isEmpty() && !dst.equals(selfUrl)) raw.add(new RawEdge(a, dst, "href", describeAnchor(a), false));
        }
        for (Element img : all) {
            if (!img.normalName().equals("img")) continue;
            String src = img.attr("src");
            if (src.isEmpty()) src = img.attr("data-src");
            String dst = HtmlUtil.joinHttp(base, src);
            if (!dst.isEmpty()) raw.add(new RawEdge(img, dst, "embed", describeImage(img), isTemplateImage(img, dst)));
        }
        for (Element m : all) {
            if (!m.normalName().equals("meta") || !m.hasAttr("property") || !m.attr("property").equals("og:image")) continue;
            String dst = HtmlUtil.joinHttp(base, m.attr("content"));
            if (!dst.isEmpty()) raw.add(new RawEdge(null, dst, "embed", "", false));
        }
        return raw;
    }

    private static boolean isInside(Element el, Element block) {
        for (Element p = el.parent(); p != null; p = p.parent()) if (p == block) return true;
        return false;
    }

    /** Tách cạnh nội dung / cạnh khuôn, khử trùng theo (dst, type, text) kèm {@code count}. */
    public static List<List<Edge>> split(List<RawEdge> raw, Element blockNode) {
        Map<List<String>, Edge> content = new LinkedHashMap<>(), nav = new LinkedHashMap<>();
        for (RawEdge e : raw) {
            boolean inside = e.el() == null || (blockNode != null && isInside(e.el(), blockNode));
            var target = inside && !e.forceNav() ? content : nav;
            var k = List.of(e.dst(), e.type(), e.text());
            Edge c = target.get(k);
            if (c == null) target.put(k, new Edge(e.dst(), e.type(), e.text(), destKind(e.dst())));
            else c.count++;
        }
        return List.of(new ArrayList<>(content.values()), new ArrayList<>(nav.values()));
    }

    /** {@code outgoing_links} của schema public: cạnh href, mỗi url một dòng. */
    public static List<PublicLink> toPublicLinks(List<Edge> contentEdges) {
        List<PublicLink> out = new ArrayList<>();
        Map<String, Integer> seen = new java.util.HashMap<>();
        for (Edge e : contentEdges) {
            if (!e.type.equals("href")) continue;
            Integer i = seen.get(e.dst);
            if (i != null) {
                if (out.get(i).text.isEmpty() && !e.text.isEmpty()) out.get(i).text = e.text;
                continue;
            }
            seen.put(e.dst, out.size());
            out.add(new PublicLink(e.dst, e.text));
        }
        return out;
    }
}
