package vn.hust.search.web;

import static com.mongodb.client.model.Accumulators.sum;
import static com.mongodb.client.model.Aggregates.group;
import static com.mongodb.client.model.Aggregates.limit;
import static com.mongodb.client.model.Aggregates.sort;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.nin;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Projections.exclude;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bson.Document;
import org.bson.conversions.Bson;
import vn.hust.search.Index;
import vn.hust.search.extract.Extractor;
import vn.hust.search.extract.HtmlUtil;
import vn.hust.search.extract.Template;
import vn.hust.search.extract.DocumentText;
import vn.hust.search.store.Download;
import vn.hust.search.store.RawStore;
import vn.hust.search.store.Download.Response;
import vn.hust.search.store.Url;
import vn.hust.search.mongo.Robots;
import vn.hust.search.mongo.DocumentJob;
import vn.hust.search.mongo.Pipeline;

/**
 * Bóc tách, tệp và đồ thị liên kết (MongoDB): {@code /api/extract/*}, {@code /api/files*},
 * {@code /api/images}, {@code /api/referrers}, {@code /api/graph/*}. Bản port của {@code routes_bt.py}.
 * Tải web đi qua {@code Function<String, Response>} để test thay bằng giả mà không cần mạng.
 */
public final class ApiExtract {
    /** Ký tự văn bản trả về giao diện; bản lưu Mongo/Lucene không bị cắt ở đây. */
    static final int MAX_TEXT_CHARS = 50_000;

    private final Index idx;
    private final RawStore store;
    private final Mongo mongo;
    private final Function<String, Response> downloader;
    private final Path filesDir;
    private final BackgroundJob jobs;
    private final ApiCrawl crawl;
    private final Function<String, DocumentJob.Resp> fileHttp;

    public ApiExtract(Index idx, RawStore store, Mongo mongo, Function<String, Download.Response> downloader, Path filesDir, BackgroundJob jobs,
                      ApiCrawl crawl, Function<String, DocumentJob.Resp> fileHttp) {
        this.idx = idx;
        this.store = store;
        this.mongo = mongo;
        this.downloader = downloader;
        this.filesDir = filesDir;
        this.jobs = jobs;
        this.crawl = crawl;
        this.fileHttp = fileHttp;
    }

    public void register(Http h) {
        h.post("/api/extract/templates", this::templates);
        h.post("/api/extract/run", this::run);
        h.get("/api/extract/status", r -> jobs.status());
        h.get("/api/extract/coverage", r -> Pipeline.coverage(mongo.get()));
        h.get("/api/extract/explain", this::explain);
        h.post("/api/extract/url", this::extractUrl);
        h.get("/api/extract/overview", r -> overview());
        h.post("/api/files/fetch", this::filesFetch);
        h.post("/api/files/extract", this::filesExtract);
        h.get("/api/files", this::files);
        h.get("/api/images", this::images);
        h.get("/api/referrers", this::referrers);
        h.get("/api/graph/out", this::graphOut);
        h.get("/api/graph/stats", this::graphStats);
        h.get("/api/graph/edges.csv", this::edgesCsv);
    }

    // ------------------------------------------------------------------ việc nền
    /** Dựng bảng khối lặp theo host (lớp 2 của thuật toán khối nội dung). */
    Object templates(Http.Req r) {
        MongoDatabase d = mongo.get();
        return jobs.run("templates", progress -> Pipeline.buildTemplates(d, store.allRecords(), progress));
    }

    /** Kho thô -> bóc tách -> Mongo (pages, links, nav_links, images). */
    Object run(Http.Req r) throws IOException {
        int limit = r.integer("limit", 0);
        MongoDatabase d = mongo.get();
        mongo.db().init();
        return jobs.run("extract", progress -> Pipeline.runExtract(d, store.allRecords(), limit, progress, 200));
    }

    /**
     * Lập danh mục tệp từ đồ thị rồi tải, chạy nền. 409 khi crawler đang chạy: hai tiến trình
     * mỗi bên 2,5 s là ~48 request/phút, gấp đôi ngưỡng site chặn.
     */
    Object filesFetch(Http.Req r) throws IOException {
        int limit = r.integer("limit", 0);
        if (crawl.isRunning()) throw new HttpError(409, "crawler đang chạy, dừng trước rồi hãy tải tệp");
        MongoDatabase d = mongo.get();
        mongo.db().init();
        return jobs.run("files", progress -> {
            Map<String, Object> result = new LinkedHashMap<>(DocumentJob.catalog(d, store.allRecords()));
            result.putAll(DocumentJob.download(d, filesDir, progress, fileHttp, DocumentJob.INTERVAL_MS, DocumentJob.MAX_BYTES, limit));
            return result;
        });
    }

    /** Bóc chữ các tệp đã tải -> documents.text. */
    Object filesExtract(Http.Req r) {
        MongoDatabase d = mongo.get();
        return jobs.run("files-extract", progress -> DocumentJob.extractText(d, filesDir, progress));
    }

    // ------------------------------------------------------------------ giải thích và bóc một url
    record Canon(String id, List<String> all) {}

    /** url -> (_id trang chính, mọi url cùng bài). Url không có trong pages thì giữ nguyên. */
    static Canon canon(MongoDatabase d, String url) {
        String n = Url.norm(url);
        String u = n != null ? n : url;
        Document p = d.getCollection("pages").find(or(eq("_id", u), eq("aliases", u))).projection(include("aliases")).first();
        if (p == null) return new Canon(u, List.of(u));
        List<String> all = new ArrayList<>(List.of(p.getString("_id")));
        all.addAll(p.getList("aliases", String.class, List.of()));
        return new Canon(p.getString("_id"), all);
    }

    /**
     * Chạy lại bước chọn khối trên HTML thô trong kho và trả về từng bước để giao diện vẽ. Không cần
     * Mongo; có Mongo thì dùng thêm bảng khuôn của host và bí danh của trang.
     */
    Object explain(Http.Req r) {
        String url = r.require("url");
        String sc = Url.scheme(url);
        if (!sc.equals("http") && !sc.equals("https")) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        Set<String> urls = new LinkedHashSet<>(List.of(url));
        Set<String> templateFps = Set.of();
        boolean hasMongo = true;
        MongoDatabase d = mongo.opt();
        if (d != null) {
            urls.addAll(canon(d, url).all());
            templateFps = Pipeline.templatesByHost(d).getOrDefault(Url.hostname(url), Set.of());
        } else {
            hasMongo = false;
        }
        JsonNode rec = store.findRecord(urls);
        String html = rec == null ? null : RawStore.decodeHtml(rec);
        if (html == null) throw new HttpError(404, "không có HTML của url này trong kho (chưa crawl hoặc là tệp)");
        Map<String, Object> out = new LinkedHashMap<>(Extractor.explain(html, rec.path("url").asText(), templateFps));
        out.put("co_mongo", hasMongo);
        out.put("khuon_host", templateFps.size());
        return out;
    }

    /** html | document | image | other, theo content-type rồi tới đuôi url. */
    static String classifyContent(String contentType, String url) {
        String ct = contentType == null ? "" : HtmlUtil.strip(contentType.split(";", -1)[0]).toLowerCase(Locale.ROOT);
        if (ct.contains("html")) return "html";
        if (DocumentText.MIME_TO_EXT.containsKey(ct) || DocumentText.SUPPORTED.contains(DocumentText.extensionFromUrl(url))) return "document";
        if (ct.startsWith("image/")) return "image";
        return ct.isEmpty() ? "html" : "other";
    }

    private static int wordCount(String s) {
        s = HtmlUtil.strip(s);
        return s.isEmpty() ? 0 : HtmlUtil.WS.split(s).length;
    }

    private static String sha1(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Đẩy một tài liệu sang Lucene; trả chuỗi lỗi, rỗng nếu thành công. */
    private String indexToLucene(Map<String, String> doc) {
        try {
            idx.put(doc);
            idx.commit();
            return "";
        } catch (IOException e) {
            return "Lucene không sẵn sàng: " + e;
        }
    }

    /**
     * Bóc tách MỘT url bất kỳ: lấy HTML trong kho nếu có (hoặc tải mới từ web), chạy thuật toán chọn
     * khối + bóc trường + chia cạnh, ghi Mongo và index Lucene. Url là tệp tài liệu thì bóc chữ tệp
     * và ghi {@code documents}.
     */
    Object extractUrl(Http.Req r) throws IOException {
        JsonNode b = r.body();
        String url = Url.pyStrip(Http.bodyStr(b, "url", ""));
        boolean refetch = Http.bodyBool(b, "tai_lai", false);
        boolean saveRequested = Http.bodyBool(b, "luu", true);
        boolean indexRequested = Http.bodyBool(b, "index", true);
        if (!Url.isFullHttp(url)) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        String fetchUrl = url;                       // tải bằng url gốc: norm() ép https, site chỉ có http sẽ hỏng
        String n = Url.norm(url);
        url = n != null ? n : url;                 // còn khoá lưu thì luôn qua norm()

        MongoDatabase d = mongo.opt();
        Set<String> hostTemplateFps = Set.of();
        Set<String> urls = new LinkedHashSet<>(List.of(url));
        if (d != null) {
            urls.addAll(canon(d, url).all());
            hostTemplateFps = Pipeline.templatesByHost(d).getOrDefault(Url.hostname(fetchUrl), Set.of());
        }

        // 1. lấy nội dung: kho trước, web sau
        JsonNode rec = null;
        String origin = "kho";
        if (!refetch) {
            rec = store.findRecord(urls);
            if (rec != null && rec.path("html_b64").asText("").isEmpty()) rec = null;   // kho chỉ ghi nhận tệp, không có byte: tải mới
        }
        if (rec == null) {
            Download.Response p;
            try {
                p = downloader.apply(fetchUrl);
            } catch (HttpError e) {
                // url lấy từ đồ thị đã bị norm() ép https; site chỉ có http thì lỗi ngay ở bắt tay
                // TLS/kết nối (không phải HTTP 4xx/5xx) — thử lại một lần bằng http.
                if (!(e.code == 502 && String.valueOf(e.getMessage()).startsWith("không tải được")
                        && fetchUrl.toLowerCase(Locale.ROOT).startsWith("https://"))) throw e;
                p = downloader.apply("http://" + fetchUrl.substring("https://".length()));
            }
            origin = "web";
            String kind = classifyContent(p.contentType(), p.url());
            String normalizedUrl = Url.norm(p.url());
            ObjectNode o = RawStore.JSON.createObjectNode();
            o.put("url", normalizedUrl != null ? normalizedUrl : p.url());
            o.put("status", p.status());
            o.put("content_type", p.contentType());
            o.put("encoding", p.encoding());
            o.put("fetched_at", Pipeline.now());
            o.put("size", p.body().length);
            o.put("sha1", sha1(p.body()));
            o.put("kind", "adhoc");
            if (kind.equals("html")) o.put("html_b64", Base64.getEncoder().encodeToString(p.body()));
            else o.putNull("html_b64");
            if (kind.equals("html") || kind.equals("document")) store.append(o);   // vào raw-adhoc: lần bóc tách cả kho sau cũng gom được
            if (kind.equals("document")) return extractDocumentUrl(d, o, p.body(), saveRequested, indexRequested);
            if (!kind.equals("html"))
                throw new HttpError(415, "url trả về " + (p.contentType().isEmpty() ? "không rõ loại" : p.contentType())
                        + " — không phải trang HTML hay tệp tài liệu; ảnh chỉ có nguồn giới thiệu, xem ở tab Đồ thị");
            rec = o;
        }

        String html = RawStore.decodeHtml(rec);
        if (html == null) throw new HttpError(422, "trang trả lỗi hoặc không có HTML");
        String recordUrl = rec.path("url").asText();
        String pageNorm = Url.norm(recordUrl);
        String pageUrl = pageNorm != null ? pageNorm : recordUrl;
        Extractor.Extraction result = Extractor.extract(html, pageUrl, hostTemplateFps);
        if (result == null) throw new HttpError(422, "tải được nhưng không bóc ra chữ nào — trang rỗng hoặc dựng bằng JS");

        // 2. lưu
        Map<String, Object> saved = defaultSaved();
        if (saveRequested) {
            if (d == null) {
                saved.put("loi_mongo", "MongoDB không sẵn sàng");
            } else {
                Pipeline.writeSinglePage(d, pageUrl, result, rec, html);
                saved.putAll(Pipeline.writeSinglePageExtras(d, result));
                saved.put("mongo", true);
            }
        }
        if (indexRequested) {
            String indexError = indexToLucene(ApiSearch.luceneDoc(result));
            saved.put("loi_index", indexError);
            saved.put("index", indexError.isEmpty());
        }
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        fieldMap.put("title", result.title());
        fieldMap.put("title_src", result.titleSrc());
        fieldMap.put("date", result.date());
        fieldMap.put("date_src", result.dateSrc());
        fieldMap.put("author", result.author());
        fieldMap.put("author_src", result.authorSrc());
        fieldMap.put("cited_source", result.citedSource());
        fieldMap.put("section", result.section());
        // nội dung đã bóc: HTML đã dọn (≤ 40 KB, để trình bày) và văn bản thuần (đưa vào chỉ mục)
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("html", result.html());
        content.put("text", HtmlUtil.head(result.text(), MAX_TEXT_CHARS));
        content.put("bi_cat", HtmlUtil.len(result.text()) > MAX_TEXT_CHARS);
        List<Map<String, Object>> linkList = new ArrayList<>();
        for (var e : result.links()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dst", e.dst);
            m.put("type", e.type);
            m.put("text", e.text);
            m.put("dst_kind", e.dstKind);
            m.put("count", e.count);
            linkList.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loai", "page");
        out.put("url", pageUrl);
        out.put("host", result.host());
        out.put("nguon", origin);
        out.put("fetched_at", rec.path("fetched_at").asText(""));
        out.put("co_mongo", d != null);
        out.put("khuon_host", hostTemplateFps.size());
        out.put("luu", saved);
        out.put("block", result.block());
        out.put("truong", fieldMap);
        out.put("so_tu", wordCount(result.text()));
        out.put("noi_dung", content);
        out.put("lien_ket", linkList);
        out.put("so_canh_khuon", result.navLinks().size());
        return out;
    }

    private static Map<String, Object> defaultSaved() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("mongo", false);
        saved.put("loi_mongo", "");
        saved.put("index", false);
        saved.put("loi_index", "");
        return saved;
    }

    /** Nhánh tệp tài liệu của extract/url: bóc chữ, ghi documents + byte, index kind=document. */
    private Object extractDocumentUrl(MongoDatabase d, JsonNode rec, byte[] data, boolean saveRequested, boolean indexRequested) throws IOException {
        String url = rec.path("url").asText();
        String host = Url.hostname(url);
        String ct = rec.path("content_type").asText("");
        String mime = HtmlUtil.strip(ct.split(";", -1)[0]).toLowerCase(Locale.ROOT);
        String ext = DocumentText.extensionFromUrl(url);
        if (ext.isEmpty()) ext = DocumentText.MIME_TO_EXT.getOrDefault(mime, "");
        DocumentText.Result result = DocumentText.extractText(data, ext);
        Map<String, Object> saved = defaultSaved();
        if (saveRequested) {
            if (d == null) {
                saved.put("loi_mongo", "MongoDB không sẵn sàng");
            } else {
                Files.createDirectories(filesDir);
                Files.write(filesDir.resolve(rec.path("sha1").asText() + "." + (ext.isEmpty() ? "bin" : ext)), data);
                Document set = new Document("host", host).append("ext", ext).append("mime", mime).append("size", data.length)
                        .append("sha1", rec.path("sha1").asText()).append("fetched_at", rec.path("fetched_at").asText())
                        .append("extractor_version", Extractor.VERSION).append("status", result.status()).append("text", result.text())
                        .append("n_pages", result.nPages()).append("needs_ocr", result.needsOcr())
                        .append("encoding_suspect", result.encodingSuspect()).append("error", result.error());
                d.getCollection("documents").updateOne(eq("_id", url), new Document("$set", set), new UpdateOptions().upsert(true));
                saved.put("mongo", true);
            }
        }
        if (indexRequested && result.status().equals("ok") && !result.text().isEmpty()) {
            String lastSegment = url.substring(url.lastIndexOf('/') + 1).split("\\?", -1)[0];
            String name = Robots.unquote(lastSegment);
            Map<String, String> doc = new java.util.HashMap<>();
            doc.put("url", url);
            doc.put("title", name.isEmpty() ? url : name);
            doc.put("text", result.text());
            doc.put("host", host);
            doc.put("section", "");
            doc.put("date", "");
            doc.put("author", "");
            doc.put("kind", "document");
            doc.put("ftype", ext);
            String indexError = indexToLucene(doc);
            saved.put("loi_index", indexError);
            saved.put("index", indexError.isEmpty());
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("status", result.status());
        file.put("n_pages", result.nPages());
        file.put("needs_ocr", result.needsOcr());
        file.put("encoding_suspect", result.encodingSuspect());
        file.put("error", result.error());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", HtmlUtil.head(result.text(), MAX_TEXT_CHARS));
        content.put("bi_cat", HtmlUtil.len(result.text()) > MAX_TEXT_CHARS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loai", "document");
        out.put("url", url);
        out.put("host", host);
        out.put("nguon", "web");
        out.put("ext", ext);
        out.put("size", data.length);
        out.put("co_mongo", d != null);
        out.put("luu", saved);
        out.put("referrers", d == null ? 0 : d.getCollection("links").countDocuments(eq("dst", url)));
        out.put("tep", file);
        out.put("noi_dung", content);
        out.put("so_ky_tu", HtmlUtil.len(result.text()));
        return out;
    }

    // ------------------------------------------------------------------ số liệu
    private static Map<String, Object> countBy(MongoDatabase d, String coll, String field) {
        Map<String, Object> m = new java.util.TreeMap<>();      // $group không đảm bảo thứ tự; sắp xếp cho phản hồi ổn định
        for (Document x : d.getCollection(coll).aggregate(List.of(group("$" + field, sum("n", 1)))))
            m.put(String.valueOf(x.get("_id")), x.get("n"));
        return m;
    }

    /** Số liệu từng bước của dây chuyền khuôn -> bóc tách -> đồ thị -> tệp. */
    Object overview() {
        MongoDatabase d = mongo.get();
        long templateCount = 0, active = 0;
        for (Document t : d.getCollection("templates").find().projection(include("n_pages"))) {
            templateCount++;
            Object np = t.get("n_pages");
            if (np instanceof Number num && num.intValue() >= Template.MIN_PAGES) active++;
        }
        Map<String, Object> job = jobs.status();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", templateCount);
        out.put("templates_active", active);
        out.put("pages", d.getCollection("pages").countDocuments());
        out.put("links", d.getCollection("links").countDocuments());
        out.put("nav_links", d.getCollection("nav_links").countDocuments());
        out.put("images", d.getCollection("images").countDocuments());
        out.put("documents", countBy(d, "documents", "status"));
        out.put("documents_text", d.getCollection("documents").countDocuments(nin("text", null, "")));
        out.put("job", Map.of("running", job.get("running"), "what", job.get("what"), "done", job.get("done")));
        return out;
    }

    /** Danh sách tệp, mỗi tệp kèm số trang giới thiệu nó (cạnh nội dung đi vào). */
    Object files(Http.Req r) {
        MongoDatabase d = mongo.get();
        int skip = Math.max(r.integer("skip", 0), 0), lim = Math.min(r.integer("limit", 50), 200);
        List<Bson> filters = new ArrayList<>();
        if (r.nonEmpty("status") != null) filters.add(eq("status", r.nonEmpty("status")));
        if (r.nonEmpty("host") != null) filters.add(eq("host", r.nonEmpty("host")));
        Bson q = filters.isEmpty() ? new Document() : and(filters);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document x : d.getCollection("documents").find(q).projection(exclude("text")).sort(ascending("_id")).skip(skip).limit(lim)) {
            Map<String, Object> m = new LinkedHashMap<>(x);
            m.remove("_id");
            m.put("url", x.getString("_id"));
            m.put("referrers", d.getCollection("links").countDocuments(eq("dst", x.getString("_id"))));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", d.getCollection("documents").countDocuments(q));
        out.put("by_status", countBy(d, "documents", "status"));
        out.put("items", items);
        return out;
    }

    Object images(Http.Req r) {
        MongoDatabase d = mongo.get();
        Boolean tpl = r.boolOpt("template");
        int skip = Math.max(r.integer("skip", 0), 0), lim = Math.min(r.integer("limit", 50), 200);
        Bson q = tpl == null ? new Document() : eq("is_template", tpl);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document x : d.getCollection("images").find(q).sort(ascending("_id")).skip(skip).limit(lim)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", x.getString("_id"));
            m.put("host", x.getString("host"));
            m.put("alts", x.get("alts"));
            m.put("is_template", x.get("is_template"));
            m.put("referrers", d.getCollection("links").countDocuments(eq("dst", x.getString("_id"))));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", d.getCollection("images").countDocuments(q));
        out.put("items", items);
        return out;
    }

    /** Nguồn giới thiệu của một trang / tệp / ảnh: các cạnh đi VÀO url này. */
    Object referrers(Http.Req r) {
        String url = r.require("url");
        int lim = r.integer("limit", 100);
        String sc = Url.scheme(url);
        if (!sc.equals("http") && !sc.equals("https")) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        MongoDatabase d = mongo.get();
        Canon c = canon(d, url);
        Bson q = in("dst", c.all());
        List<Map<String, Object>> content = new ArrayList<>();
        for (Document x : d.getCollection("links").find(q).projection(include("src", "text", "type", "count")).limit(lim)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("src", x.get("src"));
            m.put("text", x.get("text"));
            m.put("type", x.get("type"));
            m.put("count", x.get("count"));
            content.add(m);
        }
        List<Map<String, Object>> navEdges = new ArrayList<>();
        for (Document x : d.getCollection("nav_links").find(q).projection(include("host", "text", "n_pages", "sample_src"))
                .sort(descending("n_pages")).limit(lim)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", x.get("host"));
            m.put("text", x.get("text"));
            m.put("n_pages", x.get("n_pages"));
            m.put("sample_src", x.get("sample_src"));
            navEdges.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", c.id());
        out.put("content", content);
        out.put("nav", navEdges);
        out.put("total_content", d.getCollection("links").countDocuments(q));
        out.put("total_nav", d.getCollection("nav_links").countDocuments(q));
        return out;
    }

    /** Cạnh nội dung đi RA từ một trang. */
    Object graphOut(Http.Req r) {
        String url = r.require("url");
        int lim = r.integer("limit", 200);
        MongoDatabase d = mongo.get();
        String canon = canon(d, url).id();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Document e : d.getCollection("links").find(eq("src", canon)).projection(include("dst", "text", "type", "dst_kind", "count")).limit(lim)) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("dst", "text", "type", "dst_kind", "count")) m.put(k, e.get(k));
            edges.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", canon);
        out.put("co_trang", d.getCollection("pages").countDocuments(eq("_id", canon), new com.mongodb.client.model.CountOptions().limit(1)) > 0);
        out.put("edges", edges);
        return out;
    }

    Object graphStats(Http.Req r) {
        int top = r.integer("top", 10);
        MongoDatabase d = mongo.get();
        List<Map<String, Object>> topInbound = new ArrayList<>();
        for (Document x : d.getCollection("links").aggregate(List.of(group("$dst", sum("n", 1)), sort(descending("n")), limit(top)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", x.get("_id"));
            m.put("in_content_edges", x.get("n"));
            topInbound.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", d.getCollection("pages").countDocuments());
        out.put("content_edges", d.getCollection("links").countDocuments());
        out.put("nav_edges", d.getCollection("nav_links").countDocuments());
        out.put("images", d.getCollection("images").countDocuments());
        out.put("content_edges_by_dst_kind", countBy(d, "links", "dst_kind"));
        out.put("top_in_degree", topInbound);
        return out;
    }

    /** {@code csv.writer} mặc định của Python: chỉ đặt trong ngoặc kép khi có dấu phẩy, ngoặc kép hoặc xuống dòng. */
    static String csv(Object o) {
        String s = o == null ? "" : o.toString();
        return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** Xuất cạnh nội dung: source,target,text (cho Gephi / networkx), gửi chunked để khỏi dồn cả file vào bộ nhớ. */
    Object edgesCsv(Http.Req r) throws IOException {
        MongoDatabase d = mongo.get();
        r.ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        r.ex.sendResponseHeaders(200, 0);
        try (var w = new BufferedWriter(new OutputStreamWriter(r.ex.getResponseBody(), StandardCharsets.UTF_8))) {
            w.write("source,target,text\r\n");
            for (Document e : d.getCollection("links").find().projection(fields(include("src", "dst", "text"))))
                w.write(csv(e.get("src")) + "," + csv(e.get("dst")) + "," + csv(e.get("text")) + "\r\n");
        }
        return Http.SENT;
    }
}
