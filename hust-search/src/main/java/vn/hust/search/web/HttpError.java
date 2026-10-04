package vn.hust.search.web;

/** Lỗi nghiệp vụ có mã HTTP; tầng web đổi thành {"detail": "..."} như FastAPI. */
public class HttpError extends RuntimeException {
    public final int code;

    public HttpError(int code, String detail) {
        super(detail);
        this.code = code;
    }
}
