package vn.hust.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Year;
import java.util.*;

/**
 * Bọc Lucene: mở index một lần, ghi thì append, đọc thì gần thời gian thực.
 *
 * Vài lựa chọn đáng nói:
 *  - updateDocument theo Term("url") chứ không addDocument: index thêm nhiều lần
 *    trên cùng kho crawl thì mỗi url vẫn chỉ còn một bản, không đẻ trùng.
 *  - SearcherManager: tìm kiếm thấy ngay tài liệu vừa ghi mà không phải đóng writer.
 *  - StandardAnalyzer tách token theo chuẩn Unicode nên tiếng Việt ra từng âm tiết
 *    và giữ nguyên dấu; "điểm chuẩn" thành hai token, tìm cụm vẫn đúng.
 *  - Mỗi trường chữ được index hai bản: bản còn dấu và bản bỏ dấu ({@link Fold}).
 *    Chế độ tfidf dùng riêng bản bỏ dấu với ClassicSimilarity; enhanced ghép
 *    hai bản rồi thêm các tín hiệu xếp hạng cũ.
 */
public class Index implements AutoCloseable {

    public static final String F_URL = "url";
    public static final String F_TITLE = "title";
    public static final String F_TEXT = "text";
    public static final String F_HOST = "host";
    public static final String F_SECTION = "section";
    public static final String F_DATE = "date";
    /** Tác giả bóc từ bài (meta / JSON-LD / dòng "Tác giả:"); rỗng nếu không rõ. */
    public static final String F_AUTHOR = "author";
    /** page | document — lọc được, để tách bài viết khỏi tệp đính kèm. */
    public static final String F_KIND = "kind";
    /** Loại tệp để lọc: html | pdf | docx | xlsx | pptx | … — client không gửi thì tự suy ra. */
    public static final String F_FTYPE = "ftype";
    /** Bản bỏ dấu, chỉ để tìm chứ không lưu — đoạn trích vẫn lấy từ bản gốc. */
    public static final String F_TITLE_FOLDED = "title_kd";
    public static final String F_TEXT_FOLDED = "text_kd";
    /** Ngày dạng số yyyymmdd, để lọc khoảng và sắp xếp. 0 = không rõ ngày. */
    public static final String F_DATE_NUM = "date_num";
    /** Vân tay nội dung, để gộp hai url cùng một bài. */
    public static final String F_SIG = "sig";
    /** Bản HTML đã dọn, chỉ lưu chứ không index — dùng cho trang xem trước. */
    public static final String F_HTML = "html";

    private final Analyzer standardAnalyzer = new StandardAnalyzer();
    private final Analyzer foldAnalyzer = new Fold.Analyzer();
    private final Similarity similarity = new ClassicSimilarity();
    private final Analyzer analyzer;
    private final Directory dir;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final Path path;

    public Index(Path path) throws IOException {
        this.path = path;
        // Hai trường _kd dùng analyzer bỏ dấu, phần còn lại giữ nguyên như cũ.
        this.analyzer = new PerFieldAnalyzerWrapper(standardAnalyzer, Map.of(
                F_TITLE_FOLDED, foldAnalyzer, F_TEXT_FOLDED, foldAnalyzer));
        this.dir = FSDirectory.open(path);
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        cfg.setSimilarity(similarity);
        this.writer = new IndexWriter(dir, cfg);
        this.writer.commit();                       // để mở searcher trên index rỗng
        this.searchers = new SearcherManager(writer, new SearcherFactory() {
            @Override public IndexSearcher newSearcher(IndexReader reader, IndexReader previousReader) {
                IndexSearcher s = new IndexSearcher(reader);
                s.setSimilarity(similarity);
                return s;
            }
        });
    }

    /** Thêm/ghi đè một tài liệu. Khoá là url. */
    public void put(Map<String, String> d) throws IOException {
        String url = d.getOrDefault(F_URL, "");
        if (url.isBlank()) return;

        String title = d.getOrDefault(F_TITLE, "");
        String text = d.getOrDefault(F_TEXT, "");

        Document doc = new Document();
        doc.add(new StringField(F_URL, url, Field.Store.YES));
        // SortedDocValuesField riêng để SortField theo url dùng được (tab "Duyệt tất
        // cả", sort=url) — StringField chỉ lập chỉ mục ngược, không tự có DocValues.
        // Thiếu dòng này thì Lucene ném IllegalStateException "unexpected docvalues
        // type NONE" ngay khi có ai sort theo trường này.
        doc.add(new SortedDocValuesField(F_URL, new org.apache.lucene.util.BytesRef(url)));
        doc.add(new TextField(F_TITLE, title, Field.Store.YES));
        doc.add(new TextField(F_TEXT, text, Field.Store.YES));
        // Bản bỏ dấu: Store.NO vì chỉ dùng để khớp; hiển thị luôn lấy bản gốc.
        doc.add(new TextField(F_TITLE_FOLDED, title, Field.Store.NO));
        doc.add(new TextField(F_TEXT_FOLDED, text, Field.Store.NO));
        doc.add(new StringField(F_HOST, d.getOrDefault(F_HOST, ""), Field.Store.YES));
        doc.add(new SortedDocValuesField(F_HOST, new org.apache.lucene.util.BytesRef(
                d.getOrDefault(F_HOST, ""))));
        doc.add(new TextField(F_SECTION, d.getOrDefault(F_SECTION, ""), Field.Store.YES));
        String date = d.getOrDefault(F_DATE, "");
        doc.add(new StringField(F_DATE, date, Field.Store.YES));
        doc.add(new TextField(F_AUTHOR, d.getOrDefault(F_AUTHOR, ""), Field.Store.YES));
        String kind = d.getOrDefault(F_KIND, "");
        if (kind.isBlank()) kind = "page";           // tài liệu cũ không có kind coi là trang
        doc.add(new StringField(F_KIND, kind, Field.Store.YES));
        doc.add(new StringField(F_FTYPE, fileType(d.getOrDefault(F_FTYPE, ""), kind, url), Field.Store.YES));
        long dateValue = dateNum(date);
        doc.add(new LongPoint(F_DATE_NUM, dateValue));                  // để lọc khoảng
        doc.add(new NumericDocValuesField(F_DATE_NUM, dateValue));      // để sắp xếp
        doc.add(new StoredField(F_SIG, Long.toHexString(Sig.fingerprint(title, text))));
        String html = d.getOrDefault(F_HTML, "");
        if (!html.isEmpty()) doc.add(new StoredField(F_HTML, html));
        writer.updateDocument(new Term(F_URL, url), doc);
    }

    /** Loại tệp: lấy giá trị client gửi; không có thì trang là "html", tệp lấy đuôi đường dẫn url. */
    static String fileType(String given, String kind, String url) {
        if (given != null && !given.isBlank()) return given.trim().toLowerCase(Locale.ROOT);
        if (!"document".equals(kind)) return "html";
        String p = url.replaceFirst("[?#].*$", "");
        p = p.substring(p.lastIndexOf('/') + 1);
        int i = p.lastIndexOf('.');
        return i >= 0 && i < p.length() - 1 ? p.substring(i + 1).toLowerCase(Locale.ROOT) : "khac";
    }

    /** "2026-08-09" -> 20260809. Rỗng hoặc rác -> 0, nghĩa là không rõ ngày. */
    static long dateNum(String date) {
        if (date == null || date.length() < 10) return 0L;
        try {
            int n = Integer.parseInt(date.substring(0, 4));
            int t = Integer.parseInt(date.substring(5, 7));
            int g = Integer.parseInt(date.substring(8, 10));
            if (n < 1900 || n > 2200 || t < 1 || t > 12 || g < 1 || g > 31) return 0L;
            return n * 10000L + t * 100L + g;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    public void commit() throws IOException {
        writer.commit();
        searchers.maybeRefresh();
    }

    public int numDocs() {
        return writer.getDocStats().numDocs;
    }

    public long sizeBytes() throws IOException {
        long n = 0;
        for (String f : dir.listAll()) {
            try { n += dir.fileLength(f); } catch (IOException ignored) { }
        }
        return n;
    }

    /** Đếm tài liệu theo host, để bảng điều khiển biết mỗi site đã index bao nhiêu. */
    public Map<String, Integer> byHost() throws IOException {
        IndexSearcher s = searchers.acquire();
        try {
            Map<String, Integer> out = new TreeMap<>();
            for (LeafReaderContext leaf : s.getIndexReader().leaves()) {
                SortedDocValues dv = leaf.reader().getSortedDocValues(F_HOST);
                if (dv == null) continue;
                // Bỏ qua bản đã bị updateDocument xoá: chúng còn nằm trong segment
                // cho tới lần merge sau, không lọc thì tổng theo host vọt lên cao
                // hơn numDocs (đo được 2.551 so với 2.079).
                Bits live = leaf.reader().getLiveDocs();
                for (int i = 0; i < leaf.reader().maxDoc(); i++) {
                    if (live != null && !live.get(i)) continue;
                    if (dv.advanceExact(i)) {
                        String h = dv.lookupOrd(dv.ordValue()).utf8ToString();
                        out.merge(h, 1, Integer::sum);
                    }
                }
            }
            return out;
        } finally {
            searchers.release(s);
        }
    }

    public record Hit(String url, String title, String host, String section, String date,
                      float score, List<String> fragments, List<String> duplicates,
                      String author, String kind, String ftype) { }

    public record ListItem(String url, String title, String host, String section, String date,
                          String author, String kind) { }
    public record ListResult(long total, List<ListItem> items) { }

    public record TermInfo(String term, long docFreq, long totalTermFreq) { }
    public record DictPage(List<TermInfo> terms, String next) { }
    public record PostingRow(String url, String title, int tf, List<Integer> positions) { }
    public record Posting(String term, long docFreq, long totalTermFreq, List<PostingRow> rows) { }

    // -------------------------------------------------- xem chỉ mục ngược (tab "Chỉ mục ngược")
    /**
     * Duyệt từ điển (term dictionary) của một field — đúng cấu trúc lõi của chỉ
     * mục ngược: mỗi field giữ nguyên MỘT danh sách từ đã từng xuất hiện, sắp
     * theo thứ tự byte, mỗi từ kèm hai số:
     *   docFreq       = có bao nhiêu TÀI LIỆU chứa từ này (không tính số lần)
     *   totalTermFreq = tổng số LẦN từ này xuất hiện, cộng dồn trên mọi tài liệu
     *
     * {@link MultiTerms} cho view đã hợp nhất qua mọi segment của IndexWriter —
     * không phải tự cộng dồn docFreq của từng segment bằng tay.
     *
     * Phân trang bằng con trỏ (từ bắt đầu của trang sau), không bằng from/size:
     * từ điển có thể có hàng chục nghìn từ, seekCeil đi thẳng tới đúng vị trí
     * chứ không phải duyệt lại từ đầu mỗi lần xin trang mới.
     */
    public DictPage termDictionary(String field, String startTerm, int limit) throws IOException {
        IndexSearcher s = searchers.acquire();
        try {
            Terms terms = MultiTerms.getTerms(s.getIndexReader(), field);
            List<TermInfo> out = new ArrayList<>();
            String nextTerm = null;
            if (terms != null) {
                TermsEnum te = terms.iterator();
                org.apache.lucene.util.BytesRef cur = (startTerm == null || startTerm.isBlank())
                        ? te.next()
                        : (te.seekCeil(new org.apache.lucene.util.BytesRef(startTerm)) == TermsEnum.SeekStatus.END
                                ? null : te.term());
                while (cur != null && out.size() < limit) {
                    out.add(new TermInfo(cur.utf8ToString(), te.docFreq(), te.totalTermFreq()));
                    cur = te.next();
                }
                nextTerm = cur == null ? null : cur.utf8ToString();
            }
            return new DictPage(out, nextTerm);
        } finally {
            searchers.release(s);
        }
    }

    /**
     * Posting list đầy đủ của MỘT từ: docFreq/totalTermFreq đếm trên toàn
     * index, và danh sách tài liệu chứa từ đó kèm tần suất + vị trí token
     * trong từng tài liệu (giới hạn số dòng hiển thị ở {@code limit}, nhưng
     * hai số docFreq/totalTermFreq vẫn là số thật của toàn bộ posting list).
     *
     * Field không lưu vị trí (StringField như host/url/date, IndexOptions.DOCS)
     * thì trả positions rỗng và tf = 1 — kiểm bằng {@code Terms.hasPositions()}/
     * {@code hasFreqs()} trước khi gọi, gọi nhầm sẽ ném UnsupportedOperationException.
     */
    public Posting getPosting(String field, String raw, int limit) throws IOException {
        IndexSearcher s = searchers.acquire();
        try {
            String term = analyzeOneTerm(field, raw);
            Terms terms = MultiTerms.getTerms(s.getIndexReader(), field);
            if (terms == null) return new Posting(term, 0, 0, List.of());
            TermsEnum te = terms.iterator();
            if (!te.seekExact(new org.apache.lucene.util.BytesRef(term))) return new Posting(term, 0, 0, List.of());
            long docFreq = te.docFreq(), totalTermFreq = te.totalTermFreq();

            boolean hasFreqs = terms.hasFreqs();
            boolean hasPositions = terms.hasPositions();
            int flags = hasPositions ? PostingsEnum.ALL : (hasFreqs ? PostingsEnum.FREQS : PostingsEnum.NONE);
            PostingsEnum pe = te.postings(null, flags);

            StoredFields sf = s.storedFields();
            List<PostingRow> rows = new ArrayList<>();
            int doc;
            // MultiTerms trả docID đã quy về toàn cục, tra thẳng vào storedFields
            // của top-level reader được, không cần cộng docBase tay như khi tự
            // duyệt leaves() (xem byHost() ở trên, chỗ đó phải làm tay vì đi qua
            // LeafReaderContext trực tiếp).
            while (rows.size() < limit && (doc = pe.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                int freq = hasFreqs ? pe.freq() : 1;
                List<Integer> positions = new ArrayList<>(hasPositions ? freq : 0);
                if (hasPositions) for (int i = 0; i < freq; i++) positions.add(pe.nextPosition());
                Document d = sf.document(doc);
                rows.add(new PostingRow(d.get(F_URL), d.get(F_TITLE), freq, positions));
            }
            return new Posting(term, docFreq, totalTermFreq, rows);
        } finally {
            searchers.release(s);
        }
    }

    /**
     * Tách input người dùng gõ ra đúng MỘT token, qua đúng analyzer của field —
     * để tra từ điển đúng dạng đã lập chỉ mục (đã lowercase, đã/chưa bỏ dấu).
     * Field không phân tích (host/url/date, StringField) thì giữ nguyên chuỗi:
     * khoá của chúng là chính giá trị gốc, không qua Analyzer nào.
     */
    private String analyzeOneTerm(String field, String raw) throws IOException {
        if (field.equals(F_HOST) || field.equals(F_DATE) || field.equals(F_URL)) {
            return raw.trim();
        }
        boolean isFolded = field.equals(F_TITLE_FOLDED) || field.equals(F_TEXT_FOLDED);
        Analyzer an = isFolded ? foldAnalyzer : standardAnalyzer;
        String input = isFolded ? Fold.stripAccents(raw) : raw;
        try (TokenStream ts = an.tokenStream(field, input)) {
            CharTermAttribute t = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            String out = ts.incrementToken() ? t.toString() : raw.toLowerCase(Locale.ROOT);
            ts.end();
            return out;
        }
    }

    /**
     * Liệt kê toàn bộ tài liệu, không cần từ khoá — dùng cho tab "Duyệt tất cả",
     * để hình dung tổng thể kho đã index chứ không phải tìm theo truy vấn.
     *
     * Dùng {@link MatchAllDocsQuery} thay vì QueryParser, phân trang bằng
     * from/size như {@link #search(SearchParams)}. Cũng gộp bản trùng nội dung qua
     * {@link Sig} (xem {@link #mergeDuplicates}) để danh sách nhất quán với kết quả
     * tìm kiếm — không thì hai url savefile/gốc của cùng một bài sẽ hiện hai
     * dòng y hệt nhau trong bảng duyệt.
     */
    public ListResult listAll(int from, int size, String host, boolean byUrl) throws IOException {
        return listAll(from, size, host, byUrl, null);
    }

    public ListResult listAll(int from, int size, String host, boolean byUrl, String kind)
            throws IOException {
        IndexSearcher s = searchers.acquire();
        try {
            Query q = new MatchAllDocsQuery();
            if (hasText(host) || hasText(kind)) {
                BooleanQuery.Builder b = new BooleanQuery.Builder();
                b.add(q, BooleanClause.Occur.MUST);
                if (hasText(host)) b.add(new TermQuery(new Term(F_HOST, host)), BooleanClause.Occur.FILTER);
                if (hasText(kind)) b.add(new TermQuery(new Term(F_KIND, kind)), BooleanClause.Occur.FILTER);
                q = b.build();
            }
            // Tổng khớp filter, tính TRƯỚC khi gộp trùng — cũng là chặn trên như ở
            // search(), vì count() không biết trước bước gộp sẽ bớt đi bao nhiêu.
            long total = s.count(q);

            int needed = Math.max(from + size, 1);
            int window = Math.min(5000, Math.max(needed * 2, 200));

            Sort sort = byUrl
                    ? new Sort(new SortField(F_URL, SortField.Type.STRING, false))
                    : new Sort(new SortField(F_DATE_NUM, SortField.Type.LONG, true));
            TopDocs top = s.search(q, window, sort, false);

            StoredFields sf = s.storedFields();
            List<Object[]> ranked = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc sd : top.scoreDocs) {
                ranked.add(new Object[]{sf.document(sd.doc), 0.0, new ArrayList<String>()});
            }
            List<Object[]> merged = mergeDuplicates(ranked);

            List<ListItem> items = new ArrayList<>();
            for (int i = from; i < merged.size() && i < from + size; i++) {
                Document d = (Document) merged.get(i)[0];
                items.add(new ListItem(d.get(F_URL), d.get(F_TITLE), d.get(F_HOST),
                        d.get(F_SECTION), d.get(F_DATE), d.get(F_AUTHOR), d.get(F_KIND)));
            }
            return new ListResult(total, items);
        } finally {
            searchers.release(s);
        }
    }

    /** Tham số của một lượt tìm. Gom thành record cho khỏi truyền nhiều đối số rời. */
    public record SearchParams(String q, int from, int size, String host,
                       String dateFrom, String dateTo, boolean sortByDate,
                       String ranking, String kind, String ftype) {
        public SearchParams {
            ranking = ranking == null || ranking.isBlank()
                    ? "tfidf" : ranking.toLowerCase(Locale.ROOT);
            if (!ranking.equals("tfidf") && !ranking.equals("enhanced")) {
                throw new IllegalArgumentException("ranking phải là tfidf hoặc enhanced");
            }
        }

        public SearchParams(String q, int from, int size, String host, String dateFrom, String dateTo,
                    boolean sortByDate, String ranking, String kind) {
            this(q, from, size, host, dateFrom, dateTo, sortByDate, ranking, kind, null);
        }

        public SearchParams(String q, int from, int size, String host,
                    String dateFrom, String dateTo, boolean sortByDate, String ranking) {
            this(q, from, size, host, dateFrom, dateTo, sortByDate, ranking, null, null);
        }

        public SearchParams(String q, int from, int size, String host,
                    String dateFrom, String dateTo, boolean sortByDate) {
            this(q, from, size, host, dateFrom, dateTo, sortByDate, "tfidf", null, null);
        }

        public SearchParams(String q, int from, int size, String host) {
            this(q, from, size, host, null, null, false, "tfidf", null, null);
        }
    }

    public record Result(long total, long tookMs, List<Hit> hits) { }

    // ------------------------------------------------------------ dựng truy vấn
    /** Một nhánh MultiFieldQueryParser với bộ trọng số riêng. */
    private Query branch(String[] fieldNames, Map<String, Float> boost, Analyzer an,
                        String q, QueryParser.Operator op) throws Exception {
        MultiFieldQueryParser qp = new MultiFieldQueryParser(fieldNames, an, boost);
        qp.setDefaultOperator(op);
        return qp.parse(q);
    }

    /** Tách câu truy vấn thành token đúng theo analyzer, để dựng truy vấn cụm. */
    private List<String> tokenize(Analyzer an, String field, String q) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = an.tokenStream(field, q)) {
            CharTermAttribute t = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(t.toString());
            ts.end();
        }
        return out;
    }

    /**
     * Thưởng cho tài liệu có các âm tiết đứng liền nhau.
     *
     * StandardAnalyzer cắt tiếng Việt ra từng âm tiết, nên "kỹ thuật" khớp cả
     * bài chỉ tình cờ có "kỹ" ở đầu và "thuật" ở cuối. Thêm một mệnh đề SHOULD
     * đòi các âm tiết đứng cạnh nhau: bài viết đúng cụm được cộng thêm, bài
     * rải rác vẫn ra nhưng xếp dưới.
     */
    private void addPhrase(BooleanQuery.Builder b, String field, List<String> tokens,
                         int slop, float boost) {
        if (tokens.size() < 2) return;
        PhraseQuery.Builder p = new PhraseQuery.Builder();
        for (String t : tokens) p.add(new Term(field, t));
        p.setSlop(slop);
        b.add(new BoostQuery(p.build(), boost), BooleanClause.Occur.SHOULD);
    }

    /**
     * Thưởng cho từng cặp âm tiết liền nhau, không chỉ cả câu.
     *
     * "kỹ thuật máy tính" là ba từ trong đầu người dùng nhưng bốn token với
     * Lucene, và gần như không bài nào có đúng cả bốn token liền một mạch. Xét
     * từng cặp thì bài có "kỹ thuật" liền nhau được cộng một lần, bài có cả
     * "kỹ thuật" lẫn "máy tính" được cộng hai lần — đúng thứ tự người đọc mong
     * đợi, mà không phải nhét từ điển từ ghép tiếng Việt vào đâu cả.
     */
    private void addBigramPhrases(BooleanQuery.Builder b, String field, List<String> tokens,
                            float boost) {
        if (tokens.size() < 3) return;      // hai token thì themCum đã lo rồi
        for (int i = 0; i + 1 < tokens.size(); i++) {
            PhraseQuery.Builder p = new PhraseQuery.Builder();
            p.add(new Term(field, tokens.get(i)));
            p.add(new Term(field, tokens.get(i + 1)));
            b.add(new BoostQuery(p.build(), boost), BooleanClause.Occur.SHOULD);
        }
    }

    /**
     * Hạ yêu cầu "phải khớp mọi từ" xuống "khớp phần lớn từ".
     *
     * Dùng cho lượt vét: AND không ra gì thì thường chỉ vì một từ thừa trong
     * câu hỏi (gõ cả chức danh, cả năm). Đòi khớp quá nửa số token vẫn giữ được
     * độ chính xác mà không rơi về OR trần — OR trần khớp đúng một âm tiết phổ
     * biến là lôi về hàng nghìn bài chẳng liên quan.
     *
     * Ngưỡng đặt ở 1/2 chứ không phải 2/3 vì StandardAnalyzer cắt tiếng Việt
     * theo âm tiết: một từ hai âm tiết bị thừa đã chiếm 2/4 câu hỏi rồi.
     */
    private static Query relaxMinimumMatch(Query q, double ratio) {
        if (!(q instanceof BooleanQuery bq)) return q;
        List<BooleanClause> should = new ArrayList<>();
        for (BooleanClause c : bq.clauses()) {
            if (c.getOccur() != BooleanClause.Occur.SHOULD) return q;   // có MUST rồi, để yên
            should.add(c);
        }
        if (should.size() < 2) return q;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (BooleanClause c : should) b.add(c);
        b.setMinimumNumberShouldMatch(Math.max(1, (int) Math.ceil(ratio * should.size())));
        return b.build();
    }

    /** Ghép nhánh còn dấu + nhánh bỏ dấu + thưởng cụm thành một truy vấn. */
    private Query buildQuery(String q, QueryParser.Operator op, boolean relaxed) throws Exception {
        Query accentedQuery = branch(new String[]{F_TITLE, F_TEXT, F_SECTION}, standardBoost(), standardAnalyzer, q, op);
        Query foldedQuery = branch(new String[]{F_TITLE_FOLDED, F_TEXT_FOLDED}, foldedBoost(), foldAnalyzer,
                Fold.stripAccents(q), op);
        if (relaxed) {
            accentedQuery = relaxMinimumMatch(accentedQuery, 0.5);
            foldedQuery = relaxMinimumMatch(foldedQuery, 0.5);
        }

        // Hai nhánh cùng SHOULD: gõ đúng dấu thì cả hai cùng khớp nên điểm cộng
        // dồn và xếp trên; gõ không dấu thì chỉ nhánh bỏ dấu khớp, vẫn ra kết quả.
        BooleanQuery.Builder withFilters = new BooleanQuery.Builder();
        withFilters.add(accentedQuery, BooleanClause.Occur.SHOULD);
        withFilters.add(foldedQuery, BooleanClause.Occur.SHOULD);
        withFilters.setMinimumNumberShouldMatch(1);

        // Phần chọn tài liệu nằm trong MUST, phần thưởng cụm nằm ngoài ở SHOULD.
        // Để chung một tầng thì mệnh đề cụm tự nó cũng đủ khớp: tìm "kỹ thuật
        // máy tính" sẽ lôi về cả bài chỉ có "máy tính" mà không có "kỹ thuật",
        // số kết quả nở từ 379 lên 890. Cụm chỉ được xếp lại thứ tự, không được
        // mở rộng tập kết quả.
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(withFilters.build(), BooleanClause.Occur.MUST);

        List<String> tokens = tokenize(standardAnalyzer, F_TITLE, q);
        addPhrase(b, F_TITLE, tokens, 0, 4.0f);
        addPhrase(b, F_TEXT, tokens, 2, 2.0f);
        addBigramPhrases(b, F_TITLE, tokens, 2.0f);
        addBigramPhrases(b, F_TEXT, tokens, 0.8f);
        List<String> foldedTokens = tokenize(foldAnalyzer, F_TITLE_FOLDED, q);
        addPhrase(b, F_TITLE_FOLDED, foldedTokens, 0, 2.0f);
        addBigramPhrases(b, F_TITLE_FOLDED, foldedTokens, 1.0f);
        return b.build();
    }

    /** TF-IDF thuần: chỉ dùng bản bỏ dấu, title và text cùng trọng số. */
    private Query buildTfidfQuery(String q, QueryParser.Operator op, boolean relaxed) throws Exception {
        Query out = branch(new String[]{F_TITLE_FOLDED, F_TEXT_FOLDED},
                Map.of(F_TITLE_FOLDED, 1.0f, F_TEXT_FOLDED, 1.0f), foldAnalyzer,
                Fold.stripAccents(q), op);
        return relaxed ? relaxMinimumMatch(out, 0.5) : out;
    }

    private static Map<String, Float> standardBoost() {
        return Map.of(F_TITLE, 3.0f, F_SECTION, 1.5f, F_TEXT, 1.0f);
    }

    /** Nhánh bỏ dấu nhẹ tay hơn nhánh còn dấu: gõ đủ dấu vẫn phải thắng. */
    private static Map<String, Float> foldedBoost() {
        return Map.of(F_TITLE_FOLDED, 2.0f, F_TEXT_FOLDED, 0.7f);
    }

    /**
     * Bọc thêm các mệnh đề lọc. Dùng FILTER chứ không MUST: lọc chỉ có/không,
     * không được góp điểm — nếu không thì bài ở host được chọn tự dưng nhỉnh hơn
     * chỉ vì trùng một điều kiện lọc.
     */
    private Query withFilters(Query q, SearchParams t) {
        boolean hasDate = hasText(t.dateFrom()) || hasText(t.dateTo());
        if ((t.host() == null || t.host().isBlank()) && !hasDate && !hasText(t.kind()) && !hasText(t.ftype())) return q;

        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(q, BooleanClause.Occur.MUST);
        if (t.host() != null && !t.host().isBlank()) {
            b.add(new TermQuery(new Term(F_HOST, t.host())), BooleanClause.Occur.FILTER);
        }
        if (hasText(t.kind())) {
            b.add(new TermQuery(new Term(F_KIND, t.kind())), BooleanClause.Occur.FILTER);
        }
        if (hasText(t.ftype())) {
            // nhiều loại cách nhau dấu phẩy ("docx,doc"): khớp một trong số đó
            BooleanQuery.Builder types = new BooleanQuery.Builder();
            for (String x : t.ftype().toLowerCase(Locale.ROOT).split(",")) {
                if (!x.isBlank()) types.add(new TermQuery(new Term(F_FTYPE, x.trim())), BooleanClause.Occur.SHOULD);
            }
            b.add(types.build(), BooleanClause.Occur.FILTER);
        }
        if (hasDate) {
            // Cận dưới bắt đầu từ 1 chứ không phải 0: 0 là "không rõ ngày", lọc
            // theo khoảng thì phải loại chúng ra chứ không được coi là năm 0.
            long lo = hasText(t.dateFrom()) ? dateNum(t.dateFrom()) : 1L;
            long hi = hasText(t.dateTo()) ? dateNum(t.dateTo()) : 99999999L;
            if (lo == 0L) lo = 1L;
            if (hi == 0L) hi = 99999999L;
            b.add(LongPoint.newRangeQuery(F_DATE_NUM, lo, hi), BooleanClause.Occur.FILTER);
        }
        return b.build();
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    // ------------------------------------------------------------------ tìm kiếm
    /** Giữ chữ ký cũ cho gọn chỗ gọi và cho test. */
    public Result search(String q, int from, int size, String host) throws Exception {
        return search(new SearchParams(q, from, size, host));
    }

    /**
     * Tìm trên title + text, trả về đoạn trích đã bọc &lt;mark&gt; quanh từ khớp.
     * Tô sáng làm ở đây chứ không ở giao diện: Lucene biết chính xác token nào
     * khớp sau khi phân tích, còn phía trình duyệt chỉ so chuỗi thô nên sẽ trượt
     * mấy trường hợp như chữ hoa/thường hay dấu câu dính liền.
     *
     * Các bước, theo thứ tự:
     *   1. tfidf dùng AND trên title_kd/text_kd; enhanced dùng hai nhánh và
     *      thưởng cụm liền nhau.
     *   2. Không ra gì thì vét lại, chỉ đòi khớp quá nửa số âm tiết.
     *   3. enhanced mới nhân điểm Lucene với điểm nền {@link Rank}; tfidf giữ
     *      nguyên điểm ClassicSimilarity.
     *   4. Gộp các bản trùng nội dung ({@link Sig}) rồi mới cắt ra đúng trang
     *      người dùng xin. Gộp trước khi cắt là bắt buộc: gộp sau thì trang 1
     *      còn 7 dòng, trang 2 còn 9 dòng, số dòng nhảy loạn theo từng trang.
     */
    public Result search(SearchParams t) throws Exception {
        long t0 = System.currentTimeMillis();
        IndexSearcher s = searchers.acquire();
        try {
            int needed = Math.max(t.from() + t.size(), 1);
            boolean enhanced = "enhanced".equals(t.ranking());
            Query query = withFilters(enhanced
                    ? buildQuery(t.q(), QueryParser.Operator.AND, false)
                    : buildTfidfQuery(t.q(), QueryParser.Operator.AND, false), t);
            // Lấy dư gấp 5 để bước xếp lại và bước gộp có cái mà đảo; trần 1.000
            // cho khỏi phải nạp trường lưu của cả kho khi truy vấn quá phổ biến.
            int window = Math.min(1000, Math.max(needed * 5, 50));

            Sort sort = t.sortByDate()
                    ? new Sort(new SortField(F_DATE_NUM, SortField.Type.LONG, true))
                    : null;
            TopDocs top = sort == null ? s.search(query, window)
                                      : s.search(query, window, sort, true);

            if (top.totalHits.value == 0) {
                Query relaxedQuery = withFilters(enhanced
                        ? buildQuery(t.q(), QueryParser.Operator.OR, true)
                        : buildTfidfQuery(t.q(), QueryParser.Operator.OR, true), t);
                TopDocs retry = sort == null ? s.search(relaxedQuery, window)
                                          : s.search(relaxedQuery, window, sort, true);
                if (retry.totalHits.value > 0) { query = relaxedQuery; top = retry; }
            }

            // MỘT QueryScorer dùng chung cho cả highlighter lẫn fragmenter.
            // Tạo hai cái riêng thì cái của fragmenter không bao giờ được init,
            // và getBestFragments ném NullPointerException ngay lần gọi đầu.
            QueryScorer scorer = new QueryScorer(query);
            Highlighter hl = new Highlighter(new SimpleHTMLFormatter("<mark>", "</mark>"), scorer);
            hl.setTextFragmenter(new SimpleSpanFragmenter(scorer, 160));

            List<Object[]> ranked = rerank(s, top, t.sortByDate(), enhanced);
            List<Object[]> merged = mergeDuplicates(ranked);

            List<Hit> hits = new ArrayList<>();
            for (int i = t.from(); i < merged.size() && i < t.from() + t.size(); i++) {
                Document d = (Document) merged.get(i)[0];
                @SuppressWarnings("unchecked")
                List<String> duplicates = (List<String>) merged.get(i)[2];
                hits.add(new Hit(d.get(F_URL), d.get(F_TITLE), d.get(F_HOST),
                        d.get(F_SECTION), d.get(F_DATE),
                        ((Double) merged.get(i)[1]).floatValue(), snippets(hl, d), duplicates,
                        d.get(F_AUTHOR), d.get(F_KIND), d.get(F_FTYPE)));
            }
            // Tổng đã trừ đi số bản trùng nhìn thấy được trong cửa sổ. Ngoài cửa
            // sổ thì không biết, nên con số này là chặn trên chứ không phải đếm
            // chính xác — giao diện chỉ dùng nó để chia trang.
            long total = top.totalHits.value - (ranked.size() - merged.size());
            return new Result(Math.max(total, merged.size()), System.currentTimeMillis() - t0, hits);
        } finally {
            searchers.release(s);
        }
    }

    /** Nạp trường lưu; chỉ enhanced mới nhân điểm nền. Sắp theo ngày thì giữ
     *  nguyên thứ tự Lucene đã sắp, không đụng vào — người xin theo ngày là
     *  muốn đúng theo ngày. */
    private List<Object[]> rerank(IndexSearcher s, TopDocs top, boolean byDate,
                                  boolean enhanced) throws IOException {
        int year = Year.now().getValue();
        StoredFields sf = s.storedFields();
        List<Object[]> ranked = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc sd : top.scoreDocs) {
            Document d = sf.document(sd.doc);
            String text = d.get(F_TEXT);
            double score = Float.isNaN(sd.score) ? 0.0 : sd.score;
            if (!byDate && enhanced) {
                score *= Rank.baseScore(d.get(F_URL), text == null ? 0 : text.length(),
                        d.get(F_DATE), year);
            }
            ranked.add(new Object[]{d, score, new ArrayList<String>()});
        }
        if (!byDate) ranked.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
        return ranked;
    }

    /**
     * Gộp các bản cùng một bài: bản điểm cao nhất giữ chỗ, các url còn lại thu
     * vào danh sách phụ của nó.
     *
     * Quét tuyến tính, mỗi bản mới so với các bản đã giữ. Cửa sổ tối đa 1.000
     * nên xấu nhất là nửa triệu phép XOR 64 bit — nhanh hơn nhiều so với việc
     * nạp thêm một trường lưu, nên không cần đánh chỉ mục gì cho khéo.
     */
    private static List<Object[]> mergeDuplicates(List<Object[]> ranked) {
        List<Object[]> kept = new ArrayList<>();
        List<Long> fingerprint = new ArrayList<>();
        for (Object[] r : ranked) {
            Document d = (Document) r[0];
            long v = readSignature(d);
            int o = -1;
            for (int i = 0; i < fingerprint.size(); i++) {
                if (Sig.sameArticle(v, fingerprint.get(i))) { o = i; break; }
            }
            if (o < 0) {
                kept.add(r);
                fingerprint.add(v);
                continue;
            }
            Object[] current = kept.get(o);
            @SuppressWarnings("unchecked")
            List<String> duplicates = (List<String>) current[2];
            Document previous = (Document) current[0];
            if (isMoreCanonical(d.get(F_URL), previous.get(F_URL))) {
                // Bản đến sau có url gốc hơn thì đổi chỗ: giữ nguyên điểm và vị
                // trí của dòng, chỉ đổi bản đại diện. Không làm thế thì bản
                // /savefile/ hay chiếm chỗ vì nó dài hơn vài chữ ở phần vỏ
                // trang, mà điểm nền lại thưởng bài dài hơn.
                current[0] = d;
                duplicates.add(previous.get(F_URL));
            } else {
                duplicates.add(d.get(F_URL));
            }
        }
        return kept;
    }

    /**
     * Url nào đáng làm bản đại diện hơn: ít đoạn đường dẫn hơn thì thắng, hoà
     * thì ngắn hơn thắng. Quy tắc chung chứ không đi bắt riêng chữ "savefile" —
     * bản sao trên hust.edu.vn luôn nằm sâu thêm một đoạn so với bản gốc, và
     * cách này cũng đúng cho các kiểu url phái sinh khác như /amp/ hay /print/.
     */
    static boolean isMoreCanonical(String a, String b) {
        if (a == null) return false;
        if (b == null) return true;
        int da = countSegments(a), db = countSegments(b);
        if (da != db) return da < db;
        return a.length() < b.length();
    }

    private static int countSegments(String url) {
        int n = 0;
        for (int i = 0; i < url.length(); i++) if (url.charAt(i) == '/') n++;
        return n;
    }

    private static long readSignature(Document d) {
        String h = d.get(F_SIG);
        if (h == null || h.isEmpty()) return 0L;
        try {
            return Long.parseUnsignedLong(h, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Đoạn trích: thử bằng analyzer còn dấu trước, không ra thì thử analyzer bỏ
     * dấu trên chính đoạn văn bản gốc. Bộ lọc bỏ dấu chỉ đổi chữ trong token chứ
     * không đụng offset, nên Highlighter vẫn cắt đúng chỗ và trả về chữ có dấu —
     * người gõ "diem chuan" vẫn thấy "<mark>điểm</mark> <mark>chuẩn</mark>".
     */
    private List<String> snippets(Highlighter hl, Document d) throws Exception {
        List<String> frags = new ArrayList<>();
        for (String f : new String[]{F_TITLE, F_TEXT}) {
            String v = d.get(f);
            if (v == null || v.isBlank()) continue;
            String foldedField = f.equals(F_TITLE) ? F_TITLE_FOLDED : F_TEXT_FOLDED;
            // Chạy cả hai rồi lấy bản tô được nhiều chỗ hơn. Chỉ thử bản bỏ dấu
            // khi bản còn dấu trắng tay là chưa đủ: gõ "diem chuan 2026" thì
            // "2026" khớp ngay ở nhánh còn dấu, đoạn trích ra không rỗng nhưng
            // chỉ tô mỗi con số, hai chữ người ta thực sự tìm lại không được tô.
            frags.addAll(moreMarks(hl.getBestFragments(standardAnalyzer, f, v, 2),
                                     hl.getBestFragments(foldAnalyzer, foldedField, v, 2)));
            if (frags.size() >= 3) break;
        }
        if (frags.isEmpty()) {              // truy vấn khớp trường không lưu đoạn trích
            String v = d.get(F_TEXT);
            if (v != null) frags.add(v.substring(0, Math.min(220, v.length())) + "…");
        }
        return frags;
    }

    /** Bản nào bọc được nhiều thẻ mark hơn thì lấy bản đó. */
    private static List<String> moreMarks(String[] a, String[] b) {
        String[] chosen = countMarks(b) > countMarks(a) ? b : a;
        List<String> out = new ArrayList<>();
        for (String g : chosen) if (g != null && !g.isBlank()) out.add(g.replace("\n", " ").trim());
        return out;
    }

    private static int countMarks(String[] frags) {
        int n = 0;
        for (String g : frags) {
            if (g == null) continue;
            for (int i = g.indexOf("<mark>"); i >= 0; i = g.indexOf("<mark>", i + 6)) n++;
        }
        return n;
    }

    /**
     * Lấy nguyên một tài liệu theo url, cho trang xem trước.
     *
     * Đọc từ index chứ không mở lại kho JSONL: kho là 24 shard nén, tìm một url
     * trong đó phải quét tuần tự cả trăm MB, mất cỡ một phút — bằng đúng thời
     * gian index lại toàn bộ. Index thì tra theo Term("url") ra ngay.
     */
    public Map<String, String> getDocument(String url) throws IOException {
        IndexSearcher s = searchers.acquire();
        try {
            TopDocs top = s.search(new TermQuery(new Term(F_URL, url)), 1);
            if (top.scoreDocs.length == 0) return null;
            Document d = s.storedFields().document(top.scoreDocs[0].doc);
            Map<String, String> out = new LinkedHashMap<>();
            for (String f : new String[]{F_URL, F_TITLE, F_HOST, F_SECTION, F_DATE, F_AUTHOR, F_KIND, F_FTYPE, F_HTML}) {
                String v = d.get(f);
                out.put(f, v == null ? "" : v);
            }
            return out;
        } finally {
            searchers.release(s);
        }
    }

    /** Xoá sạch index, dùng khi muốn dựng lại từ đầu. */
    public void reset() throws IOException {
        writer.deleteAll();
        commit();
    }

    public Path path() { return path; }

    @Override public void close() throws IOException {
        searchers.close();
        writer.close();
        dir.close();
    }
}
