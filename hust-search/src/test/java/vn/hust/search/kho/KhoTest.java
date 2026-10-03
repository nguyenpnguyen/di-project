package vn.hust.search.kho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KhoTest {
    @TempDir Path tmp;

    static byte[] gz(String s) throws Exception {
        var b = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(b)) {
            g.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    static String dong(String url) {
        return "{\"url\":\"" + url + "\",\"status\":200,\"html_b64\":\"PGh0bWw+\"}\n";
    }

    List<String> urls(Kho k, Path d) {
        List<String> r = new ArrayList<>();
        k.records(d).forEachRemaining(n -> r.add(n.get("url").asText()));
        return r;
    }

    @Test
    void shardCutGiuDongDaDocRoiSangShardSau() throws Exception {
        Path d = Files.createDirectories(tmp.resolve("raw-x.hust.edu.vn"));
        var rnd = new Random(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++)   // nội dung ngẫu nhiên để gzip không nén gọn thành vài byte
            sb.append("{\"url\":\"https://x/a-").append(i).append(".html\",\"h\":\"").append(rnd.nextLong()).append("\"}\n");
        byte[] full = gz(sb.toString());
        Files.write(d.resolve("pages-0001.jsonl.gz"), java.util.Arrays.copyOf(full, full.length / 2));
        Files.write(d.resolve("pages-0002.jsonl.gz"), gz(dong("https://x/last-1.html")));
        List<String> u = urls(new Kho(tmp), d);
        assertTrue(u.size() > 1 && u.size() < 2000, "shard cụt phải giữ một phần: " + u.size());
        assertEquals("https://x/last-1.html", u.get(u.size() - 1));
    }

    @Test
    void nhieuGzipMemberVaGhiKho() throws Exception {
        var k = new Kho(tmp);
        for (String n : List.of("a-1", "b-2", "c-3"))
            k.ghiKho(Kho.JSON.readTree(dong("https://hust.edu.vn/" + n + ".html")));
        assertEquals(List.of("https://hust.edu.vn/a-1.html", "https://hust.edu.vn/b-2.html",
                "https://hust.edu.vn/c-3.html"), urls(k, tmp.resolve("raw-adhoc")));
    }

    @Test
    void dongJsonHongBoNotShardDoNhungShardSauVanDoc() throws Exception {
        Path d = Files.createDirectories(tmp.resolve("raw"));
        Files.write(d.resolve("pages-0001.jsonl.gz"), gz(dong("https://x/1.html") + "{hong\n" + dong("https://x/2.html")));
        Files.writeString(d.resolve("pages-0002.jsonl"), dong("https://x/3.html"));    // shard không nén
        assertEquals(List.of("https://x/1.html", "https://x/3.html"), urls(new Kho(tmp), d));
    }

    @Test
    void timBanGhiQuaDuongDanThoatUnicode() throws Exception {
        Path d = Files.createDirectories(tmp.resolve("raw"));
        // crawler ghi ensure_ascii: tin-tức -> tin-tức
        Files.write(d.resolve("pages-0001.jsonl.gz"), gz(
                dong("https://hust.edu.vn/vi/tin-t\\u1ee9c/b-1.html") + dong("https://hust.edu.vn/vi/khac/c-2.html")));
        var k = new Kho(tmp);
        JsonNode r = k.timBanGhi(Set.of("https://hust.edu.vn/vi/tin-tức/b-1.html"));
        assertNotNull(r);
        assertEquals("https://hust.edu.vn/vi/tin-tức/b-1.html", r.get("url").asText());   // Jackson tự giải mã dạng thoát
        assertNull(k.timBanGhi(Set.of("https://hust.edu.vn/khong-co-9.html")));
        // url chưa norm (www., http, #frag) vẫn tìm ra
        assertNotNull(k.timBanGhi(Set.of("http://www.hust.edu.vn/vi/khac/c-2.html#x")));
    }

    @Test
    void thoatJsonGiongPython() {
        assertEquals("/vi/tin-t\\u1ee9c/\\\"a\\\"\\n", Kho.thoatJson("/vi/tin-tức/\"a\"\n"));
        assertEquals("\\ud83d\\ude00", Kho.thoatJson("😀"));
    }

    @Test
    void giaiMa() throws Exception {
        String b64 = Base64.getEncoder().encodeToString("xin chào".getBytes(StandardCharsets.UTF_8));
        var m = Kho.JSON;
        assertEquals("xin chào", Kho.giaiMa(m.readTree(
                "{\"status\":200,\"encoding\":\"khong-co-bang-ma\",\"html_b64\":\"" + b64 + "\"}")));
        assertNull(Kho.giaiMa(m.readTree("{\"status\":404,\"html_b64\":\"" + b64 + "\"}")));
        assertNull(Kho.giaiMa(m.readTree("{\"status\":200}")));
    }
}
