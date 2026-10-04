package vn.hust.search.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Một việc nền tại một thời điểm ({@code templates | extract | files | files-extract}): chạy hai việc
 * song song là đạp nhau ở Mongo và ở nhịp tải. Trạng thái giữ đúng khoá {@code /api/extract/status} từng trả.
 */
public final class BackgroundJob {
    public interface Job {
        Object chay(IntConsumer tienDo) throws Exception;
    }

    private boolean running;
    private String what = "";
    private volatile int done;
    private double started;
    private Object result;
    private String error;

    /** Bắt đầu việc; 409 nếu đang có việc khác. */
    public synchronized Map<String, Object> run(String name, Job j) {
        if (running) throw new HttpError(409, "đang chạy: " + what);
        running = true;
        what = name;
        done = 0;
        started = System.currentTimeMillis() / 1000.0;
        result = null;
        error = null;
        Thread t = new Thread(() -> {
            Object kq = null;
            String loi = null;
            try {
                kq = j.chay(n -> done = n);
            } catch (Throwable e) {
                loi = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            synchronized (this) {
                result = kq;
                error = loi;
                running = false;
            }
        }, "background-job-" + name);
        t.setDaemon(true);
        t.start();
        return Map.of("ok", true, "started", name);
    }

    public synchronized boolean isRunning(String name) {
        return running && what.equals(name);
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("what", what);
        m.put("done", done);
        m.put("started", started);
        m.put("result", result);
        m.put("error", error);
        m.put("elapsed_sec", started == 0 ? 0 : Math.round(System.currentTimeMillis() / 1000.0 - started));
        return m;
    }
}
