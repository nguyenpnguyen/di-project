package vn.hust.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
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
 */
public class Index implements AutoCloseable {

    public static final String F_URL = "url";
    public static final String F_TITLE = "title";
    public static final String F_TEXT = "text";
    public static final String F_HOST = "host";
    public static final String F_SECTION = "section";
    public static final String F_DATE = "date";

    private final Analyzer analyzer = new StandardAnalyzer();
    private final Directory dir;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final Path path;

    public Index(Path path) throws IOException {
        this.path = path;
        this.dir = FSDirectory.open(path);
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(dir, cfg);
        this.writer.commit();                       // để mở searcher trên index rỗng
        this.searchers = new SearcherManager(writer, new SearcherFactory());
    }

    /** Thêm/ghi đè một tài liệu. Khoá là url. */
    public void put(Map<String, String> d) throws IOException {
        String url = d.getOrDefault(F_URL, "");
        if (url.isBlank()) return;

        Document doc = new Document();
        doc.add(new StringField(F_URL, url, Field.Store.YES));
        doc.add(new TextField(F_TITLE, d.getOrDefault(F_TITLE, ""), Field.Store.YES));
        doc.add(new TextField(F_TEXT, d.getOrDefault(F_TEXT, ""), Field.Store.YES));
        doc.add(new StringField(F_HOST, d.getOrDefault(F_HOST, ""), Field.Store.YES));
        doc.add(new SortedDocValuesField(F_HOST, new org.apache.lucene.util.BytesRef(
                d.getOrDefault(F_HOST, ""))));
        doc.add(new TextField(F_SECTION, d.getOrDefault(F_SECTION, ""), Field.Store.YES));
        doc.add(new StringField(F_DATE, d.getOrDefault(F_DATE, ""), Field.Store.YES));
        writer.updateDocument(new Term(F_URL, url), doc);
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
                for (int i = 0; i < leaf.reader().maxDoc(); i++) {
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
                      float score, List<String> fragments) { }

    public record Result(long total, long tookMs, List<Hit> hits) { }

    /**
     * Tìm trên title + text, trả về đoạn trích đã bọc &lt;mark&gt; quanh từ khớp.
     * Tô sáng làm ở đây chứ không ở giao diện: Lucene biết chính xác token nào
     * khớp sau khi phân tích, còn phía trình duyệt chỉ so chuỗi thô nên sẽ trượt
     * mấy trường hợp như chữ hoa/thường hay dấu câu dính liền.
     */
    public Result search(String q, int from, int size, String host) throws Exception {
        long t0 = System.currentTimeMillis();
        IndexSearcher s = searchers.acquire();
        try {
            QueryParser qp = new MultiFieldQueryParser(
                    new String[]{F_TITLE, F_TEXT, F_SECTION}, analyzer,
                    Map.of(F_TITLE, 3.0f, F_SECTION, 1.5f, F_TEXT, 1.0f));
            qp.setDefaultOperator(QueryParser.Operator.AND);
            Query text = qp.parse(q);

            Query query = text;
            if (host != null && !host.isBlank()) {
                query = new BooleanQuery.Builder()
                        .add(text, BooleanClause.Occur.MUST)
                        .add(new TermQuery(new Term(F_HOST, host)), BooleanClause.Occur.FILTER)
                        .build();
            }

            TopDocs top = s.search(query, Math.max(from + size, 1));
            // MỘT QueryScorer dùng chung cho cả highlighter lẫn fragmenter.
            // Tạo hai cái riêng thì cái của fragmenter không bao giờ được init,
            // và getBestFragments ném NullPointerException ngay lần gọi đầu.
            QueryScorer scorer = new QueryScorer(query);
            Highlighter hl = new Highlighter(new SimpleHTMLFormatter("<mark>", "</mark>"), scorer);
            hl.setTextFragmenter(new SimpleSpanFragmenter(scorer, 160));

            List<Hit> hits = new ArrayList<>();
            StoredFields sf = s.storedFields();
            for (int i = from; i < top.scoreDocs.length && i < from + size; i++) {
                ScoreDoc sd = top.scoreDocs[i];
                Document d = sf.document(sd.doc);
                List<String> frags = new ArrayList<>();
                for (String f : new String[]{F_TITLE, F_TEXT}) {
                    String v = d.get(f);
                    if (v == null || v.isBlank()) continue;
                    String[] got = hl.getBestFragments(analyzer, f, v, 2);
                    for (String g : got) if (!g.isBlank()) frags.add(g.replace("\n", " ").trim());
                    if (frags.size() >= 3) break;
                }
                if (frags.isEmpty()) {          // truy vấn khớp trường không lưu đoạn trích
                    String v = d.get(F_TEXT);
                    if (v != null) frags.add(v.substring(0, Math.min(220, v.length())) + "…");
                }
                hits.add(new Hit(d.get(F_URL), d.get(F_TITLE), d.get(F_HOST),
                        d.get(F_SECTION), d.get(F_DATE), sd.score, frags));
            }
            return new Result(top.totalHits.value, System.currentTimeMillis() - t0, hits);
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
