package vn.hust.search.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * So bản Java ĐANG CHẠY (env {@code API}, mặc định localhost:8000) với ảnh chụp phản hồi của bản Python
 * ở {@code tests/golden/api/}. Chạy bằng {@code mvn test -Pstack} trên stack đã dựng lại bằng bản Java.
 * Hình dạng phải khớp (cùng khoá, cùng kiểu, cùng mã HTTP); số liệu được phép lệch vì kho đã đổi.
 */
@Tag("stack")
class ApiGoldenTest {
    static final Path GOLDEN = Path.of("tests/golden/api");
    static final String API = System.getenv().getOrDefault("API", "http://localhost:8000");
    /**
     * Khoá có trong ảnh chụp Python mà bản Java cố ý không còn: {@code crawler_dir} (không còn thư mục crawler
     * trong container) và {@code unsupported} (khoá của bảng đếm theo trạng thái; Q2 chuyển doc/xls/ppt về pending).
     */
    static final Set<String> IGNORED_KEYS = Set.of("crawler_dir", "unsupported");
    static final HttpClient CLI = HttpClient.newHttpClient();

    record Sample(String name, String path, int status, JsonNode body) {}

    static JsonNode getJson(String path) throws Exception {
        var r = CLI.send(HttpRequest.newBuilder(URI.create(API + path)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var out = Http.M.createObjectNode().put("status", r.statusCode());
        try {
            out.set("body", Http.M.readTree(r.body()));
        } catch (Exception e) {
            out.put("body", r.body());
        }
        return out;
    }

    static List<Sample> sample() throws Exception {
        List<Sample> docs = new ArrayList<>();
        try (Stream<Path> fs = Files.list(GOLDEN)) {
            for (Path f : fs.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonNode j = Http.M.readTree(f.toFile());
                String name = f.getFileName().toString();
                for (JsonNode e : j.isArray() ? j : List.of(j)) docs.add(new Sample(name, e.get("path").asText(), e.get("status").asInt(), e.get("body")));
            }
        }
        return docs;
    }

    /** Ghi lại mọi chỗ lệch hình dạng giữa mẫu Python và phản hồi Java. */
    static void checkShape(String o, JsonNode sample, JsonNode actual, List<String> errors) {
        if (sample == null || sample.isNull() || actual == null || actual.isNull()) return;     // null: không biết kiểu
        if (sample.isObject()) {
            if (!actual.isObject()) { errors.add(o + ": mong đối tượng, nhận " + actual.getNodeType()); return; }
            sample.fieldNames().forEachRemaining(k -> {
                if (IGNORED_KEYS.contains(k)) return;
                if (!actual.has(k)) errors.add(o + "." + k + ": thiếu khoá");
                else checkShape(o + "." + k, sample.get(k), actual.get(k), errors);
            });
        } else if (sample.isArray()) {
            if (!actual.isArray()) { errors.add(o + ": mong mảng, nhận " + actual.getNodeType()); return; }
            if (sample.size() > 0 && actual.size() > 0) checkShape(o + "[0]", sample.get(0), actual.get(0), errors);
        } else if (sample.isNumber() != actual.isNumber() || sample.isTextual() != actual.isTextual() || sample.isBoolean() != actual.isBoolean()) {
            errors.add(o + ": mong " + sample.getNodeType() + ", nhận " + actual.getNodeType());
        }
    }

    @Test
    void endpointShapesMatchSnapshot() throws Exception {
        assumeTrue(getJson("/api/health").path("status").asInt() == 200, "không có stack ở " + API);
        List<String> errors = new ArrayList<>();
        int n = 0;
        for (Sample m : sample()) {
            JsonNode t = getJson(m.path());
            n++;
            if (t.get("status").asInt() != m.status()) { errors.add(m.name() + " " + m.path() + ": mã " + t.get("status") + " thay vì " + m.status()); continue; }
            if (m.name().equals("loi-search-thieu-q.json")) continue;     // FastAPI trả detail dạng mảng, Java trả chuỗi (KE-HOACH mục 3)
            checkShape(m.name(), m.body(), t.get("body"), errors);
        }
        assertTrue(errors.isEmpty(), n + " mẫu, " + errors.size() + " lệch:\n" + String.join("\n", errors));
    }

    /** overlap@10 của 25 truy vấn × 2 ranking so với ảnh chụp; chạy sau khi dựng lại index bằng bản Java (giai đoạn 9). */
    @Test
    void searchMatchesSnapshot() throws Exception {
        assumeTrue(getJson("/api/health").path("status").asInt() == 200, "không có stack ở " + API);
        double total = 0;
        int n = 0;
        List<String> low = new ArrayList<>();
        for (Sample m : sample()) {
            if (!m.name().equals("search.json") || m.status() != 200) continue;
            Set<String> a = new HashSet<>(), b = new HashSet<>();
            m.body().get("hits").forEach(h -> a.add(h.get("url").asText()));
            getJson(m.path()).get("body").path("hits").forEach(h -> b.add(h.get("url").asText()));
            Set<String> common = new HashSet<>(a);
            common.retainAll(b);
            double overlap = a.isEmpty() ? 1 : (double) common.size() / a.size();
            total += overlap;
            n++;
            if (overlap < 0.5) low.add(String.format("%.2f  %s", overlap, m.path()));
        }
        System.out.printf("overlap@10 trung bình %.3f trên %d truy vấn; dưới 0,5:%n%s%n", total / n, n, String.join("\n", low));
        assertTrue(n > 0 && total / n >= 0.8, "overlap@10 trung bình " + total / n);
    }
}
