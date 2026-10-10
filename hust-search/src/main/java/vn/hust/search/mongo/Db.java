package vn.hust.search.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationOptions;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.Document;

/**
 * Kết nối MongoDB, lược đồ {@code $jsonSchema} và index — bản port của {@code db.py}.
 * Lược đồ nằm ở {@code mongo-schema.json}; mô tả từng trường ở docs/SCHEMA.md.
 * Mongo là tầng lưu kết quả bóc tách nằm giữa kho thô của crawler và Lucene.
 */
public final class Db implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase db;

    public Db(String url, String name) {
        this.client = MongoClients.create(com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(url))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS)).build());
        this.db = client.getDatabase(name);
    }

    public MongoDatabase get() {
        return db;
    }

    /** Ném lỗi nếu Mongo chưa lên (chờ tối đa 3 giây). */
    public void ping() {
        db.runCommand(new Document("ping", 1));
    }

    /** Tạo collection kèm validator và index; chạy lại nhiều lần không sao. */
    public void init() throws IOException {
        Document cfg;
        try (InputStream in = Db.class.getResourceAsStream("/mongo-schema.json")) {
            cfg = Document.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        Set<String> have = new HashSet<>();
        db.listCollectionNames().forEach(have::add);
        Document schemas = cfg.get("schemas", Document.class);
        for (String name : schemas.keySet()) {
            Document validator = new Document("$jsonSchema",
                    new Document("bsonType", "object").append("required", schemas.get(name, Document.class).get("required"))
                            .append("properties", schemas.get(name, Document.class).get("properties")));
            if (have.contains(name)) {
                db.runCommand(new Document("collMod", name).append("validator", validator).append("validationAction", "error"));
            } else {
                db.createCollection(name, new CreateCollectionOptions().validationOptions(
                        new ValidationOptions().validator(validator).validationAction(ValidationAction.ERROR)));
            }
        }
        Document indexes = cfg.get("indexes", Document.class);
        for (String name : indexes.keySet())
            for (Document keys : indexes.getList(name, Document.class))
                db.getCollection(name).createIndex(keys, new IndexOptions());
    }

    public void dropDb() {
        db.drop();
    }

    @Override
    public void close() {
        client.close();
    }

    /** Tên các collection theo lược đồ. */
    public static List<String> collections() {
        return List.of("pages", "links", "nav_links", "images", "documents", "templates");
    }
}
