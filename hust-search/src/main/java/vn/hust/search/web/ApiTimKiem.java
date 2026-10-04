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
import vn.hust.search.boctach.BocTach;
import vn.hust.search.boctach.HtmlSach;
import vn.hust.search.kho.Kho;
import vn.hust.search.kho.TaiVe.PhanHoi;
import vn.hust.search.kho.Url;
import vn.hust.search.mongo.Trich;

/**
 * Tìm kiếm, chỉ mục và tải lẻ: {@code /api/search}, {@code /api/index/*}, {@code /api/preview},
 * {@code /api/fetch}, {@code /api/health}, {@code /api/stats}. Bản port của {@code main.py}; Lucene
 * giờ chạy cùng tiến trình nên gọi thẳng {@link Index} thay vì chuyển tiếp HTTP.
 * Lỗi của Lucene (câu truy vấn sai cú pháp, thiếu q) giữ khoá {@code "error"} như trước vì giao diện
 * đọc {@code d.error} ở các tab tìm kiếm; mọi lỗi còn lại là {@code "detail"}.
 */
public final class ApiTimKiem {
    private static final Pattern NGAY = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");
    private static final Pattern NGAY_CHUAN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Index idx;
    private final Kho kho;
    private final Mongo mongo;
    private final Function<String, PhanHoi> taiVe;
    private final ApiCrawl crawl;
    private final Path data;

    public ApiTimKiem(Index idx, Kho kho, Mongo mongo, Function<String, PhanHoi> taiVe, ApiCrawl crawl) {
        this.idx = idx;
        this.kho = kho;
        this.mongo = mongo;
        this.taiVe = taiVe;
        this.crawl = crawl;
        this.data = kho.dir();
    }

    public void dang(Http h) {
        h.get("/api/stats", r -> stats());
        h.get("/api/health", r -> health());
        h.post("/api/index/run", this::indexRun);
        h.post("/api/index/documents", this::indexDocuments);
        h.get("/api/index/stats", r -> thongKeIndex());
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
        long tongTrang = 0, tongByte = 0;
        for (Path d : kho.khoDirs()) {
            JsonNode s = Kho.JSON.createObjectNode();
            Path st = d.resolve("state.json");
            if (Files.exists(st)) s = Kho.JSON.readTree(st.toFile());
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
            tongTrang += s.path("done").size();
            tongByte += size;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", Kho.hostOf(d));
            m.put("done", s.path("done").size());
            m.put("queued", s.path("frontier").size());
            m.put("articles", s.path("by_key").size());
            m.put("shards", nShard);
            m.put("bytes", size);
            sites.add(m);
        }
        sites.sort((a, b) -> Integer.compare((int) b.get("done"), (int) a.get("done")));
        Path lf = null;
        try (Stream<Path> fs = Files.list(data)) {
            lf = fs.filter(p -> p.getFileName().toString().contains("links") && !p.getFileName().toString().contains(".")
                    && Files.isRegularFile(p)).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            // chưa có thư mục dữ liệu
        }
        long links = 0;
        if (lf != null) try (Stream<String> ls = Files.lines(lf)) {
            links = ls.filter(l -> !l.isBlank()).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sites", sites);
        out.put("total_pages", tongTrang);
        out.put("total_bytes", tongByte);
        out.put("links_file", lf == null ? null : lf.getFileName().toString());
        out.put("links", links);
        return out;
    }

    Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("api", true);
        m.put("lucene", true);                      // cùng tiến trình: API sống thì Lucene sống
        m.put("mongo", mongo.opt() != null);
        m.put("crawler", crawl.goiDuoc());
        m.put("data_dir", data.toString());
        return m;
    }

    Map<String, Object> thongKeIndex() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs", idx.numDocs());
        m.put("index_bytes", idx.sizeBytes());
        m.put("index_dir", idx.path().toString());
        m.put("by_host", idx.byHost());
        return m;
    }

    // ------------------------------------------------------------------ chỉ mục
    private static final class Dung extends RuntimeException {
        Dung() {
            super(null, null, false, false);
        }
    }

    /** Gom tài liệu thành mẻ rồi ghi vào Lucene; ném {@link Dung} khi đủ {@code limit}. */
    private final class MeGhi implements Consumer<Map<String, String>> {
        final List<Map<String, String>> buf = new ArrayList<>();
        final int batch, limit;
        int sent;

        MeGhi(int batch, int limit) {
            this.batch = batch;
            this.limit = limit;
        }

        @Override
        public void accept(Map<String, String> d) {
            buf.add(d);
            if (buf.size() >= batch) xa();
            if (limit > 0 && sent + buf.size() >= limit) throw new Dung();
        }

        void xa() {
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

    static Map<String, String> luceneDoc(BocTach.Ket k) {
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
        boolean tuMongo = d != null && (source.equals("mongo")
                || source.equals("auto") && d.getCollection("pages").countDocuments(new Document(), new CountOptions().limit(1)) > 0);
        if (reset) idx.reset();
        MeGhi me = new MeGhi(batch, limit);
        try {
            if (tuMongo) {
                Trich.luceneTuMongo(d, me);
            } else {
                for (var it = kho.banGhi(); it.hasNext(); ) {
                    JsonNode rec = it.next();
                    String html = Kho.giaiMa(rec);
                    if (html == null) continue;
                    BocTach.Ket k = BocTach.bocTach(html, rec.path("url").asText(), null);
                    if (k != null) me.accept(luceneDoc(k));
                }
                // Chữ của tệp tài liệu chỉ có trong Mongo: thiếu bước này thì dựng lại từ kho thô xoá mất mọi pdf/docx khỏi chỉ mục
                if (d != null) Trich.tepTuMongo(d, me);
            }
        } catch (Dung e) {
            // đủ limit
        }
        me.xa();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed", me.sent);
        out.put("source", tuMongo ? "mongo" : "raw");
        out.put("index", thongKeIndex());
        return out;
    }

    /** Schema chung ngoài API ({@code PublicDocument} của bản Python): kiểm hợp lệ viết tay, lỗi là 422. */
    record PublicDocument(String url, String title, String content, String host, String section, String publishedAt,
                          String author, String kind, List<Map<String, String>> outgoingLinks) {

        static PublicDocument parse(JsonNode n, String o) {
            if (!n.isObject()) throw new HttpError(422, o + "phải là một đối tượng");
            if (!n.hasNonNull("url")) throw new HttpError(422, o + "url: thiếu trường bắt buộc");
            String url = Url.pyStrip(Http.bodyStr(n, "url", ""));
            if (!Url.httpDayDu(url)) throw new HttpError(422, o + "url: url phải là HTTP hoặc HTTPS đầy đủ");
            String kind = Http.bodyStr(n, "kind", "page");
            if (!kind.equals("page") && !kind.equals("document")) throw new HttpError(422, o + "kind: kind phải là page hoặc document");
            String title = HtmlSach.head(Http.bodyStr(n, "title", ""), 500);
            String content = HtmlSach.head(Http.bodyStr(n, "content", ""), 200_000);
            String ngay = HtmlSach.head(Url.pyStrip(Http.bodyStr(n, "published_at", "")), 10);
            if (!ngay.isEmpty() && !ngayHopLe(ngay)) throw new HttpError(422, o + "published_at: published_at phải có dạng YYYY-MM-DD");
            if (Url.pyStrip(title).isEmpty() && Url.pyStrip(content).isEmpty())
                throw new HttpError(422, o + "cần ít nhất title hoặc content không rỗng");
            List<Map<String, String>> links = new ArrayList<>();
            JsonNode ol = n.get("outgoing_links");
            if (ol != null && !ol.isNull()) {
                if (!ol.isArray()) throw new HttpError(422, o + "outgoing_links phải là một mảng");
                int i = 0;
                for (JsonNode l : ol) {
                    String u = Url.pyStrip(Http.bodyStr(l, "url", ""));
                    if (!Url.httpDayDu(u)) throw new HttpError(422, o + "outgoing_links[" + i + "].url: url phải dùng HTTP hoặc HTTPS");
                    links.add(Map.of("url", u, "text", Http.bodyStr(l, "text", "")));
                    i++;
                }
            }
            return new PublicDocument(url, title, content, Http.bodyStr(n, "host", ""), Http.bodyStr(n, "section", ""), ngay,
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
    static boolean ngayHopLe(String v) {
        if (!NGAY.matcher(v).matches()) return false;
        String[] p = v.split("-");
        try {
            LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code _date_or_empty}: ngày chuẩn YYYY-MM-DD hoặc rỗng. */
    static String ngayHoacRong(String v) {
        v = HtmlSach.head(Url.pyStrip(v == null ? "" : v), 10);
        return NGAY_CHUAN.matcher(v).matches() && ngayHopLe(v) ? v : "";
    }

    Object indexDocuments(Http.Req r) throws IOException {
        JsonNode b = r.body();
        boolean reset = Http.bodyBool(b, "reset", false);
        int batch = Http.bodyInt(b, "batch", 200, 1, 1000);
        JsonNode arr = b.get("documents");
        if (arr == null || !arr.isArray()) throw new HttpError(422, "documents: thiếu trường bắt buộc, phải là một mảng");
        if (arr.size() > 5000) throw new HttpError(422, "documents: tối đa 5000 tài liệu");
        Map<String, PublicDocument> unique = new LinkedHashMap<>();
        int trung = 0, i = 0;
        for (JsonNode n : arr) {
            PublicDocument d = PublicDocument.parse(n, "documents[" + i++ + "].");
            if (unique.containsKey(d.url())) trung++;
            unique.put(d.url(), d);
        }
        if (reset) idx.reset();
        MeGhi me = new MeGhi(batch, 0);
        unique.values().forEach(d -> me.accept(d.lucene()));
        me.xa();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed", unique.size());
        out.put("duplicates_in_request", trung);
        out.put("index", thongKeIndex());
        return out;
    }

    // ------------------------------------------------------------------ tìm kiếm
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Http.Status loiLucene(int code, String msg) {
        return new Http.Status(code, Map.of("error", msg));
    }

    Object search(Http.Req r) {
        String q = r.bat("q");
        int from = r.integer("from_", 0), size = r.integer("size", 10);
        String ranking = Url.pyStrip(r.str("ranking", "tfidf")).toLowerCase(Locale.ROOT);
        if (!ranking.equals("tfidf") && !ranking.equals("enhanced")) throw new HttpError(400, "ranking phải là tfidf hoặc enhanced");
        // Tầng này không tự lọc gì: lọc ở Lucene thì bộ đếm tổng và việc chia trang mới khớp nhau.
        q = q.strip();
        if (q.isEmpty()) return loiLucene(400, "thiếu tham số q");
        from = Math.max(from, 0);
        size = Math.min(Math.max(size, 1), 50);
        boolean theoNgay = "date".equals(r.str("sort", "score"));
        try {
            Index.Result res = idx.search(new Index.Truy(q, from, size, r.co("host"), r.co("date_from"), r.co("date_to"),
                    theoNgay, ranking, r.co("kind"), r.co("ftype")));
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Index.Hit h : res.hits()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("url", nz(h.url()));
                m.put("title", nz(h.title()));
                m.put("host", nz(h.host()));
                m.put("section", nz(h.section()));
                m.put("date", nz(h.date()));
                m.put("author", nz(h.author()));
                m.put("kind", nz(h.kind()));
                m.put("ftype", nz(h.ftype()));
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
            out.put("sort", theoNgay ? "date" : "score");
            out.put("hits", hits);
            return out;
        } catch (Exception e) {
            // câu truy vấn sai cú pháp là lỗi của người dùng, đừng trả 500
            return loiLucene(400, "không phân tích được truy vấn: " + e.getMessage());
        }
    }

    /** Liệt kê toàn bộ tài liệu đã index, không cần từ khoá — cho tab "Duyệt tất cả". */
    Object list(Http.Req r) throws IOException {
        int from = Math.max(r.integer("from_", 0), 0);
        int size = Math.min(Math.max(r.integer("size", 20), 1), 200);
        String host = r.co("host");
        boolean theoUrl = "url".equals(r.str("sort", "date"));
        Index.ListResult res = idx.listAll(from, size, host, theoUrl, r.co("kind"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Index.ListItem it : res.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", nz(it.url()));
            m.put("title", nz(it.title()));
            m.put("host", nz(it.host()));
            m.put("section", nz(it.section()));
            m.put("date", nz(it.date()));
            m.put("author", nz(it.author()));
            m.put("kind", nz(it.kind()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", res.total());
        out.put("from", from);
        out.put("size", size);
        out.put("host", nz(host));
        out.put("sort", theoUrl ? "url" : "date");
        out.put("items", items);
        return out;
    }

    /** Duyệt từ điển chỉ mục ngược của một field, phân trang bằng con trỏ {@code after}. */
    Object dict(Http.Req r) throws IOException {
        String field = r.str("field", "text");
        int limit = Math.min(Math.max(r.integer("limit", 50), 1), 200);
        Index.DictPage res = idx.tuDien(field, r.co("after"), limit);
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
        out.put("next", res.tiepTheo());
        return out;
    }

    /** Posting list đầy đủ của một từ: docFreq/totalTermFreq + danh sách tài liệu. */
    Object posting(Http.Req r) throws IOException {
        String field = r.str("field", "text");
        String term = r.str("term", "").strip();
        if (term.isEmpty()) throw new HttpError(400, "thiếu tham số term");
        int limit = Math.min(Math.max(r.integer("limit", 50), 1), 500);
        Index.Posting res = idx.layPosting(field, term, limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Index.PostingRow row : res.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", nz(row.url()));
            m.put("title", nz(row.title()));
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
        String url = r.bat("url").strip();
        Map<String, String> d = url.isEmpty() ? null : idx.layTaiLieu(url);
        if (d == null) throw new HttpError(404, "chưa có trang này trong index");
        return d;
    }

    // ------------------------------------------------------------------ tải lẻ
    /** Tải đúng một url, lưu kho, bóc chữ rồi đẩy thẳng vào Lucene: tải xong là tìm được. */
    Object fetch(Http.Req r) {
        String url = Url.pyStrip(Http.bodyStr(r.body(), "url", ""));
        if (!Url.httpDayDu(url)) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        PhanHoi p = taiVe.apply(url);
        ObjectNode rec = Kho.JSON.createObjectNode();
        rec.put("url", p.url());
        rec.put("status", p.status());
        rec.put("encoding", p.encoding());
        rec.put("fetched_at", Trich.now());
        rec.put("html_b64", Base64.getEncoder().encodeToString(p.body()));
        rec.put("kind", "adhoc");
        String html = Kho.giaiMa(rec);
        BocTach.Ket k = html == null ? null : BocTach.bocTach(html, p.url(), null);
        if (k == null) throw new HttpError(422, "tải được nhưng không bóc ra chữ nào — trang rỗng hoặc dựng bằng JS");
        try {
            idx.put(luceneDoc(k));
            idx.commit();
        } catch (IOException e) {
            throw new HttpError(502, "Lucene không sẵn sàng: " + e);
        }
        try {
            kho.ghiKho(rec);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {                                     // Mongo chưa lên thì bỏ qua, tải lẻ vẫn thành công
            Trich.ghiMotTrang(mongo.get(), k.url(), k, rec, html);
        } catch (Exception e) {
            System.out.println("[mongo] không ghi được trang tải lẻ: " + e);
        }
        List<Map<String, String>> links = new ArrayList<>();
        for (var c : k.outgoingLinks()) links.add(Map.of("url", c.url, "text", c.text));
        var doc = new PublicDocument(k.url(), k.title(), k.text(), k.host(), k.section(), ngayHoacRong(k.date()),
                HtmlSach.head(k.author(), 200), "page", links);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("url", k.url());
        out.put("title", k.title());
        out.put("host", k.host());
        out.put("date", k.date());
        out.put("chars", HtmlSach.len(k.text()));
        out.put("bytes", p.body().length);
        out.put("status", p.status());
        out.put("indexed", true);
        out.put("index_docs", idx.numDocs());
        out.put("preview", HtmlSach.head(k.html(), 4000));
        out.put("document", doc.dump());
        return out;
    }
}
