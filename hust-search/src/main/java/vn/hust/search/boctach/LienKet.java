package vn.hust.search.boctach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import vn.hust.search.kho.Url;

/**
 * Đồ thị liên kết: cạnh {@code nguồn --> đích : văn bản mô tả} — bản port của {@code lien_ket.py}.
 * Cạnh nằm trong khối nội dung là cạnh nội dung (người viết chủ động giới thiệu); cạnh ngoài khối
 * là cạnh khuôn (menu, footer, sidebar) — lưu gộp theo host chứ không theo từng trang.
 */
public final class LienKet {
    private LienKet() {}

    static final Set<String> DUOI_TAI_LIEU = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");
    static final Set<String> DUOI_ANH = Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico");
    static final String[] DUONG_DAN_KHUON = {"/themes/", "/templates/", "/assets/"};
    static final String HOST_NHA = "hust.edu.vn";

    /** Một cạnh đã khử trùng theo (dst, type, text). */
    public static final class Canh {
        public final String dst, type, text, dstKind;
        public int count;

        Canh(String dst, String type, String text, String dstKind) {
            this.dst = dst;
            this.type = type;
            this.text = text;
            this.dstKind = dstKind;
            this.count = 1;
        }
    }

    /** Cạnh thô, còn giữ phần tử HTML để biết nó nằm trong hay ngoài khối. {@code el} null = thuộc trang nói chung. */
    public record Tho(Element el, String dst, String type, String text, boolean forceNav) {}

    /** Url công khai của một cạnh href trong khối; {@code text} lấy từ lần đầu có chữ. */
    public static final class CongKhai {
        public final String url;
        public String text;

        CongKhai(String url, String text) {
            this.url = url;
            this.text = text;
        }
    }

    public static boolean trongHoHust(String host) {
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return host.equals(HOST_NHA) || host.endsWith("." + HOST_NHA);
    }

    /** page | document | image | external. Host ngoài họ hust.edu.vn luôn là external. */
    public static String loaiDich(String url) {
        if (!trongHoHust(Url.hostname(url))) return "external";
        String[] pq = Url.pathQuery(url);
        String path = pq[0];
        String ten = path.substring(path.lastIndexOf('/') + 1);
        String duoi = ten.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (DUOI_TAI_LIEU.contains(duoi) || pq[1].contains("download=1")) return "document";
        if (DUOI_ANH.contains(duoi)) return "image";
        return "page";
    }

    private static String moTaA(Element a) {
        String text = HtmlSach.cleanSpace(HtmlSach.textBs4(a));
        if (!text.isEmpty()) return text;
        String t = a.attr("title");
        text = HtmlSach.cleanSpace(t.isEmpty() ? a.attr("aria-label") : t);
        if (!text.isEmpty()) return text;
        Element img = Truong.theoThe(a, "img");
        return img == null ? "" : HtmlSach.cleanSpace(img.attr("alt"));
    }

    private static String moTaImg(Element img) {
        String t = img.attr("alt");
        if (t.isEmpty()) t = img.attr("title");
        String text = HtmlSach.cleanSpace(t);
        if (!text.isEmpty()) return text;
        Element fig = null;
        for (Element p = img.parent(); p != null; p = p.parent()) if (p.normalName().equals("figure")) { fig = p; break; }
        Element cap = fig == null ? null : Truong.theoThe(fig, "figcaption");
        return cap == null ? "" : HtmlSach.cleanSpace(HtmlSach.textBs4(cap));
    }

    private static boolean laAnhKhuon(Element img, String dst) {
        String path = Url.pathQuery(dst)[0];
        for (String s : DUONG_DAN_KHUON) if (path.contains(s)) return true;
        for (String k : new String[]{"width", "height"}) {
            String v = HtmlSach.strip(img.attr(k));
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
    public static List<Tho> thuThap(Document doc, String base) {
        List<Tho> raw = new ArrayList<>();
        String selfUrl = HtmlSach.joinHttp(base, base);
        List<Element> all = HtmlSach.conChau(doc);
        for (Element a : all) {
            if (!a.normalName().equals("a") || !a.hasAttr("href")) continue;
            String dst = HtmlSach.joinHttp(base, a.attr("href"));
            if (!dst.isEmpty() && !dst.equals(selfUrl)) raw.add(new Tho(a, dst, "href", moTaA(a), false));
        }
        for (Element img : all) {
            if (!img.normalName().equals("img")) continue;
            String src = img.attr("src");
            if (src.isEmpty()) src = img.attr("data-src");
            String dst = HtmlSach.joinHttp(base, src);
            if (!dst.isEmpty()) raw.add(new Tho(img, dst, "embed", moTaImg(img), laAnhKhuon(img, dst)));
        }
        for (Element m : all) {
            if (!m.normalName().equals("meta") || !m.hasAttr("property") || !m.attr("property").equals("og:image")) continue;
            String dst = HtmlSach.joinHttp(base, m.attr("content"));
            if (!dst.isEmpty()) raw.add(new Tho(null, dst, "embed", "", false));
        }
        return raw;
    }

    private static boolean ben_trong(Element el, Element khoi) {
        for (Element p = el.parent(); p != null; p = p.parent()) if (p == khoi) return true;
        return false;
    }

    /** Tách cạnh nội dung / cạnh khuôn, khử trùng theo (dst, type, text) kèm {@code count}. */
    public static List<List<Canh>> chia(List<Tho> raw, Element khoiNode) {
        Map<List<String>, Canh> nd = new LinkedHashMap<>(), kh = new LinkedHashMap<>();
        for (Tho e : raw) {
            boolean trong = e.el() == null || (khoiNode != null && ben_trong(e.el(), khoiNode));
            var dich = trong && !e.forceNav() ? nd : kh;
            var k = List.of(e.dst(), e.type(), e.text());
            Canh c = dich.get(k);
            if (c == null) dich.put(k, new Canh(e.dst(), e.type(), e.text(), loaiDich(e.dst())));
            else c.count++;
        }
        return List.of(new ArrayList<>(nd.values()), new ArrayList<>(kh.values()));
    }

    /** {@code outgoing_links} của schema public: cạnh href, mỗi url một dòng. */
    public static List<CongKhai> canhRaCongKhai(List<Canh> canhNoiDung) {
        List<CongKhai> out = new ArrayList<>();
        Map<String, Integer> seen = new java.util.HashMap<>();
        for (Canh e : canhNoiDung) {
            if (!e.type.equals("href")) continue;
            Integer i = seen.get(e.dst);
            if (i != null) {
                if (out.get(i).text.isEmpty() && !e.text.isEmpty()) out.get(i).text = e.text;
                continue;
            }
            seen.put(e.dst, out.size());
            out.add(new CongKhai(e.dst, e.text));
        }
        return out;
    }
}
