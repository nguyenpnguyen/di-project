package vn.hust.search.boctach;

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
import vn.hust.search.kho.Kho;
import vn.hust.search.kho.Url;

/**
 * Cổng so khớp bản bóc tách Java với bản Python trên cả kho thật (golden/boc_tach.jsonl.gz).
 * Cần kho thô ({@code DATA_DIR}, mặc định ../hust-crawler/data); không có thì bỏ qua.
 * Báo cáo chi tiết ghi ở target/so-khop.txt.
 */
class SoKhopTest {
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
        Set<String> hop = new HashSet<>(x);
        hop.addAll(y);
        x.retainAll(y);
        return (double) x.size() / hop.size();
    }

    /** Bộ đếm một biến thể (không khuôn / có khuôn). */
    static class Dem {
        int n, nullLech;
        Map<String, Integer> lech = new TreeMap<>();
        int textTot;                       // số trang Jaccard >= 0.98
        int canhKhop, htmlKhop, htmlDai;
        List<String> htmlVd = new ArrayList<>();
        Map<String, Integer> co = new TreeMap<>();   // đếm phía Java: trường không rỗng, phân bố method
        List<String[]> xau = new ArrayList<>();   // {jaccard, url, pathJava, pathPy}

        void so(JsonNode g, BocTach.Ket k, String url) {
            n++;
            if (g.isNull() || k == null) {
                if (g.isNull() != (k == null)) nullLech++;
                else {                                   // cùng là null: khớp
                    textTot++;
                    canhKhop++;
                }
                return;
            }
            co.merge("method=" + k.block().method(), 1, Integer::sum);
            if (!k.title().isEmpty()) co.merge("title!=''", 1, Integer::sum);
            if (!k.date().isEmpty()) co.merge("date!=''", 1, Integer::sum);
            if (!k.author().isEmpty()) co.merge("author!=''", 1, Integer::sum);
            if (!k.citedSource().isEmpty()) co.merge("nguon!=''", 1, Integer::sum);
            if (!k.section().isEmpty()) co.merge("section!=''", 1, Integer::sum);
            co.merge("canh", k.links().size(), Integer::sum);
            chk("method", s(g.get("block"), "method"), k.block().method());
            chk("title", s(g, "title"), k.title());
            chk("date", s(g, "date"), k.date());
            chk("author", s(g, "author"), k.author());
            chk("cited_source", s(g, "cited_source"), k.citedSource());
            chk("section", s(g, "section"), k.section());
            chk("path", s(g.get("block"), "path"), k.block().path());
            if (sha1(k.html()).equals(s(g, "html_sha1"))) htmlKhop++;
            if (k.html().length() == g.get("html_len").asInt()) {
                htmlDai++;
                if (!sha1(k.html()).equals(s(g, "html_sha1")) && htmlVd.size() < 3) htmlVd.add(url);
            }
            double j = jaccard(s(g, "text"), k.text());
            if (j >= 0.98) textTot++;
            Set<String> eg = new HashSet<>(), ej = new HashSet<>();
            for (JsonNode e : g.get("edges")) eg.add(e.get(0).asText() + "\u0000" + e.get(1).asText());
            for (var e : k.links()) ej.add(e.dst + "\u0000" + e.type);
            if (eg.equals(ej)) canhKhop++;
            if (j < 0.98 || !s(g.get("block"), "path").equals(k.block().path()))
                xau.add(new String[]{String.format("%.3f", j), url, k.block().path(), s(g.get("block"), "path")});
        }

        void chk(String f, String py, String java) {
            if (!py.equals(java)) lech.merge(f, 1, Integer::sum);
        }

        String bao(String ten) {
            int m = Math.max(1, n - nullLech);
            StringBuilder sb = new StringBuilder(String.format("== %s: %d trang, null lệch %d%n", ten, n, nullLech));
            for (var e : lech.entrySet())
                sb.append(String.format("   %-13s lệch %4d (%.2f%%)%n", e.getKey(), e.getValue(), 100.0 * e.getValue() / m));
            sb.append("   phía Java: ").append(co).append('\n');
            sb.append(String.format("   html xem trước (thông tin, không chặn): sha1 khớp %.2f%%, cùng độ dài %.2f%%%n",
                    100.0 * htmlKhop / m, 100.0 * htmlDai / m));
            sb.append("   html cùng độ dài nhưng khác nội dung, ví dụ: ").append(htmlVd).append('\n');
            sb.append(String.format("   text Jaccard>=0.98: %.2f%%   cạnh (dst,type) khớp: %.2f%%%n", 100.0 * textTot / m, 100.0 * canhKhop / m));
            xau.sort((a, b) -> a[0].compareTo(b[0]));
            sb.append("   20 trang lệch nặng nhất (jaccard, url, path Java, path Python):\n");
            for (String[] x : xau.subList(0, Math.min(20, xau.size())))
                sb.append("     ").append(String.join("  |  ", x)).append('\n');
            return sb.toString();
        }

        double pct(String f) {
            return 100.0 - 100.0 * lech.getOrDefault(f, 0) / Math.max(1, n - nullLech);
        }
    }

    @Test
    void khopBanPython() throws Exception {
        Path data = Path.of(System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "../hust-crawler/data")));
        assumeTrue(Files.isDirectory(data.resolve("raw")), "không có kho thô ở " + data);

        Map<String, JsonNode> gold = new HashMap<>();
        try (var br = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getResourceAsStream("/golden/boc_tach.jsonl.gz")), StandardCharsets.UTF_8))) {
            for (String l; (l = br.readLine()) != null; ) {
                JsonNode r = M.readTree(l);
                gold.put(r.get("url").asText(), r);
            }
        }
        JsonNode khuonGold = M.readTree(getClass().getResourceAsStream("/golden/khuon.json"));

        Dem khong = new Dem(), co = new Dem();
        Set<String> seen = new HashSet<>();
        Map<String, List<Set<String>>> vanTayTheoHost = new HashMap<>();
        new Kho(data).tatCaBanGhi(rec -> {
            String url = Url.norm(rec.path("url").asText());
            if (url == null) url = rec.path("url").asText();
            if (!seen.add(url)) return;
            String html = Kho.giaiMa(rec);
            if (html == null) return;
            JsonNode g = gold.get(url);
            if (g == null) return;
            String host = Url.hostname(url);
            khong.so(g.get("khong_khuon"), BocTach.bocTach(html, url, Set.of()), url);
            JsonNode gk = g.get("co_khuon");
            JsonNode tap = khuonGold.path(host).path("tap_khuon");
            if (gk.isObject()) {
                Set<String> kh = new HashSet<>();
                tap.forEach(x -> kh.add(x.asText()));
                co.so(gk, BocTach.bocTach(html, url, kh), url);
            }
            vanTayTheoHost.computeIfAbsent(host, h -> new ArrayList<>()).add(Khuon.vanTayTrang(BocTach.parse(html)));
        });

        // --- vân tay khuôn: Java dựng lại bảng đếm thì phải ra đúng tập khuôn của Python
        StringBuilder kb = new StringBuilder("== khuôn (tập khuôn Java vs Python, theo host)\n");
        int khuonLech = 0;
        for (var e : vanTayTheoHost.entrySet()) {
            Khuon.Bang bang = Khuon.demHost(e.getValue());
            Set<String> java = Khuon.tapKhuon(bang);
            Set<String> py = new HashSet<>();
            khuonGold.path(e.getKey()).path("tap_khuon").forEach(x -> py.add(x.asText()));
            if (!java.equals(py)) {
                khuonLech++;
                Set<String> thieu = new HashSet<>(py);
                thieu.removeAll(java);
                kb.append(String.format("   %s: Java %d, Python %d, thiếu %d%n", e.getKey(), java.size(), py.size(), thieu.size()));
            }
        }
        kb.append("   host lệch tập khuôn: ").append(khuonLech).append('\n');

        String bao = khong.bao("không khuôn") + co.bao("có khuôn") + kb;
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/so-khop.txt"), bao);
        System.out.println(bao);

        for (Dem d : List.of(khong, co)) {
            for (String f : List.of("method", "title", "date", "author", "cited_source", "section"))
                assertTrue(d.pct(f) >= 99.0, f + " khớp " + d.pct(f) + "% < 99%");
            assertTrue(d.pct("path") >= 97.0, "path khớp " + d.pct("path") + "% < 97%");
            assertTrue(100.0 * d.textTot / d.n >= 97.0, "text Jaccard>=0.98 chỉ " + 100.0 * d.textTot / d.n + "%");
            assertTrue(100.0 * d.canhKhop / d.n >= 98.0, "cạnh khớp chỉ " + 100.0 * d.canhKhop / d.n + "%");
        }
        assertTrue(khuonLech == 0, "tập khuôn lệch ở " + khuonLech + " host");
    }
}
