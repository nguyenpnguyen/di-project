package vn.hust.search.web;

import com.mongodb.client.MongoDatabase;
import vn.hust.search.mongo.Db;

/** Mongo có thể chưa lên: tìm kiếm không phụ thuộc nó, các route bóc tách thì trả 503. */
public final class Mongo {
    private final Db db;

    public Mongo(Db db) {
        this.db = db;
    }

    public Db db() {
        return db;
    }

    /** Ping rồi trả database; 503 nếu chưa lên (chờ tối đa 3 giây). */
    public MongoDatabase get() {
        try {
            db.ping();
            return db.get();
        } catch (Exception e) {
            throw new HttpError(503, "MongoDB không sẵn sàng: " + e.getMessage());
        }
    }

    /** Như {@link #get} nhưng null thay vì 503, cho các route chạy được khi không có Mongo. */
    public MongoDatabase opt() {
        try {
            return get();
        } catch (HttpError e) {
            return null;
        }
    }
}
