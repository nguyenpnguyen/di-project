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
import vn.hust.search.boctach.BocTach;
import vn.hust.search.boctach.HtmlSach;
import vn.hust.search.boctach.Khuon;
import vn.hust.search.boctach.Tep;
import vn.hust.search.kho.Kho;
import vn.hust.search.kho.TaiVe.PhanHoi;
import vn.hust.search.kho.Url;
import vn.hust.search.mongo.Robots;
import vn.hust.search.mongo.TepJob;
import vn.hust.search.mongo.Trich;

/**
 * Bóc tách, tệp và đồ thị liên kết (MongoDB): {@code /api/extract/*}, {@code /api/files*},
 * {@code /api/images}, {@code /api/referrers}, {@code /api/graph/*}. Bản port của {@code routes_bt.py}.
 * Tải web đi qua {@code Function<String, PhanHoi>} để test thay bằng giả mà không cần mạng.
 */
public final class ApiBocTach {
    /** Ký tự văn bản trả về giao diện; bản lưu Mongo/Lucene không bị cắt ở đây. */
    static final int TOI_DA_VAN_BAN = 50_000;

    private final Index idx;
    private final Kho kho;
    private final Mongo mongo;
    private final Function<String, PhanHoi> taiVe;
    private final Path filesDir;
    private final ViecNen viec;
    private final ApiCrawl crawl;
    private final Function<String, TepJob.Resp> httpTep;

    public ApiBocTach(Index idx, Kho kho, Mongo mongo, Function<String, PhanHoi> taiVe, Path filesDir, ViecNen viec,
                      ApiCrawl crawl, Function<String, TepJob.Resp> httpTep) {
        this.idx = idx;
        this.kho = kho;
        this.mongo = mongo;
        this.taiVe = taiVe;
        this.filesDir = filesDir;
        this.viec = viec;
        this.crawl = crawl;
        this.httpTep = httpTep;
    }

    public void dang(Http h) {
        h.post("/api/extract/templates", this::templates);
        h.post("/api/extract/run", this::run);
        h.get("/api/extract/status", r -> viec.status());
        h.get("/api/extract/coverage", r -> Trich.coverage(mongo.get()));
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
        return viec.chay("templates", cb -> Trich.dungTemplates(d, kho.banGhi(), cb));
    }

    /** Kho thô -> bóc tách -> Mongo (pages, links, nav_links, images). */
    Object run(Http.Req r) throws IOException {
        int gioiHan = r.integer("limit", 0);
        MongoDatabase d = mongo.get();
        mongo.db().init();
        return viec.chay("extract", cb -> Trich.chayExtract(d, kho.banGhi(), gioiHan, cb, 200));
    }

    /**
     * Lập danh mục tệp từ đồ thị rồi tải, chạy nền. 409 khi crawler đang chạy: hai tiến trình
     * mỗi bên 2,5 s là ~48 request/phút, gấp đôi ngưỡng site chặn.
     */
    Object filesFetch(Http.Req r) throws IOException {
        int gioiHan = r.integer("limit", 0);
        if (crawl.dangChay()) throw new HttpError(409, "crawler đang chạy, dừng trước rồi hãy tải tệp");
        MongoDatabase d = mongo.get();
        mongo.db().init();
        return viec.chay("files", cb -> {
            Map<String, Object> kq = new LinkedHashMap<>(TepJob.danhMuc(d, kho.banGhi()));
            kq.putAll(TepJob.tai(d, filesDir, cb, httpTep, TepJob.NHIP_MS, TepJob.MAX_BYTES, gioiHan));
            return kq;
        });
    }

    /** Bóc chữ các tệp đã tải -> documents.text. */
    Object filesExtract(Http.Req r) {
        MongoDatabase d = mongo.get();
        return viec.chay("files-extract", cb -> TepJob.bocChu(d, filesDir, cb));
    }

    // ------------------------------------------------------------------ giải thích và bóc một url
    record Canon(String id, List<String> tatCa) {}

    /** url -> (_id trang chính, mọi url cùng bài). Url không có trong pages thì giữ nguyên. */
    static Canon canon(MongoDatabase d, String url) {
        String n = Url.norm(url);
        String u = n != null ? n : url;
        Document p = d.getCollection("pages").find(or(eq("_id", u), eq("aliases", u))).projection(include("aliases")).first();
        if (p == null) return new Canon(u, List.of(u));
        List<String> tat = new ArrayList<>(List.of(p.getString("_id")));
        tat.addAll(p.getList("aliases", String.class, List.of()));
        return new Canon(p.getString("_id"), tat);
    }

    /**
     * Chạy lại bước chọn khối trên HTML thô trong kho và trả về từng bước để giao diện vẽ. Không cần
     * Mongo; có Mongo thì dùng thêm bảng khuôn của host và bí danh của trang.
     */
    Object explain(Http.Req r) {
        String url = r.bat("url");
        String sc = Url.scheme(url);
        if (!sc.equals("http") && !sc.equals("https")) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        Set<String> urls = new LinkedHashSet<>(List.of(url));
        Set<String> khuon = Set.of();
        boolean coMongo = true;
        MongoDatabase d = mongo.opt();
        if (d != null) {
            urls.addAll(canon(d, url).tatCa());
            khuon = Trich.khuonTheoHost(d).getOrDefault(Url.hostname(url), Set.of());
        } else {
            coMongo = false;
        }
        JsonNode rec = kho.timBanGhi(urls);
        String html = rec == null ? null : Kho.giaiMa(rec);
        if (html == null) throw new HttpError(404, "không có HTML của url này trong kho (chưa crawl hoặc là tệp)");
        Map<String, Object> out = new LinkedHashMap<>(BocTach.giaiThich(html, rec.path("url").asText(), khuon));
        out.put("co_mongo", coMongo);
        out.put("khuon_host", khuon.size());
        return out;
    }

    /** html | document | image | other, theo content-type rồi tới đuôi url. */
    static String loaiNoiDung(String ctype, String url) {
        String ct = ctype == null ? "" : HtmlSach.strip(ctype.split(";", -1)[0]).toLowerCase(Locale.ROOT);
        if (ct.contains("html")) return "html";
        if (Tep.MIME_SANG_DUOI.containsKey(ct) || Tep.HO_TRO.contains(Tep.duoiTuUrl(url))) return "document";
        if (ct.startsWith("image/")) return "image";
        return ct.isEmpty() ? "html" : "other";
    }

    private static int soTu(String s) {
        s = HtmlSach.strip(s);
        return s.isEmpty() ? 0 : HtmlSach.WS.split(s).length;
    }

    private static String sha1(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Đẩy một tài liệu sang Lucene; trả chuỗi lỗi, rỗng nếu thành công. */
    private String indexLucene(Map<String, String> doc) {
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
        boolean taiLai = Http.bodyBool(b, "tai_lai", false);
        boolean luuReq = Http.bodyBool(b, "luu", true);
        boolean indexReq = Http.bodyBool(b, "index", true);
        if (!Url.httpDayDu(url)) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        String urlTai = url;                       // tải bằng url gốc: norm() ép https, site chỉ có http sẽ hỏng
        String n = Url.norm(url);
        url = n != null ? n : url;                 // còn khoá lưu thì luôn qua norm()

        MongoDatabase d = mongo.opt();
        Set<String> khuonHost = Set.of();
        Set<String> urls = new LinkedHashSet<>(List.of(url));
        if (d != null) {
            urls.addAll(canon(d, url).tatCa());
            khuonHost = Trich.khuonTheoHost(d).getOrDefault(Url.hostname(urlTai), Set.of());
        }

        // 1. lấy nội dung: kho trước, web sau
        JsonNode rec = null;
        String nguon = "kho";
        if (!taiLai) {
            rec = kho.timBanGhi(urls);
            if (rec != null && rec.path("html_b64").asText("").isEmpty()) rec = null;   // kho chỉ ghi nhận tệp, không có byte: tải mới
        }
        if (rec == null) {
            PhanHoi p;
            try {
                p = taiVe.apply(urlTai);
            } catch (HttpError e) {
                // url lấy từ đồ thị đã bị norm() ép https; site chỉ có http thì lỗi ngay ở bắt tay
                // TLS/kết nối (không phải HTTP 4xx/5xx) — thử lại một lần bằng http.
                if (!(e.code == 502 && String.valueOf(e.getMessage()).startsWith("không tải được")
                        && urlTai.toLowerCase(Locale.ROOT).startsWith("https://"))) throw e;
                p = taiVe.apply("http://" + urlTai.substring("https://".length()));
            }
            nguon = "web";
            String loai = loaiNoiDung(p.contentType(), p.url());
            String pu = Url.norm(p.url());
            ObjectNode o = Kho.JSON.createObjectNode();
            o.put("url", pu != null ? pu : p.url());
            o.put("status", p.status());
            o.put("content_type", p.contentType());
            o.put("encoding", p.encoding());
            o.put("fetched_at", Trich.now());
            o.put("size", p.body().length);
            o.put("sha1", sha1(p.body()));
            o.put("kind", "adhoc");
            if (loai.equals("html")) o.put("html_b64", Base64.getEncoder().encodeToString(p.body()));
            else o.putNull("html_b64");
            if (loai.equals("html") || loai.equals("document")) kho.ghiKho(o);   // vào raw-adhoc: lần bóc tách cả kho sau cũng gom được
            if (loai.equals("document")) return bocTepUrl(d, o, p.body(), luuReq, indexReq);
            if (!loai.equals("html"))
                throw new HttpError(415, "url trả về " + (p.contentType().isEmpty() ? "không rõ loại" : p.contentType())
                        + " — không phải trang HTML hay tệp tài liệu; ảnh chỉ có nguồn giới thiệu, xem ở tab Đồ thị");
            rec = o;
        }

        String html = Kho.giaiMa(rec);
        if (html == null) throw new HttpError(422, "trang trả lỗi hoặc không có HTML");
        String recUrl = rec.path("url").asText();
        String pn = Url.norm(recUrl);
        String pageUrl = pn != null ? pn : recUrl;
        BocTach.Ket kq = BocTach.bocTach(html, pageUrl, khuonHost);
        if (kq == null) throw new HttpError(422, "tải được nhưng không bóc ra chữ nào — trang rỗng hoặc dựng bằng JS");

        // 2. lưu
        Map<String, Object> luu = luuMacDinh();
        if (luuReq) {
            if (d == null) {
                luu.put("loi_mongo", "MongoDB không sẵn sàng");
            } else {
                Trich.ghiMotTrang(d, pageUrl, kq, rec, html);
                luu.putAll(Trich.ghiPhuMotTrang(d, kq));
                luu.put("mongo", true);
            }
        }
        if (indexReq) {
            String loi = indexLucene(ApiTimKiem.luceneDoc(kq));
            luu.put("loi_index", loi);
            luu.put("index", loi.isEmpty());
        }
        Map<String, Object> truong = new LinkedHashMap<>();
        truong.put("title", kq.title());
        truong.put("title_src", kq.titleSrc());
        truong.put("date", kq.date());
        truong.put("date_src", kq.dateSrc());
        truong.put("author", kq.author());
        truong.put("author_src", kq.authorSrc());
        truong.put("cited_source", kq.citedSource());
        truong.put("section", kq.section());
        // nội dung đã bóc: HTML đã dọn (≤ 40 KB, để trình bày) và văn bản thuần (đưa vào chỉ mục)
        Map<String, Object> noiDung = new LinkedHashMap<>();
        noiDung.put("html", kq.html());
        noiDung.put("text", HtmlSach.head(kq.text(), TOI_DA_VAN_BAN));
        noiDung.put("bi_cat", HtmlSach.len(kq.text()) > TOI_DA_VAN_BAN);
        List<Map<String, Object>> lienKet = new ArrayList<>();
        for (var e : kq.links()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dst", e.dst);
            m.put("type", e.type);
            m.put("text", e.text);
            m.put("dst_kind", e.dstKind);
            m.put("count", e.count);
            lienKet.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loai", "page");
        out.put("url", pageUrl);
        out.put("host", kq.host());
        out.put("nguon", nguon);
        out.put("fetched_at", rec.path("fetched_at").asText(""));
        out.put("co_mongo", d != null);
        out.put("khuon_host", khuonHost.size());
        out.put("luu", luu);
        out.put("block", kq.block());
        out.put("truong", truong);
        out.put("so_tu", soTu(kq.text()));
        out.put("noi_dung", noiDung);
        out.put("lien_ket", lienKet);
        out.put("so_canh_khuon", kq.navLinks().size());
        return out;
    }

    private static Map<String, Object> luuMacDinh() {
        Map<String, Object> luu = new LinkedHashMap<>();
        luu.put("mongo", false);
        luu.put("loi_mongo", "");
        luu.put("index", false);
        luu.put("loi_index", "");
        return luu;
    }

    /** Nhánh tệp tài liệu của extract/url: bóc chữ, ghi documents + byte, index kind=document. */
    private Object bocTepUrl(MongoDatabase d, JsonNode rec, byte[] data, boolean luuReq, boolean indexReq) throws IOException {
        String url = rec.path("url").asText();
        String host = Url.hostname(url);
        String ct = rec.path("content_type").asText("");
        String mime = HtmlSach.strip(ct.split(";", -1)[0]).toLowerCase(Locale.ROOT);
        String ext = Tep.duoiTuUrl(url);
        if (ext.isEmpty()) ext = Tep.MIME_SANG_DUOI.getOrDefault(mime, "");
        Tep.KetQua kq = Tep.bocChu(data, ext);
        Map<String, Object> luu = luuMacDinh();
        if (luuReq) {
            if (d == null) {
                luu.put("loi_mongo", "MongoDB không sẵn sàng");
            } else {
                Files.createDirectories(filesDir);
                Files.write(filesDir.resolve(rec.path("sha1").asText() + "." + (ext.isEmpty() ? "bin" : ext)), data);
                Document set = new Document("host", host).append("ext", ext).append("mime", mime).append("size", data.length)
                        .append("sha1", rec.path("sha1").asText()).append("fetched_at", rec.path("fetched_at").asText())
                        .append("extractor_version", BocTach.VERSION).append("status", kq.status()).append("text", kq.text())
                        .append("n_pages", kq.nPages()).append("needs_ocr", kq.needsOcr())
                        .append("encoding_suspect", kq.encodingSuspect()).append("error", kq.error());
                d.getCollection("documents").updateOne(eq("_id", url), new Document("$set", set), new UpdateOptions().upsert(true));
                luu.put("mongo", true);
            }
        }
        if (indexReq && kq.status().equals("ok") && !kq.text().isEmpty()) {
            String cuoi = url.substring(url.lastIndexOf('/') + 1).split("\\?", -1)[0];
            String ten = Robots.unquote(cuoi);
            Map<String, String> doc = new java.util.HashMap<>();
            doc.put("url", url);
            doc.put("title", ten.isEmpty() ? url : ten);
            doc.put("text", kq.text());
            doc.put("host", host);
            doc.put("section", "");
            doc.put("date", "");
            doc.put("author", "");
            doc.put("kind", "document");
            doc.put("ftype", ext);
            String loi = indexLucene(doc);
            luu.put("loi_index", loi);
            luu.put("index", loi.isEmpty());
        }
        Map<String, Object> tep = new LinkedHashMap<>();
        tep.put("status", kq.status());
        tep.put("n_pages", kq.nPages());
        tep.put("needs_ocr", kq.needsOcr());
        tep.put("encoding_suspect", kq.encodingSuspect());
        tep.put("error", kq.error());
        Map<String, Object> noiDung = new LinkedHashMap<>();
        noiDung.put("text", HtmlSach.head(kq.text(), TOI_DA_VAN_BAN));
        noiDung.put("bi_cat", HtmlSach.len(kq.text()) > TOI_DA_VAN_BAN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loai", "document");
        out.put("url", url);
        out.put("host", host);
        out.put("nguon", "web");
        out.put("ext", ext);
        out.put("size", data.length);
        out.put("co_mongo", d != null);
        out.put("luu", luu);
        out.put("referrers", d == null ? 0 : d.getCollection("links").countDocuments(eq("dst", url)));
        out.put("tep", tep);
        out.put("noi_dung", noiDung);
        out.put("so_ky_tu", HtmlSach.len(kq.text()));
        return out;
    }

    // ------------------------------------------------------------------ số liệu
    private static Map<String, Object> theoTruong(MongoDatabase d, String coll, String field) {
        Map<String, Object> m = new java.util.TreeMap<>();      // $group không đảm bảo thứ tự; sắp xếp cho phản hồi ổn định
        for (Document x : d.getCollection(coll).aggregate(List.of(group("$" + field, sum("n", 1)))))
            m.put(String.valueOf(x.get("_id")), x.get("n"));
        return m;
    }

    /** Số liệu từng bước của dây chuyền khuôn -> bóc tách -> đồ thị -> tệp. */
    Object overview() {
        MongoDatabase d = mongo.get();
        long nTpl = 0, hoatDong = 0;
        for (Document t : d.getCollection("templates").find().projection(include("n_pages"))) {
            nTpl++;
            Object np = t.get("n_pages");
            if (np instanceof Number num && num.intValue() >= Khuon.TOI_THIEU_TRANG) hoatDong++;
        }
        Map<String, Object> job = viec.status();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", nTpl);
        out.put("templates_active", hoatDong);
        out.put("pages", d.getCollection("pages").countDocuments());
        out.put("links", d.getCollection("links").countDocuments());
        out.put("nav_links", d.getCollection("nav_links").countDocuments());
        out.put("images", d.getCollection("images").countDocuments());
        out.put("documents", theoTruong(d, "documents", "status"));
        out.put("documents_text", d.getCollection("documents").countDocuments(nin("text", null, "")));
        out.put("job", Map.of("running", job.get("running"), "what", job.get("what"), "done", job.get("done")));
        return out;
    }

    /** Danh sách tệp, mỗi tệp kèm số trang giới thiệu nó (cạnh nội dung đi vào). */
    Object files(Http.Req r) {
        MongoDatabase d = mongo.get();
        int skip = Math.max(r.integer("skip", 0), 0), lim = Math.min(r.integer("limit", 50), 200);
        List<Bson> loc = new ArrayList<>();
        if (r.co("status") != null) loc.add(eq("status", r.co("status")));
        if (r.co("host") != null) loc.add(eq("host", r.co("host")));
        Bson q = loc.isEmpty() ? new Document() : and(loc);
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
        out.put("by_status", theoTruong(d, "documents", "status"));
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
        String url = r.bat("url");
        int lim = r.integer("limit", 100);
        String sc = Url.scheme(url);
        if (!sc.equals("http") && !sc.equals("https")) throw new HttpError(422, "url phải là HTTP hoặc HTTPS đầy đủ");
        MongoDatabase d = mongo.get();
        Canon c = canon(d, url);
        Bson q = in("dst", c.tatCa());
        List<Map<String, Object>> noiDung = new ArrayList<>();
        for (Document x : d.getCollection("links").find(q).projection(include("src", "text", "type", "count")).limit(lim)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("src", x.get("src"));
            m.put("text", x.get("text"));
            m.put("type", x.get("type"));
            m.put("count", x.get("count"));
            noiDung.add(m);
        }
        List<Map<String, Object>> khuon = new ArrayList<>();
        for (Document x : d.getCollection("nav_links").find(q).projection(include("host", "text", "n_pages", "sample_src"))
                .sort(descending("n_pages")).limit(lim)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", x.get("host"));
            m.put("text", x.get("text"));
            m.put("n_pages", x.get("n_pages"));
            m.put("sample_src", x.get("sample_src"));
            khuon.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", c.id());
        out.put("content", noiDung);
        out.put("nav", khuon);
        out.put("total_content", d.getCollection("links").countDocuments(q));
        out.put("total_nav", d.getCollection("nav_links").countDocuments(q));
        return out;
    }

    /** Cạnh nội dung đi RA từ một trang. */
    Object graphOut(Http.Req r) {
        String url = r.bat("url");
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
        List<Map<String, Object>> topVao = new ArrayList<>();
        for (Document x : d.getCollection("links").aggregate(List.of(group("$dst", sum("n", 1)), sort(descending("n")), limit(top)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", x.get("_id"));
            m.put("in_content_edges", x.get("n"));
            topVao.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", d.getCollection("pages").countDocuments());
        out.put("content_edges", d.getCollection("links").countDocuments());
        out.put("nav_edges", d.getCollection("nav_links").countDocuments());
        out.put("images", d.getCollection("images").countDocuments());
        out.put("content_edges_by_dst_kind", theoTruong(d, "links", "dst_kind"));
        out.put("top_in_degree", topVao);
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
        return Http.DA_GUI;
    }
}
