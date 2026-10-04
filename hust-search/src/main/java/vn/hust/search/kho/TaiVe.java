package vn.hust.search.kho;

import java.io.IOException;
import java.net.URI;
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
public class TaiVe {
    public static final long NHIP_MS = 3000;
    private static final String UA_TRINH_DUYET = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);

    /** Phần của phản hồi mà các nơi dùng tới. {@code url} là url sau khi theo redirect. */
    public record PhanHoi(String url, int status, String contentType, String encoding, byte[] body) {}

    private final HttpClient cli = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();
    private final long nhipMs;
    private long lanCuoi = 0;

    public TaiVe() {
        this(NHIP_MS);
    }

    public TaiVe(long nhipMs) {
        this.nhipMs = nhipMs;
    }

    public PhanHoi tai(String url) {
        synchronized (this) {
            long cho = nhipMs - (System.currentTimeMillis() - lanCuoi);
            if (cho > 0) {
                try {
                    Thread.sleep(cho);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lanCuoi = System.currentTimeMillis();
        }
        HttpResponse<byte[]> r;
        try {
            r = cli.send(HttpRequest.newBuilder(Url.httpUri(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", UA_TRINH_DUYET).build(), HttpResponse.BodyHandlers.ofByteArray());
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
        return new PhanHoi(r.uri().toString(), r.statusCode(), ct, enc, r.body());
    }
}
