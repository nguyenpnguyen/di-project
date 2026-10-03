package vn.hust.search;

/**
 * Dấu vân tay nội dung, để nhận ra hai url khác nhau nhưng cùng một bài.
 *
 * Kho crawl có sẵn kiểu trùng này: hust.edu.vn phát cùng một bài ở cả
 * /vi/news/tin-tuc/… lẫn /vi/news/savefile/tin-tuc/…, nên tìm "học bổng" ra
 * hai dòng y hệt nhau. So chuỗi thẳng thì không ăn thua vì hai bản lệch nhau
 * vài chữ ở phần vỏ trang (menu, ngày sinh trang, số lượt xem).
 *
 * Cách làm là SimHash tự cài, không kéo thư viện:
 *   1. bỏ dấu, thường hoá, cắt thành các cụm 3 từ liền nhau (shingle);
 *   2. băm mỗi cụm bằng FNV-1a 64 bit;
 *   3. mỗi bit nào được nhiều cụm bầu 1 hơn 0 thì bật lên.
 * Hai văn bản giống nhau phần lớn sẽ cho hai vân tay lệch nhau vài bit, nên
 * đếm bit khác nhau (Hamming) là biết chúng có phải một bài không. Khác hẳn
 * băm thường: đổi một chữ là băm thường đổi sạch, còn SimHash gần như không đổi.
 */
public final class Sig {

    private Sig() { }

    /**
     * Dưới ngần này cụm thì vân tay không đủ tin, trả 0 = "đừng gộp bài này".
     *
     * Con số đo ra chứ không đoán: lấy một bài rồi thêm phần vỏ trang ("Lượt
     * xem 214 · Chia sẻ Facebook") vào bản thứ hai, đếm bit lệch giữa hai vân
     * tay theo độ dài bài — 34 cụm lệch 10 bit, 70 cụm lệch 6, từ 106 cụm trở
     * lên đứng yên ở 3. Trong khi hai bài khác chủ đề lệch 18 bit trở lên. Nên
     * dưới 100 cụm thì hai ngưỡng đó chồng lên nhau, gộp là gộp nhầm.
     */
    private static final int TOI_THIEU_CUM = 100;

    public static long vanTay(String tieuDe, String than) {
        String t = Fold.bo_dau((tieuDe == null ? "" : tieuDe) + " "
                + (than == null ? "" : than)).toLowerCase();
        String[] tu = t.split("[^a-z0-9]+");
        int[] phieu = new int[64];
        int cum = 0;
        for (int i = 0; i + 2 < tu.length; i++) {
            if (tu[i].isEmpty()) continue;
            long h = bam(tu[i] + " " + tu[i + 1] + " " + tu[i + 2]);
            cum++;
            for (int b = 0; b < 64; b++) phieu[b] += ((h >>> b) & 1L) == 1L ? 1 : -1;
        }
        if (cum < TOI_THIEU_CUM) return 0L;
        long out = 0L;
        for (int b = 0; b < 64; b++) if (phieu[b] > 0) out |= (1L << b);
        return out;
    }

    /** FNV-1a 64 bit: ngắn, không cần thư viện, tán bit đủ đều cho việc này. */
    static long bam(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Số bit khác nhau giữa hai vân tay. */
    public static int lech(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** Ngưỡng coi là cùng một bài: 3/64 bit, đúng mức nhiễu đo được ở trên. Hai
     *  bài khác chủ đề lệch từ 18 bit, nên khoảng cách an toàn còn rất rộng. */
    public static boolean cungMotBai(long a, long b) {
        return a != 0L && b != 0L && lech(a, b) <= 3;
    }
}
