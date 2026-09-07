package vn.hust.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * HTTP mỏng bọc quanh Lucene. Dùng com.sun.net.httpserver có sẵn trong JDK nên
 * không kéo thêm framework nào — cả dịch vụ chỉ còn Lucene và Jackson.
 *
 *   POST /bulk    [{url,title,text,host,section,date}, …]   thêm/ghi đè, trả số đã nhận
 *   GET  /search?q=&from=&size=&host=                       kết quả kèm đoạn tô sáng
 *   GET  /stats                                             số tài liệu, dung lượng, theo host
 *   POST /reset                                             xoá sạch index
 *   GET  /health
 */
public class SearchServer {

    private static final ObjectMapper M = new ObjectMapper();
    private final Index index;

    public SearchServer(Index index) { this.index = index; }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("INDEX_DIR", "/index"));
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8081"));

        Index idx = new Index(dir);
        SearchServer app = new SearchServer(idx);

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.createContext("/bulk", app::bulk);
        http.createContext("/search", app::search);
        http.createContext("/stats", app::stats);
        http.createContext("/reset", app::reset);
        http.createContext("/health", (ex) -> send(ex, 200, Map.of("ok", true)));
        http.start();

        System.out.printf("Lucene sẵn sàng: cổng %d, index %s, %d tài liệu%n",
                port, dir, idx.numDocs());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { idx.close(); } catch (IOException ignored) { }
        }));
    }

    // ------------------------------------------------------------ điểm cuối
    private void bulk(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, Map.of("error", "cần POST")); return; }
        try (InputStream in = ex.getRequestBody()) {
            JsonNode arr = M.readTree(in);
            if (!arr.isArray()) { send(ex, 400, Map.of("error", "cần một mảng JSON")); return; }
            int n = 0;
            for (JsonNode d : arr) {
                Map<String, String> m = new HashMap<>();
                d.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText("")));
                index.put(m);
                n++;
            }
            index.commit();
            send(ex, 200, Map.of("indexed", n, "total", index.numDocs()));
        } catch (Exception e) {
            send(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    private void search(HttpExchange ex) throws IOException {
        Map<String, String> p = query(ex);
        String q = p.getOrDefault("q", "").trim();
        if (q.isEmpty()) { send(ex, 400, Map.of("error", "thiếu tham số q")); return; }
        int from = parseInt(p.get("from"), 0);
        int size = Math.min(parseInt(p.get("size"), 10), 50);
        try {
            Index.Result r = index.search(q, from, size, p.get("host"));
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Index.Hit h : r.hits()) {
                hits.add(new LinkedHashMap<>(Map.of(
                        "url", nz(h.url()), "title", nz(h.title()), "host", nz(h.host()),
                        "section", nz(h.section()), "date", nz(h.date()),
                        "score", h.score(), "fragments", h.fragments())));
            }
            send(ex, 200, new LinkedHashMap<>(Map.of(
                    "q", q, "total", r.total(), "took_ms", r.tookMs(),
                    "from", from, "size", size, "hits", hits)));
        } catch (Exception e) {
            // câu truy vấn sai cú pháp là lỗi của người dùng, đừng trả 500
            send(ex, 400, Map.of("error", "không phân tích được truy vấn: " + e.getMessage()));
        }
    }

    private void stats(HttpExchange ex) throws IOException {
        try {
            send(ex, 200, new LinkedHashMap<>(Map.of(
                    "docs", index.numDocs(),
                    "index_bytes", index.sizeBytes(),
                    "index_dir", index.path().toString(),
                    "by_host", index.byHost())));
        } catch (Exception e) {
            send(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    private void reset(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, Map.of("error", "cần POST")); return; }
        try {
            index.reset();
            send(ex, 200, Map.of("ok", true, "docs", index.numDocs()));
        } catch (Exception e) {
            send(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    // ------------------------------------------------------------- tiện ích
    private static String nz(String s) { return s == null ? "" : s; }

    private static int parseInt(String s, int dflt) {
        try { return s == null ? dflt : Integer.parseInt(s); } catch (NumberFormatException e) { return dflt; }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = M.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
