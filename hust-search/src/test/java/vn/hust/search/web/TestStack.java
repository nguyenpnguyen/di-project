package vn.hust.search.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import vn.hust.search.Index;
import vn.hust.search.store.Download;
import vn.hust.search.store.RawStore;
import vn.hust.search.store.Download.Response;
import vn.hust.search.mongo.Db;
import vn.hust.search.mongo.DocumentJob;

/** Dựng cả tầng web trên cổng ngẫu nhiên với Index/kho tạm, tải web giả; test gọi qua HTTP thật. */
final class TestStack implements AutoCloseable {
    record Reply(int status, JsonNode json, String text) {}

    final Path tmp;
    final Index idx;
    final RawStore store;
    final Db db;
    final Mongo mongo;
    final BackgroundJob jobs = new BackgroundJob();
    final HttpServer server;
    final HttpClient cli = HttpClient.newHttpClient();
    final int port;
    /** Url đã được gọi tải, theo thứ tự. */
    final java.util.List<String> fetched = new java.util.ArrayList<>();
    volatile Function<String, Download.Response> web = u -> { throw new HttpError(502, "không tải được: test chưa giả web"); };

    /** {@code db}: Mongo thật (đã ping được) hoặc null để dùng Mongo "chết". */
    TestStack(Path tmp, Db db) throws Exception {
        this.tmp = tmp;
        this.idx = new Index(tmp.resolve("index"));
        this.store = new RawStore(Files.createDirectories(tmp.resolve("data")));
        this.db = db != null ? db : new Db("mongodb://127.0.0.1:1", "chet");
        this.mongo = new Mongo(this.db);
        ApiCrawl crawl = new ApiCrawl("http://127.0.0.1:1", jobs);
        Function<String, Response> fetcher = u -> {
            fetched.add(u);
            return web.apply(u);
        };
        Http http = new Http(Files.createDirectories(tmp.resolve("static")));
        new ApiSearch(idx, store, mongo, fetcher, crawl).register(http);
        new ApiExtract(idx, store, mongo, fetcher, store.dir().resolve("files"), jobs, crawl,
                u -> new DocumentJob.Resp(404, "", "", new byte[0])).register(http);
        crawl.register(http);
        server = http.start(0);
        port = server.getAddress().getPort();
    }

    static Download.Response html(String url, String content) {
        return new Download.Response(url, 200, "text/html; charset=utf-8", "utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    Reply get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build());
    }

    Reply post(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> r = cli.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode j = null;
        try {
            j = Http.M.readTree(r.body());
        } catch (Exception e) {
            // không phải JSON (csv, html)
        }
        return new Reply(r.statusCode(), j, r.body());
    }

    @Override
    public void close() throws Exception {
        server.stop(0);
        idx.close();
        db.close();
    }
}
