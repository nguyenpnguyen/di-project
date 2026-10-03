package vn.hust.search.kho;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chuẩn hoá url: bản sao của {@code crawl_all.py} (norm, dedup_key, kind_of, page_of).
 *
 * <p>Sửa bên này thì sửa bên kia và sinh lại {@code golden/url.jsonl.gz}
 * ({@code UrlGoldenTest} đòi khớp 100%). Hai bên phải cho ra cùng một chuỗi: crawler dùng
 * {@code norm()} làm khoá hàng đợi, lệch một ký tự là "thiếu link" giả.
 *
 * <p>{@code java.net.URI} ném lỗi trên dấu cách, chữ có dấu, '|'... còn {@code urllib.parse}
 * thì dễ dãi, nên urlsplit/urljoin/urlunparse của Python được chép tay ở đây (bản 3.10).
 */
public final class Url {
    private Url() {}

    public static final String BASE = "https://hust.edu.vn";
    public static final String UA =
            "hust-research-crawler/1.0 (nghien cuu mon IT5420; lien he: student@sis.hust.edu.vn)";

    // (?d): chỉ '\n' là dấu xuống dòng, như Python; UNICODE_CHARACTER_CLASS: \d khớp mọi chữ số Unicode
    private static final int F = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern ART_ID = Pattern.compile("-(\\d+)\\.html$", F);
    private static final List<Pattern> PAGE_PATTERNS = List.of(
            Pattern.compile("^(?<a>.*/)page-(?<n>\\d+)/?$", F),                      // NukeViet
            Pattern.compile("^(?<a>.*/)trang-(?<n>\\d+)/?$", F),
            Pattern.compile("^(?<a>.*/)p(?<n>\\d+)/?$", F),
            Pattern.compile("^(?<a>.*?[?&])page=(?<n>\\d+)$", F),                    // WordPress, query
            Pattern.compile("^(?<a>.*?[?&])paged=(?<n>\\d+)$", F),
            Pattern.compile("^(?<a>.*/)page/(?<n>\\d+)/?$", F));                     // WordPress, path
    private static final List<String> PAGE_TPL = List.of(
            "{a}page-{n}/", "{a}trang-{n}/", "{a}p{n}/", "{a}page={n}", "{a}paged={n}", "{a}page/{n}/");
    private static final Set<String> QUERY_RAC =
            Set.of("fbclid", "utm_source", "utm_medium", "utm_campaign", "gidzl", "PHPSESSID");

    /** Khuôn có {n}, số trang, gốc (phần url trước chỗ đánh số). */
    public record Page(String tpl, long n, String root) {}

    public static Page pageOf(String url) {
        for (int i = 0; i < PAGE_PATTERNS.size(); i++) {
            Matcher m = PAGE_PATTERNS.get(i).matcher(url);
            if (m.find()) {                       // mẫu đã neo bằng ^
                String a = m.group("a");
                return new Page(PAGE_TPL.get(i).replace("{a}", a), Long.parseLong(m.group("n")), a);
            }
        }
        return null;
    }

    public static String norm(String url) {
        return norm(url, null);
    }

    /** null nếu không phải http(s). Python raise ValueError ở vài url hỏng; ở đây cũng trả null. */
    public static String norm(String url, String base) {
        if (base == null || base.isEmpty()) base = BASE;
        if (url == null || url.isEmpty() || url.startsWith("javascript:") || url.startsWith("mailto:")
                || url.startsWith("tel:") || url.startsWith("#")) return null;
        Parts p;
        try {
            p = urlparse(urljoin(base, pyStrip(url)));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!p.scheme.equals("http") && !p.scheme.equals("https")) return null;
        String path = p.path.replaceAll("//+", "/");
        StringBuilder q = new StringBuilder();
        for (String kv : p.query.split("&", -1)) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            if (QUERY_RAC.contains(eq < 0 ? kv : kv.substring(0, eq))) continue;
            if (q.length() > 0) q.append('&');
            q.append(kv);
        }
        // netloc.lower().replace("www.", "") thay MỌI chỗ, kể cả cổng — chép đúng, khớp crawler quan trọng hơn
        String netloc = p.netloc.toLowerCase(Locale.ROOT).replace("www.", "");
        return urlunsplit("https", netloc, path, q.toString(), "");
    }

    public static String dedupKey(String url) {
        String p = urlparse(url).path;
        if (!ART_ID.matcher(p).find()) return null;
        int end = p.length();
        while (end > 0 && p.charAt(end - 1) == '/') end--;
        String s = p.substring(0, end);
        return s.substring(s.lastIndexOf('/') + 1);
    }

    public static String kindOf(String url) {
        if (pageOf(url) != null) return "listing-page";
        String p = urlparse(url).path;
        if (ART_ID.matcher(p).find()) return "article";
        return p.endsWith("/") ? "listing" : "other";
    }

    /** Đường dẫn của url, như {@code urlparse(url).path} (đã tách ;params). */
    public static String pathOf(String url) {
        return urlparse(url).path;
    }

    // ------------------------------------------------------- urllib.parse (Python 3.10)
    private static final String SCHEME_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-.";
    private static final Set<String> USES_RELATIVE = Set.of("", "ftp", "http", "gopher", "nntp", "imap", "wais",
            "file", "https", "shttp", "mms", "prospero", "rtsp", "rtspu", "sftp", "svn", "svn+ssh", "ws", "wss");
    private static final Set<String> USES_NETLOC = Set.of("", "ftp", "http", "gopher", "nntp", "telnet", "imap",
            "wais", "file", "mms", "https", "shttp", "snews", "prospero", "rtsp", "rtspu", "rsync", "svn",
            "svn+ssh", "sftp", "nfs", "git", "git+ssh", "ws", "wss");
    private static final Set<String> USES_PARAMS = Set.of("", "ftp", "hdl", "prospero", "http", "imap", "https",
            "shttp", "rtsp", "rtspu", "sip", "sips", "mms", "sftp", "tel");

    record Parts(String scheme, String netloc, String path, String params, String query, String fragment) {}

    /** str.strip() của Python: bỏ mọi khoảng trắng Unicode ở hai đầu. */
    private static String pyStrip(String s) {
        int a = 0, b = s.length();
        while (a < b && isPySpace(s.charAt(a))) a++;
        while (b > a && isPySpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isPySpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'
                || (c >= 0x1c && c <= 0x1f);
    }

    static Parts urlsplit(String url, String scheme) {
        // bỏ ký tự điều khiển / khoảng trắng đầu chuỗi và \t \r \n ở mọi chỗ
        int i0 = 0;
        while (i0 < url.length() && url.charAt(i0) <= 0x20) i0++;
        url = url.substring(i0).replace("\t", "").replace("\r", "").replace("\n", "");
        String netloc = "", query = "", fragment = "";
        int i = url.indexOf(':');
        if (i > 0) {
            boolean ok = true;
            for (int k = 0; k < i; k++) {
                if (SCHEME_CHARS.indexOf(url.charAt(k)) < 0) { ok = false; break; }
            }
            if (ok) {
                scheme = url.substring(0, i).toLowerCase(Locale.ROOT);
                url = url.substring(i + 1);
            }
        }
        if (url.startsWith("//")) {
            int end = url.length();
            for (char c : new char[]{'/', '?', '#'}) {
                int d = url.indexOf(c, 2);
                if (d >= 0 && d < end) end = d;
            }
            netloc = url.substring(2, end);
            url = url.substring(end);
            if ((netloc.contains("[") && !netloc.contains("]")) || (netloc.contains("]") && !netloc.contains("[")))
                throw new IllegalArgumentException("Invalid IPv6 URL");
        }
        int h = url.indexOf('#');
        if (h >= 0) {
            fragment = url.substring(h + 1);
            url = url.substring(0, h);
        }
        int qm = url.indexOf('?');
        if (qm >= 0) {
            query = url.substring(qm + 1);
            url = url.substring(0, qm);
        }
        return new Parts(scheme, netloc, url, "", query, fragment);
    }

    static Parts urlparse(String url) {
        return urlparse(url, "");
    }

    static Parts urlparse(String url, String scheme) {
        Parts s = urlsplit(url, scheme);
        String path = s.path, params = "";
        if (USES_PARAMS.contains(s.scheme) && path.indexOf(';') >= 0) {
            int i = path.indexOf(';', Math.max(path.lastIndexOf('/'), 0));
            if (path.indexOf('/') < 0) i = path.indexOf(';');
            if (i >= 0) {
                params = path.substring(i + 1);
                path = path.substring(0, i);
            }
        }
        return new Parts(s.scheme, s.netloc, path, params, s.query, s.fragment);
    }

    static String urlunsplit(String scheme, String netloc, String url, String query, String fragment) {
        if (!netloc.isEmpty() || (!scheme.isEmpty() && USES_NETLOC.contains(scheme) && !url.startsWith("//"))) {
            if (!url.isEmpty() && url.charAt(0) != '/') url = "/" + url;
            url = "//" + netloc + url;
        }
        if (!scheme.isEmpty()) url = scheme + ":" + url;
        if (!query.isEmpty()) url = url + "?" + query;
        if (!fragment.isEmpty()) url = url + "#" + fragment;
        return url;
    }

    static String urlunparse(Parts p) {
        String url = p.params.isEmpty() ? p.path : p.path + ";" + p.params;
        return urlunsplit(p.scheme, p.netloc, url, p.query, p.fragment);
    }

    static String urljoin(String base, String url) {
        if (base.isEmpty()) return url;
        if (url.isEmpty()) return base;
        Parts b = urlparse(base);
        Parts u = urlparse(url, b.scheme);
        if (!u.scheme.equals(b.scheme) || !USES_RELATIVE.contains(u.scheme)) return url;
        String netloc = u.netloc, path = u.path, params = u.params, query = u.query;
        if (USES_NETLOC.contains(u.scheme)) {
            if (!netloc.isEmpty()) return urlunparse(u);
            netloc = b.netloc;
        }
        if (path.isEmpty() && params.isEmpty()) {
            path = b.path;
            params = b.params;
            if (query.isEmpty()) query = b.query;
            return urlunparse(new Parts(u.scheme, netloc, path, params, query, u.fragment));
        }
        List<String> baseParts = new java.util.ArrayList<>(List.of(b.path.split("/", -1)));
        if (!baseParts.get(baseParts.size() - 1).isEmpty()) baseParts.remove(baseParts.size() - 1);
        List<String> segments = new java.util.ArrayList<>();
        if (path.startsWith("/")) {
            segments.addAll(List.of(path.split("/", -1)));
        } else {
            segments.addAll(baseParts);
            segments.addAll(List.of(path.split("/", -1)));
            // bỏ phần tử rỗng ở giữa để ghép lại không sinh "//"
            if (segments.size() > 2) {
                List<String> gon = new java.util.ArrayList<>();
                gon.add(segments.get(0));
                for (String s : segments.subList(1, segments.size() - 1)) if (!s.isEmpty()) gon.add(s);
                gon.add(segments.get(segments.size() - 1));
                segments = gon;
            }
        }
        List<String> resolved = new java.util.ArrayList<>();
        for (String seg : segments) {
            if (seg.equals("..")) {
                if (!resolved.isEmpty()) resolved.remove(resolved.size() - 1);
            } else if (!seg.equals(".")) {
                resolved.add(seg);
            }
        }
        String last = segments.get(segments.size() - 1);
        if (last.equals(".") || last.equals("..")) resolved.add("");
        String joined = String.join("/", resolved);
        return urlunparse(new Parts(u.scheme, netloc, joined.isEmpty() ? "/" : joined, params, query, u.fragment));
    }
}
