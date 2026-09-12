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
 *   GET  /search?q=&from=&size=&host=&date_from=&date_to=&sort=&ranking=
 *                                                          kết quả kèm đoạn tô sáng
 *   GET  /list?from=&size=&host=&sort=                    liệt kê toàn bộ, không cần q
 *   GET  /dict?field=&after=&limit=                        duyệt từ điển (term dictionary)
 *   GET  /posting?field=&term=&limit=                      posting list đầy đủ của 1 từ
 *   GET  /doc?url=                                          một tài liệu kèm HTML đã dọn
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
        http.createContext("/list", app::list);
        http.createContext("/dict", app::dict);
        http.createContext("/posting", app::posting);
        http.createContext("/doc", app::doc);
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
        int from = Math.max(parseInt(p.get("from"), 0), 0);
        int size = Math.min(Math.max(parseInt(p.get("size"), 10), 1), 50);
        String ranking = p.getOrDefault("ranking", "tfidf").trim().toLowerCase(Locale.ROOT);
        if (!ranking.equals("tfidf") && !ranking.equals("enhanced")) {
            send(ex, 400, Map.of("error", "ranking phải là tfidf hoặc enhanced"));
            return;
        }
        boolean theoNgay = "date".equalsIgnoreCase(p.get("sort"));
        try {
            Index.Result r = index.search(new Index.Truy(q, from, size, p.get("host"),
                    p.get("date_from"), p.get("date_to"), theoNgay, ranking));
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Index.Hit h : r.hits()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("url", nz(h.url()));
                m.put("title", nz(h.title()));
                m.put("host", nz(h.host()));
                m.put("section", nz(h.section()));
                m.put("date", nz(h.date()));
                m.put("score", h.score());
                m.put("fragments", h.fragments());
                m.put("duplicates", h.duplicates());
                hits.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("q", q);
            out.put("ranking", ranking);
            out.put("total", r.total());
            out.put("took_ms", r.tookMs());
            out.put("from", from);
            out.put("size", size);
            out.put("sort", theoNgay ? "date" : "score");
            out.put("hits", hits);
            send(ex, 200, out);
        } catch (Exception e) {
            // câu truy vấn sai cú pháp là lỗi của người dùng, đừng trả 500
            send(ex, 400, Map.of("error", "không phân tích được truy vấn: " + e.getMessage()));
        }
    }

    /** Liệt kê toàn bộ tài liệu, không lọc theo từ khoá — cho tab "Duyệt tất cả". */
    private void list(HttpExchange ex) throws IOException {
        Map<String, String> p = query(ex);
        int from = Math.max(parseInt(p.get("from"), 0), 0);
        int size = Math.min(Math.max(parseInt(p.get("size"), 20), 1), 200);
        String host = p.get("host");
        boolean theoUrl = "url".equalsIgnoreCase(p.get("sort"));
        try {
            Index.ListResult r = index.listAll(from, size, host, theoUrl);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Index.ListItem it : r.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("url", nz(it.url()));
                m.put("title", nz(it.title()));
                m.put("host", nz(it.host()));
                m.put("section", nz(it.section()));
                m.put("date", nz(it.date()));
                items.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", r.total());
            out.put("from", from);
            out.put("size", size);
            out.put("host", nz(host));
            out.put("sort", theoUrl ? "url" : "date");
            out.put("items", items);
            send(ex, 200, out);
        } catch (Exception e) {
            send(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    /** Duyệt từ điển chỉ mục ngược của một field, phân trang bằng con trỏ "after". */
    private void dict(HttpExchange ex) throws IOException {
        Map<String, String> p = query(ex);
        String field = p.getOrDefault("field", "text");
        String after = p.get("after");
        int limit = Math.min(Math.max(parseInt(p.get("limit"), 50), 1), 200);
        try {
            Index.DictPage r = index.tuDien(field, after, limit);
            List<Map<String, Object>> terms = new ArrayList<>();
            for (Index.TermInfo t : r.terms()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("term", t.term());
                m.put("doc_freq", t.docFreq());
                m.put("total_term_freq", t.totalTermFreq());
                terms.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("field", field);
            out.put("terms", terms);
            out.put("next", r.tiepTheo());
            send(ex, 200, out);
        } catch (Exception e) {
            send(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    /** Posting list đầy đủ của một từ: docFreq/totalTermFreq + danh sách tài liệu. */
    private void posting(HttpExchange ex) throws IOException {
        Map<String, String> p = query(ex);
        String field = p.getOrDefault("field", "text");
        String term = p.getOrDefault("term", "").trim();
        int limit = Math.min(Math.max(parseInt(p.get("limit"), 50), 1), 500);
        if (term.isEmpty()) { send(ex, 400, Map.of("error", "thiếu tham số term")); return; }
        try {
            Index.Posting r = index.layPosting(field, term, limit);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Index.PostingRow row : r.rows()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("url", nz(row.url()));
                m.put("title", nz(row.title()));
                m.put("tf", row.tf());
                m.put("positions", row.positions());
                rows.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("field", field);
            out.put("term", r.term());
            out.put("doc_freq", r.docFreq());
            out.put("total_term_freq", r.totalTermFreq());
            out.put("rows", rows);
            send(ex, 200, out);
        } catch (Exception e) {
            send(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    private void doc(HttpExchange ex) throws IOException {
        String url = query(ex).getOrDefault("url", "").trim();
        if (url.isEmpty()) { send(ex, 400, Map.of("error", "thiếu tham số url")); return; }
        try {
            Map<String, String> d = index.layTaiLieu(url);
            if (d == null) { send(ex, 404, Map.of("error", "chưa có trong index")); return; }
            send(ex, 200, d);
        } catch (Exception e) {
            send(ex, 500, Map.of("error", String.valueOf(e)));
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
