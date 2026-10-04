package vn.hust.search.boctach;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import vn.hust.search.boctach.LienKet.Canh;
import vn.hust.search.boctach.LienKet.CongKhai;
import vn.hust.search.kho.Url;

/**
 * Bóc tách trang HTML: khối nội dung, các trường, đồ thị liên kết — bản port của
 * {@code boc_tach/__init__.py}. {@link #bocTach} là cửa vào duy nhất; các lớp con làm từng phần.
 */
public final class BocTach {
    private BocTach() {}

    /** Bản Python là "1"; đổi để {@code coverage} / {@code extractor_version} phân biệt được trang do bản nào bóc. */
    public static final String VERSION = "2";

    public record Block(String path, double score, String method) {}

    /** Bản ghi đã bóc. {@code links} là cạnh nội dung, {@code navLinks} là cạnh khuôn. */
    public record Ket(String url, String host, String section, String title, String titleSrc, String text, String html,
                      String date, String dateSrc, String author, String authorSrc, String citedSource, Block block,
                      List<CongKhai> outgoingLinks, List<Canh> links, List<Canh> navLinks) {}

    public static Document parse(String html) {
        return Jsoup.parse(html);
    }

    /** HTML thô -> bản ghi đã bóc. null nếu không có cả tiêu đề lẫn chữ. */
    public static Ket bocTach(String html, String url, Set<String> khuon) {
        Document doc = parse(html);
        String host = Url.hostname(url);

        List<JsonNode> ld = Truong.jsonLd(doc);              // trước khi dọn cây: JSON-LD nằm trong <script>
        var raw = LienKet.thuThap(doc, url);                 // cạnh trước khi dọn: menu/footer bị xoá mất
        String section = Truong.section(doc);
        var tgMeta = Truong.tacGiaMeta(doc, ld);

        Khoi k = Khoi.timKhoi(doc, host, khuon, null);
        var tieuDe = Truong.tieuDe(doc, ld, k.node());
        var ngay = Truong.ngayDang(doc, ld, k.node());
        var tgn = Truong.dongTacGiaNguon(k.node());
        var tacGia = !tgMeta.v().isEmpty() ? tgMeta : tgn.tacGia();

        String text = k.node() != null ? HtmlSach.cleanSpace(HtmlSach.textBs4(k.node())) : "";
        if (tieuDe.v().isEmpty() && text.isEmpty()) return null;
        var chia = LienKet.chia(raw, k.node());
        List<Canh> noiDung = chia.get(0);
        return new Ket(url, host, section, HtmlSach.head(tieuDe.v(), 500), tieuDe.src(), HtmlSach.head(text, 200_000),
                HtmlSach.donHtml(k.node(), url), ngay.v(), ngay.src(), tacGia.v(), tacGia.src(), tgn.nguon(),
                new Block(k.path(), Khoi.lam(k.score(), 2), k.method()), LienKet.canhRaCongKhai(noiDung),
                noiDung, chia.get(1));
    }

    /**
     * Chạy lại bước chọn khối + chia cạnh của {@link #bocTach} và trả về từng bước để giao diện vẽ:
     * phễu số chữ qua từng lớp, các bậc đi xuống cây, cạnh trong/ngoài khối.
     */
    public static Map<String, Object> giaiThich(String html, String url, Set<String> khuon) {
        Document doc = parse(html);
        String host = Url.hostname(url);
        var raw = LienKet.thuThap(doc, url);
        int toanTrang = Khoi.soChu(doc.body());
        Map<String, Object> vet = new LinkedHashMap<>();
        Khoi k = Khoi.timKhoi(doc, host, khuon, vet);
        var chia = LienKet.chia(raw, k.node());
        String text = k.node() != null ? HtmlSach.cleanSpace(HtmlSach.textBs4(k.node())) : "";
        Map<String, Integer> theoLoai = new LinkedHashMap<>();
        for (Canh e : chia.get(0)) theoLoai.merge(e.dstKind, 1, Integer::sum);

        var block = new LinkedHashMap<String, Object>();
        block.put("path", k.path());
        block.put("score", Khoi.lam(k.score(), 2));
        block.put("method", k.method());
        block.put("selector", Khoi.SELECTOR_THEO_HOST.getOrDefault(host, ""));
        var canh = new LinkedHashMap<String, Object>();
        canh.put("noi_dung", chia.get(0).size());
        canh.put("khuon", chia.get(1).size());
        canh.put("noi_dung_theo_loai", theoLoai);
        var out = new LinkedHashMap<String, Object>();
        out.put("url", url);
        out.put("host", host);
        out.put("block", block);
        out.put("pheu", List.of(
                Map.of("buoc", "Toàn trang", "chu", toanTrang),
                Map.of("buoc", "Sau dọn cây", "chu", vet.getOrDefault("sau_don", 0)),
                Map.of("buoc", "Sau khử khuôn", "chu", vet.getOrDefault("sau_khuon", 0)),
                Map.of("buoc", "Khối được chọn", "chu", Khoi.soChu(k.node()))));
        out.put("khoi_khuon_bo", vet.getOrDefault("khoi_khuon_bo", 0));
        out.put("bac", vet.getOrDefault("bac", List.of()));
        out.put("trich", HtmlSach.head(text, 600));
        out.put("canh", canh);
        return out;
    }
}
