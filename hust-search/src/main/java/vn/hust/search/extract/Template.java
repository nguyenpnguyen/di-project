package vn.hust.search.extract;

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
public final class Template {
    private Template() {}

    /** Khối "lá": thẻ khối không chứa thẻ khối nào khác bên trong. */
    static final Set<String> BLOCK_TAGS = Set.of("p", "li", "td", "th", "div", "dt", "dd", "address", "h1", "h2",
            "h3", "h4", "h5", "h6", "blockquote", "figcaption");
    public static final double PAGE_RATIO_THRESHOLD = 0.30;   // khối có mặt trên > 30% số trang thì là khuôn
    public static final int MIN_PAGES = 20;     // host ít hơn số này thì không đủ mẫu để kết luận

    private static final Pattern NUMBER = Pattern.compile("\\d+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Bảng đếm của một host: số trang và số trang chứa từng vân tay. */
    public record Counts(int nPages, Map<String, Integer> blocks) {}

    public record LeafBlock(Element node, String text) {}

    public static String fingerprint(String text) {
        String t = HtmlUtil.cleanSpace(text).toLowerCase(Locale.ROOT);
        t = NUMBER.matcher(t).replaceAll("0");
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
    public static List<LeafBlock> leafBlocks(Document doc) {
        List<LeafBlock> out = new ArrayList<>();
        for (Element t : doc.getAllElements()) {
            if (!BLOCK_TAGS.contains(t.normalName()) || hasBlockChild(t)) continue;
            String text = HtmlUtil.WS.matcher(HtmlUtil.textBs4(t)).replaceAll(" ");
            if (HtmlUtil.len(text) >= 2) out.add(new LeafBlock(t, text));
        }
        return out;
    }

    private static boolean hasBlockChild(Element t) {
        for (Element c : HtmlUtil.descendants(t)) if (BLOCK_TAGS.contains(c.normalName())) return true;
        return false;
    }

    public static Set<String> pageFingerprints(Document doc) {
        Set<String> s = new HashSet<>();
        for (LeafBlock k : leafBlocks(doc)) s.add(fingerprint(k.text()));
        return s;
    }

    public static Counts countByHost(List<Set<String>> fingerprintSets) {
        Map<String, Integer> counts = new HashMap<>();
        for (Set<String> s : fingerprintSets) for (String v : s) counts.merge(v, 1, Integer::sum);
        return new Counts(fingerprintSets.size(), counts);
    }

    /** Tập vân tay bị coi là khuôn; rỗng nếu host chưa đủ mẫu. */
    public static Set<String> templateSet(Counts counts) {
        Set<String> out = new HashSet<>();
        if (counts == null || counts.nPages() < MIN_PAGES) return out;
        double cutoff = PAGE_RATIO_THRESHOLD * counts.nPages();
        counts.blocks().forEach((v, n) -> {
            if (n > cutoff) out.add(v);
        });
        return out;
    }

    /** Xoá các khối lá thuộc khuôn khỏi cây. Trả về số khối đã xoá. */
    public static int removeTemplate(Document doc, Set<String> templateFps) {
        if (templateFps == null || templateFps.isEmpty()) return 0;
        int n = 0;
        for (LeafBlock k : leafBlocks(doc)) {
            if (templateFps.contains(fingerprint(k.text()))) {
                k.node().remove();
                n++;
            }
        }
        return n;
    }
}
