package vn.hust.search;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.text.Normalizer;

/**
 * Bỏ dấu tiếng Việt, viết tay chứ không kéo thêm thư viện.
 *
 * Cách làm: NFD tách một chữ có dấu thành chữ gốc + ký tự dấu rời, vứt hết ký
 * tự dấu là xong. Riêng "đ" không phải chữ ghép dấu — Unicode xếp nó là một chữ
 * cái riêng nên NFD không tách được, phải thay tay.
 *
 * Vì sao cần: StandardAnalyzer giữ nguyên dấu, nên gõ "diem chuan" không ra gì
 * dù kho đầy bài "điểm chuẩn". Index thêm một bản bỏ dấu song song thì gõ kiểu
 * nào cũng ra, mà bản còn dấu vẫn được cộng điểm cao hơn nên gõ đúng dấu vẫn
 * xếp trên.
 */
public final class Fold {

    private Fold() { }

    /** "Điểm chuẩn ĐHBK" -> "Diem chuan DHBK". Giữ nguyên độ dài từng ký tự gốc
     *  ở mức đủ dùng: một chữ cái vào là một chữ cái ra, nên offset tô sáng
     *  không lệch. */
    public static String bo_dau(String s) {
        if (s == null || s.isEmpty()) return "";
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;   // dấu rời
            if (c == 'đ') b.append('d');
            else if (c == 'Đ') b.append('D');
            else b.append(c);
        }
        return b.toString();
    }

    /** Lọc token: đổi chữ trong token, không đụng tới offset nên Highlighter
     *  vẫn trỏ đúng vào đoạn văn bản gốc còn dấu. */
    public static final class Filter extends TokenFilter {
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);

        public Filter(TokenStream in) { super(in); }

        @Override public boolean incrementToken() throws IOException {
            if (!input.incrementToken()) return false;
            String s = term.toString();
            String f = bo_dau(s);
            if (!f.equals(s)) term.setEmpty().append(f);
            return true;
        }
    }

    /**
     * Cùng bộ tách token với StandardAnalyzer, chỉ thêm bước bỏ dấu. Giữ nguyên
     * tokenizer là có chủ ý: hai trường song song (còn dấu / bỏ dấu) phải sinh
     * ra token ở cùng vị trí thì truy vấn cụm mới chạy đúng trên cả hai.
     */
    public static final class Analyzer extends org.apache.lucene.analysis.Analyzer {
        @Override protected TokenStreamComponents createComponents(String field) {
            Tokenizer src = new StandardTokenizer();
            TokenStream out = new Filter(new LowerCaseFilter(src));
            return new TokenStreamComponents(src, out);
        }
    }
}
