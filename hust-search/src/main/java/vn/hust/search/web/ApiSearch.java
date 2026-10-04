package vn.hust.search.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bson.Document;
import vn.hust.search.Index;
import vn.hust.search.extract.Extractor;
import vn.hust.search.extract.HtmlUtil;
import vn.hust.search.store.RawStore;
import vn.hust.search.store.Download.Response;
import vn.hust.search.store.Url;
import vn.hust.search.mongo.Pipeline;

/**
 * Tìm kiếm, chỉ mục và tải lẻ: {@code /api/search}, {@code /api/index/*}, {@code /api/preview},
 * {@code /api/fetch}, {@code /api/health}, {@code /api/stats}. Bản port của {@code main.py}; Lucene
 * giờ chạy cùng tiến trình nên gọi thẳng {@link Index} thay vì chuyển tiếp HTTP.
 * Lỗi của Lucene (câu truy vấn sai cú pháp, thiếu q) giữ khoá {@code "error"} như trước vì giao diện
 * đọc {@code d.error} ở các tab tìm kiếm; mọi lỗi còn lại là {@code "detail"}.
 */
public final class ApiSearch {
    private static final Pattern DATE_ANY = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");
    private static final Pattern DATE_ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Index idx;
    private final RawStore store;
    private final Mongo mongo;
    private final Function<String, Response> downloader;
    private final ApiCrawl crawl;
    private final Path data;

    public ApiSearch(Index idx, RawStore store, Mongo mongo, Function<String, Response> downloader, ApiCrawl crawl) {
        this.idx = idx;
        this.store = store;
        this.mongo = mongo;
        this.downloader = downloader;
        this.crawl = crawl;
        this.data = store.dir();
    }

    public void register(Http h) {
        h.get("/api/stats", r -> stats());
        h.get("/api/health", r -> health());
        h.post("/api/index/run", this::indexRun);
        h.post("/api/index/documents", this::indexDocuments);
        h.get("/api/index/stats", r -> indexStats());
        h.get("/api/search", this::search);
        h.get("/api/index/list", this::list);
        h.get("/api/index/dict", this::dict);
        h.get("/api/index/posting", this::posting);
        h.post("/api/fetch", this::fetch);
        h.get("/api/preview", this::preview);
    }

    // ------------------------------------------------------------------ kho và sức khoẻ
    Map<String, Object> stats() throws IOException {
        List<Map<String, Object>> sites = new ArrayList<>();
        long totalPages = 0, totalBytes = 0;
        for (Path d : store.storeDirs()) {
            JsonNode s = RawStore.JSON.createObjectNode();
            Path st = d.resolve("state.json");
            if (Files.exists(st)) s = RawStore.JSON.readTree(st.toFile());
            long size = 0;
            int nShard = 0;
            try (Stream<Path> fs = Files.list(d)) {
                for (Path f : (Iterable<Path>) fs::iterator) {
                    String n = f.getFileName().toString();
                    if (n.startsWith("pages-") && n.contains(".jsonl")) {
                        nShard++;
                        size += Files.size(f);
                    }
                }
            }
            totalPages += s.path("done").size();
            totalBytes += size;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", RawStore.hostOf(d));
            m.put("done", s.path("done").size());
            m.put("queued", s.path("frontier").size());
            m.put("articles", s.path("by_key").size());
            m.put("shards", nShard);
            m.put("bytes", size);
            sites.add(m);
        }
        sites.sort((a, b) -> Integer.compare((int) b.get("done"), (int) a.get("done")));
        Path linksFile = null;
        try (Stream<Path> fs = Files.list(data)) {
            linksFile = fs.filter(p -> p.getFileName().toString().contains("links") && !p.getFileName().toString().contains(".")
                    && Files.isRegularFile(p)).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            // chưa có thư mục dữ liệu
        }
        long links = 0;
        if (linksFile != null) try (Stream<String> lines = Files.lines(linksFile)) {
            links = lines.filter(l -> !l.isBlank()).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sites", sites);
        out.put("total_pages", totalPages);
        out.put("total_bytes", totalBytes);
        out.put("links_file", linksFile == null ? null : linksFile.getFileName().toString());
        out.put("links", links);
        return out;
    }

    Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("api", true);
        m.put("lucene", true);                      // cùng tiến trình: API sống thì Lucene sống
        m.put("mongo", mongo.opt() != null);
        m.put("crawler", crawl.isReachable());
        m.put("data_dir", data.toString());
        return m;
    }

    Map<String, Object> indexStats() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs", idx.numDocs());
        m.put("index_bytes", idx.sizeBytes());
        m.put("index_dir", idx.path().toString());
        m.put("by_host", idx.byHost());
        return m;
    }

    // ------------------------------------------------------------------ chỉ mục
    private static final class StopSignal extends RuntimeException {
        StopSignal() {
            super(null, null, false, false);
        }
    }

    /** Gom tài liệu thành mẻ rồi ghi vào Lucene; ném {@link StopSignal} khi đủ {@code limit}. */
    private final class BatchWriter implements Consumer<Map<String, String>> {
        final List<Map<String, String>> buf = new ArrayList<>();
        final int batch, limit;
        int sent;

        BatchWriter(int batch, int limit) {
            this.batch = batch;
            this.limit = limit;
        }

        @Override
        public void accept(Map<String, String> d) {
            buf.add(d);
            if (buf.size() >= batch) flush();
            if (limit > 0 && sent + buf.size() >= limit) throw new StopSignal();
        }

        void flush() {
            if (buf.isEmpty()) return;
            try {
                for (var d : buf) idx.put(d);
                idx.commit();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            sent += buf.size();
            buf.clear();
        }
    }

    static Map<String, String> luceneDoc(Extractor.Extraction k) {
        Map<String, String> m = new HashMap<>();
        m.put("url", k.url());
        m.put("title", k.title());
        m.put("text", k.text());
        m.put("host", k.host());
        m.put("section", k.section());
        m.put("date", k.date());
        m.put("html", k.html());
        m.put("author", k.author());
        m.put("kind", "page");
        return m;
    }

    Object indexRun(Http.Req r) throws IOException {
        JsonNode b = r.body();
        int limit = Http.bodyInt(b, "limit", 0, 0, Integer.MAX_VALUE);
        String source = Http.bodyStr(b, "source", "auto");
        int batch = Http.bodyInt(b, "batch", 200, 1, 1000);
        boolean reset = Http.bodyBool(b, "reset", false);
        if (!Set.of("auto", "mongo", "raw").contains(source)) throw new HttpError(400, "source phải là auto, mongo hoặc raw");
        MongoDatabase d = null;
        try {
            d = mongo.get();
        } catch (HttpError e) {
            if (source.equals("mongo")) throw e;
        }
        boolean fromMongo = d != null && (source.equals("mongo")
                || source.equals("auto") && d.getCollection("pages").countDocuments(new Document(), new CountOptions().limit(1)) > 0);
        if (reset) idx.reset();
        BatchWriter writer = new BatchWriter(batch, limit);
        try {
            if (fromMongo) {
                Pipeline.luceneFromMongo(d, writer);
            } else {
                for (var it = store.allRecords(); it.hasNext(); ) {
                    JsonNode rec = it.next();
                    String html = RawStore.decodeHtml(rec);
                    if (html == null) continue;
                    Extractor.Extraction k = Extractor.extract(html, rec.path("url").asText(), null);
                    if (k != null) writer.accept(luceneDoc(k));
                }
                // Chữ của tệp tài liệu chỉ có trong Mongo: thiếu bước này thì dựng lại từ kho thô xoá mất mọi pdf/docx khỏi chỉ mục
                if (d != null) Pipeline.filesFromMongo(d, writer);
            }
        } catch (StopSignal e) {
            // đủ limit
        }
        writer.flush();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed", writer.sent);
        out.put("source", fromMongo ? "mongo" : "raw");
        out.put("index", indexStats());
        return out;
    }

    /** Schema chung ngoài API ({@code PublicDocument} của bản Python): kiểm hợp lệ viết tay, lỗi là 422. */
    record PublicDocument(String url, String title, String content, String host, String section, String publishedAt,
                          String author, String kind, List<Map<String, String>> outgoingLinks) {

        static PublicDocument parse(JsonNode n, String o) {
            if (!n.isObject()) throw new HttpError(422, o + "phải là một đối tượng");
            if (!n.hasNonNull("url")) throw new HttpError(422, o + "url: thiếu trường bắt buộc");
            String url = Url.pyStrip(Http.bodyStr(n, "url", ""));
            if (!Url.isFullHttp(url)) throw new HttpError(422, o + "url: url phải là HTTP hoặc HTTPS đầy đủ");
            String kind = Http.bodyStr(n, "kind", "page");
            if (!kind.equals("page") && !kind.equals("document")) throw new HttpError(422, o + "kind: kind phải là page hoặc document");
            String title = HtmlUtil.head(Http.bodyStr(n, "title", ""), 500);
            String content = HtmlUtil.head(Http.bodyStr(n, "content", ""), 200_000);
            String date = HtmlUtil.head(Url.pyStrip(Http.bodyStr(n, "published_at", "")), 10);
            if (!date.isEmpty() && !isValidDate(date)) throw new HttpError(422, o + "published_at: published_at phải có dạng YYYY-MM-DD");
            if (Url.pyStrip(title).isEmpty() && Url.pyStrip(content).isEmpty())
                throw new HttpError(422, o + "cần ít nhất title hoặc content không rỗng");
            List<Map<String, String>> links = new ArrayList<>();
            JsonNode ol = n.get("outgoing_links");
            if (ol != null && !ol.isNull()) {
                if (!ol.isArray()) throw new HttpError(422, o + "outgoing_links phải là một mảng");
                int i = 0;
                for (JsonNode l : ol) {
                    String u = Url.pyStrip(Http.bodyStr(l, "url", ""));
                    if (!Url.isFullHttp(u)) throw new HttpError(422, o + "outgoing_links[" + i + "].url: url phải dùng HTTP hoặc HTTPS");
                    links.add(Map.of("url", u, "text", Http.bodyStr(l, "text", "")));
                    i++;
                }
            }
            return new PublicDocument(url, title, content, Http.bodyStr(n, "host", ""), Http.bodyStr(n, "section", ""), date,
                    Http.bodyStr(n, "author", ""), kind, links);
        }

        Map<String, Object> dump() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", url);
            m.put("title", title);
            m.put("content", content);
            m.put("host", host);
            m.put("section", section);
            m.put("published_at", publishedAt);
            m.put("author", author);
            m.put("kind", kind);
            m.put("outgoing_links", outgoingLinks);
            return m;
        }

        /** Tài liệu cho Lucene; host rỗng thì suy từ url. */
        Map<String, String> lucene() {
            Map<String, String> m = new HashMap<>();
            String h = Url.pyStrip(host).toLowerCase(Locale.ROOT);
            m.put("url", url);
            m.put("title", title);
            m.put("text", content);
            m.put("host", h.isEmpty() ? Url.hostname(url) : h);
            m.put("section", section);
            m.put("date", publishedAt);
            m.put("author", author);
            m.put("kind", kind);
            return m;
        }
    }

    /** {@code time.strptime(v, "%Y-%m-%d")}: cho phép tháng/ngày một chữ số, từ chối ngày không có thật. */
    static boolean isValidDate(String v) {
        if (!DATE_ANY.matcher(v).matches()) return false;
        String[] p = v.split("-");
        try {
            LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code _date_or_empty}: ngày chuẩn YYYY-MM-DD hoặc rỗng. */
    static String dateOrEmpty(String v) {
        v = HtmlUtil.head(Url.pyStrip(v == null ? "" : v), 10);
        return DATE_ISO.matcher(v).matches() && isValidDate(v) ? v : "";
    }

    Object indexDocuments(Http.Req r) throws IOException {
        JsonNode b = r.body();
        boolean reset = Http.bodyBool(b, "reset", false);
        int batch = Http.bodyInt(b, "batch", 200, 1, 1000);
        JsonNode arr = b.get("documents");
        if (arr == null || !arr.isArray()) throw new HttpError(422, "documents: thiếu trường bắt buộc, phải là một mảng");
        if (arr.size() > 5000) throw new HttpError(422, "documents: tối đa 5000 tài liệu");
        Map<String, PublicDocument> unique = new LinkedHashMap<>();
        int duplicates = 0, i = 0;
        for (JsonNode n : arr) {
            PublicDocument d = PublicDocument.parse(n, "documents[" + i++ + "].");
            if (unique.containsKey(d.url())) duplicates++;
            unique.put(d.url(), d);
        }
        if (reset) idx.reset();
        BatchWriter writer = new BatchWriter(batch, 0);
        unique.values().forEach(d -> writer.accept(d.lucene()));
        writer.flush();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed", unique.size());
        out.put("duplicates_in_request", duplicates);
        out.put("index", indexStats());
        return out;
    }

    // ------------------------------------------------------------------ tìm kiếm
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Http.Status luceneError(int code, String msg) {
        return new Http.Status(code, Map.of("error", msg));
    }

    Object search(Http.Req r) {
        String q = r.require("q");
        int from = r.integer("from_", 0), size = r.integer("size", 10);
        String ranking = Url.pyStrip(r.str("ranking", "tfidf")).toLowerCase(Locale.ROOT);
        if (!ranking.equals("tfidf") && !ranking.equals("enhanced")) throw new HttpError(400, "ranking phải là tfidf hoặc enhanced");
        // Tầng này không tự lọc gì: lọc ở Lucene thì bộ đếm tổng và việc chia trang mới khớp nhau.
        q = q.strip();
        if (q.isEmpty()) return luceneError(400, "thiếu tham số q");
        from = Math.max(from, 0);
        size = Math.min(Math.max(size, 1), 50);
        boolean byDate = "date".equals(r.str("sort", "score"));
        try {
            Index.Result res = idx.search(new Index.SearchParams(q, from, size, r.nonEmpty("host"), r.nonEmpty("date_from"), r.nonEmpty("date_to"),
                    byDate, ranking, r.nonEmpty("kind"), r.nonEmpty("ftype")));
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Index.Hit h : res.hits()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("url", orEmpty(h.url()));
                m.put("title", orEmpty(h.title()));
                m.put("host", orEmpty(h.host()));
                m.put("section", orEmpty(h.section()));
                m.put("date", orEmpty(h.date()));
                m.put("author", orEmpty(h.author()));
                m.put("kind", orEmpty(h.kind()));
                m.put("ftype", orEmpty(h.ftype()));
                m.put("score", h.score());
                m.put("fragments", h.fragments());
                m.put("duplicates", h.duplicates());
                hits.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("q", q);
            out.put("ranking", ranking);
            out.put("total", res.total());
            out.put("took_ms", res.tookMs());
            out.put("from", from);
            out.put("size", size);
            out.put("sort", byDate ? "date" : "score");
            out.put("hits", hits);
            return out;
        } catch (Exception e) {
            // câu truy vấn sai cú pháp là lỗi của người dùng, đừng trả 500
            return luceneError(400, "không phân tích được truy vấn: " + e.getMessage());
        }
    }

    /** Liệt kê toàn bộ tài liệu đã index, không cần từ khoá — cho tab "Duyệt tất cả". */
    Object list(Http.Req r) throws IOException {
        int from = Math.max(r.integer("from_", 0), 0);
        int size = Math.min(Math.max(r.integer("size", 20), 1), 200);
        String host = r.nonEmpty("host");
        boolean byUrl = "url".equals(r.str("sort", "date"));
        Index.ListResult res = idx.listAll(from, size, host, byUrl, r.nonEmpty("kind"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Index.ListItem it : res.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", orEmpty(it.url()));
            m.put("title", orEmpty(it.title()));
            m.put("host", orEmpty(it.host()));
            m.put("section", orEmpty(it.section()));
            m.put("date", orEmpty(it.date()));
            m.put("author", orEmpty(it.author()));
            m.put("kind", orEmpty(it.kind()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", res.total());
        out.put("from", from);
        out.put("size", size);
        out.put("host", orEmpty(host));
        out.put("sort", byUrl ? "url" : "date");
        out.put("items", items);
        return out;
    }

    /** Duyệt từ điển chỉ mục ngược của một field, phân trang bằng con trỏ {@code after}. */
    Object dict(Http.Req r) throws IOException {
        String field = r.str("field", "text");
        int limit = Math.min(Math.max(r.integer("limit", 50), 1), 200);
        Index.DictPage res = idx.termDictionary(field, r.nonEmpty("after"), limit);
        List<Map<String, Object>> terms = new ArrayList<>();
        for (Index.TermInfo t : res.terms()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("term", t.term());
            m.put("doc_freq", t.docFreq());
            m.put("total_term_freq", t.totalTermFreq());
            terms.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field);
        out.put("terms", terms);
        out.put("next", res.next());
        return out;
    }

    /** Posting list đầy đủ của một từ: docFreq/totalTermFreq + danh sách tài liệu. */
    Object posting(Http.Req r) throws IOException {
        String field = r.str("field", "text");
        String term = r.str("term", "").strip();
        if (term.isEmpty()) throw new HttpError(400, "thiếu tham số term");
        int limit = Math.min(Math.max(r.integer("limit", 50), 1), 500);
        Index.Posting res = idx.getPosting(field, term, limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Index.PostingRow row : res.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", orEmpty(row.url()));
            m.put("title", orEmpty(row.title()));
            m.put("tf", row.tf());
            m.put("positions", row.positions());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field);
        out.put("term", res.term());
        out.put("doc_freq", res.docFreq());
        out.put("total_term_freq", res.totalTermFreq());
        out.put("rows", rows);
        return out;
    }

    /** HTML đã dọn của một trang đã index, lấy từ index của mình — không gọi lại site nên không bao giờ bị chặn. */
    Object preview(Http.Req r) throws IOException {
        String url = r.require("url").strip();
        Map<String, String> d = url.isEmpty() ? null : idx.getDocument(url);
        if (d == null) throw new HttpError(404, "chưa có trang này trong index");
        return d;
    }

    // ------------------------------------------------------------------ tải lẻ
    /** Tải đúng một url, lưu kho, bóc chữ rồi đẩy thẳng vào Lucene: tải xong là tìm được. */
    Object fetch(Http.Req r) {
        String url = Url.pyStrip(Http.bodyStr(r.body(), "url", ""));
        if (!Url.isFullHttp(url)) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        Response p = downloader.apply(url);
        ObjectNode rec = RawStore.JSON.createObjectNode();
        rec.put("url", p.url());
        rec.put("status", p.status());
        rec.put("encoding", p.encoding());
        rec.put("fetched_at", Pipeline.now());
        rec.put("html_b64", Base64.getEncoder().encodeToString(p.body()));
        rec.put("kind", "adhoc");
        String html = RawStore.decodeHtml(rec);
        Extractor.Extraction k = html == null ? null : Extractor.extract(html, p.url(), null);
        if (k == null) throw new HttpError(422, "tải được nhưng không bóc ra chữ nào — trang rỗng hoặc dựng bằng JS");
        try {
            idx.put(luceneDoc(k));
            idx.commit();
        } catch (IOException e) {
            throw new HttpError(502, "Lucene không sẵn sàng: " + e);
        }
        try {
            store.append(rec);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {                                     // Mongo chưa lên thì bỏ qua, tải lẻ vẫn thành công
            Pipeline.writeSinglePage(mongo.get(), k.url(), k, rec, html);
        } catch (Exception e) {
            System.out.println("[mongo] không ghi được trang tải lẻ: " + e);
        }
        List<Map<String, String>> links = new ArrayList<>();
        for (var c : k.outgoingLinks()) links.add(Map.of("url", c.url, "text", c.text));
        var doc = new PublicDocument(k.url(), k.title(), k.text(), k.host(), k.section(), dateOrEmpty(k.date()),
                HtmlUtil.head(k.author(), 200), "page", links);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("url", k.url());
        out.put("title", k.title());
        out.put("host", k.host());
        out.put("date", k.date());
        out.put("chars", HtmlUtil.len(k.text()));
        out.put("bytes", p.body().length);
        out.put("status", p.status());
        out.put("indexed", true);
        out.put("index_docs", idx.numDocs());
        out.put("preview", HtmlUtil.head(k.html(), 4000));
        out.put("document", doc.dump());
        return out;
    }
}
