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
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Cổng so khớp Tika với pdfminer/python-docx/openpyxl trên các tệp thật (golden/tep.jsonl.gz):
 * cùng status ≥ 98%, chữ bóc ra không ngắn hơn bản Python quá 10% trên ≥ 95% số tệp.
 * Tika và pdfminer ngắt dòng khác nhau là bình thường nên chỉ so độ dài / từ đầu.
 */
class DocumentTextGoldenTest {
    /** Java chứa ≥ 90% từ của 300 ký tự đầu bản Python. Python nhân đôi chữ ở ô gộp của docx nên độ dài thô không công bằng. */
    static boolean coversHead(String head, String text) {
        var words = java.util.Arrays.stream(head.trim().split("\\s+")).filter(x -> !x.isEmpty()).toList();
        if (words.isEmpty()) return false;
        var present = new java.util.HashSet<>(java.util.Arrays.asList(text.split("\\s+")));
        return words.stream().filter(present::contains).count() >= 0.9 * words.size();
    }

    @Test
    void matchesPython() throws Exception {
        Path files = Path.of(System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "../hust-crawler/data")), "files");
        assumeTrue(Files.isDirectory(files), "không có data/files");
        int n = 0, sameStatus = 0, notShort = 0, sameOcr = 0;
        List<String> mismatches = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getResourceAsStream("/golden/tep.jsonl.gz")), StandardCharsets.UTF_8))) {
            for (String l; (l = reader.readLine()) != null; ) {
                JsonNode g = new ObjectMapper().readTree(l);
                String ext = g.get("ext").asText();
                var r = DocumentText.extractText(Files.readAllBytes(files.resolve(g.get("sha1").asText() + "." + ext)), ext);
                n++;
                if (r.status().equals(g.get("status").asText())) sameStatus++;
                else mismatches.add(ext + " " + g.get("sha1").asText().substring(0, 8) + " py=" + g.get("status").asText()
                        + " java=" + r.status() + " " + r.error());
                if (r.needsOcr() == g.get("needs_ocr").asBoolean()) sameOcr++;
                int py = g.get("len").asInt();
                if (r.text().length() >= 0.9 * py || coversHead(g.get("head").asText(), r.text())) notShort++;
                else mismatches.add(ext + " " + g.get("sha1").asText().substring(0, 8) + " ngắn: py=" + py + " java=" + r.text().length());
            }
        }
        String report = String.format("%d tệp: cùng status %.1f%%, cùng needs_ocr %.1f%%, chữ đủ (dài hơn 90%% hoặc phủ đầu) %.1f%%%n%s",
                n, 100.0 * sameStatus / n, 100.0 * sameOcr / n, 100.0 * notShort / n, String.join("\n", mismatches));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/tep-so-khop.txt"), report);
        System.out.println(report);
        assertTrue(sameStatus >= 0.98 * n, "status");
        assertTrue(notShort >= 0.95 * n, "độ dài chữ");
    }
}
