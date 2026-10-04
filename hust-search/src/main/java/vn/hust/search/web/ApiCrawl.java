package vn.hust.search.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Chuyển tiếp {@code /api/crawl/*} sang service crawler ({@code crawlctl.py}, mạng nội bộ docker).
 * Hai việc ở phía Java: từ chối {@code render} (đã bỏ Playwright) và từ chối khi đang tải tệp —
 * crawler và việc tải tệp mỗi bên 2,5 s là ~48 request/phút, gấp đôi ngưỡng site chặn.
 * Chiều ngược lại ({@code /api/files/fetch} từ chối khi crawler chạy) dùng {@link #isRunning()}.
 */
public final class ApiCrawl {
    private final String url;
    private final BackgroundJob jobs;
    private final HttpClient cli = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ApiCrawl(String crawlerUrl, BackgroundJob jobs) {
        this.url = crawlerUrl.replaceAll("/+$", "");
        this.jobs = jobs;
    }

    public void register(Http h) {
        h.post("/api/crawl/start", r -> {
            JsonNode b = r.body();
            if (!Http.bodyStr(b, "render", "never").equals("never"))
                throw new HttpError(400, "không còn hỗ trợ render (đã bỏ Playwright)");
            if (jobs.isRunning("files")) throw new HttpError(409, "đang tải tệp tài liệu, đợi xong rồi hãy crawl");
            return forward("POST", "/start", b.toString(), 30);
        });
        h.post("/api/crawl/stop", r -> forward("POST", "/stop", "{}", 60));   // crawler chờ tới 30 s cho tiến trình thoát êm
        h.get("/api/crawl/status", r -> forward("GET", "/status", null, 10));
    }

    private Http.Status forward(String method, String path, String body, int seconds) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(seconds))
                    .header("Content-Type", "application/json");
            request = method.equals("POST") ? request.POST(HttpRequest.BodyPublishers.ofString(body)) : request.GET();
            HttpResponse<byte[]> r = cli.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Http.Status(r.statusCode(), Http.M.readTree(r.body()));
        } catch (IOException e) {
            throw new HttpError(502, "không gọi được crawler (" + url + "): " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpError(502, "không gọi được crawler: bị ngắt");
        }
    }

    /** Crawler có trả lời không (cho {@code /api/health}). */
    public boolean isReachable() {
        try {
            return forward("GET", "/status", null, 2).code() == 200;
        } catch (HttpError e) {
            return false;
        }
    }

    /** Crawler đang chạy một mẻ không; không gọi được thì coi như không. */
    public boolean isRunning() {
        try {
            var s = forward("GET", "/status", null, 3);
            return s.code() == 200 && ((JsonNode) s.body()).path("running").asBoolean(false);
        } catch (HttpError e) {
            return false;
        }
    }
}
