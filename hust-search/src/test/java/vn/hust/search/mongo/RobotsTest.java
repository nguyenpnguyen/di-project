package vn.hust.search.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import vn.hust.search.kho.Url;

/** Robots phải cho kết quả giống urllib.robotparser của Python (dòng khớp đầu tiên thắng). */
class RobotsTest {
    static final Map<String, String> ROBOTS = Map.of(
            "A", "User-agent: hust-research-crawler\nDisallow: /private/\nAllow: /private/ok/\n\nUser-agent: *\nDisallow: /\n",
            "B", "User-agent: *\nAllow: /a/b\nDisallow: /a/\nDisallow:\n# comment\nDisallow: /c # inline\n",
            "C", "User-agent: googlebot\nDisallow: /g/\n\nUser-agent: *\nDisallow: /tiếng-việt/\nDisallow: /%E1%BA%A5/\nDisallow: /q?x=1\nDisallow: /*.pdf$\n",
            "D", "Disallow: /orphan/\nUser-agent: *\nDisallow: /d/\nUser-agent: other\nDisallow: /e/\n",
            "E", "");

    // {robots, url, canFetch} — sinh bằng urllib.robotparser của Python 3.10
    static final String[][] CA = {
        {"A", "https://h.vn/private/ok/x.pdf", "false"},
        {"A", "https://h.vn/private/x.pdf", "false"},
        {"A", "https://h.vn/x.pdf", "true"},
        {"A", "https://h.vn/a/b/c", "true"},
        {"A", "https://h.vn/a/c", "true"},
        {"A", "https://h.vn/c", "true"},
        {"A", "https://h.vn/cc/y", "true"},
        {"A", "https://h.vn/tiếng-việt/x.pdf", "true"},
        {"A", "https://h.vn/ti%E1%BA%BFng-vi%E1%BB%87t/x.pdf", "true"},
        {"A", "https://h.vn/%E1%BA%A5/x", "true"},
        {"A", "https://h.vn/ấ/x", "true"},
        {"A", "https://h.vn/q?x=1", "true"},
        {"A", "https://h.vn/q?x=2", "true"},
        {"A", "https://h.vn/d/f", "true"},
        {"A", "https://h.vn/e/f", "true"},
        {"A", "https://h.vn/orphan/f", "true"},
        {"A", "https://h.vn/", "true"},
        {"A", "https://h.vn", "true"},
        {"A", "https://h.vn/f;p/x?y#z", "true"},
        {"A", "https://h.vn/a.pdf", "true"},
        {"B", "https://h.vn/private/ok/x.pdf", "true"},
        {"B", "https://h.vn/private/x.pdf", "true"},
        {"B", "https://h.vn/x.pdf", "true"},
        {"B", "https://h.vn/a/b/c", "true"},
        {"B", "https://h.vn/a/c", "false"},
        {"B", "https://h.vn/c", "true"},
        {"B", "https://h.vn/cc/y", "true"},
        {"B", "https://h.vn/tiếng-việt/x.pdf", "true"},
        {"B", "https://h.vn/ti%E1%BA%BFng-vi%E1%BB%87t/x.pdf", "true"},
        {"B", "https://h.vn/%E1%BA%A5/x", "true"},
        {"B", "https://h.vn/ấ/x", "true"},
        {"B", "https://h.vn/q?x=1", "true"},
        {"B", "https://h.vn/q?x=2", "true"},
        {"B", "https://h.vn/d/f", "true"},
        {"B", "https://h.vn/e/f", "true"},
        {"B", "https://h.vn/orphan/f", "true"},
        {"B", "https://h.vn/", "true"},
        {"B", "https://h.vn", "true"},
        {"B", "https://h.vn/f;p/x?y#z", "true"},
        {"B", "https://h.vn/a.pdf", "true"},
        {"C", "https://h.vn/private/ok/x.pdf", "true"},
        {"C", "https://h.vn/private/x.pdf", "true"},
        {"C", "https://h.vn/x.pdf", "true"},
        {"C", "https://h.vn/a/b/c", "true"},
        {"C", "https://h.vn/a/c", "true"},
        {"C", "https://h.vn/c", "true"},
        {"C", "https://h.vn/cc/y", "true"},
        {"C", "https://h.vn/tiếng-việt/x.pdf", "false"},
        {"C", "https://h.vn/ti%E1%BA%BFng-vi%E1%BB%87t/x.pdf", "false"},
        {"C", "https://h.vn/%E1%BA%A5/x", "false"},
        {"C", "https://h.vn/ấ/x", "false"},
        {"C", "https://h.vn/q?x=1", "false"},
        {"C", "https://h.vn/q?x=2", "true"},
        {"C", "https://h.vn/d/f", "true"},
        {"C", "https://h.vn/e/f", "true"},
        {"C", "https://h.vn/orphan/f", "true"},
        {"C", "https://h.vn/", "true"},
        {"C", "https://h.vn", "true"},
        {"C", "https://h.vn/f;p/x?y#z", "true"},
        {"C", "https://h.vn/a.pdf", "true"},
        {"D", "https://h.vn/private/ok/x.pdf", "true"},
        {"D", "https://h.vn/private/x.pdf", "true"},
        {"D", "https://h.vn/x.pdf", "true"},
        {"D", "https://h.vn/a/b/c", "true"},
        {"D", "https://h.vn/a/c", "true"},
        {"D", "https://h.vn/c", "true"},
        {"D", "https://h.vn/cc/y", "true"},
        {"D", "https://h.vn/tiếng-việt/x.pdf", "true"},
        {"D", "https://h.vn/ti%E1%BA%BFng-vi%E1%BB%87t/x.pdf", "true"},
        {"D", "https://h.vn/%E1%BA%A5/x", "true"},
        {"D", "https://h.vn/ấ/x", "true"},
        {"D", "https://h.vn/q?x=1", "true"},
        {"D", "https://h.vn/q?x=2", "true"},
        {"D", "https://h.vn/d/f", "false"},
        {"D", "https://h.vn/e/f", "true"},
        {"D", "https://h.vn/orphan/f", "true"},
        {"D", "https://h.vn/", "true"},
        {"D", "https://h.vn", "true"},
        {"D", "https://h.vn/f;p/x?y#z", "true"},
        {"D", "https://h.vn/a.pdf", "true"},
        {"E", "https://h.vn/private/ok/x.pdf", "true"},
        {"E", "https://h.vn/private/x.pdf", "true"},
        {"E", "https://h.vn/x.pdf", "true"},
        {"E", "https://h.vn/a/b/c", "true"},
        {"E", "https://h.vn/a/c", "true"},
        {"E", "https://h.vn/c", "true"},
        {"E", "https://h.vn/cc/y", "true"},
        {"E", "https://h.vn/tiếng-việt/x.pdf", "true"},
        {"E", "https://h.vn/ti%E1%BA%BFng-vi%E1%BB%87t/x.pdf", "true"},
        {"E", "https://h.vn/%E1%BA%A5/x", "true"},
        {"E", "https://h.vn/ấ/x", "true"},
        {"E", "https://h.vn/q?x=1", "true"},
        {"E", "https://h.vn/q?x=2", "true"},
        {"E", "https://h.vn/d/f", "true"},
        {"E", "https://h.vn/e/f", "true"},
        {"E", "https://h.vn/orphan/f", "true"},
        {"E", "https://h.vn/", "true"},
        {"E", "https://h.vn", "true"},
        {"E", "https://h.vn/f;p/x?y#z", "true"},
        {"E", "https://h.vn/a.pdf", "true"},
    };

    @Test
    void khopRobotparserCuaPython() {
        List<String> lech = new ArrayList<>();
        for (String[] c : CA) {
            boolean java = Robots.parse(ROBOTS.get(c[0]).lines().toList()).canFetch(Url.UA, c[1]);
            if (java != Boolean.parseBoolean(c[2])) lech.add(c[0] + " " + c[1] + " python=" + c[2]);
        }
        assertTrue(lech.isEmpty(), String.join("\n", lech));
    }

    @Test
    void robotsThatCuaCacHostHustKhopTrenMoiUrlTep() throws Exception {
        Path dir = Path.of("src/test/resources/golden/robots");
        Map<String, Robots> rp = new java.util.HashMap<>();
        try (var br = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                Files.newInputStream(dir.resolve("ket-qua.jsonl.gz"))), StandardCharsets.UTF_8))) {
            int n = 0;
            for (String l; (l = br.readLine()) != null; n++) {
                var r = new ObjectMapper().readTree(l);
                String url = r.get(0).asText();
                String host = Url.hostname(url);
                Robots robots = rp.computeIfAbsent(host, h -> {
                    try {
                        boolean co = Files.readString(dir.resolve(h + ".status")).trim().equals("200");
                        return Robots.parse(co ? Files.readString(dir.resolve(h + ".txt")).lines().toList() : List.of());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                assertEquals(r.get(1).asBoolean(), robots.canFetch(Url.UA, url), url);
            }
            assertTrue(n > 2000, "golden robots rỗng: " + n);
        }
    }
}
