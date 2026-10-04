package vn.hust.search.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Kho thô của crawler: các thư mục {@code raw*} trong {@code DATA_DIR}, mỗi thư mục nhiều shard
 * {@code pages-*.jsonl[.gz]}, mỗi dòng một trang với HTML nằm ở {@code html_b64}.
 * Bản port của {@code main.py: kho_dirs / records / tim_ban_ghi / tat_ca_ban_ghi / _ghi_kho}.
 */
public class RawStore {
    public static final ObjectMapper JSON = new ObjectMapper();
    private final Path data;

    public RawStore(Path data) {
        this.data = data;
    }

    public Path dir() {
        return data;
    }

    public List<Path> storeDirs() {
        try (Stream<Path> s = Files.list(data)) {
            return s.filter(p -> p.getFileName().toString().startsWith("raw") && Files.isDirectory(p))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();                      // chưa có kho
        }
    }

    public static String hostOf(Path d) {
        String n = d.getFileName().toString();
        return n.equals("raw") ? "hust.edu.vn" : n.substring(4);
    }

    static List<Path> shards(Path d) {
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(p -> p.getFileName().toString().matches("pages-.*\\.jsonl(\\.gz)?")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Các dòng của một shard. Đọc hết byte đã giải nén được TRƯỚC khi giải mã chữ: để
     * InputStreamReader đọc thẳng từ gzip cụt thì EOFException làm mất cả khối đệm chưa trả.
     * Shard cụt/hỏng thì trả phần đã đọc được (dòng cuối có thể dở, parse JSON sẽ hỏng và bị bỏ).
     */
    static List<String> readShard(Path p) {
        byte[] raw;
        var out = new ByteArrayOutputStream();
        try (InputStream in = Files.newInputStream(p)) {
            InputStream src = p.toString().endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in;  // nhiều member cũng được
            byte[] buf = new byte[1 << 16];
            for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (IOException e) {
            // shard đang ghi dở hoặc hỏng: giữ phần đã đọc
        }
        raw = out.toByteArray();
        return List.of(new String(raw, StandardCharsets.UTF_8).split("\n"));
    }

    /**
     * Duyệt từng dòng của từng shard. Dòng JSON hỏng thì bỏ nốt shard đó. {@code loc} trả false
     * thì bỏ qua dòng mà không parse. {@code nhan} trả false thì dừng hẳn.
     */
    private void scan(Path d, java.util.function.Predicate<String> prefilter, java.util.function.Predicate<JsonNode> accept) {
        for (Path p : shards(d)) {
            for (String line : readShard(p)) {
                if (line.isBlank() || !prefilter.test(line)) continue;
                JsonNode rec;
                try {
                    rec = JSON.readTree(line);
                } catch (JsonProcessingException e) {
                    break;
                }
                if (!accept.test(rec)) return;
            }
        }
    }

    /** Đọc lười từng shard (mỗi lần nạp một shard vào bộ nhớ) mọi bản ghi của một kho. */
    public Iterator<JsonNode> records(Path d) {
        List<JsonNode> buf = new ArrayList<>();
        Iterator<Path> sh = shards(d).iterator();
        return new Iterator<>() {
            int i = 0;

            @Override
            public boolean hasNext() {
                while (i >= buf.size()) {
                    if (!sh.hasNext()) return false;
                    buf.clear();
                    i = 0;
                    for (String line : readShard(sh.next())) {
                        if (line.isBlank()) continue;
                        try {
                            buf.add(JSON.readTree(line));
                        } catch (JsonProcessingException e) {
                            break;
                        }
                    }
                }
                return true;
            }

            @Override
            public JsonNode next() {
                if (!hasNext()) throw new NoSuchElementException();
                return buf.get(i++);
            }
        };
    }

    /** Mọi bản ghi của mọi kho, bỏ url trùng (theo chuỗi url thô). Đọc lười, dừng sớm được. */
    public Iterator<JsonNode> allRecords() {
        Set<String> seen = new HashSet<>();
        Iterator<Path> dirs = storeDirs().iterator();
        return new Iterator<>() {
            Iterator<JsonNode> cur = Collections.emptyIterator();
            JsonNode pending;

            @Override
            public boolean hasNext() {
                while (pending == null) {
                    while (!cur.hasNext()) {
                        if (!dirs.hasNext()) return false;
                        cur = records(dirs.next());
                    }
                    JsonNode r = cur.next();
                    if (seen.add(r.path("url").asText())) pending = r;
                }
                return true;
            }

            @Override
            public JsonNode next() {
                if (!hasNext()) throw new NoSuchElementException();
                JsonNode r = pending;
                pending = null;
                return r;
            }
        };
    }

    public void forEachRecord(Consumer<JsonNode> f) {
        allRecords().forEachRemaining(f);
    }

    /**
     * Bản ghi đầu tiên có url (đã norm) thuộc {@code urls}. Lọc thô bằng chuỗi đường dẫn trước khi
     * parse JSON: kho hàng trăm MB, parse mọi dòng thì chậm. Khoá lọc gồm cả dạng thô và dạng
     * json.dumps (thoát \\uXXXX chữ thường), vì crawler có thể ghi bằng ensure_ascii.
     */
    public JsonNode findRecord(Set<String> urls) {
        Set<String> target = new HashSet<>();
        for (String u : urls) {
            String n = Url.norm(u);
            target.add(n != null ? n : u);
        }
        Set<String> keys = new HashSet<>();
        for (String u : target) {
            String path = Url.pathOf(u);
            if (path.isEmpty()) path = "/";
            keys.add(path);
            keys.add(jsonEscape(path));
        }
        JsonNode[] found = {null};
        for (Path d : storeDirs()) {
            scan(d, line -> keys.stream().anyMatch(line::contains), rec -> {
                String u = rec.path("url").asText(null);
                String n = u == null ? null : Url.norm(u);
                if (target.contains(n != null ? n : u)) {
                    found[0] = rec;
                    return false;
                }
                return true;
            });
            if (found[0] != null) return found[0];
        }
        return null;
    }

    /** {@code json.dumps(s)[1:-1]} của Python (ensure_ascii): chữ Việt thành \\uXXXX thường. */
    static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c >= ' ' && c <= '~') b.append(c);
                    else b.append(String.format("\\u%04x", (int) c));
                }
            }
        }
        return b.toString();
    }

    /**
     * Ghi một bản ghi vào kho riêng {@code raw-adhoc}, không đụng kho của crawler (crawl_all.py giữ
     * state.json của từng host mở suốt mẻ). Mở nối thêm: mỗi lần ghi thêm một gzip member, đọc lại
     * được bình thường. Đóng stream sau mỗi dòng nên dòng nào ghi xong là còn nguyên khi kill cứng.
     */
    public synchronized void append(JsonNode rec) throws IOException {
        Path adhoc = data.resolve("raw-adhoc");
        Files.createDirectories(adhoc);
        try (var out = Files.newOutputStream(adhoc.resolve("pages-0001.jsonl.gz"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
             var w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(out), StandardCharsets.UTF_8))) {
            w.write(JSON.writeValueAsString(rec));
            w.write('\n');
        }
    }

    /** HTML của bản ghi; null nếu không có thân hoặc status ≥ 400. Bảng mã lạ thì lùi về UTF-8. */
    public static String decodeHtml(JsonNode rec) {
        String b64 = rec.path("html_b64").asText("");
        if (b64.isEmpty() || rec.path("status").asInt(0) >= 400) return null;
        byte[] raw = Base64.getDecoder().decode(b64);
        String enc = rec.path("encoding").asText("");
        java.nio.charset.Charset cs = StandardCharsets.UTF_8;
        if (!enc.isEmpty()) {
            try {
                cs = java.nio.charset.Charset.forName(enc);
            } catch (IllegalArgumentException e) {
                // tên bảng mã lạ: dùng UTF-8
            }
        }
        return new String(raw, cs);
    }
}
