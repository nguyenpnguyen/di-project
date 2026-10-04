package vn.hust.search.mongo;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.bson.Document;
import vn.hust.search.boctach.BocTach;
import vn.hust.search.boctach.HtmlSach;
import vn.hust.search.boctach.Khuon;
import vn.hust.search.boctach.LienKet;
import vn.hust.search.boctach.Tep;
import vn.hust.search.kho.Kho;
import vn.hust.search.kho.Url;

/**
 * Kho thô -> bóc tách -> MongoDB — bản port của {@code trich.py}.
 * Chạy lại bao nhiêu lần cũng không nhân đôi: {@code pages} ghi theo _id, {@code links} xoá theo
 * {@code src} rồi ghi lại, {@code nav_links}/{@code images} dựng lại từ đầu mỗi lần chạy.
 */
public final class Trich {
    private Trich() {}

    /**
     * Chỉ giữ vân tay có mặt trên ít nhất chừng này trang: khối chỉ xuất hiện 1-2 lần không thể là
     * khuôn, mà bảng đầy đủ của 6.000 trang có thể vượt 16 MB của một document Mongo.
     */
    public static final int TOI_THIEU_DEM = 3;
    public static final double NGUONG_GIU = 0.05;
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    /** {@code _id} của links / nav_links: sha1 của các phần nối bằng '|'. Công thức giữ nguyên bản Python để dữ liệu cũ vẫn khớp. */
    public static String sha1(String... parts) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    static String host(String url) {
        return Url.hostname(url);
    }

    /** Lớp 2: đếm khối lặp theo host rồi ghi collection {@code templates}. */
    public static Map<String, Map<String, Object>> dungTemplates(MongoDatabase db, Iterator<JsonNode> records, IntConsumer onProgress) {
        Map<String, List<Set<String>>> theoHost = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int n = 0;
        while (records.hasNext()) {
            JsonNode rec = records.next();
            String raw = rec.path("url").asText();
            String url = Url.norm(raw);
            if (url == null) url = raw;
            if (!seen.add(url)) continue;
            String html = Kho.giaiMa(rec);
            if (html == null) continue;
            theoHost.computeIfAbsent(host(url), h -> new ArrayList<>()).add(Khuon.vanTayTrang(BocTach.parse(html)));
            if (++n % 100 == 0) onProgress.accept(n);
        }
        db.getCollection("templates").deleteMany(new Document());
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (var e : theoHost.entrySet()) {
            var bang = Khuon.demHost(e.getValue());
            double giu = Math.max(TOI_THIEU_DEM, NGUONG_GIU * bang.nPages());
            Map<String, Integer> blocks = new HashMap<>();
            bang.blocks().forEach((v, c) -> {
                if (c >= giu) blocks.put(v, c);
            });
            db.getCollection("templates").replaceOne(eq("_id", e.getKey()),
                    new Document("_id", e.getKey()).append("n_pages", bang.nPages()).append("blocks", new Document(new HashMap<String, Object>(blocks))), UPSERT);
            out.put(e.getKey(), Map.of("n_pages", bang.nPages(), "template_blocks", Khuon.tapKhuon(new Khuon.Bang(bang.nPages(), blocks)).size()));
        }
        return out;
    }

    public static Map<String, Set<String>> khuonTheoHost(MongoDatabase db) {
        Map<String, Set<String>> out = new HashMap<>();
        for (Document t : db.getCollection("templates").find()) {
            Map<String, Integer> blocks = new HashMap<>();
            t.get("blocks", Document.class).forEach((k, v) -> blocks.put(k, ((Number) v).intValue()));
            out.put(t.getString("_id"), Khuon.tapKhuon(new Khuon.Bang(((Number) t.get("n_pages")).intValue(), blocks)));
        }
        return out;
    }

    /** Kết quả bóc tách -> (document {@code pages}, các document {@code links}). */
    public record BanGhi(Document page, List<Document> links) {}

    public static BanGhi dungBanGhi(String url, String host, BocTach.Ket d, JsonNode rec, String html) {
        String sha = java.util.HexFormat.of().formatHex(sha1Bytes(html.getBytes(StandardCharsets.UTF_8)));
        String[] words = HtmlSach.WS.split(HtmlSach.strip(d.text()));
        int wc = d.text().isBlank() ? 0 : words.length;
        Document page = new Document("_id", url).append("aliases", new ArrayList<String>()).append("host", host)
                .append("lang", Url.pathOf(url).startsWith("/en/") ? "en" : "vi").append("kind", Url.kindOf(url))
                .append("title", d.title()).append("title_src", d.titleSrc())
                .append("published_at", d.date()).append("published_at_src", d.dateSrc())
                .append("author", d.author()).append("author_src", d.authorSrc()).append("cited_source", d.citedSource())
                .append("section", d.section())
                .append("content", new Document("text", d.text()).append("html", d.html()).append("word_count", wc)
                        .append("block", new Document("path", d.block().path()).append("score", d.block().score())
                                .append("method", d.block().method())))
                .append("raw", new Document("sha1", sha).append("fetched_at", rec.path("fetched_at").asText("")))
                .append("extractor_version", BocTach.VERSION).append("extracted_at", now());
        List<Document> links = new ArrayList<>();
        for (var e : d.links()) {
            links.add(new Document("_id", sha1(url, e.dst, e.type, e.text)).append("src", url).append("dst", e.dst)
                    .append("type", e.type).append("text", e.text).append("dst_kind", e.dstKind).append("count", e.count)
                    .append("src_host", host).append("dst_host", host(e.dst)));
        }
        return new BanGhi(page, links);
    }

    private static byte[] sha1Bytes(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ghi một trang tải lẻ: pages + links của nó, không đụng nav_links và images (hai bảng tổng hợp, dựng lại ở extract/run). */
    public static void ghiMotTrang(MongoDatabase db, String url, BocTach.Ket d, JsonNode rec, String html) {
        String n = Url.norm(url);
        url = n != null ? n : url;
        var br = dungBanGhi(url, host(url), d, rec, html);
        db.getCollection("pages").replaceOne(eq("_id", url), br.page(), UPSERT);
        db.getCollection("links").deleteMany(eq("src", url));
        if (!br.links().isEmpty()) db.getCollection("links").insertMany(br.links(), new InsertManyOptions().ordered(false));
    }

    /** Phần phụ khi ghi một trang lẻ: danh mục ảnh và danh mục tệp tài liệu mà trang trỏ tới (chỉ upsert, không xoá). */
    public static Map<String, Integer> ghiPhuMotTrang(MongoDatabase db, BocTach.Ket d) {
        int nAnh = 0, nTep = 0;
        var up = new UpdateOptions().upsert(true);
        for (var e : d.links()) {
            if (e.dstKind.equals("image")) {
                var upd = new ArrayList<org.bson.conversions.Bson>(List.of(Updates.set("is_template", false),
                        Updates.setOnInsert("host", host(e.dst))));
                upd.add(e.text.isEmpty() ? Updates.setOnInsert("alts", new ArrayList<String>()) : Updates.addToSet("alts", e.text));
                db.getCollection("images").updateOne(eq("_id", e.dst), Updates.combine(upd), up);
                nAnh++;
            } else if (e.dstKind.equals("document")) {
                String h = host(e.dst);
                if (!LienKet.trongHoHust(h)) continue;
                var r = db.getCollection("documents").updateOne(eq("_id", e.dst), Updates.combine(
                        Updates.setOnInsert("host", h), Updates.setOnInsert("ext", Tep.duoiTuUrl(e.dst)),
                        Updates.setOnInsert("mime", ""), Updates.setOnInsert("extractor_version", BocTach.VERSION),
                        Updates.setOnInsert("status", "pending")), up);
                if (r.getUpsertedId() != null) nTep++;
            }
        }
        for (var e : d.navLinks()) {
            if (e.dstKind.equals("image"))
                db.getCollection("images").updateOne(eq("_id", e.dst), Updates.combine(Updates.setOnInsert("host", host(e.dst)),
                        Updates.setOnInsert("alts", new ArrayList<String>()), Updates.setOnInsert("is_template", true)), up);
        }
        return Map.of("images", nAnh, "new_documents", nTep);
    }

    /** Tài liệu để index, đọc từ Mongo: mọi trang, và tệp đã bóc được chữ. */
    public static void luceneTuMongo(MongoDatabase db, Consumer<Map<String, String>> f) {
        for (Document p : db.getCollection("pages").find()) {
            var c = p.get("content", Document.class);
            Map<String, String> m = new HashMap<>();
            m.put("url", p.getString("_id"));
            m.put("title", p.getString("title"));
            m.put("text", c.getString("text"));
            m.put("host", p.getString("host"));
            m.put("section", p.getString("section") == null ? "" : p.getString("section"));
            m.put("date", p.getString("published_at") == null ? "" : p.getString("published_at"));
            m.put("html", c.getString("html") == null ? "" : c.getString("html"));
            m.put("author", p.getString("author") == null ? "" : p.getString("author"));
            m.put("kind", "page");
            f.accept(m);
        }
        tepTuMongo(db, f);
    }

    /**
     * Tệp tài liệu đã bóc được chữ. Chữ của tệp CHỈ có trong Mongo (kho crawl không lưu byte
     * pdf/docx), nên mọi nguồn index — kể cả kho thô — đều phải lấy tệp từ đây.
     */
    public static void tepTuMongo(MongoDatabase db, Consumer<Map<String, String>> f) {
        var loc = com.mongodb.client.model.Filters.and(eq("status", "ok"), com.mongodb.client.model.Filters.nin("text", null, ""));
        for (Document d : db.getCollection("documents").find(loc)) {
            String id = d.getString("_id");
            String title = d.getString("title");
            Map<String, String> m = new HashMap<>();
            m.put("url", id);
            m.put("title", title != null && !title.isEmpty() ? title : Robots.unquote(id.substring(id.lastIndexOf('/') + 1)));
            m.put("text", d.getString("text"));
            m.put("host", d.getString("host"));
            m.put("section", "");
            m.put("date", "");
            m.put("html", "");
            m.put("author", "");
            m.put("kind", "document");
            m.put("ftype", d.getString("ext") == null ? "" : d.getString("ext"));
            f.accept(m);
        }
    }

    /** Chạy toàn bộ kho -> pages/links/nav_links/images. Bộ đệm {@code batch} trang mỗi lượt ghi. */
    public static Map<String, Integer> chayExtract(MongoDatabase db, Iterator<JsonNode> records, int limit,
                                                   IntConsumer onProgress, int batch) {
        var kh = khuonTheoHost(db);
        Map<String, String> daThay = new HashMap<>();            // khoá khử trùng -> _id trang chính
        Map<List<String>, Object[]> nav = new LinkedHashMap<>();  // (host,dst,type,text) -> {dst_kind, n_pages, sample_src}
        Map<String, Object[]> anh = new LinkedHashMap<>();        // url -> {alts(TreeSet), content}
        List<Document> pagesBuf = new ArrayList<>();
        List<Document> linksBuf = new ArrayList<>();
        List<String> srcBuf = new ArrayList<>();
        Map<String, Document> bufIdx = new HashMap<>();
        int n = 0, skipped = 0, nLinks = 0;

        while (records.hasNext()) {
            JsonNode rec = records.next();
            String raw = rec.path("url").asText();
            String url = Url.norm(raw);
            if (url == null) url = raw;
            String key = Url.dedupKey(url);
            if (key == null) key = url;
            String chinh = daThay.get(key);
            if (chinh != null) {                                  // bản trùng: chỉ ghi thêm bí danh
                if (!chinh.equals(url)) {
                    Document trong = bufIdx.get(chinh);
                    if (trong != null) {                          // trang chính còn trong bộ đệm, chưa ghi
                        @SuppressWarnings("unchecked") List<String> al = (List<String>) trong.get("aliases");
                        if (!al.contains(url)) al.add(url);
                    } else {
                        db.getCollection("pages").updateOne(eq("_id", chinh), Updates.addToSet("aliases", url));
                    }
                }
                continue;
            }
            String html = Kho.giaiMa(rec);
            String host = host(url);
            var d = html != null ? BocTach.bocTach(html, url, kh.get(host)) : null;
            if (d == null) {
                skipped++;
                continue;
            }
            daThay.put(key, url);
            var br = dungBanGhi(url, host, d, rec, html);
            pagesBuf.add(br.page());
            srcBuf.add(url);
            bufIdx.put(url, br.page());
            linksBuf.addAll(br.links());
            for (var e : d.links()) {
                if (e.dstKind.equals("image")) {
                    Object[] a = anh.computeIfAbsent(e.dst, k -> new Object[]{new TreeSet<String>(), false});
                    a[1] = true;
                    if (!e.text.isEmpty()) {
                        @SuppressWarnings("unchecked") var alts = (TreeSet<String>) a[0];
                        alts.add(e.text);
                    }
                }
            }
            nLinks += d.links().size();
            for (var e : d.navLinks()) {
                Object[] v = nav.computeIfAbsent(List.of(host, e.dst, e.type, e.text),
                        k -> new Object[]{e.dstKind, 0, new ArrayList<String>()});
                v[1] = (int) v[1] + 1;
                @SuppressWarnings("unchecked") var ss = (List<String>) v[2];
                if (ss.size() < 3) ss.add(url);
                if (e.dstKind.equals("image")) anh.computeIfAbsent(e.dst, k -> new Object[]{new TreeSet<String>(), false});
            }
            n++;
            if (pagesBuf.size() >= batch) {
                xa(db, pagesBuf, linksBuf, srcBuf);
                bufIdx.clear();
            }
            if (n % 100 == 0) onProgress.accept(n);
            if (limit > 0 && n >= limit) break;
        }
        xa(db, pagesBuf, linksBuf, srcBuf);
        bufIdx.clear();

        db.getCollection("nav_links").deleteMany(new Document());
        List<Document> docs = new ArrayList<>();
        for (var e : nav.entrySet()) {
            var k = e.getKey();
            docs.add(new Document("_id", sha1(k.get(0), k.get(1), k.get(2), k.get(3))).append("host", k.get(0))
                    .append("dst", k.get(1)).append("type", k.get(2)).append("text", k.get(3))
                    .append("dst_kind", e.getValue()[0]).append("n_pages", e.getValue()[1]).append("sample_src", e.getValue()[2]));
        }
        chen(db, "nav_links", docs);
        db.getCollection("images").deleteMany(new Document());
        List<Document> imgs = new ArrayList<>();
        for (var e : anh.entrySet()) {
            imgs.add(new Document("_id", e.getKey()).append("host", host(e.getKey()))
                    .append("alts", new ArrayList<>((TreeSet<?>) e.getValue()[0])).append("is_template", !(boolean) e.getValue()[1]));
        }
        chen(db, "images", imgs);
        return Map.of("pages", n, "skipped", skipped, "links", nLinks, "nav_links", docs.size(), "images", imgs.size());
    }

    private static void chen(MongoDatabase db, String coll, List<Document> docs) {
        for (int i = 0; i < docs.size(); i += 1000)
            db.getCollection(coll).insertMany(docs.subList(i, Math.min(i + 1000, docs.size())), new InsertManyOptions().ordered(false));
    }

    private static void xa(MongoDatabase db, List<Document> pages, List<Document> links, List<String> src) {
        if (!pages.isEmpty()) {
            db.getCollection("links").deleteMany(in("src", src));
            var ops = new ArrayList<ReplaceOneModel<Document>>();
            for (Document p : pages) ops.add(new ReplaceOneModel<>(eq("_id", p.getString("_id")), p, UPSERT));
            db.getCollection("pages").bulkWrite(ops);
            chen(db, "links", links);
        }
        pages.clear();
        links.clear();
        src.clear();
    }

    /** % trường khác rỗng theo host và tỉ lệ từng {@code method} chọn khối. */
    public static Map<String, Map<String, Object>> coverage(MongoDatabase db) {
        Map<String, Map<String, Object>> theo = new LinkedHashMap<>();
        var proj = new Document("host", 1).append("title", 1).append("published_at", 1).append("author", 1)
                .append("content.block.method", 1).append("content.word_count", 1);
        for (Document p : db.getCollection("pages").find().projection(proj)) {
            Map<String, Object> h = theo.computeIfAbsent(p.getString("host"), k -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("pages", 0);
                m.put("title", 0);
                m.put("published_at", 0);
                m.put("author", 0);
                m.put("methods", new LinkedHashMap<String, Integer>());
                return m;
            });
            h.merge("pages", 1, (a, b) -> (int) a + (int) b);
            for (String f : List.of("title", "published_at", "author"))
                if (p.get(f) != null && !p.getString(f).isEmpty()) h.merge(f, 1, (a, b) -> (int) a + (int) b);
            @SuppressWarnings("unchecked") var methods = (Map<String, Integer>) h.get("methods");
            methods.merge(p.get("content", Document.class).get("block", Document.class).getString("method"), 1, Integer::sum);
        }
        for (var h : theo.values())
            for (String f : List.of("title", "published_at", "author"))
                h.put(f + "_pct", Math.round(1000.0 * (int) h.get(f) / (int) h.get("pages")) / 10.0);
        return theo;
    }
}
