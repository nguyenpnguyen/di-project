package vn.hust.search.boctach;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Bóc chữ từ tệp tài liệu bằng Apache Tika: pdf, docx, xlsx, pptx và cả doc/xls/ppt cũ (POI).
 * Bản port của {@code boc_tach/tep.py}. Không OCR: PDF scan không có lớp chữ chỉ được gắn cờ
 * {@code needs_ocr}; image không có tesseract nên Tika không tự OCR được.
 */
public final class Tep {
    private Tep() {}

    public static final Set<String> HO_TRO = Set.of("pdf", "docx", "xlsx", "pptx", "doc", "xls", "ppt");
    public static final Map<String, String> MIME_SANG_DUOI = Map.of(
            "application/pdf", "pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx",
            "application/msword", "doc",
            "application/vnd.ms-excel", "xls",
            "application/vnd.ms-powerpoint", "ppt");
    /** Dựng AutoDetectParser nạp cả bộ parser qua ServiceLoader (~1 s); dùng chung, nó an toàn khi nhiều luồng. */
    private static final AutoDetectParser PARSER = new AutoDetectParser();
    static final int TOI_DA_KY_TU = 500_000;
    /** PDF có ít hơn chừng này ký tự mỗi trang thì coi là scan (không có lớp chữ). */
    static final int NGUONG_KY_TU_TRANG = 20;

    /** Kết quả bóc chữ; {@code status}: ok | unsupported | error. */
    public record KetQua(String status, String text, int nPages, boolean needsOcr, boolean encodingSuspect, String error) {}

    private static final Pattern NGANG = Pattern.compile("[ \\t\\r\\f\\u000B]+");
    private static final Pattern XUONG = Pattern.compile("\\s*\\n\\s*", Pattern.UNICODE_CHARACTER_CLASS);

    public static String duoiTuUrl(String url) {
        String q = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        String ten = q.substring(q.lastIndexOf('/') + 1);
        return ten.contains(".") ? ten.substring(ten.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /**
     * Heuristic: văn bản tiếng Việt bảng mã cũ (TCVN3/VNI) đọc bằng Unicode ra nhiều ký tự Latin-1
     * lạ mà gần như không có chữ Việt có dấu. Có thể báo nhầm/sót.
     */
    public static boolean nghiSaiBangMa(String text) {
        if (text.length() < 200) return false;
        long la = 0, viet = 0;
        for (char c : text.toCharArray()) {
            if (c >= '¡' && c <= 'ÿ') la++;
            if ("ăâđêôơưĂÂĐÊÔƠƯ".indexOf(c) >= 0 || (c >= 'Ạ' && c <= 'ỹ')) viet++;
        }
        return (double) la / text.length() > 0.04 && (double) viet / text.length() < 0.005;
    }

    private static int soTrang(Metadata md, String ext, byte[] data) {
        for (String k : new String[]{"xmpTPg:NPages", "meta:page-count", "meta:slide-count"}) {
            String v = md.get(k);
            if (v != null) {
                try {
                    return Integer.parseInt(v.trim());
                } catch (NumberFormatException e) {
                    // thử khoá khác
                }
            }
        }
        if (ext.equals("xlsx")) {                             // số sheet = số xl/worksheets/sheetN.xml
            int n = 0;
            try (var z = new ZipInputStream(new ByteArrayInputStream(data))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; )
                    if (e.getName().matches("xl/worksheets/sheet\\d+\\.xml")) n++;
            } catch (java.io.IOException ex) {
                // không đếm được: 0
            }
            return n;
        }
        return 0;
    }

    /** Không ném lỗi: tệp hỏng thành {@code status=error}. */
    public static KetQua bocChu(byte[] data, String ext) {
        ext = ext.toLowerCase(java.util.Locale.ROOT);
        if (!HO_TRO.contains(ext)) return new KetQua("unsupported", "", 0, false, false, "");
        var handler = new BodyContentHandler(TOI_DA_KY_TU);
        var md = new Metadata();
        try (var in = TikaInputStream.get(data)) {
            PARSER.parse(in, handler, md, new ParseContext());
        } catch (Exception e) {                               // TikaException, SAXException, IOException, RuntimeException của POI
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                String m = e.getClass().getSimpleName() + ": " + e.getMessage();
                return new KetQua("error", "", 0, false, false, m.substring(0, Math.min(300, m.length())));
            }                                                 // quá giới hạn ký tự: giữ phần đã có
        }
        // Tika dò kiểu theo nội dung; đuôi nói tệp là văn phòng/pdf mà nội dung lại là chữ thường thì tệp hỏng
        String ct = String.valueOf(md.get("Content-Type"));
        if (ct.startsWith("text/") || ct.equals("application/octet-stream"))
            return new KetQua("error", "", 0, false, false, "nội dung không phải " + ext + " (" + ct + ")");
        String text = NGANG.matcher(handler.toString()).replaceAll(" ");
        text = HtmlSach.strip(XUONG.matcher(text).replaceAll("\n"));
        text = HtmlSach.head(text, TOI_DA_KY_TU);
        int n = soTrang(md, ext, data);
        boolean ocr = false;
        if (ext.equals("pdf") && text.length() < NGUONG_KY_TU_TRANG * Math.max(n, 1)) {
            ocr = true;
            text = "";                                        // vài ký tự lẻ không đáng index
        }
        return new KetQua("ok", text, n, ocr, nghiSaiBangMa(text), "");
    }
}
