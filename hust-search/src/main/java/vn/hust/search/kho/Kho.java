package vn.hust.search.kho;

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
public class Kho {
    static final ObjectMapper JSON = new ObjectMapper();
    private final Path data;

    public Kho(Path data) {
        this.data = data;
    }

    public Path dir() {
        return data;
    }

    public List<Path> khoDirs() {
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
    static List<String> docShard(Path p) {
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
    private void duyet(Path d, java.util.function.Predicate<String> loc, java.util.function.Predicate<JsonNode> nhan) {
        for (Path p : shards(d)) {
            for (String line : docShard(p)) {
                if (line.isBlank() || !loc.test(line)) continue;
                JsonNode rec;
                try {
                    rec = JSON.readTree(line);
                } catch (JsonProcessingException e) {
                    break;
                }
                if (!nhan.test(rec)) return;
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
                    for (String line : docShard(sh.next())) {
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

    /** Mọi bản ghi của mọi kho, bỏ url trùng (theo chuỗi url thô). */
    public void tatCaBanGhi(Consumer<JsonNode> f) {
        Set<String> seen = new HashSet<>();
        for (Path d : khoDirs()) {
            for (Iterator<JsonNode> it = records(d); it.hasNext(); ) {
                JsonNode r = it.next();
                if (seen.add(r.path("url").asText())) f.accept(r);
            }
        }
    }

    /**
     * Bản ghi đầu tiên có url (đã norm) thuộc {@code urls}. Lọc thô bằng chuỗi đường dẫn trước khi
     * parse JSON: kho hàng trăm MB, parse mọi dòng thì chậm. Khoá lọc gồm cả dạng thô và dạng
     * json.dumps (thoát \\uXXXX chữ thường), vì crawler có thể ghi bằng ensure_ascii.
     */
    public JsonNode timBanGhi(Set<String> urls) {
        Set<String> dich = new HashSet<>();
        for (String u : urls) {
            String n = Url.norm(u);
            dich.add(n != null ? n : u);
        }
        Set<String> khoa = new HashSet<>();
        for (String u : dich) {
            String path = Url.pathOf(u);
            if (path.isEmpty()) path = "/";
            khoa.add(path);
            khoa.add(thoatJson(path));
        }
        JsonNode[] kq = {null};
        for (Path d : khoDirs()) {
            duyet(d, line -> khoa.stream().anyMatch(line::contains), rec -> {
                String u = rec.path("url").asText(null);
                String n = u == null ? null : Url.norm(u);
                if (dich.contains(n != null ? n : u)) {
                    kq[0] = rec;
                    return false;
                }
                return true;
            });
            if (kq[0] != null) return kq[0];
        }
        return null;
    }

    /** {@code json.dumps(s)[1:-1]} của Python (ensure_ascii): chữ Việt thành \\uXXXX thường. */
    static String thoatJson(String s) {
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
    public synchronized void ghiKho(JsonNode rec) throws IOException {
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
    public static String giaiMa(JsonNode rec) {
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
