package vn.hust.search.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.hust.search.kho.Kho;

/**
 * Chạy templates + extract cả kho thật vào MongoDB thật (có $jsonSchema), DB tạm. Cần kho thô
 * ({@code DATA_DIR}) và Mongo ({@code MONGO_URL}); thiếu một trong hai thì bỏ qua.
 */
class KhoThatTest {
    @Test
    void dungTemplatesVaExtractCaKho() throws Exception {
        Path data = Path.of(System.getenv().getOrDefault("DATA_DIR", "../hust-crawler/data"));
        assumeTrue(Files.isDirectory(data.resolve("raw")), "không có kho thô");
        try (var d = new Db(System.getenv().getOrDefault("MONGO_URL", "mongodb://localhost:27017"), "test_" + UUID.randomUUID().toString().replace("-", ""))) {
            try {
                d.ping();
            } catch (Exception e) {
                assumeTrue(false, "không có MongoDB");
            }
            try {
                d.init();
                var kho = new Kho(data);
                long t0 = System.currentTimeMillis();
                var tpl = Trich.dungTemplates(d.get(), kho.banGhi(), n -> { });
                long t1 = System.currentTimeMillis();
                var r = Trich.chayExtract(d.get(), kho.banGhi(), 0, n -> { }, 200);
                long t2 = System.currentTimeMillis();
                var cov = Trich.coverage(d.get());
                System.out.printf("templates %d host (%d ms); extract %s (%d ms); coverage %d host; links %d, nav %d, images %d%n",
                        tpl.size(), t1 - t0, r, t2 - t1, cov.size(), d.get().getCollection("links").countDocuments(),
                        d.get().getCollection("nav_links").countDocuments(), d.get().getCollection("images").countDocuments());
                // Mongo của stack Python (kho ở thời điểm trước đó) có 3388 trang / 35641 link / 8935 ảnh
                assertTrue(Math.abs(d.get().getCollection("pages").countDocuments() - 3388) <= 10);
                assertEquals(8935, d.get().getCollection("images").countDocuments());
            } finally {
                d.dropDb();
            }
        }
    }
}
