package vn.hust.search.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.hust.search.store.Download;
import vn.hust.search.mongo.Db;

/**
 * Port của tests/test_extract_url.py: POST /api/extract/url bóc một url bất kỳ, kể cả url chưa có trong
 * kho. Chạy trên Mongo thật (MONGO_URL, mặc định localhost:27017), database tạm; không có thì bỏ qua.
 */
class ExtractUrlTest {
    static final String ARTICLE = "<html><head><title>Thông báo học bổng 2026 - ĐHBK</title></head><body>"
            + "<nav><a href='/tuyen-sinh/'>Tuyển sinh</a></nav><main>"
            + "<p>Nhà trường thông báo học bổng năm 2026 cho sinh viên, hạn nộp hồ sơ ngày 05/09/2026.</p>"
            + "<a href='/uploads/hb.pdf'>Tải mẫu đơn</a><img src='/uploads/anh.jpg' alt='Lễ trao học bổng'>"
            + "<p>Tác giả: Nguyễn Văn A</p></main></body></html>";

    @TempDir Path tmp;
    Db db;
    TestStack s;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv().getOrDefault("MONGO_URL", "mongodb://localhost:27017");
        db = new Db(url, "test_" + UUID.randomUUID().toString().replace("-", ""));
        try {
            db.ping();
        } catch (Exception e) {
            db.close();
            db = null;
            assumeTrue(false, "không có MongoDB ở " + url);
        }
        db.init();
        s = new TestStack(tmp, db);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.dropDb();
            s.close();
        }
    }

    void web(String content) {
        s.web = u -> TestStack.html(u, content);
    }

    long count(String coll, Document q) {
        return db.get().getCollection(coll).countDocuments(q);
    }

    TestStack.Reply url(String json) throws Exception {
        return s.post("/api/extract/url", json);
    }

    @Test
    void urlNotInStoreIsFetchedFromWebExtractedAndSavedToMongoLucene() throws Exception {
        web(ARTICLE);
        var reply = url("{\"url\":\"http://www.svbk.hust.edu.vn/tin/hb-1.html\"}");
        assertEquals(200, reply.status(), reply.text());
        JsonNode r = reply.json();
        String u = "https://svbk.hust.edu.vn/tin/hb-1.html";            // qua norm()
        assertEquals("web", r.get("nguon").asText());
        assertEquals("page", r.get("loai").asText());
        assertEquals(u, r.get("url").asText());
        assertEquals("Nguyễn Văn A", r.get("truong").get("author").asText());
        assertEquals("2026-09-05", r.get("truong").get("date").asText());
        assertTrue(r.get("luu").get("mongo").asBoolean() && r.get("luu").get("index").asBoolean(), r.get("luu").toString());
        assertTrue(r.get("noi_dung").get("text").asText().contains("Nhà trường thông báo học bổng"));
        assertTrue(r.get("noi_dung").get("html").asText().contains("<p>"));      // HTML đã dọn để trình bày
        assertFalse(r.get("noi_dung").get("text").asText().contains("Tuyển sinh"));   // menu không lọt vào nội dung
        Set<String> cap = new HashSet<>();
        for (JsonNode e : r.get("lien_ket")) cap.add(e.get("dst").asText() + "|" + e.get("text").asText());
        assertEquals(Set.of("https://svbk.hust.edu.vn/uploads/hb.pdf|Tải mẫu đơn",
                "https://svbk.hust.edu.vn/uploads/anh.jpg|Lễ trao học bổng"), cap);
        assertEquals(1, r.get("so_canh_khuon").asInt());
        assertEquals("Nguyễn Văn A", db.get().getCollection("pages").find(new Document("_id", u)).first().getString("author"));
        assertEquals(2, count("links", new Document("src", u)));
        assertEquals("pending", db.get().getCollection("documents")
                .find(new Document("_id", "https://svbk.hust.edu.vn/uploads/hb.pdf")).first().getString("status"));
        assertEquals(List.of("Lễ trao học bổng"), db.get().getCollection("images")
                .find(new Document("_id", "https://svbk.hust.edu.vn/uploads/anh.jpg")).first().getList("alts", String.class));
        assertEquals("Nguyễn Văn A", s.get("/api/search?q=h%E1%BB%8Dc+b%E1%BB%95ng").json().get("hits").get(0).get("author").asText());
        assertTrue(Files.exists(tmp.resolve("data/raw-adhoc/pages-0001.jsonl.gz")));    // ghi raw-adhoc
        assertEquals(0, count("nav_links", new Document()));            // nav không ghi lẻ (tránh đếm đôi)
    }

    @Test
    void fetchesWithOriginalUrlWithoutForcingHttps() throws Exception {
        web(ARTICLE);
        var r = url("{\"url\":\"http://site-chi-co-http.example/a.html\"}").json();
        assertEquals(List.of("http://site-chi-co-http.example/a.html"), s.fetched);   // tải đúng http
        assertEquals("https://site-chi-co-http.example/a.html", r.get("url").asText());   // khoá lưu qua norm()
    }

    @Test
    void httpsConnectionFailureRetriesHttpButHttpErrorDoesNot() throws Exception {
        s.web = u -> {
            if (u.startsWith("https://")) throw new HttpError(502, "không tải được: SSL wrong version number");
            return TestStack.html(u, ARTICLE);
        };
        assertEquals(200, url("{\"url\":\"https://site-chi-co-http.example/a.html\"}").status());
        assertEquals(List.of("https://site-chi-co-http.example/a.html", "http://site-chi-co-http.example/a.html"), s.fetched);

        s.fetched.clear();
        s.web = u -> { throw new HttpError(502, "site trả HTTP 404"); };
        assertEquals(502, url("{\"url\":\"https://x.example/khong-co\"}").status());
        assertEquals(1, s.fetched.size());                    // 404 thật thì không thử lại
    }

    void append(String u, String html) throws Exception {
        s.store.append(rec(u, html));
    }

    static JsonNode rec(String u, String html) throws Exception {
        return vn.hust.search.store.RawStore.JSON.readTree(vn.hust.search.store.RawStore.JSON.writeValueAsString(Map.of("url", u, "status", 200,
                "encoding", "utf-8", "html_b64", Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void urlInStoreDoesNotCallWeb() throws Exception {
        append("https://hust.edu.vn/vi/a-1.html", ARTICLE);
        web("");
        var r = url("{\"url\":\"https://hust.edu.vn/vi/a-1.html\"}").json();
        assertEquals("kho", r.get("nguon").asText());
        assertTrue(s.fetched.isEmpty());
    }

    @Test
    void refetchSkipsStore() throws Exception {
        append("https://hust.edu.vn/vi/a-1.html", "<p>cu</p>");
        web(ARTICLE);
        var r = url("{\"url\":\"https://hust.edu.vn/vi/a-1.html\",\"tai_lai\":true}").json();
        assertEquals("web", r.get("nguon").asText());
    }

    @Test
    void runningTwiceDoesNotDuplicate() throws Exception {
        web(ARTICLE);
        for (int i = 0; i < 2; i++) url("{\"url\":\"https://svbk.hust.edu.vn/tin/hb-1.html\"}");
        assertEquals(1, count("pages", new Document()));
        assertEquals(2, count("links", new Document()));
        assertEquals(List.of("Lễ trao học bổng"), db.get().getCollection("images").find().first().getList("alts", String.class));
    }

    @Test
    void noSaveNoIndexWhenDisabled() throws Exception {
        web(ARTICLE);
        var r = url("{\"url\":\"https://x.hust.edu.vn/a\",\"luu\":false,\"index\":false}").json();
        assertFalse(r.get("luu").get("mongo").asBoolean() || r.get("luu").get("index").asBoolean());
        assertEquals(0, count("pages", new Document()));
        assertEquals(0, s.idx.numDocs());
    }

    @Test
    void pdfUrlExtractsTextAndWritesDocuments() throws Exception {
        byte[] pdf = Files.readAllBytes(Path.of("src/test/resources/tep/co-chu.pdf"));
        s.web = u -> new Download.Response(u, 200, "application/pdf", "utf-8", pdf);
        var reply = url("{\"url\":\"https://hust.edu.vn/uploads/hb.pdf\"}");
        assertEquals(200, reply.status(), reply.text());
        JsonNode r = reply.json();
        assertEquals("document", r.get("loai").asText());
        assertEquals("ok", r.get("tep").get("status").asText());
        Document doc = db.get().getCollection("documents").find(new Document("_id", "https://hust.edu.vn/uploads/hb.pdf")).first();
        assertTrue(doc.getString("text").contains("hoc bong"));
        assertTrue(r.get("noi_dung").get("text").asText().contains("hoc bong"));
        try (var fs = Files.list(tmp.resolve("data/files"))) {
            assertEquals(1, fs.count());
        }
        var hit = s.get("/api/search?q=hoc+bong&kind=document").json().get("hits").get(0);
        assertEquals("document", hit.get("kind").asText());
        assertEquals("pdf", hit.get("ftype").asText());
        // kho thô không nhét byte pdf vào html_b64
        assertTrue(s.store.allRecords().next().get("html_b64").isNull());
    }

    @Test
    void imageAndInvalidUrlRejected() throws Exception {
        s.web = u -> new Download.Response(u, 200, "image/png", "utf-8", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertEquals(415, url("{\"url\":\"https://hust.edu.vn/a.png\"}").status());
        assertEquals(422, url("{\"url\":\"ftp://x/y\"}").status());
    }

    @Test
    void mongoDownStillExtractsAndReportsSaveError() throws Exception {
        db.dropDb();
        var dead = new TestStack(tmp.resolve("chet"), null);
        try {
            dead.web = u -> TestStack.html(u, ARTICLE);
            var r = dead.post("/api/extract/url", "{\"url\":\"https://x.hust.edu.vn/a\"}").json();
            assertFalse(r.get("co_mongo").asBoolean());
            assertEquals("MongoDB không sẵn sàng", r.get("luu").get("loi_mongo").asText());
            assertTrue(r.get("luu").get("index").asBoolean());
        } finally {
            dead.close();
        }
    }

    @Test
    void graphOutReportsUrlWithoutPage() throws Exception {
        assertFalse(s.get("/api/graph/out?url=https://x.hust.edu.vn/chua-co").json().get("co_trang").asBoolean());
        web(ARTICLE);
        url("{\"url\":\"https://x.hust.edu.vn/chua-co\"}");
        var r = s.get("/api/graph/out?url=https://x.hust.edu.vn/chua-co").json();
        assertTrue(r.get("co_trang").asBoolean());
        assertEquals(2, r.get("edges").size());
        // referrers: cạnh vào pdf; csv xuất cạnh; thống kê đồ thị; tổng quan
        var ref = s.get("/api/referrers?url=https://x.hust.edu.vn/uploads/hb.pdf").json();
        assertEquals(1, ref.get("total_content").asInt());
        assertEquals("https://x.hust.edu.vn/chua-co", ref.get("content").get(0).get("src").asText());
        var csv = s.get("/api/graph/edges.csv").text();
        assertTrue(csv.startsWith("source,target,text\r\n"), csv);
        assertTrue(csv.contains("https://x.hust.edu.vn/chua-co,https://x.hust.edu.vn/uploads/hb.pdf,Tải mẫu đơn\r\n"), csv);
        assertEquals(2, s.get("/api/graph/stats").json().get("content_edges").asInt());
        assertEquals(1, s.get("/api/extract/overview").json().get("pages").asInt());
        assertEquals(1, s.get("/api/files?status=pending").json().get("items").size());
        assertEquals(1, s.get("/api/images?template=false").json().get("total").asInt());
        assertEquals(422, s.get("/api/images?template=maybe").status());
        assertNotNull(s.get("/api/extract/coverage").json().get("x.hust.edu.vn"));
    }

    JsonNode awaitJob() throws Exception {
        for (int i = 0; i < 200; i++) {
            var st = s.get("/api/extract/status").json();
            if (!st.get("running").asBoolean()) return st;
            Thread.sleep(50);
        }
        throw new AssertionError("việc nền không xong");
    }

    @Test
    void backgroundJobsOverHttpRunOneAtATime() throws Exception {
        append("https://hust.edu.vn/vi/tin-tuc/a-1.html", ARTICLE);
        append("https://hust.edu.vn/vi/tin-tuc/b-2.html", ARTICLE.replace("học bổng", "học phí"));
        var first = s.post("/api/extract/run", "");
        assertEquals(200, first.status(), first.text());
        assertEquals("extract", first.json().get("started").asText());
        var st = awaitJob();
        assertTrue(st.get("error").isNull(), st.toString());
        assertEquals(2, st.get("result").get("pages").asInt(), st.toString());
        assertEquals("extract", st.get("what").asText());
        assertEquals(2, count("pages", new Document()));
        assertEquals(200, s.post("/api/extract/templates", "{}").status());
        awaitJob();
        assertEquals(1, count("templates", new Document()));
        // việc đang chạy thì từ chối việc thứ hai
        var firstJob = s.jobs.run("giu", progress -> { Thread.sleep(300); return "xong"; });
        assertEquals("giu", firstJob.get("started"));
        var second = s.post("/api/extract/run", "{}");
        assertEquals(409, second.status());
        assertEquals("đang chạy: giu", second.json().get("detail").asText());
        assertEquals(409, s.post("/api/extract/templates", "{}").status());
        awaitJob();
    }
}
