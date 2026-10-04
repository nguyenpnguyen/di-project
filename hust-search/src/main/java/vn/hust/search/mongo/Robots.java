package vn.hust.search.mongo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import vn.hust.search.extract.HtmlUtil;
import vn.hust.search.store.Url;

/**
 * robots.txt theo đúng luật của {@code urllib.robotparser} (Python 3.10): trong một nhóm, dòng khớp
 * ĐẦU TIÊN thắng — khác luật khớp-dài-nhất của Google. Không tải được robots.txt thì dùng
 * {@code parse(List.of())} nghĩa là cho phép tất cả.
 */
public final class Robots {
    private record Rule(String path, boolean allow) {
        boolean matches(String f) {
            return path.equals("*") || f.startsWith(path);
        }
    }

    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();

        boolean appliesTo(String ua) {
            ua = ua.split("/", -1)[0].toLowerCase(Locale.ROOT);
            for (String a : agents) {
                if (a.equals("*")) return true;
                if (ua.contains(a.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }

        boolean allows(String f) {
            for (Rule d : rules) if (d.matches(f)) return d.allow;
            return true;
        }
    }

    private final List<Group> groups = new ArrayList<>();
    private Group defaultGroup;

    public static Robots parse(List<String> lines) {
        Robots r = new Robots();
        int state = 0;
        Group e = new Group();
        for (String line : lines) {
            if (line.isEmpty()) {
                if (state == 1) { e = new Group(); state = 0; }
                else if (state == 2) { r.addGroup(e); e = new Group(); state = 0; }
            }
            int i = line.indexOf('#');
            if (i >= 0) line = line.substring(0, i);
            line = HtmlUtil.strip(line);
            if (line.isEmpty()) continue;
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = HtmlUtil.strip(line.substring(0, c)).toLowerCase(Locale.ROOT);
            String v = unquote(HtmlUtil.strip(line.substring(c + 1)));
            switch (k) {
                case "user-agent" -> {
                    if (state == 2) { r.addGroup(e); e = new Group(); }
                    e.agents.add(v);
                    state = 1;
                }
                case "disallow", "allow" -> {
                    if (state != 0) {
                        boolean allow = k.equals("allow");
                        if (v.isEmpty() && !allow) allow = true;          // Disallow: rỗng = cho phép tất cả
                        e.rules.add(new Rule(quote(v), allow));
                        state = 2;
                    }
                }
                default -> {}
            }
        }
        if (state == 2) r.addGroup(e);
        return r;
    }

    private void addGroup(Group e) {
        if (e.agents.contains("*")) {
            if (defaultGroup == null) defaultGroup = e;                             // nhóm * đầu tiên thắng
        } else {
            groups.add(e);
        }
    }

    public boolean canFetch(String ua, String url) {
        String f = quote(Url.afterHost(unquote(url)));
        if (f.isEmpty()) f = "/";
        for (Group e : groups) if (e.appliesTo(ua)) return e.allows(f);
        return defaultGroup == null || defaultGroup.allows(f);
    }

    /** {@code urllib.parse.unquote}: %XX là byte UTF-8, hỏng thì thay bằng U+FFFD. */
    public static String unquote(String s) {
        if (s.indexOf('%') < 0) return s;
        var out = new ByteArrayOutputStream();
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '%' && i + 2 < b.length + 0 && hex(b[i + 1]) >= 0 && hex(b[i + 2]) >= 0) {
                out.write(hex(b[i + 1]) * 16 + hex(b[i + 2]));
                i += 2;
            } else {
                out.write(b[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte c) {
        return Character.digit((char) c, 16);
    }

    /** {@code urllib.parse.quote} với safe='/': chỉ giữ chữ-số ASCII, _ . - ~ và /. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || c == '-' || c == '~' || c == '/') sb.append((char) c);
            else sb.append(String.format("%%%02X", c));
        }
        return sb.toString();
    }
}
