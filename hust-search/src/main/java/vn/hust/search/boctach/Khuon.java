package vn.hust.search.boctach;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Lớp 2 của thuật toán khối nội dung: khử khuôn mẫu theo cả host (bản port của {@code khuon.py}).
 * Menu, footer, banner lặp gần như nguyên xi trên mọi trang cùng site; khối chữ xuất hiện trên quá
 * nhiều trang của một host thì là khuôn. Dùng vân tay văn bản chứ không dựng cây kiểu Site Style Tree.
 */
public final class Khuon {
    private Khuon() {}

    /** Khối "lá": thẻ khối không chứa thẻ khối nào khác bên trong. */
    static final Set<String> THE_KHOI = Set.of("p", "li", "td", "th", "div", "dt", "dd", "address", "h1", "h2",
            "h3", "h4", "h5", "h6", "blockquote", "figcaption");
    public static final double NGUONG_TRANG = 0.30;   // khối có mặt trên > 30% số trang thì là khuôn
    public static final int TOI_THIEU_TRANG = 20;     // host ít hơn số này thì không đủ mẫu để kết luận

    private static final Pattern SO = Pattern.compile("\\d+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Bảng đếm của một host: số trang và số trang chứa từng vân tay. */
    public record Bang(int nPages, Map<String, Integer> blocks) {}

    public record KhoiLa(Element node, String text) {}

    public static String vanTay(String text) {
        String t = HtmlSach.cleanSpace(text).toLowerCase(Locale.ROOT);
        t = SO.matcher(t).replaceAll("0");
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(t.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Các khối lá có chữ (từ 2 ký tự), theo thứ tự tài liệu. */
    public static List<KhoiLa> khoiLa(Document doc) {
        List<KhoiLa> out = new ArrayList<>();
        for (Element t : doc.getAllElements()) {
            if (!THE_KHOI.contains(t.normalName()) || coKhoiCon(t)) continue;
            String text = HtmlSach.WS.matcher(HtmlSach.textBs4(t)).replaceAll(" ");
            if (HtmlSach.len(text) >= 2) out.add(new KhoiLa(t, text));
        }
        return out;
    }

    private static boolean coKhoiCon(Element t) {
        for (Element c : HtmlSach.conChau(t)) if (THE_KHOI.contains(c.normalName())) return true;
        return false;
    }

    public static Set<String> vanTayTrang(Document doc) {
        Set<String> s = new HashSet<>();
        for (KhoiLa k : khoiLa(doc)) s.add(vanTay(k.text()));
        return s;
    }

    public static Bang demHost(List<Set<String>> tapVanTay) {
        Map<String, Integer> dem = new HashMap<>();
        for (Set<String> s : tapVanTay) for (String v : s) dem.merge(v, 1, Integer::sum);
        return new Bang(tapVanTay.size(), dem);
    }

    /** Tập vân tay bị coi là khuôn; rỗng nếu host chưa đủ mẫu. */
    public static Set<String> tapKhuon(Bang bang) {
        Set<String> out = new HashSet<>();
        if (bang == null || bang.nPages() < TOI_THIEU_TRANG) return out;
        double can = NGUONG_TRANG * bang.nPages();
        bang.blocks().forEach((v, n) -> {
            if (n > can) out.add(v);
        });
        return out;
    }

    /** Xoá các khối lá thuộc khuôn khỏi cây. Trả về số khối đã xoá. */
    public static int boKhuon(Document doc, Set<String> khuon) {
        if (khuon == null || khuon.isEmpty()) return 0;
        int n = 0;
        for (KhoiLa k : khoiLa(doc)) {
            if (khuon.contains(vanTay(k.text()))) {
                k.node().remove();
                n++;
            }
        }
        return n;
    }
}
