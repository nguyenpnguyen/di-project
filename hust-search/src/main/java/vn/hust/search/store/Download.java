package vn.hust.search.store;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.hust.search.web.HttpError;

/**
 * Tải lẻ một url theo nhịp chung của mọi lần tải lẻ ({@code /api/fetch}, {@code /api/extract/url}).
 * Chặn nhịp ở phía máy chủ chứ không tin giao diện: bấm nhanh tay hay mở hai tab là bắn liên tiếp,
 * đúng kiểu ăn 429. Bản port của {@code main.py: tai_ve}.
 */
public class Download {
    public static final long INTERVAL_MS = 3000;
    private static final String BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);

    /** Phần của phản hồi mà các nơi dùng tới. {@code url} là url sau khi theo redirect. */
    public record Response(String url, int status, String contentType, String encoding, byte[] body) {}

    private final HttpClient cli = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();
    private final long intervalMs;
    private long lastRequest = 0;

    public Download() {
        this(INTERVAL_MS);
    }

    public Download(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public Response fetch(String url) {
        synchronized (this) {
            long waitMs = intervalMs - (System.currentTimeMillis() - lastRequest);
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequest = System.currentTimeMillis();
        }
        HttpResponse<byte[]> r;
        try {
            r = cli.send(HttpRequest.newBuilder(Url.httpUri(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", BROWSER_UA).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | IllegalArgumentException e) {
            throw new HttpError(502, "không tải được: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpError(502, "không tải được: bị ngắt");
        }
        if (r.statusCode() == 429) throw new HttpError(429, "site đang chặn nhịp, đợi rồi thử lại");
        if (r.statusCode() >= 400) throw new HttpError(502, "site trả HTTP " + r.statusCode());
        String ct = r.headers().firstValue("Content-Type").orElse("");
        Matcher m = CHARSET.matcher(ct);
        String enc = m.find() ? m.group(1) : StandardCharsets.UTF_8.name().toLowerCase();
        return new Response(r.uri().toString(), r.statusCode(), ct, enc, r.body());
    }
}
