package vn.hust.search.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.hust.search.kho.Kho;
import vn.hust.search.mongo.TepJob.Resp;

/**
 * Port của tests/test_mongo.py + test_tep.py (phần Mongo), chạy trên MongoDB THẬT nên $jsonSchema
 * lần đầu được kiểm (mongomock không thực thi nó). Cần {@code MONGO_URL}; không có Mongo thì bỏ qua.
 * Mỗi test dùng một database tạm tên ngẫu nhiên, xoá khi xong.
 */
class MongoTest {
    static final String BAI = """
            <html><body><nav><a href="/tuyen-sinh/">Tuyển sinh</a></nav><main>
            <p>Điểm chuẩn năm nay đã được công bố cho toàn bộ các ngành đào tạo.</p>
            <a href="/uploads/diem-chuan.pdf">Xem chi tiết tại đây</a>
            <img src="/uploads/anh1.jpg" alt="Lễ khai giảng"></main></body></html>""";

    Db d;
    MongoDatabase db;
    @TempDir Path tmp;

    @BeforeEach
    void mo() throws Exception {
        String url = System.getenv().getOrDefault("MONGO_URL", "mongodb://localhost:27017");
        d = new Db(url, "test_" + UUID.randomUUID().toString().replace("-", ""));
        try {
            d.ping();
        } catch (Exception e) {
            d.close();
            d = null;
            assumeTrue(false, "không có MongoDB ở " + url);
        }
        d.init();
        db = d.get();
    }

    @AfterEach
    void dong() {
        if (d != null) {
            d.dropDb();
            d.close();
        }
    }

    static JsonNode rec(String url, String html) throws Exception {
        return Kho.JSON.readTree(Kho.JSON.writeValueAsString(Map.of("url", url, "status", 200, "encoding", "utf-8",
                "fetched_at", "2026-09-01T00:00:00", "html_b64", Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)))));
    }

    List<JsonNode> recs() throws Exception {
        return List.of(
                rec("https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html", BAI),
                rec("https://hust.edu.vn/vi/khac/diem-chuan-654601.html", BAI),            // cùng đoạn cuối: bản trùng
                rec("https://hust.edu.vn/vi/tin-tuc/bai-khac-654601.html", BAI.replace("Điểm chuẩn", "Học phí")),
                Kho.JSON.readTree("{\"url\":\"https://hust.edu.vn/x.pdf\",\"status\":200,\"html_b64\":null}"));
    }

    Map<String, Integer> chay() throws Exception {
        return Trich.chayExtract(db, recs().iterator(), 0, n -> { }, 200);
    }

    long dem(String c) {
        return db.getCollection(c).countDocuments();
    }

    // ------------------------------------------------------------------ Trich
    @Test
    void chayHaiLanKhongNhanDoiBanGhi() throws Exception {
        chay();
        long[] a = {dem("pages"), dem("links"), dem("nav_links"), dem("images")};
        chay();
        assertEquals(List.of(a[0], a[1], a[2], a[3]), List.of(dem("pages"), dem("links"), dem("nav_links"), dem("images")));
        assertEquals(2, a[0]);
    }

    @Test
    void baiTrungDedupKeyThanhBiDanhConSoGiongKhongGop() throws Exception {
        chay();
        Document p = db.getCollection("pages").find(new Document("_id", "https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html")).first();
        assertEquals(List.of("https://hust.edu.vn/vi/khac/diem-chuan-654601.html"), p.getList("aliases", String.class));
        assertNotNull(db.getCollection("pages").find(new Document("_id", "https://hust.edu.vn/vi/tin-tuc/bai-khac-654601.html")).first());
    }

    @Test
    void trangGhiDuTruongTheoLuocDo() throws Exception {
        chay();
        Document p = db.getCollection("pages").find(new Document("_id", "https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html")).first();
        for (String f : List.of("_id", "host", "title", "content", "extractor_version")) assertTrue(p.containsKey(f), f);
        assertTrue(List.of("selector", "heuristic", "fallback").contains(p.get("content", Document.class).get("block", Document.class).getString("method")));
        assertEquals("2026-09-01T00:00:00", p.get("raw", Document.class).getString("fetched_at"));
        assertEquals("2", p.getString("extractor_version"));
    }

    @Test
    void navGopTheoHostVaAnhKhuonDanhDau() throws Exception {
        chay();
        Document n = db.getCollection("nav_links").find(new Document("dst", "https://hust.edu.vn/tuyen-sinh/")).first();
        assertEquals(2, n.getInteger("n_pages"));
        assertEquals("hust.edu.vn", n.getString("host"));
        assertEquals("Tuyển sinh", n.getString("text"));
        assertFalse(db.getCollection("images").find(new Document("_id", "https://hust.edu.vn/uploads/anh1.jpg")).first().getBoolean("is_template"));
    }

    @Test
    void limitDungSom() throws Exception {
        assertEquals(1, Trich.chayExtract(db, recs().iterator(), 1, n -> { }, 200).get("pages"));
    }

    @Test
    void bangTemplatesChiGhiKhoiLapTu3Trang() throws Exception {
        List<JsonNode> r = new ArrayList<>();
        for (int i = 0; i < 25; i++)
            r.add(rec("https://a.hust.edu.vn/p/" + i + ".html", "<body><ul><li>Menu chung</li></ul><p>Bài viết duy nhất "
                    + String.valueOf((char) (97 + i)).repeat(6) + "</p></body>"));
        var out = Trich.dungTemplates(db, r.iterator(), n -> { });
        assertEquals(25, out.get("a.hust.edu.vn").get("n_pages"));
        assertTrue((int) out.get("a.hust.edu.vn").get("template_blocks") >= 1);
        assertEquals(1, Trich.khuonTheoHost(db).size());               // đọc lại từ Mongo được
    }

    @Test
    void idCuaLinksLaSha1CongThucCu() {
        // vector từ Python: hashlib.sha1("src|dst|type|text".encode()).hexdigest()
        assertEquals("9cf30ef1d37b7e12bf7000f632035ddae044a26a",
                Trich.sha1("https://hust.edu.vn/a-1.html", "https://hust.edu.vn/tệp.pdf", "href", "Xem chi tiết"));
    }

    @Test
    void luceneTuMongoCoTrangVaTepCoChuKhongLayTepScan() throws Exception {
        Trich.chayExtract(db, List.of(rec("https://hust.edu.vn/vi/a-1.html",
                "<html><head><meta name='author' content='Lê Văn C'></head><body><main>"
                        + "<p>Nội dung học bổng của trường dành cho sinh viên năm cuối.</p></main></body></html>")).iterator(), 0, n -> { }, 200);
        db.getCollection("documents").insertMany(List.of(
                new Document("_id", "https://hust.edu.vn/uploads/thong-bao%20hoc-bong.pdf").append("host", "hust.edu.vn")
                        .append("ext", "pdf").append("status", "ok").append("text", "Thông báo học bổng"),
                new Document("_id", "https://hust.edu.vn/uploads/scan.pdf").append("host", "hust.edu.vn").append("ext", "pdf")
                        .append("status", "ok").append("text", "").append("needs_ocr", true)));
        Map<String, Map<String, String>> docs = new HashMap<>();
        Trich.luceneTuMongo(db, m -> docs.put(m.get("url"), m));
        assertEquals(2, docs.size());
        var trang = docs.get("https://hust.edu.vn/vi/a-1.html");
        assertEquals("page", trang.get("kind"));
        assertEquals("Lê Văn C", trang.get("author"));
        var tep = docs.get("https://hust.edu.vn/uploads/thong-bao%20hoc-bong.pdf");
        assertEquals("document", tep.get("kind"));
        assertEquals("thong-bao hoc-bong.pdf", tep.get("title"));
        assertEquals("pdf", tep.get("ftype"));
    }

    @Test
    void coverageTheoHost() throws Exception {
        chay();
        var c = Trich.coverage(db).get("hust.edu.vn");
        assertEquals(2, c.get("pages"));
        assertEquals(0.0, c.get("title_pct"));                          // fixture không có tiêu đề
    }

    // ------------------------------------------------------------------ lược đồ (lần đầu chạy trên Mongo thật)
    @Test
    void banGhiSaiLuocDoBiTuChoi() throws Exception {
        chay();
        var pages = db.getCollection("pages");
        var links = db.getCollection("links");
        var tpl = db.getCollection("templates");
        // thiếu title (bắt buộc)
        assertThrows(MongoWriteException.class, () -> pages.insertOne(new Document("_id", "x").append("host", "h")
                .append("content", new Document("text", "t").append("word_count", 1).append("block", new Document("path", "p").append("method", "selector")))
                .append("extractor_version", "2")));
        // method ngoài danh sách
        assertThrows(MongoWriteException.class, () -> pages.insertOne(new Document("_id", "y").append("host", "h").append("title", "t")
                .append("content", new Document("text", "t").append("word_count", 1).append("block", new Document("path", "p").append("method", "la")))
                .append("extractor_version", "2")));
        // published_at sai mẫu YYYY-MM-DD
        Document hop = pages.find().first();
        hop.put("_id", "z");
        hop.put("published_at", "05/09/2026");
        assertThrows(MongoWriteException.class, () -> pages.insertOne(hop));
        // dst_kind lạ, count âm
        Document l = links.find().first();
        l.put("_id", "l1");
        l.put("dst_kind", "video");
        assertThrows(MongoWriteException.class, () -> links.insertOne(l));
        l.put("dst_kind", "page");
        l.put("count", -1);
        assertThrows(MongoWriteException.class, () -> links.insertOne(l));
        // kiểu số: double không phải int/long
        assertThrows(MongoWriteException.class, () -> tpl.insertOne(new Document("_id", "h").append("n_pages", 1.5).append("blocks", new Document())));
        // bản ghi đúng thì được
        tpl.insertOne(new Document("_id", "h").append("n_pages", 3).append("blocks", new Document()));
        assertEquals(1, tpl.countDocuments());
    }

    @Test
    void initChayLaiKhongLoi() throws Exception {
        d.init();
        d.init();
        assertEquals(6, db.listCollectionNames().into(new ArrayList<>()).size());
    }

    @Test
    void ghiMotTrangVaPhu() throws Exception {
        var r = rec("https://hust.edu.vn/vi/tin-tuc/diem-chuan-654601.html", BAI);
        String html = Kho.giaiMa(r);
        var ket = vn.hust.search.boctach.BocTach.bocTach(html, r.get("url").asText(), null);
        Trich.ghiMotTrang(db, r.get("url").asText(), ket, r, html);
        Trich.ghiMotTrang(db, r.get("url").asText(), ket, r, html);          // ghi lại không nhân đôi
        assertEquals(1, dem("pages"));
        assertEquals(2, dem("links"));
        var phu = Trich.ghiPhuMotTrang(db, ket);
        assertEquals(1, phu.get("images"));
        assertEquals(1, phu.get("new_documents"));
        assertEquals(0, Trich.ghiPhuMotTrang(db, ket).get("new_documents"));
        assertEquals("pending", db.getCollection("documents").find().first().getString("status"));
    }

    // ------------------------------------------------------------------ TepJob
    Document linkTep(String dst, String kind) {
        return new Document("_id", dst + "|1").append("src", "https://hust.edu.vn/a.html").append("dst", dst).append("type", "href")
                .append("text", "t").append("dst_kind", kind).append("count", 1).append("src_host", "hust.edu.vn").append("dst_host", "x");
    }

    void nap() {
        db.getCollection("links").insertMany(List.of(
                linkTep("https://hust.edu.vn/uploads/a.pdf", "document"), linkTep("https://svbk.hust.edu.vn/uploads/b.docx", "document"),
                linkTep("https://hust.edu.vn/uploads/c.doc", "document"), linkTep("https://drive.google.com/x.pdf", "external"),
                linkTep("https://hust.edu.vn/cam/d.pdf", "document"), linkTep("https://hust.edu.vn/uploads/e.pdf", "document")));
    }

    static Function<String, Resp> mayChu(Map<String, Resp> routes, List<String> goi) {
        return url -> {
            goi.add(url);
            String path = java.net.URI.create(url).getPath();
            if (path.equals("/robots.txt")) return new Resp(200, "text/plain", "", "User-agent: *\nDisallow: /cam/\n".getBytes());
            return routes.getOrDefault(path, new Resp(404, "", "", new byte[0]));
        };
    }

    @Test
    void danhMucChiLayHostHustVaDocCuCungPending() throws Exception {
        nap();
        var r = TepJob.danhMuc(db, List.<JsonNode>of().iterator());
        assertEquals(5L, ((Number) r.get("total")).longValue());
        assertNull(db.getCollection("documents").find(new Document("_id", "https://drive.google.com/x.pdf")).first());
        assertEquals("pending", db.getCollection("documents").find(new Document("_id", "https://hust.edu.vn/uploads/c.doc")).first().getString("status"));
        assertEquals(0, TepJob.danhMuc(db, List.<JsonNode>of().iterator()).get("new"));          // chạy lại không thêm
    }

    @Test
    void danhMucLayCaBanGhiKhoThoKhongPhaiHtml() throws Exception {
        var rec = Kho.JSON.readTree("{\"url\":\"https://hust.edu.vn/tai?download=1\",\"status\":200,\"content_type\":\"application/pdf\",\"html_b64\":null}");
        TepJob.danhMuc(db, List.of(rec).iterator());
        Document x = db.getCollection("documents").find(new Document("_id", "https://hust.edu.vn/tai?download=1")).first();
        assertEquals("pdf", x.getString("ext"));
        assertEquals("pending", x.getString("status"));
    }

    @Test
    void chuyenDinhDangCuVePending() {
        db.getCollection("documents").insertMany(List.of(
                new Document("_id", "https://hust.edu.vn/a.doc").append("host", "h").append("ext", "doc").append("status", "unsupported"),
                new Document("_id", "https://hust.edu.vn/b.xyz").append("host", "h").append("ext", "xyz").append("status", "unsupported")));
        assertEquals(1, TepJob.chuyenDinhDangCu(db));
        assertEquals("unsupported", db.getCollection("documents").find(new Document("_id", "https://hust.edu.vn/b.xyz")).first().getString("status"));
    }

    @Test
    void taiRoiBocChuTheoRobotsKichThuocVaLoi() throws Exception {
        nap();
        TepJob.danhMuc(db, List.<JsonNode>of().iterator());
        List<String> goi = new ArrayList<>();
        var cli = mayChu(Map.of(
                "/uploads/a.pdf", new Resp(200, "application/pdf", "", Files.readAllBytes(Path.of("src/test/resources/tep/co-chu.pdf"))),
                "/uploads/b.docx", new Resp(200, "", "", Files.readAllBytes(Path.of("src/test/resources/tep/mau.docx"))),
                "/uploads/e.pdf", new Resp(200, "", "", new byte[200_000])), goi);
        Path thuMuc = tmp.resolve("files");
        var r = TepJob.tai(db, thuMuc, n -> { }, cli, 0, 100_000, 0);
        assertEquals(List.of(2, 1), List.of(r.get("errors"), r.get("too_large")));       // robots cấm d.pdf, c.doc 404 (Q2: doc không còn unsupported) ; e.pdf quá lớn
        assertFalse(goi.contains("https://hust.edu.vn/cam/d.pdf"));                       // bị cấm thì KHÔNG gọi
        assertEquals("robots.txt cấm", db.getCollection("documents").find(new Document("_id", "https://hust.edu.vn/cam/d.pdf")).first().getString("error"));
        assertEquals(2, Files.list(thuMuc).count());                                      // a.pdf, b.docx
        int nGoi = goi.size();                                                            // gọi lại không tải lại tệp đã có sha1
        TepJob.tai(db, thuMuc, n -> { }, cli, 0, 100_000, 0);
        assertTrue(goi.subList(nGoi, goi.size()).stream().noneMatch(g -> g.endsWith(".pdf")));

        var kq = TepJob.bocChu(db, thuMuc, n -> { });
        assertEquals(List.of(2, 2), List.of(kq.get("extracted"), kq.get("ok")));
        Document a = db.getCollection("documents").find(new Document("_id", "https://hust.edu.vn/uploads/a.pdf")).first();
        assertEquals("ok", a.getString("status"));
        assertTrue(a.getString("text").contains("hoc bong"));
        Document b = db.getCollection("documents").find(new Document("_id", "https://svbk.hust.edu.vn/uploads/b.docx")).first();
        assertTrue(b.getString("text").contains("học bổng"));
    }

    @Test
    void httpLoiGhiStatusError() throws Exception {
        nap();
        TepJob.danhMuc(db, List.<JsonNode>of().iterator());
        TepJob.tai(db, tmp.resolve("f"), n -> { }, mayChu(Map.of(), new ArrayList<>()), 0, 1 << 20, 0);   // mọi đường dẫn 404
        assertEquals("HTTP 404", db.getCollection("documents").find(new Document("_id", "https://hust.edu.vn/uploads/a.pdf")).first().getString("error"));
    }
}
