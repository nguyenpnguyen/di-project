package vn.hust.search;

/**
 * Điểm nền của một tài liệu, tính từ chính nó chứ không từ truy vấn.
 *
 * Vì sao cần: kho crawl hiện 82% là trang mục lục ("… - Trang 9"), mà mục lục
 * nào cũng nhắc lại tiêu đề của cả chục bài nên BM25 chấm chúng rất cao. Người
 * tìm "học bổng" muốn ra bài viết, không muốn ra trang liệt kê. Ba tín hiệu rẻ
 * tiền dưới đây nhân vào điểm BM25 để kéo bài viết lên trước.
 *
 * Cố ý để hệ số nhẹ tay: đây là điểm nền, không được lấn át độ khớp từ khoá.
 * Khoảng dao động tổng cộng chỉ quanh 0,35x — đủ đảo thứ tự hai kết quả sát
 * nhau, không đủ đẩy một bài lạc đề lên đầu.
 */
public final class Rank {

    private Rank() { }

    /** Loại trang đoán từ url. Không cần trường mới trong index: đường dẫn của
     *  hust.edu.vn đã nói rõ trang phân trang, trang chuyên mục hay bài viết. */
    static double kieuTrang(String url) {
        if (url == null || url.isEmpty()) return 1.0;
        String u = url.toLowerCase();
        if (u.contains("/page-") || u.contains("/page/")) return 0.50;   // trang 2, 3, 4…
        if (u.endsWith(".html") || u.endsWith(".htm")) return 1.00;      // bài viết
        if (u.endsWith("/")) return 0.70;                                // cửa vào chuyên mục
        return 0.90;
    }

    /** Bài dài thì thường là nội dung thật, bài vài chục chữ thường là vỏ trang.
     *  Đường cong thoải để không thành "cứ dài là hơn". */
    static double doDay(int soKyTu) {
        double x = Math.min(1.0, soKyTu / 1200.0);
        return 0.75 + 0.25 * x;
    }

    /** Tin mới nhỉnh hơn tin cũ. Không có ngày thì đứng giữa, không thưởng không phạt. */
    static double doMoi(String ngay, int namHienTai) {
        if (ngay == null || ngay.length() < 4) return 1.0;
        int nam;
        try {
            nam = Integer.parseInt(ngay.substring(0, 4));
        } catch (NumberFormatException e) {
            return 1.0;
        }
        if (nam < 1990 || nam > namHienTai + 1) return 1.0;              // ngày rác
        double tuoi = Math.max(0, namHienTai - nam);
        return 0.92 + 0.16 * Math.exp(-tuoi / 3.0);
    }

    /** Nhân ba tín hiệu lại. Gọi một lần cho mỗi tài liệu trong cửa sổ xếp lại. */
    public static double diemNen(String url, int soKyTu, String ngay, int namHienTai) {
        return kieuTrang(url) * doDay(soKyTu) * doMoi(ngay, namHienTai);
    }
}
