package vn.hust.search.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * Port của tests/danh_gia_khoi.py: precision / recall / F1 trên túi âm tiết, ba mốc body / lớp 3 /
 * lớp 2+3. Bộ mẫu là trang TỔNG HỢP (sinh_mau.py): chỉ chứng minh thuật toán chạy đúng trên các bố
 * cục đó, không phải số đo trên dữ liệu thật. Số Java phải bằng số Python ± 0,01.
 */
class BlockEvaluationTest {
    static final Pattern W = Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);

    static Map<String, Integer> bagOfWords(String text) {
        Map<String, Integer> m = new HashMap<>();
        var mt = W.matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (mt.find()) m.merge(mt.group(), 1, Integer::sum);
        return m;
    }

    static double[] prf(String pred, String gold) {
        var p = bagOfWords(pred);
        var g = bagOfWords(gold);
        int overlap = 0, predSize = 0, goldSize = 0;
        for (var e : p.entrySet()) {
            overlap += Math.min(e.getValue(), g.getOrDefault(e.getKey(), 0));
            predSize += e.getValue();
        }
        for (int v : g.values()) goldSize += v;
        double pr = (double) overlap / Math.max(predSize, 1), rc = (double) overlap / Math.max(goldSize, 1);
        return new double[]{pr, rc, pr + rc > 0 ? 2 * pr * rc / (pr + rc) : 0};
    }

    @Test
    void pythonBaselineThreeLevels() throws Exception {
        Map<String, List<double[]>> results = new java.util.LinkedHashMap<>();
        for (String m : List.of("body", "lop3", "lop2+3")) results.put(m, new ArrayList<>());
        var mapper = new ObjectMapper();
        try (Stream<Path> hosts = Files.list(ExtractorTest.FIXTURES)) {
            for (Path dir : hosts.filter(Files::isDirectory).sorted().toList()) {
                List<Path> files;
                try (Stream<Path> s = Files.list(dir)) {
                    files = s.filter(f -> f.toString().endsWith(".html")).sorted().toList();
                }
                List<String> htmls = new ArrayList<>();
                for (Path f : files) htmls.add(Files.readString(f));
                var templateFps = Template.templateSet(Template.countByHost(htmls.stream().map(h -> Template.pageFingerprints(Extractor.parse(h))).toList()));
                for (int i = 0; i < files.size(); i++) {
                    String name = files.get(i).getFileName().toString().replace(".html", ".gold.json");
                    var content = mapper.readTree(Files.readString(dir.resolve(name))).get("content");
                    List<String> parts = new ArrayList<>();
                    content.forEach(c -> parts.add(c.asText()));
                    String gold = String.join(" ", parts);
                    String h = htmls.get(i);
                    results.get("body").add(prf(HtmlUtil.textBs4(Extractor.parse(h).body()), gold));
                    results.get("lop3").add(prf(ExtractorTest.text(ExtractorTest.block(h, "", null, null)), gold));
                    results.get("lop2+3").add(prf(ExtractorTest.text(ExtractorTest.block(h, "", templateFps, null)), gold));
                }
            }
        }
        // số đo của bản Python (tests/danh_gia_khoi.py, 150 trang): P, R, F1
        double[][] py = {{0.467, 1.000, 0.630}, {0.950, 1.000, 0.974}, {0.933, 1.000, 0.962}};
        int i = 0;
        for (var e : results.entrySet()) {
            var all = e.getValue();
            assertEquals(150, all.size());
            for (int k = 0; k < 3; k++) {
                final int column = k;
                double v = all.stream().mapToDouble(x -> x[column]).average().orElse(0);
                System.out.printf("%-7s %s=%.3f (Python %.3f)%n", e.getKey(), "PRF".charAt(k), v, py[i][k]);
                assertEquals(py[i][k], v, 0.01, e.getKey() + " " + "PRF".charAt(k));
            }
            i++;
        }
    }
}
