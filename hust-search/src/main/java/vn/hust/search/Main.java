package vn.hust.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import vn.hust.search.kho.Kho;
import vn.hust.search.kho.TaiVe;
import vn.hust.search.mongo.Db;
import vn.hust.search.mongo.TepJob;
import vn.hust.search.web.ApiBocTach;
import vn.hust.search.web.ApiCrawl;
import vn.hust.search.web.ApiTimKiem;
import vn.hust.search.web.Http;
import vn.hust.search.web.Mongo;
import vn.hust.search.web.BackgroundJob;

/**
 * Một tiến trình: Lucene + MongoDB + bóc tách + HTTP API và giao diện, cổng {@code PORT} (mặc định 8000).
 * Mongo chưa lên thì vẫn chạy: tìm kiếm không cần nó, các route bóc tách trả 503.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        Path indexDir = Path.of(env.getOrDefault("INDEX_DIR", "/index"));
        Path data = Path.of(env.getOrDefault("DATA_DIR", "/data"));
        Path staticDir = Path.of(env.getOrDefault("STATIC_DIR", "/app/static"));
        int port = Integer.parseInt(env.getOrDefault("PORT", "8000"));

        Index idx = new Index(indexDir);
        Kho kho = new Kho(data);
        Db db = new Db(env.getOrDefault("MONGO_URL", "mongodb://mongo:27017"), env.getOrDefault("MONGO_DB", "hust"));
        Mongo mongo = new Mongo(db);
        try {
            db.init();
            long n = TepJob.chuyenDinhDangCu(db.get());   // Q2: doc/xls/ppt từng bị đánh dấu unsupported, Tika đọc được
            if (n > 0) System.out.println("[mongo] chuyển " + n + " tệp doc/xls/ppt từ unsupported về pending");
        } catch (Exception e) {
            System.out.println("[mongo] chưa khởi tạo được lược đồ: " + e);
        }

        BackgroundJob viec = new BackgroundJob();
        ApiCrawl crawl = new ApiCrawl(env.getOrDefault("CRAWLER_URL", "http://crawler:8090"), viec);
        TaiVe taiVe = new TaiVe();
        Http http = new Http(staticDir);
        new ApiTimKiem(idx, kho, mongo, taiVe::tai, crawl).dang(http);
        new ApiBocTach(idx, kho, mongo, taiVe::tai, data.resolve("files"), viec, crawl, TepJob.httpThat()).dang(http);
        crawl.dang(http);
        http.start(port);

        System.out.printf("Sẵn sàng: cổng %d, index %s (%d tài liệu), dữ liệu %s%n", port, indexDir, idx.numDocs(), data);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                idx.close();
            } catch (IOException ignored) {
                // đang thoát
            }
            db.close();
        }));
    }
}
