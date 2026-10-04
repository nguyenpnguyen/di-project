package vn.hust.search;

/**
 * Điểm nền của một tài liệu, tính từ chính nó chứ không từ truy vấn.
 *
 * Vì sao cần: kho crawl hiện 82% là trang mục lục ("… - Trang 9"), mà mục lục
 * nào cũng nhắc lại tiêu đề của cả chục bài nên điểm Lucene chấm chúng rất cao. Người
 * tìm "học bổng" muốn ra bài viết, không muốn ra trang liệt kê. Ba tín hiệu rẻ
 * tiền dưới đây nhân vào điểm Lucene để kéo bài viết lên trước.
 *
 * Cố ý để hệ số nhẹ tay: đây là điểm nền, không được lấn át độ khớp từ khoá.
 * Khoảng dao động tổng cộng chỉ quanh 0,35x — đủ đảo thứ tự hai kết quả sát
 * nhau, không đủ đẩy một bài lạc đề lên đầu.
 */
public final class Rank {

    private Rank() { }

    /** Loại trang đoán từ url. Không cần trường mới trong index: đường dẫn của
     *  hust.edu.vn đã nói rõ trang phân trang, trang chuyên mục hay bài viết. */
    static double pageType(String url) {
        if (url == null || url.isEmpty()) return 1.0;
        String u = url.toLowerCase();
        if (u.contains("/page-") || u.contains("/page/")) return 0.50;   // trang 2, 3, 4…
        if (u.endsWith(".html") || u.endsWith(".htm")) return 1.00;      // bài viết
        if (u.endsWith("/")) return 0.70;                                // cửa vào chuyên mục
        return 0.90;
    }

    /** Bài dài thì thường là nội dung thật, bài vài chục chữ thường là vỏ trang.
     *  Đường cong thoải để không thành "cứ dài là hơn". */
    static double lengthFactor(int charCount) {
        double x = Math.min(1.0, charCount / 1200.0);
        return 0.75 + 0.25 * x;
    }

    /** Tin mới nhỉnh hơn tin cũ. Không có ngày thì đứng giữa, không thưởng không phạt. */
    static double recencyFactor(String date, int currentYear) {
        if (date == null || date.length() < 4) return 1.0;
        int year;
        try {
            year = Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return 1.0;
        }
        if (year < 1990 || year > currentYear + 1) return 1.0;              // ngày rác
        double age = Math.max(0, currentYear - year);
        return 0.92 + 0.16 * Math.exp(-age / 3.0);
    }

    /** Nhân ba tín hiệu lại. Gọi một lần cho mỗi tài liệu trong cửa sổ xếp lại. */
    public static double baseScore(String url, int charCount, String date, int currentYear) {
        return pageType(url) * lengthFactor(charCount) * recencyFactor(date, currentYear);
    }
}
