package vn.hust.search.extract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import vn.hust.search.store.RawStore;
import vn.hust.search.store.Url;

/**
 * Cổng so khớp bản bóc tách Java với bản Python trên cả kho thật (golden/boc_tach.jsonl.gz).
 * Cần kho thô ({@code DATA_DIR}, mặc định ../hust-crawler/data); không có thì bỏ qua.
 * Báo cáo chi tiết ghi ở target/so-khop.txt.
 */
class PythonParityTest {
    static final ObjectMapper M = new ObjectMapper();

    static String s(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() ? "" : v.asText();
    }

    static String sha1(String x) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                    .digest(x.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static double jaccard(String a, String b) {
        if (a.equals(b)) return 1.0;
        Set<String> x = new HashSet<>(List.of(a.isBlank() ? new String[0] : a.trim().split("\\s+")));
        Set<String> y = new HashSet<>(List.of(b.isBlank() ? new String[0] : b.trim().split("\\s+")));
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        x.retainAll(y);
        return (double) x.size() / union.size();
    }

    /** Bộ đếm một biến thể (không khuôn / có khuôn). */
    static class Tally {
        int n, nullMismatches;
        Map<String, Integer> mismatches = new TreeMap<>();
        int textGood;                       // số trang Jaccard >= 0.98
        int edgesMatched, htmlMatched, htmlSameLength;
        List<String> htmlSamples = new ArrayList<>();
        Map<String, Integer> javaCounts = new TreeMap<>();   // đếm phía Java: trường không rỗng, phân bố method
        List<String[]> worst = new ArrayList<>();   // {jaccard, url, pathJava, pathPy}

        void compare(JsonNode g, Extractor.Extraction k, String url) {
            n++;
            if (g.isNull() || k == null) {
                if (g.isNull() != (k == null)) nullMismatches++;
                else {                                   // cùng là null: khớp
                    textGood++;
                    edgesMatched++;
                }
                return;
            }
            javaCounts.merge("method=" + k.block().method(), 1, Integer::sum);
            if (!k.title().isEmpty()) javaCounts.merge("title!=''", 1, Integer::sum);
            if (!k.date().isEmpty()) javaCounts.merge("date!=''", 1, Integer::sum);
            if (!k.author().isEmpty()) javaCounts.merge("author!=''", 1, Integer::sum);
            if (!k.citedSource().isEmpty()) javaCounts.merge("nguon!=''", 1, Integer::sum);
            if (!k.section().isEmpty()) javaCounts.merge("section!=''", 1, Integer::sum);
            javaCounts.merge("canh", k.links().size(), Integer::sum);
            check("method", s(g.get("block"), "method"), k.block().method());
            check("title", s(g, "title"), k.title());
            check("date", s(g, "date"), k.date());
            check("author", s(g, "author"), k.author());
            check("cited_source", s(g, "cited_source"), k.citedSource());
            check("section", s(g, "section"), k.section());
            check("path", s(g.get("block"), "path"), k.block().path());
            if (sha1(k.html()).equals(s(g, "html_sha1"))) htmlMatched++;
            if (k.html().length() == g.get("html_len").asInt()) {
                htmlSameLength++;
                if (!sha1(k.html()).equals(s(g, "html_sha1")) && htmlSamples.size() < 3) htmlSamples.add(url);
            }
            double j = jaccard(s(g, "text"), k.text());
            if (j >= 0.98) textGood++;
            Set<String> goldEdges = new HashSet<>(), javaEdges = new HashSet<>();
            for (JsonNode e : g.get("edges")) goldEdges.add(e.get(0).asText() + "\u0000" + e.get(1).asText());
            for (var e : k.links()) javaEdges.add(e.dst + "\u0000" + e.type);
            if (goldEdges.equals(javaEdges)) edgesMatched++;
            if (j < 0.98 || !s(g.get("block"), "path").equals(k.block().path()))
                worst.add(new String[]{String.format("%.3f", j), url, k.block().path(), s(g.get("block"), "path")});
        }

        void check(String f, String py, String java) {
            if (!py.equals(java)) mismatches.merge(f, 1, Integer::sum);
        }

        String report(String name) {
            int m = Math.max(1, n - nullMismatches);
            StringBuilder sb = new StringBuilder(String.format("== %s: %d trang, null lệch %d%n", name, n, nullMismatches));
            for (var e : mismatches.entrySet())
                sb.append(String.format("   %-13s lệch %4d (%.2f%%)%n", e.getKey(), e.getValue(), 100.0 * e.getValue() / m));
            sb.append("   phía Java: ").append(javaCounts).append('\n');
            sb.append(String.format("   html xem trước (thông tin, không chặn): sha1 khớp %.2f%%, cùng độ dài %.2f%%%n",
                    100.0 * htmlMatched / m, 100.0 * htmlSameLength / m));
            sb.append("   html cùng độ dài nhưng khác nội dung, ví dụ: ").append(htmlSamples).append('\n');
            sb.append(String.format("   text Jaccard>=0.98: %.2f%%   cạnh (dst,type) khớp: %.2f%%%n", 100.0 * textGood / m, 100.0 * edgesMatched / m));
            worst.sort((a, b) -> a[0].compareTo(b[0]));
            sb.append("   20 trang lệch nặng nhất (jaccard, url, path Java, path Python):\n");
            for (String[] x : worst.subList(0, Math.min(20, worst.size())))
                sb.append("     ").append(String.join("  |  ", x)).append('\n');
            return sb.toString();
        }

        double pct(String f) {
            return 100.0 - 100.0 * mismatches.getOrDefault(f, 0) / Math.max(1, n - nullMismatches);
        }
    }

    @Test
    void matchesPython() throws Exception {
        Path data = Path.of(System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "../hust-crawler/data")));
        assumeTrue(Files.isDirectory(data.resolve("raw")), "không có kho thô ở " + data);

        Map<String, JsonNode> gold = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getResourceAsStream("/golden/boc_tach.jsonl.gz")), StandardCharsets.UTF_8))) {
            for (String l; (l = reader.readLine()) != null; ) {
                JsonNode r = M.readTree(l);
                gold.put(r.get("url").asText(), r);
            }
        }
        JsonNode goldTemplateFile = M.readTree(getClass().getResourceAsStream("/golden/khuon.json"));

        Tally withoutTemplate = new Tally(), withTemplate = new Tally();
        Set<String> seen = new HashSet<>();
        Map<String, List<Set<String>>> fingerprintsByHost = new HashMap<>();
        new RawStore(data).forEachRecord(rec -> {
            String url = Url.norm(rec.path("url").asText());
            if (url == null) url = rec.path("url").asText();
            if (!seen.add(url)) return;
            String html = RawStore.decodeHtml(rec);
            if (html == null) return;
            JsonNode g = gold.get(url);
            if (g == null) return;
            String host = Url.hostname(url);
            withoutTemplate.compare(g.get("khong_khuon"), Extractor.extract(html, url, Set.of()), url);
            JsonNode goldTemplates = g.get("co_khuon");
            JsonNode goldSet = goldTemplateFile.path(host).path("tap_khuon");
            if (goldTemplates.isObject()) {
                Set<String> javaTemplates = new HashSet<>();
                goldSet.forEach(x -> javaTemplates.add(x.asText()));
                withTemplate.compare(goldTemplates, Extractor.extract(html, url, javaTemplates), url);
            }
            fingerprintsByHost.computeIfAbsent(host, h -> new ArrayList<>()).add(Template.pageFingerprints(Extractor.parse(html)));
        });

        // --- vân tay khuôn: Java dựng lại bảng đếm thì phải ra đúng tập khuôn của Python
        StringBuilder templateReport = new StringBuilder("== khuôn (tập khuôn Java vs Python, theo host)\n");
        int templateMismatchHosts = 0;
        for (var e : fingerprintsByHost.entrySet()) {
            Template.Counts counts = Template.countByHost(e.getValue());
            Set<String> java = Template.templateSet(counts);
            Set<String> py = new HashSet<>();
            goldTemplateFile.path(e.getKey()).path("tap_khuon").forEach(x -> py.add(x.asText()));
            if (!java.equals(py)) {
                templateMismatchHosts++;
                Set<String> missing = new HashSet<>(py);
                missing.removeAll(java);
                templateReport.append(String.format("   %s: Java %d, Python %d, thiếu %d%n", e.getKey(), java.size(), py.size(), missing.size()));
            }
        }
        templateReport.append("   host lệch tập khuôn: ").append(templateMismatchHosts).append('\n');

        String report = withoutTemplate.report("không khuôn") + withTemplate.report("có khuôn") + templateReport;
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/so-khop.txt"), report);
        System.out.println(report);

        for (Tally d : List.of(withoutTemplate, withTemplate)) {
            for (String f : List.of("method", "title", "date", "author", "cited_source", "section"))
                assertTrue(d.pct(f) >= 99.0, f + " khớp " + d.pct(f) + "% < 99%");
            assertTrue(d.pct("path") >= 97.0, "path khớp " + d.pct("path") + "% < 97%");
            assertTrue(100.0 * d.textGood / d.n >= 97.0, "text Jaccard>=0.98 chỉ " + 100.0 * d.textGood / d.n + "%");
            assertTrue(100.0 * d.edgesMatched / d.n >= 98.0, "cạnh khớp chỉ " + 100.0 * d.edgesMatched / d.n + "%");
        }
        assertTrue(templateMismatchHosts == 0, "tập khuôn lệch ở " + templateMismatchHosts + " host");
    }
}
