package vn.hust.search.boctach;

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
class DanhGiaKhoiTest {
    static final Pattern W = Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);

    static Map<String, Integer> tui(String text) {
        Map<String, Integer> m = new HashMap<>();
        var mt = W.matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (mt.find()) m.merge(mt.group(), 1, Integer::sum);
        return m;
    }

    static double[] prf(String pred, String gold) {
        var p = tui(pred);
        var g = tui(gold);
        int trung = 0, sp = 0, sg = 0;
        for (var e : p.entrySet()) {
            trung += Math.min(e.getValue(), g.getOrDefault(e.getKey(), 0));
            sp += e.getValue();
        }
        for (int v : g.values()) sg += v;
        double pr = (double) trung / Math.max(sp, 1), rc = (double) trung / Math.max(sg, 1);
        return new double[]{pr, rc, pr + rc > 0 ? 2 * pr * rc / (pr + rc) : 0};
    }

    @Test
    void banPythonBaMoc() throws Exception {
        Map<String, List<double[]>> ket = new java.util.LinkedHashMap<>();
        for (String m : List.of("body", "lop3", "lop2+3")) ket.put(m, new ArrayList<>());
        var mapper = new ObjectMapper();
        try (Stream<Path> hosts = Files.list(BocTachTest.FX)) {
            for (Path dir : hosts.filter(Files::isDirectory).sorted().toList()) {
                List<Path> files;
                try (Stream<Path> s = Files.list(dir)) {
                    files = s.filter(f -> f.toString().endsWith(".html")).sorted().toList();
                }
                List<String> htmls = new ArrayList<>();
                for (Path f : files) htmls.add(Files.readString(f));
                var kh = Khuon.tapKhuon(Khuon.demHost(htmls.stream().map(h -> Khuon.vanTayTrang(BocTach.parse(h))).toList()));
                for (int i = 0; i < files.size(); i++) {
                    String name = files.get(i).getFileName().toString().replace(".html", ".gold.json");
                    var content = mapper.readTree(Files.readString(dir.resolve(name))).get("content");
                    List<String> parts = new ArrayList<>();
                    content.forEach(c -> parts.add(c.asText()));
                    String gold = String.join(" ", parts);
                    String h = htmls.get(i);
                    ket.get("body").add(prf(HtmlSach.textBs4(BocTach.parse(h).body()), gold));
                    ket.get("lop3").add(prf(BocTachTest.text(BocTachTest.khoi(h, "", null, null)), gold));
                    ket.get("lop2+3").add(prf(BocTachTest.text(BocTachTest.khoi(h, "", kh, null)), gold));
                }
            }
        }
        // số đo của bản Python (tests/danh_gia_khoi.py, 150 trang): P, R, F1
        double[][] py = {{0.467, 1.000, 0.630}, {0.950, 1.000, 0.974}, {0.933, 1.000, 0.962}};
        int i = 0;
        for (var e : ket.entrySet()) {
            var all = e.getValue();
            assertEquals(150, all.size());
            for (int k = 0; k < 3; k++) {
                final int kk = k;
                double v = all.stream().mapToDouble(x -> x[kk]).average().orElse(0);
                System.out.printf("%-7s %s=%.3f (Python %.3f)%n", e.getKey(), "PRF".charAt(k), v, py[i][k]);
                assertEquals(py[i][k], v, 0.01, e.getKey() + " " + "PRF".charAt(k));
            }
            i++;
        }
    }
}
