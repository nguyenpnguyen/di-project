package vn.hust.search.boctach;

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
 * <p>Ba lớp: (1) dọn cây, (2) khử khuôn theo host ({@link Khuon}, tuỳ chọn), (3) chấm điểm nút theo
 * mật độ chữ / mật độ link rồi đi từ gốc xuống. Hằng số ALPHA/BETA/GAMMA/DELTA giữ nguyên bản Python.
 * Họ ý tưởng: CETD (Sun, Song, Liao — SIGIR 2011), Boilerpipe (Kohlschütter và cs. — WSDM 2010).
 */
public record Khoi(Element node, String path, double score, String method) {
    /** Selector đã kiểm chứng theo host. */
    public static final Map<String, String> SELECTOR_THEO_HOST = Map.of("hust.edu.vn", ".bodytext");

    private static final Set<String> BO_HAN = Set.of("script", "style", "noscript", "iframe", "form", "svg", "button",
            "input", "select", "nav", "footer", "aside", "template");
    private static final Set<String> UNG_VIEN = Set.of("div", "section", "article", "main", "td", "table", "tbody",
            "tr", "body", "form");
    static final double ALPHA = 2.0, BETA = 30.0, GAMMA = 1.0, DELTA = 0.65;
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern PHAT = Pattern.compile("nav|menu|footer|header|sidebar|comment|share|related|"
            + "breadcrumb|banner|widget|social|advert|popup|modal|pagination|tag", F);
    private static final Pattern THUONG = Pattern.compile(
            "content|article|post|entry|detail|bodytext|main|news-body|noi-?dung", F);
    private static final Pattern DAU_CAU = Pattern.compile("[.,;:?!…]");

    /** Lớp 1. Bỏ thẻ không mang nội dung, thẻ ẩn và comment. */
    public static void donCay(Document doc) {
        List<Comment> comments = new ArrayList<>();
        doc.traverse((n, d) -> {
            if (n instanceof Comment c) comments.add(c);
        });
        comments.forEach(Node::remove);
        for (Element t : doc.getAllElements()) if (BO_HAN.contains(t.normalName())) t.remove();
        for (Element t : doc.getAllElements()) {
            if (t == doc || !HtmlSach.conGan(t)) continue;
            String style = t.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
            if (t.hasAttr("hidden") || style.contains("display:none")) t.remove();
        }
    }

    /** Các lớp CSS theo thứ tự và kể cả trùng, như {@code node["class"]} của bs4. */
    static List<String> lop(Element e) {
        List<String> out = new ArrayList<>();
        for (String s : HtmlSach.WS.split(e.attr("class"))) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Đường dẫn CSS rút gọn tới nút, để ghi lại khối nào đã được chọn. */
    public static String duongDan(Element node) {
        List<String> parts = new ArrayList<>();
        for (Element n = node; n != null && !(n instanceof Document); n = n.parent()) {
            String p = n.normalName();
            List<String> lop = lop(n);
            if (!n.attr("id").isEmpty()) p += "#" + n.attr("id");
            else if (!lop.isEmpty()) p += "." + String.join(".", lop);
            parts.add(p);
        }
        java.util.Collections.reverse(parts);
        return String.join(" > ", parts);
    }

    /** {nút: [C, LC, P, Q]} tính từ lá lên gốc, một lượt. C chữ, LC chữ trong link, P đoạn văn thật, Q dấu câu. */
    static IdentityHashMap<Element, int[]> thongKe(Document doc) {
        var tk = new IdentityHashMap<Element, int[]>();
        List<Element> all = new ArrayList<>(doc.getAllElements());
        for (int i = all.size() - 1; i >= 1; i--) {           // bỏ Document ở vị trí 0
            Element t = all.get(i);
            int c = 0, lc = 0, p = 0, q = 0;
            boolean laLink = t.normalName().equals("a");
            for (Node ch : t.childNodes()) {
                if (ch instanceof Element ce) {
                    int[] s = tk.get(ce);
                    if (s != null) {
                        c += s[0];
                        lc += s[1];
                        p += s[2];
                        q += s[3];
                    }
                } else if (ch instanceof TextNode tn) {
                    String txt = HtmlSach.cleanSpace(tn.getWholeText());
                    if (!txt.isEmpty()) {
                        int n = HtmlSach.len(txt);
                        c += n;
                        if (laLink) lc += n;
                        var m = DAU_CAU.matcher(txt);
                        while (m.find()) q++;
                    }
                }
            }
            if (t.normalName().equals("p") && c >= 25 && q >= 1) p++;
            tk.put(t, new int[]{c, lc, p, q});
        }
        return tk;
    }

    static double diem(int[] s) {
        double dens = (double) s[1] / Math.max(s[0], 1);
        return (s[0] - s[1]) * Math.pow(1 - dens, ALPHA) + BETA * s[2] + GAMMA * s[3];
    }

    static double heSo(Element t) {
        String lop = String.join(" ", lop(t)) + " " + t.attr("id");
        if (THUONG.matcher(lop).find()) return 1.3;
        if (PHAT.matcher(lop).find()) return 0.3;
        return 1.0;
    }

    /** Số ký tự chữ hiển thị (bỏ chữ trong script/style/noscript/template), khoảng trắng gộp. */
    public static int soChu(Element node) {
        if (node == null) return 0;
        int[] n = {0};
        node.traverse((x, d) -> {
            if (x instanceof TextNode t && x.parentNode() instanceof Element p
                    && !Set.of("script", "style", "noscript", "template").contains(p.normalName())) {
                n[0] += HtmlSach.len(HtmlSach.cleanSpace(t.getWholeText()));
            }
        });
        return n[0];
    }

    /** Nhãn ngắn của một nút: tag#id hoặc tag.lop-dau. */
    static String nhan(Element t) {
        if (!t.attr("id").isEmpty()) return t.normalName() + "#" + t.attr("id");
        List<String> lop = lop(t);
        return lop.isEmpty() ? t.normalName() : t.normalName() + "." + lop.get(0);
    }

    /** Phần tử đầu tiên (theo thứ tự tài liệu) có lớp CSS đúng bằng {@code lop}, phân biệt hoa thường. */
    static Element theoLop(Document doc, String lop) {
        for (Element e : doc.getAllElements()) if (lop(e).contains(lop)) return e;
        return null;
    }

    /**
     * Chọn khối nội dung. Cây bị sửa tại chỗ (dọn cây, bỏ khuôn).
     *
     * @param vet tuỳ chọn: được điền số chữ sau từng lớp và các bậc đi xuống cây, để giao diện
     *            vẽ lại thuật toán đã chọn thế nào. Truyền null thì không tốn gì thêm.
     */
    public static Khoi timKhoi(Document doc, String host, Set<String> khuon, Map<String, Object> vet) {
        donCay(doc);
        Element body = doc.body();
        if (vet != null) vet.put("sau_don", soChu(body));
        int nKhuon = Khuon.boKhuon(doc, khuon == null ? Set.of() : khuon);
        if (vet != null) {
            vet.put("sau_khuon", soChu(body));
            vet.put("khoi_khuon_bo", nKhuon);
            vet.put("bac", new ArrayList<Map<String, Object>>());
        }

        String sel = SELECTOR_THEO_HOST.get(host);
        if (sel != null) {
            Element node = theoLop(doc, sel.substring(1));      // chỉ hỗ trợ selector dạng ".lop"
            if (node != null && !HtmlSach.text(node, "", true).isEmpty())
                return new Khoi(node, duongDan(node), 0.0, "selector");
        }

        var tk = thongKe(doc);
        Element node = body;
        if (tk.getOrDefault(node, new int[]{0})[0] == 0) return new Khoi(body, duongDan(body), 0.0, "fallback");
        while (true) {
            List<Element> con = new ArrayList<>();
            for (Element c : node.children()) if (UNG_VIEN.contains(c.normalName()) && tk.containsKey(c)) con.add(c);
            if (con.isEmpty()) break;
            Element tot = con.get(0);
            double best = diem(tk.get(tot)) * heSo(tot);
            for (Element c : con) {                       // max() của Python giữ phần tử đầu khi hoà
                double d = diem(tk.get(c)) * heSo(c);
                if (d > best) {
                    best = d;
                    tot = c;
                }
            }
            boolean dung = diem(tk.get(tot)) * heSo(tot) < DELTA * diem(tk.get(node));
            if (vet != null) {
                @SuppressWarnings("unchecked")
                var bac = (List<Map<String, Object>>) vet.get("bac");
                bac.add(ghiBac(node, con, tot, tk, dung));
            }
            if (dung) break;
            node = tot;
        }
        if (node == body && tk.get(node)[0] == 0) return new Khoi(body, "body", 0.0, "fallback");
        return new Khoi(node, duongDan(node), diem(tk.get(node)), "heuristic");
    }

    static double lam(double x, int chuSo) {
        double k = Math.pow(10, chuSo);
        return Math.round(x * k) / k;
    }

    /** Một bậc đi xuống: nút cha, các con ứng viên (điểm cao nhất trước) và con thắng. */
    private static Map<String, Object> ghiBac(Element cha, List<Element> con, Element tot,
                                              IdentityHashMap<Element, int[]> tk, boolean dung) {
        final int toiDa = 6;
        List<Element> xep = new ArrayList<>(con);
        xep.sort((a, b) -> Double.compare(diem(tk.get(b)) * heSo(b), diem(tk.get(a)) * heSo(a)));   // ổn định, như sorted()
        List<Map<String, Object>> uv = new ArrayList<>();
        for (Element c : xep.subList(0, Math.min(toiDa, xep.size()))) {
            int[] s = tk.get(c);
            var m = new LinkedHashMap<String, Object>();
            m.put("nhan", nhan(c));
            m.put("chu", s[0]);
            m.put("chu_link", s[1]);
            m.put("doan", s[2]);
            m.put("he_so", heSo(c));
            m.put("diem", lam(diem(s) * heSo(c), 1));
            m.put("thang", c == tot);
            uv.add(m);
        }
        var b = new LinkedHashMap<String, Object>();
        b.put("cha", nhan(cha));
        b.put("diem_cha", lam(diem(tk.get(cha)), 1));
        b.put("nguong", lam(DELTA * diem(tk.get(cha)), 1));
        b.put("ung_vien", uv);
        b.put("con_lai", Math.max(0, xep.size() - toiDa));
        b.put("dung", dung);
        return b;
    }
}
