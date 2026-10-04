package vn.hust.search.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Router nhỏ trên {@code com.sun.net.httpserver}: bảng {@code (method, path) -> handler}, đọc query và
 * body JSON, đổi {@link HttpError} thành {@code {"detail": "..."}} như FastAPI, phục vụ {@code static/}.
 * Handler trả đối tượng bất kỳ (gửi JSON 200), {@link Status} (mã khác 200) hoặc {@link #SENT}
 * (handler tự ghi phản hồi, vd. CSV chunked).
 */
public final class Http {
    public static final ObjectMapper M = new ObjectMapper();
    /** Handler đã tự gửi phản hồi qua {@link Req#ex}. */
    public static final Object SENT = new Object();

    public interface Handler {
        Object handle(Req r) throws Exception;
    }

    public record Status(int code, Object body) {}

    private final Map<String, Handler> routes = new HashMap<>();
    private final Set<String> paths = new java.util.HashSet<>();
    private final Path staticDir;

    public Http(Path staticDir) {
        this.staticDir = staticDir;
    }

    public void get(String path, Handler h) {
        routes.put("GET " + path, h);
        paths.add(path);
    }

    public void post(String path, Handler h) {
        routes.put("POST " + path, h);
        paths.add(path);
    }

    /** Một yêu cầu: query đã giải mã UTF-8 và các hàm đọc kiểu có mặc định (sai kiểu = 422 như FastAPI). */
    public static final class Req {
        public final HttpExchange ex;
        private final Map<String, String> q = new LinkedHashMap<>();
        private JsonNode body;

        Req(HttpExchange ex) {
            this.ex = ex;
            String raw = ex.getRequestURI().getRawQuery();
            if (raw == null) return;
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                q.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }

        /** Null nếu không có tham số. */
        public String str(String k) {
            return q.get(k);
        }

        public String str(String k, String fallback) {
            return q.getOrDefault(k, fallback);
        }

        /** Tham số bắt buộc: thiếu thì 422. */
        public String require(String k) {
            String v = q.get(k);
            if (v == null) throw new HttpError(422, "thiếu tham số " + k);
            return v;
        }

        /** Rỗng hoặc vắng đều thành null — như Python lọc {@code if v}. */
        public String nonEmpty(String k) {
            String v = q.get(k);
            return v == null || v.isEmpty() ? null : v;
        }

        public int integer(String k, int fallback) {
            String v = q.get(k);
            if (v == null) return fallback;
            try {
                return Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                throw new HttpError(422, "tham số " + k + " phải là số nguyên");
            }
        }

        /** Null nếu không có tham số. */
        public Boolean boolOpt(String k) {
            String v = q.get(k);
            return v == null ? null : parseBool(k, v);
        }

        public JsonNode body() {
            if (body == null) {
                try {
                    byte[] b = ex.getRequestBody().readAllBytes();
                    body = b.length == 0 ? M.createObjectNode() : M.readTree(b);
                } catch (IOException e) {
                    throw new HttpError(422, "JSON không hợp lệ: " + e.getMessage());
                }
                if (!body.isObject()) throw new HttpError(422, "body phải là một đối tượng JSON");
            }
            return body;
        }
    }

    static boolean parseBool(String k, String v) {
        switch (v.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on", "t", "y": return true;
            case "false", "0", "no", "off", "f", "n": return false;
            default: throw new HttpError(422, "tham số " + k + " phải là true hoặc false");
        }
    }

    // ---- đọc trường của body JSON (kiểu sai = 422)
    public static String bodyStr(JsonNode b, String k, String fallback) {
        JsonNode v = b.get(k);
        if (v == null || v.isNull()) return fallback;
        if (!v.isTextual()) throw new HttpError(422, k + " phải là chuỗi");
        return v.asText();
    }

    public static boolean bodyBool(JsonNode b, String k, boolean fallback) {
        JsonNode v = b.get(k);
        if (v == null || v.isNull()) return fallback;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) return parseBool(k, v.asText());
        throw new HttpError(422, k + " phải là true hoặc false");
    }

    public static int bodyInt(JsonNode b, String k, int fallback, int min, int max) {
        JsonNode v = b.get(k);
        if (v == null || v.isNull()) return fallback;
        if (!v.canConvertToInt() || v.isFloatingPointNumber() && v.asDouble() != Math.rint(v.asDouble()))
            throw new HttpError(422, k + " phải là số nguyên");
        int n = v.asInt();
        if (n < min || n > max) throw new HttpError(422, k + " phải nằm trong khoảng " + min + ".." + max);
        return n;
    }

    // ---- gửi
    public static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = M.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Handler h = routes.get(method + " " + path);
            if (h == null) {
                if (method.equals("GET") && (path.equals("/") || path.startsWith("/static/"))) {
                    serveStatic(ex, path.equals("/") ? "index.html" : path.substring("/static/".length()));
                    return;
                }
                throw new HttpError(paths.contains(path) ? 405 : 404, paths.contains(path) ? "Method Not Allowed" : "Not Found");
            }
            Object out = h.handle(new Req(ex));
            if (out == SENT) return;
            if (out instanceof Status s) send(ex, s.code(), s.body());
            else send(ex, 200, out);
        } catch (HttpError e) {
            send(ex, e.code, Map.of("detail", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("detail", "lỗi nội bộ: " + e));
        }
    }

    private void serveStatic(HttpExchange ex, String rel) throws IOException {
        Path f = staticDir.resolve(rel).normalize();
        if (!f.startsWith(staticDir.normalize()) || !Files.isRegularFile(f)) throw new HttpError(404, "Not Found");
        String n = f.getFileName().toString();
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        String ct = switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            default -> "application/octet-stream";
        };
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    /** Mở cổng; mỗi yêu cầu một virtual thread nên lượt tải lẻ đứng chờ nhịp không chặn lượt tìm kiếm. */
    public HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/", ex -> dispatch(ex));
        s.start();
        return s;
    }
}
