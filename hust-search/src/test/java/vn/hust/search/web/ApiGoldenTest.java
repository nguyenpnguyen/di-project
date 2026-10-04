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
    static final Set<String> BO_QUA = Set.of("crawler_dir", "unsupported");
    static final HttpClient CLI = HttpClient.newHttpClient();

    record Mau(String ten, String path, int status, JsonNode body) {}

    static JsonNode lay(String path) throws Exception {
        var r = CLI.send(HttpRequest.newBuilder(URI.create(API + path)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var out = Http.M.createObjectNode().put("status", r.statusCode());
        try {
            out.set("body", Http.M.readTree(r.body()));
        } catch (Exception e) {
            out.put("body", r.body());
        }
        return out;
    }

    static List<Mau> mau() throws Exception {
        List<Mau> ds = new ArrayList<>();
        try (Stream<Path> fs = Files.list(GOLDEN)) {
            for (Path f : fs.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonNode j = Http.M.readTree(f.toFile());
                String ten = f.getFileName().toString();
                for (JsonNode e : j.isArray() ? j : List.of(j)) ds.add(new Mau(ten, e.get("path").asText(), e.get("status").asInt(), e.get("body")));
            }
        }
        return ds;
    }

    /** Ghi lại mọi chỗ lệch hình dạng giữa mẫu Python và phản hồi Java. */
    static void soHinh(String o, JsonNode mau, JsonNode that, List<String> loi) {
        if (mau == null || mau.isNull() || that == null || that.isNull()) return;     // null: không biết kiểu
        if (mau.isObject()) {
            if (!that.isObject()) { loi.add(o + ": mong đối tượng, nhận " + that.getNodeType()); return; }
            mau.fieldNames().forEachRemaining(k -> {
                if (BO_QUA.contains(k)) return;
                if (!that.has(k)) loi.add(o + "." + k + ": thiếu khoá");
                else soHinh(o + "." + k, mau.get(k), that.get(k), loi);
            });
        } else if (mau.isArray()) {
            if (!that.isArray()) { loi.add(o + ": mong mảng, nhận " + that.getNodeType()); return; }
            if (mau.size() > 0 && that.size() > 0) soHinh(o + "[0]", mau.get(0), that.get(0), loi);
        } else if (mau.isNumber() != that.isNumber() || mau.isTextual() != that.isTextual() || mau.isBoolean() != that.isBoolean()) {
            loi.add(o + ": mong " + mau.getNodeType() + ", nhận " + that.getNodeType());
        }
    }

    @Test
    void hinhDangMoiEndpointKhopAnhChup() throws Exception {
        assumeTrue(lay("/api/health").path("status").asInt() == 200, "không có stack ở " + API);
        List<String> loi = new ArrayList<>();
        int n = 0;
        for (Mau m : mau()) {
            JsonNode t = lay(m.path());
            n++;
            if (t.get("status").asInt() != m.status()) { loi.add(m.ten() + " " + m.path() + ": mã " + t.get("status") + " thay vì " + m.status()); continue; }
            if (m.ten().equals("loi-search-thieu-q.json")) continue;     // FastAPI trả detail dạng mảng, Java trả chuỗi (KE-HOACH mục 3)
            soHinh(m.ten(), m.body(), t.get("body"), loi);
        }
        assertTrue(loi.isEmpty(), n + " mẫu, " + loi.size() + " lệch:\n" + String.join("\n", loi));
    }

    /** overlap@10 của 25 truy vấn × 2 ranking so với ảnh chụp; chạy sau khi dựng lại index bằng bản Java (giai đoạn 9). */
    @Test
    void timKiemTrungVoiAnhChup() throws Exception {
        assumeTrue(lay("/api/health").path("status").asInt() == 200, "không có stack ở " + API);
        double tong = 0;
        int n = 0;
        List<String> thap = new ArrayList<>();
        for (Mau m : mau()) {
            if (!m.ten().equals("search.json") || m.status() != 200) continue;
            Set<String> a = new HashSet<>(), b = new HashSet<>();
            m.body().get("hits").forEach(h -> a.add(h.get("url").asText()));
            lay(m.path()).get("body").path("hits").forEach(h -> b.add(h.get("url").asText()));
            Set<String> chung = new HashSet<>(a);
            chung.retainAll(b);
            double ov = a.isEmpty() ? 1 : (double) chung.size() / a.size();
            tong += ov;
            n++;
            if (ov < 0.5) thap.add(String.format("%.2f  %s", ov, m.path()));
        }
        System.out.printf("overlap@10 trung bình %.3f trên %d truy vấn; dưới 0,5:%n%s%n", tong / n, n, String.join("\n", thap));
        assertTrue(n > 0 && tong / n >= 0.8, "overlap@10 trung bình " + tong / n);
    }
}
