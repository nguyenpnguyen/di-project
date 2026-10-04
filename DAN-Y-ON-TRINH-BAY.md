# Dàn ý trình bày: Thu thập dữ liệu và tìm kiếm với Lucene

> **Mục tiêu:** trình diễn đúng hai yêu cầu của đề bài trong 5–7 phút.
>
> **Luồng cần nhớ:** `Link bài viết → dữ liệu bóc tách → JSON → Lucene Index → truy vấn → kết quả TF-IDF`

---

## 1. Đề bài và phần nhóm thực hiện

### 1.1. Thu thập dữ liệu

- **Đầu vào:** một link bài viết.
- **Đầu ra:**
  - tiêu đề;
  - nội dung văn bản;
  - các liên kết đi ra, gồm URL đầy đủ và văn bản mô tả.

### 1.2. Tìm kiếm với Lucene

- **Tạo chỉ mục:** tập văn bản JSON → Lucene Index.
- **Truy vấn:** câu truy vấn → danh sách kết quả xếp hạng theo TF-IDF.

### Câu mở đầu

> “Nhóm em trình diễn hai phần: bóc tách dữ liệu từ một link bài viết và dùng Apache Lucene để tạo chỉ mục, xử lý truy vấn, rồi trả kết quả theo TF-IDF.”

---

## 2. Luồng hệ thống

```text
Link bài viết
      │
      ▼
Python tải HTML và bóc tách
      │
      ▼
{title, content, outgoing_links}
      │
      ▼
Document JSON
      │
      ▼
Java + Apache Lucene tạo chỉ mục
      │
      ▼
Query → TF-IDF → danh sách kết quả
```

Phân công công nghệ:

- **Python:** tải trang, bóc HTML và cung cấp API.
- **Java 21 + Lucene 9.11:** tạo chỉ mục, phân tích truy vấn và xếp hạng.
- **FastAPI:** nối phần thu thập với dịch vụ Lucene.
- **Docker Compose:** chạy API và Lucene thành hai service.

> Chỉ cần trình bày sơ đồ trên. Kho HTML thô, cơ chế resume, rate-limit, SimHash và Docker volume là nội dung dự phòng khi được hỏi.

---

## 3. Demo 1 — Thu thập dữ liệu từ một link

### Thao tác

1. Mở giao diện `http://localhost:8000`.
2. Chọn tab **Tải một trang**.
3. Dán URL một bài viết HUST.
4. Bấm **Tải và index**.
5. Chỉ vào ba đầu ra đúng đề bài:
   - `title`;
   - `content`;
   - `outgoing_links`.

Ví dụ kết quả:

```json
{
  "url": "https://hust.edu.vn/vi/news/example.html",
  "title": "Tiêu đề bài viết",
  "content": "Nội dung văn bản đã bóc tách...",
  "outgoing_links": [
    {
      "url": "https://hust.edu.vn/admissions/",
      "text": "Thông tin tuyển sinh"
    }
  ]
}
```

### Cách hệ thống bóc tách

`POST /api/fetch` thực hiện:

1. tải HTML bằng `httpx`;
2. tạo DOM bằng `BeautifulSoup` và `lxml`;
3. lấy tiêu đề từ `headline`, `og:title` hoặc thẻ `<title>`;
4. lấy nội dung từ `.bodytext`, `<main>` hoặc `<body>`;
5. bỏ `script`, `style`, `nav`, `footer`;
6. lấy các thẻ `a[href]`, dùng `urljoin` để đổi thành URL đầy đủ;
7. dùng text của thẻ `<a>` làm mô tả và khử link trùng.

### Câu nói khi demo

> “Đầu vào là một URL. API tải HTML, bóc tiêu đề và phần thân bài, sau đó duyệt các thẻ `a[href]`. Mỗi link được chuẩn hóa thành URL đầy đủ và giữ lại văn bản mô tả. Kết quả trả về là một document JSON và được index ngay.”

---

## 4. Demo 2 — JSON → Lucene Index → kết quả TF-IDF

### 4.1. Nạp tập văn bản JSON

Dữ liệu đầu vào có dạng:

```json
{
  "documents": [
    {
      "url": "https://example.test/doc-1",
      "title": "Thông báo điểm chuẩn",
      "content": "Thông tin điểm chuẩn tuyển sinh...",
      "published_at": "2026-09-11"
    }
  ]
}
```

Nạp corpus mẫu:

```bash
cd hust-search
curl -s -X POST http://localhost:8000/api/index/documents \
  -H 'content-type: application/json' \
  --data @tests/fixtures/corpus.json
```

Luồng xử lý:

```text
Corpus JSON
   │  FastAPI kiểm tra schema
   ▼
POST /bulk
   │
   ▼
Lucene IndexWriter.updateDocument()
   │
   ▼
Inverted index trên đĩa
```

Lucene lập **chỉ mục đảo**:

```text
term → danh sách document chứa term
```

Ví dụ:

```text
"diem"  → doc1, doc3, doc8
"chuan" → doc1, doc8
```

Nhờ đó Lucene tra postings list thay vì quét lại toàn bộ JSON khi có truy vấn.

### 4.2. Truy vấn và xếp hạng TF-IDF

Trên giao diện:

1. mở tab **Tìm kiếm**;
2. chọn chế độ **TF-IDF**;
3. nhập truy vấn;
4. sắp xếp theo **điểm liên quan**;
5. chỉ ra `score` giảm dần và đoạn trích được tô sáng.

Hoặc gọi API:

```bash
curl -s --get http://localhost:8000/api/search \
  --data-urlencode 'q=diem chuan' \
  --data-urlencode 'ranking=tfidf' \
  --data-urlencode 'sort=score'
```

Luồng truy vấn:

```text
"diem chuan"
      │ bỏ dấu + lowercase + tách token
      ▼
[diem, chuan]
      │ tra title_kd và text_kd
      ▼
ClassicSimilarity tính TF-IDF
      │
      ▼
Kết quả xếp theo score giảm dần
```

Ý nghĩa TF-IDF:

- **TF:** từ xuất hiện nhiều trong một tài liệu thì quan trọng hơn với tài liệu đó.
- **IDF:** từ xuất hiện trong ít tài liệu thì có khả năng phân biệt cao hơn.
- Lucene dùng `ClassicSimilarity`; có thể diễn đạt ngắn gọn:

```text
score ≈ tổng(TF × IDF² × chuẩn hóa độ dài)
```

### Tìm kiếm tiếng Việt không dấu

Hệ thống lưu thêm hai trường `title_kd` và `text_kd`. `Fold.java` tách dấu Unicode, bỏ dấu và đổi `đ → d`.

```text
"Điểm chuẩn" → "diem chuan"
```

Vì vậy truy vấn `diem chuan` vẫn tìm được tài liệu chứa “Điểm chuẩn”. Kết quả hiển thị nội dung có dấu ban đầu và Lucene bọc từ khớp bằng `<mark>`.

### Câu nói khi demo

> “Corpus JSON được API kiểm tra rồi gửi sang Lucene. Lucene phân tích title và content để tạo chỉ mục đảo. Khi nhận truy vấn, hệ thống bỏ dấu, tách token, tra các postings list và dùng `ClassicSimilarity` tính TF-IDF. Kết quả có score cao hơn đứng trước.”

---

## 5. Kịch bản trình bày 5–7 phút

### 0:00–0:30 — Nêu đề bài

- URL → tiêu đề, nội dung, link đi ra.
- JSON → index; query → kết quả TF-IDF.

### 0:30–1:00 — Giới thiệu kiến trúc

Chỉ vào một sơ đồ:

```text
URL → parse → JSON → Lucene Index → query → TF-IDF results
```

### 1:00–2:30 — Demo thu thập dữ liệu

- Dán một link bài viết.
- Chỉ ra `title`, `content`, `outgoing_links.url`, `outgoing_links.text`.
- Nói ngắn cách `BeautifulSoup` bóc HTML và `urljoin` chuẩn hóa link.

### 2:30–3:30 — Demo tạo chỉ mục

- Nạp `tests/fixtures/corpus.json`.
- Giải thích JSON được gửi theo batch sang Lucene.
- Nêu `updateDocument()` ghi đè theo URL, tránh trùng khi index lại.

### 3:30–5:00 — Demo truy vấn TF-IDF

- Chọn `ranking=tfidf`, `sort=score`.
- Tìm một từ có tần suất khác nhau giữa các document để chỉ ra ảnh hưởng của TF.
- Tìm `diem chuan` để chứng minh tìm tiếng Việt không dấu.
- Chỉ ra `score` và đoạn trích có `<mark>`.

### 5:00–5:30 — Kết luận

> “Hệ thống đáp ứng hai đầu bài: bóc tách dữ liệu có cấu trúc từ link bài viết và tạo chỉ mục Lucene để trả kết quả theo TF-IDF. Crawler, parser và search engine được tách rời nên có thể index lại dữ liệu mà không cần tải lại website.”

Thời gian còn lại dùng để trả lời câu hỏi, không chủ động trình bày các phần mở rộng.

---

## 6. Phiếu trả lời nhanh

### Vì sao dùng Lucene?

Lucene có sẵn inverted index, analyzer, query parser, TF-IDF và highlighter; không cần quét toàn bộ tập JSON cho mỗi truy vấn.

### Chỉ mục có phải file JSON không?

Không. JSON là đầu vào. Lucene chuyển document thành các segment nhị phân chứa terms dictionary, postings và stored fields.

### Index nằm ở RAM hay đĩa?

Index nằm trên đĩa tại Docker volume gắn vào `/index`. RAM chỉ làm buffer và cache.

### Vì sao dùng `title_kd` và `text_kd`?

Để truy vấn không dấu khớp nội dung tiếng Việt có dấu, trong khi vẫn giữ bản gốc để hiển thị.

### TF và IDF khác nhau thế nào?

TF đo mức lặp của từ trong một document; IDF đo độ hiếm của từ trong toàn corpus.

### `sort=date` có còn là thứ tự TF-IDF không?

Không. Muốn chứng minh xếp hạng TF-IDF phải dùng `ranking=tfidf` và `sort=score`.

### Link đi ra có được dùng để tìm kiếm không?

Chưa. Link đi ra được bóc tách và hiển thị đúng yêu cầu thu thập dữ liệu; trường tìm kiếm chính là title và content.

### Phần nào là mở rộng ngoài đề bài?

Crawl toàn site, resume, rate-limit tự dò, gộp bài trùng bằng SimHash, lọc ngày/host và chế độ `enhanced`. Chỉ nói khi giáo viên hỏi.

### Giới hạn hiện tại?

- `StandardAnalyzer` tách tiếng Việt theo âm tiết, chưa có bộ tách từ ghép.
- Chưa bóc nội dung PDF/DOC.
- Các subdomain chưa được crawl sâu toàn bộ.
- API chưa có xác thực, chỉ phù hợp demo nội bộ.

---

## 7. Checklist trước khi trình bày

```bash
cd hust-search
docker compose up -d --build
curl -s http://localhost:8000/api/health
```

- [ ] Chọn sẵn một URL HUST tải được và có link đi ra.
- [ ] Nạp thử `tests/fixtures/corpus.json`.
- [ ] Kiểm tra truy vấn TF-IDF trả nhiều kết quả có score khác nhau.
- [ ] Kiểm tra `diem chuan` tìm được nội dung có dấu.
- [ ] Luôn chọn `ranking=tfidf` và `sort=score` khi demo đề bài.
- [ ] Không phụ thuộc hoàn toàn vào mạng thật; corpus mẫu là phương án ổn định.
