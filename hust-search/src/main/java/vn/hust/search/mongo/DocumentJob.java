package vn.hust.search.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Updates;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.bson.Document;
import vn.hust.search.extract.Extractor;
import vn.hust.search.extract.Links;
import vn.hust.search.extract.DocumentText;
import vn.hust.search.store.Url;

/**
 * Tệp tài liệu: danh mục -> tải -> bóc chữ, ghi vào collection {@code documents} — bản port của
 * {@code tep_job.py}. Chỉ tải host trong họ hust.edu.vn; host ngoài (Google Drive...) chỉ có cạnh
 * trong {@code links} với dst_kind=external, không vào {@code documents}.
 */
public final class DocumentJob {
    private DocumentJob() {}

    public static final long MAX_BYTES = 50L * 1024 * 1024;
    public static final long INTERVAL_MS = 2500;             // giữa hai request, như crawler

    /** Phản hồi HTTP tối thiểu; mã lỗi không ném ngoại lệ, chỉ lỗi kết nối mới ném. */
    public record Resp(int status, String contentType, String retryAfter, byte[] body) {}

    /** Tải url bằng HTTP thật (theo redirect, UA của crawler). */
    public static Function<String, Resp> realHttp() {
        HttpClient cli = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60)).build();
        return url -> {
            try {
                var r = cli.send(HttpRequest.newBuilder(Url.httpUri(url)).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", Url.UA).build(), HttpResponse.BodyHandlers.ofByteArray());
                return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                        r.headers().firstValue("Retry-After").orElse(""), r.body());
            } catch (IOException | IllegalArgumentException e) {
                throw new RuntimeException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("InterruptedException", e);
            }
        };
    }

    /**
     * Thêm vào {@code documents} mọi tệp mà một cạnh nội dung trỏ tới, cộng các bản ghi kho thô
     * không phải HTML mà content-type là pdf/office. Tệp đã có thì không đụng.
     */
    public static Map<String, Object> catalog(MongoDatabase db, Iterator<JsonNode> records) {
        int[] added = {0};
        java.util.function.BiConsumer<String, String> addDocument = (u, mime) -> {
            String n = Url.norm(u);
            String url = n != null ? n : u;
            String host = Url.hostname(url);
            if (!Links.inHustFamily(host)) return;
            String ext = DocumentText.extensionFromUrl(url);
            if (ext.isEmpty()) ext = DocumentText.MIME_TO_EXT.getOrDefault(mime.split(";", -1)[0].strip().toLowerCase(Locale.ROOT), "");
            var r = db.getCollection("documents").updateOne(eq("_id", url), Updates.combine(
                    Updates.setOnInsert("host", host), Updates.setOnInsert("ext", ext), Updates.setOnInsert("mime", mime),
                    Updates.setOnInsert("status", "pending"), Updates.setOnInsert("extractor_version", Extractor.VERSION)),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
            if (r.getUpsertedId() != null) added[0]++;
        };
        for (String dst : db.getCollection("links").distinct("dst", eq("dst_kind", "document"), String.class)) addDocument.accept(dst, "");
        while (records.hasNext()) {
            JsonNode rec = records.next();
            String ct = rec.path("content_type").asText("").split(";", -1)[0].strip().toLowerCase(Locale.ROOT);
            boolean noHtml = rec.path("html_b64").asText("").isEmpty();
            if (noHtml && DocumentText.MIME_TO_EXT.containsKey(ct) && rec.path("status").asInt(0) < 400) addDocument.accept(rec.path("url").asText(), ct);
        }
        return Map.of("new", added[0], "total", db.getCollection("documents").countDocuments());
    }

    /**
     * Q2: định dạng cũ (doc/xls/ppt) trước đây bị đánh dấu {@code unsupported}; Tika đọc được nên
     * chuyển về {@code pending} (đã có sha1 thì vào thẳng bước bóc chữ, chưa có thì tải). Trả số tệp chuyển.
     */
    public static long migrateLegacyFormats(MongoDatabase db) {
        return db.getCollection("documents").updateMany(and(eq("status", "unsupported"),
                com.mongodb.client.model.Filters.in("ext", "doc", "xls", "ppt")), Updates.set("status", "pending")).getModifiedCount();
    }

    /** Tải các tệp {@code pending} chưa có byte (sha1 rỗng). Gọi lại thì bỏ qua tệp đã tải. */
    public static Map<String, Integer> download(MongoDatabase db, Path dir, IntConsumer onProgress, Function<String, Resp> get,
                                           long intervalMs, long maxBytes, int limit) throws IOException {
        Files.createDirectories(dir);
        Map<String, Robots> robots = new HashMap<>();
        int n = 0, ok = 0, errors = 0, tooLarge = 0;
        List<Document> docs = new ArrayList<>();
        db.getCollection("documents").find(and(eq("status", "pending"), exists("sha1", false))).into(docs);
        for (Document d : docs) {
            if (limit > 0 && n >= limit) break;
            String url = d.getString("_id");
            n++;
            Document update = new Document();
            try {
                if (!robotsAllowed(robots, get, url)) {
                    update.append("status", "error").append("error", "robots.txt cấm");
                } else {
                    sleep(intervalMs);
                    Resp r = get.apply(url);
                    if (r.status() == 429) {
                        sleep((long) (1000 * parseDouble(r.retryAfter(), 30)));
                        r = get.apply(url);
                    }
                    if (r.status() >= 400) {
                        update.append("status", "error").append("error", "HTTP " + r.status());
                    } else if (r.body().length > maxBytes) {
                        update.append("status", "skipped_too_large").append("size", r.body().length);
                    } else {
                        String mime = r.contentType().split(";", -1)[0].strip().toLowerCase(Locale.ROOT);
                        String ext = d.getString("ext") == null || d.getString("ext").isEmpty()
                                ? DocumentText.MIME_TO_EXT.getOrDefault(mime, "") : d.getString("ext");
                        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(r.body()));
                        Files.write(dir.resolve(sha + "." + (ext.isEmpty() ? "bin" : ext)), r.body());
                        update.append("sha1", sha).append("size", r.body().length).append("mime", mime).append("ext", ext)
                                .append("fetched_at", Pipeline.now());
                        if (!DocumentText.SUPPORTED.contains(ext)) update.append("status", "unsupported");
                    }
                }
            } catch (Exception e) {                                   // lỗi kết nối: ghi lại, không làm hỏng cả mẻ
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                update = new Document("status", "error").append("error", m.substring(0, Math.min(300, m.length())));
            }
            db.getCollection("documents").updateOne(eq("_id", url), new Document("$set", update));
            String st = update.getString("status") == null ? "pending" : update.getString("status");
            if (st.equals("pending") || st.equals("unsupported")) ok++;
            else if (st.equals("error")) errors++;
            else if (st.equals("skipped_too_large")) tooLarge++;
            onProgress.accept(n);
        }
        return Map.of("tried", n, "downloaded", ok, "errors", errors, "too_large", tooLarge);
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** robots.txt của host (tải 1 lần); không tải được hoặc khác 200 thì coi như cho phép. */
    private static boolean robotsAllowed(Map<String, Robots> cache, Function<String, Resp> get, String url) {
        String netloc = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "").split("[/?#]", 2)[0];
        Robots rp = cache.get(netloc);
        if (rp == null) {
            try {
                Resp r = get.apply("https://" + netloc + "/robots.txt");
                rp = Robots.parse(r.status() == 200 ? new String(r.body(), java.nio.charset.StandardCharsets.UTF_8).lines().toList() : List.of());
            } catch (Exception e) {
                rp = Robots.parse(List.of());
            }
            cache.put(netloc, rp);
        }
        return rp.canFetch(Url.UA, url);
    }

    /** Bóc chữ các tệp đã tải mà chưa bóc ({@code pending} có sha1). */
    public static Map<String, Integer> extractText(MongoDatabase db, Path dir, IntConsumer onProgress) throws IOException {
        int n = 0, ok = 0, ocr = 0, errors = 0;
        List<Document> docs = new ArrayList<>();
        db.getCollection("documents").find(and(eq("status", "pending"), exists("sha1", true))).into(docs);
        for (Document d : docs) {
            String ext = d.getString("ext") == null ? "" : d.getString("ext");
            Path f = dir.resolve(d.getString("sha1") + "." + (ext.isEmpty() ? "bin" : ext));
            if (!Files.exists(f)) {
                db.getCollection("documents").updateOne(eq("_id", d.getString("_id")),
                        Updates.combine(Updates.set("status", "error"), Updates.set("error", "mất tệp đã tải")));
                errors++;
                continue;
            }
            var result = DocumentText.extractText(Files.readAllBytes(f), ext);
            db.getCollection("documents").updateOne(eq("_id", d.getString("_id")), Updates.combine(
                    Updates.set("status", result.status()), Updates.set("text", result.text()), Updates.set("n_pages", result.nPages()),
                    Updates.set("needs_ocr", result.needsOcr()), Updates.set("encoding_suspect", result.encodingSuspect()),
                    Updates.set("error", result.error()), Updates.set("extractor_version", Extractor.VERSION)));
            n++;
            if (result.status().equals("ok")) ok++;
            if (result.needsOcr()) ocr++;
            if (result.status().equals("error")) errors++;
            onProgress.accept(n);
        }
        return Map.of("extracted", n, "ok", ok, "needs_ocr", ocr, "errors", errors);
    }
}
