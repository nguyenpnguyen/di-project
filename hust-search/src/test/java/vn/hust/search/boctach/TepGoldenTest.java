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
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Cổng so khớp Tika với pdfminer/python-docx/openpyxl trên các tệp thật (golden/tep.jsonl.gz):
 * cùng status ≥ 98%, chữ bóc ra không ngắn hơn bản Python quá 10% trên ≥ 95% số tệp.
 * Tika và pdfminer ngắt dòng khác nhau là bình thường nên chỉ so độ dài / từ đầu.
 */
class TepGoldenTest {
    /** Java chứa ≥ 90% từ của 300 ký tự đầu bản Python. Python nhân đôi chữ ở ô gộp của docx nên độ dài thô không công bằng. */
    static boolean phuDau(String head, String text) {
        var tu = java.util.Arrays.stream(head.trim().split("\\s+")).filter(x -> !x.isEmpty()).toList();
        if (tu.isEmpty()) return false;
        var co = new java.util.HashSet<>(java.util.Arrays.asList(text.split("\\s+")));
        return tu.stream().filter(co::contains).count() >= 0.9 * tu.size();
    }

    @Test
    void khopBanPython() throws Exception {
        Path files = Path.of(System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "../hust-crawler/data")), "files");
        assumeTrue(Files.isDirectory(files), "không có data/files");
        int n = 0, cungStatus = 0, khongNgan = 0, cungOcr = 0;
        List<String> lech = new ArrayList<>();
        try (var br = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getResourceAsStream("/golden/tep.jsonl.gz")), StandardCharsets.UTF_8))) {
            for (String l; (l = br.readLine()) != null; ) {
                JsonNode g = new ObjectMapper().readTree(l);
                String ext = g.get("ext").asText();
                var r = Tep.bocChu(Files.readAllBytes(files.resolve(g.get("sha1").asText() + "." + ext)), ext);
                n++;
                if (r.status().equals(g.get("status").asText())) cungStatus++;
                else lech.add(ext + " " + g.get("sha1").asText().substring(0, 8) + " py=" + g.get("status").asText()
                        + " java=" + r.status() + " " + r.error());
                if (r.needsOcr() == g.get("needs_ocr").asBoolean()) cungOcr++;
                int py = g.get("len").asInt();
                if (r.text().length() >= 0.9 * py || phuDau(g.get("head").asText(), r.text())) khongNgan++;
                else lech.add(ext + " " + g.get("sha1").asText().substring(0, 8) + " ngắn: py=" + py + " java=" + r.text().length());
            }
        }
        String bao = String.format("%d tệp: cùng status %.1f%%, cùng needs_ocr %.1f%%, chữ đủ (dài hơn 90%% hoặc phủ đầu) %.1f%%%n%s",
                n, 100.0 * cungStatus / n, 100.0 * cungOcr / n, 100.0 * khongNgan / n, String.join("\n", lech));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/tep-so-khop.txt"), bao);
        System.out.println(bao);
        assertTrue(cungStatus >= 0.98 * n, "status");
        assertTrue(khongNgan >= 0.95 * n, "độ dài chữ");
    }
}
