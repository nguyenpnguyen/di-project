package vn.hust.search.kho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/** Url phải khớp 100% bản Python (golden/url.jsonl.gz: [input, base, norm, dedup_key, kind_of, page_of]). */
class UrlGoldenTest {
    static String s(JsonNode n) {
        return n.isNull() ? null : n.asText();
    }

    @Test
    void khopBanPython() throws Exception {
        var mapper = new ObjectMapper();
        List<String> lech = new ArrayList<>();
        int n = 0;
        try (var in = new GZIPInputStream(getClass().getResourceAsStream("/golden/url.jsonl.gz"));
             var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = br.readLine()) != null; n++) {
                JsonNode r = mapper.readTree(line);
                String inp = r.get(0).asText(), base = s(r.get(1));
                String norm = Url.norm(inp, base);
                String got = norm + " | " + (norm == null ? null : Url.dedupKey(norm)) + " | "
                        + (norm == null ? null : Url.kindOf(norm)) + " | " + page(norm);
                String want = s(r.get(2)) + " | " + s(r.get(3)) + " | " + s(r.get(4)) + " | " + wantPage(r.get(5));
                if (!got.equals(want) && lech.size() < 40) lech.add("input=" + inp + " base=" + base
                        + "\n   java:   " + got + "\n   python: " + want);
                else if (!got.equals(want)) lech.add(null);
            }
        }
        assertTrue(n > 200_000, "golden rỗng: " + n);
        long tong = lech.size();
        lech.removeIf(x -> x == null);
        assertEquals(0, tong, tong + "/" + n + " dòng lệch, 40 dòng đầu:\n" + String.join("\n", lech));
    }

    static String page(String norm) {
        var p = norm == null ? null : Url.pageOf(norm);
        return p == null ? "null" : p.tpl() + "," + p.n() + "," + p.root();
    }

    static String wantPage(JsonNode n) {
        return n.isNull() ? "null" : n.get(0).asText() + "," + n.get(1).asLong() + "," + n.get(2).asText();
    }
}
