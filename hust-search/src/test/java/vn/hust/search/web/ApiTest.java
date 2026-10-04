package vn.hust.search.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port của tests/test_api.py (kiểm tra hợp lệ PublicDocument, tải lẻ không cần mạng) cộng hợp đồng
 * lỗi chung: {"detail": chuỗi}, 404/405, tìm kiếm sai cú pháp giữ khoá "error". Không cần Mongo.
 */
class ApiTest {
    @TempDir Path tmp;
    TestStack s;

    @BeforeEach
    void dung() throws Exception {
        s = new TestStack(tmp, null);
    }

    @AfterEach
    void dong() throws Exception {
        s.close();
    }

    @Test
    void publicDocumentHopLeVaSuyHostTuUrl() throws Exception {
        var r = s.post("/api/index/documents", """
                {"documents":[
                  {"url":"https://fixture.local/doc","content":"nội dung fixturealpha",
                   "outgoing_links":[{"url":"https://fixture.local/next","text":"Đi tiếp"}]},
                  {"url":"https://fixture.local/doc","title":"bản sau thắng","content":"fixturealpha"}]}""");
        assertEquals(200, r.status());
        assertEquals(1, r.json().get("indexed").asInt());
        assertEquals(1, r.json().get("duplicates_in_request").asInt());
        var t = s.get("/api/search?q=fixturealpha");
        assertEquals("fixture.local", t.json().get("hits").get(0).get("host").asText());
        assertEquals("bản sau thắng", t.json().get("hits").get(0).get("title").asText());
    }

    @Test
    void publicDocumentCatDoDaiVaTuChoiSai() throws Exception {
        String dai = "a".repeat(501), noiDung = "b".repeat(200_001);
        var ok = s.post("/api/index/documents", "{\"documents\":[{\"url\":\"https://fixture.local/capped\",\"title\":\""
                + dai + "\",\"content\":\"" + noiDung + "\"}]}");
        assertEquals(200, ok.status());
        var l = s.get("/api/index/list?kind=page");
        assertEquals(500, l.json().get("items").get(0).get("title").asText().length());
        for (String sai : new String[]{
                "{\"url\":\"javascript:alert(1)\",\"title\":\"x\"}",
                "{\"url\":\"https://fixture.local/empty\"}",
                "{\"url\":\"https://fixture.local/bad-date\",\"title\":\"x\",\"published_at\":\"not-a-date\"}",
                "{\"url\":\"https://fixture.local/k\",\"title\":\"x\",\"kind\":\"video\"}",
                "{\"url\":\"https://fixture.local/t\",\"title\":5}"}) {
            var r = s.post("/api/index/documents", "{\"documents\":[" + sai + "]}");
            assertEquals(422, r.status(), sai);
            assertTrue(r.json().get("detail").isTextual(), sai);
        }
        assertEquals(422, s.post("/api/index/documents", "{}").status());
    }

    @Test
    void fetchTraVePublicDocumentKhongCanMang() throws Exception {
        s.web = u -> TestStack.html(u, "<html><head><title>Bai test</title></head><body><main>"
                + "<p>Noi dung bai.</p><a href='/next#part' title=' Mo ta next '></a></main></body></html>");
        var r = s.post("/api/fetch", "{\"url\":\"https://fixture.local/article\"}");
        assertEquals(200, r.status(), r.text());
        var doc = r.json().get("document");
        assertEquals("Bai test", doc.get("title").asText());
        assertEquals("Noi dung bai.", doc.get("content").asText());
        assertEquals("https://fixture.local/next", doc.get("outgoing_links").get(0).get("url").asText());
        assertEquals("Mo ta next", doc.get("outgoing_links").get(0).get("text").asText());
        assertTrue(r.json().get("indexed").asBoolean());
        assertEquals(1, r.json().get("index_docs").asInt());
        // tải lẻ ghi vào raw-adhoc, đọc lại được
        assertTrue(Files.exists(tmp.resolve("data/raw-adhoc/pages-0001.jsonl.gz")));
        assertEquals("https://fixture.local/article", s.kho.banGhi().next().get("url").asText());
        // và tìm được ngay, kèm html để xem trước
        assertEquals(1, s.get("/api/search?q=Noi+dung").json().get("total").asInt());
        assertTrue(s.get("/api/preview?url=https://fixture.local/article").json().has("html"));
    }

    @Test
    void fetchTuChoiUrlKhongPhaiHttp() throws Exception {
        assertEquals(422, s.post("/api/fetch", "{\"url\":\"ftp://fixture.local/article\"}").status());
        assertTrue(s.daTai.isEmpty());
    }

    @Test
    void fetchTrangRongLa422() throws Exception {
        s.web = u -> TestStack.html(u, "<html><body></body></html>");
        var r = s.post("/api/fetch", "{\"url\":\"https://fixture.local/rong\"}");
        assertEquals(422, r.status());
        assertTrue(r.json().get("detail").asText().contains("không bóc ra chữ nào"));
    }

    @Test
    void loiHttpCuaSiteThanhDetail() throws Exception {
        s.web = u -> { throw new HttpError(429, "site đang chặn nhịp, đợi rồi thử lại"); };
        var r = s.post("/api/fetch", "{\"url\":\"https://fixture.local/a\"}");
        assertEquals(429, r.status());
        assertEquals("site đang chặn nhịp, đợi rồi thử lại", r.json().get("detail").asText());
    }

    @Test
    void searchValidationVaLoiLucene() throws Exception {
        var thieu = s.get("/api/search");
        assertEquals(422, thieu.status());
        assertTrue(thieu.json().get("detail").isTextual());
        var rk = s.get("/api/search?q=a&ranking=xyz");
        assertEquals(400, rk.status());
        assertEquals("ranking phải là tfidf hoặc enhanced", rk.json().get("detail").asText());
        assertEquals(422, s.get("/api/search?q=a&size=abc").status());
        // lỗi của Lucene giữ khoá "error": giao diện đọc d.error
        var cuPhap = s.get("/api/search?q=%C4%91i%E1%BB%83m%20AND%20AND");
        assertEquals(400, cuPhap.status());
        assertTrue(cuPhap.json().has("error"), cuPhap.text());
        assertEquals("thiếu tham số term", s.get("/api/index/posting?field=text").json().get("detail").asText());
    }

    @Test
    void indexRunTuKhoThoKhiKhongCoMongo() throws Exception {
        String html = "<html><head><title>Diem chuan</title></head><body><main><p>Diem chuan nam nay cong bo.</p></main></body></html>";
        s.web = u -> TestStack.html(u, html);
        assertEquals(200, s.post("/api/fetch", "{\"url\":\"https://hust.edu.vn/vi/a-1.html\"}").status());
        assertEquals(400, s.post("/api/index/run", "{\"source\":\"bậy\"}").status());
        assertEquals(503, s.post("/api/index/run", "{\"source\":\"mongo\"}").status());
        var r = s.post("/api/index/run", "{\"reset\":true,\"source\":\"auto\"}");
        assertEquals(200, r.status(), r.text());
        assertEquals("raw", r.json().get("source").asText());
        assertEquals(1, r.json().get("indexed").asInt());
        assertEquals(1, r.json().get("index").get("docs").asInt());
        assertEquals(422, s.post("/api/index/run", "{\"batch\":0}").status());
    }

    @Test
    void tuyenDuongVaTinh() throws Exception {
        assertEquals(404, s.get("/api/khong-co").status());
        assertEquals("Not Found", s.get("/api/khong-co").json().get("detail").asText());
        assertEquals(405, s.post("/api/search", "{}").status());
        Files.writeString(tmp.resolve("static/index.html"), "<h1>xin chào</h1>");
        Files.writeString(tmp.resolve("bi-mat.txt"), "x");
        assertTrue(s.get("/").text().contains("xin chào"));
        assertEquals(404, s.get("/static/%2e%2e/bi-mat.txt").status());
        assertEquals(404, s.get("/static/khong-co.js").status());
    }

    @Test
    void healthStatsVaCrawl() throws Exception {
        var h = s.get("/api/health").json();
        assertTrue(h.get("api").asBoolean() && h.get("lucene").asBoolean());
        assertFalse(h.get("mongo").asBoolean());
        assertFalse(h.get("crawler").asBoolean());
        Files.createDirectories(tmp.resolve("data/raw-x.hust.edu.vn"));
        Files.writeString(tmp.resolve("data/raw-x.hust.edu.vn/state.json"), "{\"done\":{\"a\":1,\"b\":2},\"frontier\":[1],\"by_key\":{\"k\":1}}");
        Files.writeString(tmp.resolve("data/N1-links"), "u1\n\nu2\n");
        var st = s.get("/api/stats").json();
        assertEquals("x.hust.edu.vn", st.get("sites").get(0).get("host").asText());
        assertEquals(2, st.get("sites").get(0).get("done").asInt());
        assertEquals(1, st.get("sites").get(0).get("queued").asInt());
        assertEquals("N1-links", st.get("links_file").asText());
        assertEquals(2, st.get("links").asInt());
        // render bị bỏ cùng Playwright; crawler không gọi được thì 502
        assertEquals(400, s.post("/api/crawl/start", "{\"render\":\"auto\"}").status());
        assertEquals(502, s.post("/api/crawl/start", "{}").status());
        // các route Mongo trả 503 khi Mongo chưa lên
        assertEquals(503, s.get("/api/graph/stats").status());
        assertEquals("false", String.valueOf(s.get("/api/extract/status").json().get("running").asBoolean()));
    }
}
