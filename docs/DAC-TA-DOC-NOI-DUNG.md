# Đặc tả mô-đun "Đọc nội dung" (N1) — bản nháp

Nguồn: slide nhóm *"Thiết kế hệ thống thu thập dựa trên kiến trúc dịch vụ nhỏ"* (Google Slides,
đọc ngày 22/10/2026). Tài liệu chia ba loại, đừng trộn:

- **[Slide]**: slide ghi đúng như vậy.
- **[Suy ra]**: slide không ghi chữ, tôi suy từ hình vẽ hoặc từ các slide khác. Có thể sai.
- **[Đề xuất]**: slide chưa nói. Đây là gợi ý, cần chốt với N5 (Tải về) và N8 (Tổng hợp) trước khi code.

## 1. Slide nói gì

**Phân công [Slide]:** Đọc nội dung là N1, Tải về là N5, Điều khiển là BAT, Tổng hợp là N8,
Lưu trữ là N10, Mongo là "Ctrl + F". Các mô-đun nói chuyện qua HTTP, dữ liệu là JSON.

**Luồng [Slide]:**

1. Khởi động: mọi mô-đun ở trạng thái chờ. Tổng hợp nạp tập url mầm.
2. Điều khiển gửi url cần thu thập đến Tải về.
3. Tải về gửi dữ liệu tải được sang **Đọc nội dung**.
4. **Đọc nội dung** gửi kết quả bóc tách sang Tổng hợp.
5. Tổng hợp gửi bản ghi sang Lưu trữ.
6. Tổng hợp gửi url mới sang Điều khiển.

```
Điều khiển ──2──▶ Tải về ×2 ──3──▶ ĐỌC NỘI DUNG ──4──▶ Tổng hợp ──5──▶ Lưu trữ ──▶ Mongo
     ▲                                                        │
     └──────────────────────────6─────────────────────────────┘
```

Hình vẽ có **hai** hộp Tải về, cả hai cùng nối vào Đọc nội dung.

**Đầu vào / đầu ra của Đọc nội dung [Slide]:**

- Đầu vào: định dạng tài liệu (`html`, `other`), nội dung (tài liệu), uri (địa chỉ truy cập).
- Đầu ra với html: tiêu đề, nội dung văn bản, liên kết đi ra kèm văn bản liên kết.
  Văn bản liên kết là "câu hoàn thiện chứa thẻ a".
- Đầu ra với other: nội dung văn bản tách được.
- Gọi API để đẩy kết quả sang Tổng hợp.

**API [Slide]:**

```
POST http://ip:cổng/parse
http content - dữ liệu được tải về
```

Slide chỉ ghi đúng hai dòng này, không có ví dụ body, không có phản hồi.

**API của Tổng hợp mà N1 phải gọi [Slide]**, bản gốc giữ nguyên (thiếu dấu phẩy sau `"html"`):

```
POST http://ip:cổng/add
{"url": "https://hust.edu.vn/tin-tuc1",
 "format": "html"
 "title": "Tin tức 1",
 "content": "Nội dung tin tức bóc tách được",
 "out-links": ["url đi ra", "văn bản liên kết"]
}
```

**Mô-đun lân cận [Slide]:**

- Tải về: tải trang web bằng Puppeteer + Chrome headless, tải tài liệu thì tải thẳng. Đầu ra "gửi
  đến mô-đun đọc". API `POST /load {"url"}` và `GET /ping → {"status": "đang chờ"}`.
- Tổng hợp: nhận uri, định dạng, các trường nội dung. Lọc url trong miền hust, loại url đã có.
- Mongo: có ba nhóm `html` (url, format, title, content, **intro**), `other` (url, format kiểu
  `docx`, content) và `graph` (`src`, `dest`, `intro` = văn bản liên kết).

## 2. Cổng [Suy ra]

Slide 1 có năm số cổng nhưng không ghi chữ nào cho biết số nào của mô-đun nào. Tôi ghép theo toạ độ:
mỗi số nằm ngay trên một hộp.

| Mô-đun | Cổng | Mức chắc |
|---|---|---|
| Điều khiển | 5200 | chắc: slide Điều khiển ghi `http://ip:5200/add` |
| Tải về | 8800 | số nằm giữa hai hộp Tải về |
| **Đọc nội dung** | **6600** | số nằm ngay trên hộp Đọc nội dung |
| Tổng hợp | 3300 | số nằm ngay trên hộp |
| Lưu trữ | 2200 | số nằm ngay trên hộp |

Tôi không xem được ảnh slide, chỉ đọc toạ độ qua API. Nhờ người vẽ slide xác nhận lại.

## 3. Chỗ slide chưa rõ hoặc lệch nhau

Đây là danh sách cần hỏi. Mỗi mục ghi ai phải trả lời.

| # | Vấn đề | Vì sao quan trọng | Hỏi ai |
|---|---|---|---|
| 1 | Body của `/parse` là gì? "http content" có thể là JSON `{url, format, content}`, cũng có thể là body HTTP thô kèm header. | Slide 1 nói mọi mô-đun dùng JSON, nhưng slide `/parse` không có JSON. Không có url thì không giải được link tương đối. | N5 |
| 2 | Tài liệu nhị phân (pdf, docx) đi qua JSON thế nào? | JSON không chở byte thô. Phải base64, hoặc gửi multipart, hoặc chỉ gửi url để N1 tự tải. | N5 |
| 3 | `/parse` trả gì, đồng bộ hay bất đồng bộ? | N1 phải đẩy sang N8. Nếu `/parse` chờ đẩy xong mới trả, N5 bị giữ lâu với pdf lớn. Nếu trả ngay (202), lỗi phía N8 thì N5 không biết. | N5 |
| 4 | `out-links` có dạng gì? Ví dụ trên slide là **một** cặp phẳng `["url", "văn bản"]`, không chứa được nhiều link. | N8 phải đọc được. Có hai cách: mảng cặp `[["url","văn bản"], …]` hoặc mảng đối tượng. | N8 |
| 5 | "Câu hoàn thiện chứa thẻ a" là cả câu bao quanh, hay chỉ chữ trong thẻ `<a>`? Link menu không nằm trong câu nào thì sao? | Đổi định nghĩa là đổi trường `intro` của `graph` trong Mongo. | N8, N10 |
| 6 | `out-links` lấy từ cả trang hay chỉ từ phần thân bài? | N8 lấy url mới từ `out-links` để gửi Điều khiển (bước 6). Nếu chỉ lấy trong thân bài, link menu và link phân trang mất, nên crawl không lan ra được. | N8 |
| 7 | `format` mang giá trị gì? Slide Đọc nội dung ghi `html`/`other`, slide Mongo ghi `docx`. | N8 và N10 chia bản ghi vào nhóm `html`/`other` theo trường này. | N8, N10 |
| 8 | Mongo có `intro` (đoạn giới thiệu), nhưng đầu ra của N1 không có. Ai bóc `intro`? | Thiếu thì trường này luôn rỗng. | N8, N10 |
| 9 | `content` là toàn bộ chữ trên trang, hay chỉ thân bài (bỏ menu, footer)? | Lấy toàn trang thì trang nào cũng có chung một đoạn menu dài, tìm kiếm sau này bị nhiễu. | cả nhóm |
| 10 | Url nào là `url`: url được yêu cầu, hay url cuối sau chuyển hướng? Puppeteer có thể đi qua redirect. | N8 khử trùng theo url. | N5, N8 |
| 11 | N1 trả lời thế nào khi lỗi: trang rỗng, pdf hỏng, pdf chỉ có ảnh (cần OCR), định dạng lạ? Có báo cho N8 không? | Không chốt thì N8 nhận bản ghi rỗng, hoặc url lỗi bị tải lại mãi. | N5, N8 |
| 12 | N1 có cần `GET /ping` như N5 không? | Điều khiển dùng để biết N1 còn sống. Slide chỉ cho N5 có `/ping`. | BAT |
| 13 | Url ra phải ở dạng tuyệt đối không, ai chuẩn hoá (bỏ `#…`, `http`↔`https`)? | N8 "loại url đã có". Hai bên chuẩn hoá khác nhau thì trùng lặp hoặc báo thiếu giả. Repo này từng dính lỗi "thiếu 17 link" vì một bên giữ `http://`, bên kia đổi sang `https://`. | N8 |

## 4. Đặc tả đề xuất [Đề xuất]

Mọi thứ dưới đây là gợi ý để mang đi chốt, không phải điều slide đã quyết.

### 4.1 `POST /parse` (cổng 6600)

Phương án A: JSON. Hợp với câu "dùng JSON" ở slide 1.

```json
{
  "url": "https://hust.edu.vn/vi/news/tin-tuc-su-kien/abc-654601.html",
  "final_url": "https://hust.edu.vn/vi/news/tin-tuc-su-kien/abc-654601.html",
  "format": "html",
  "content_type": "text/html; charset=utf-8",
  "content": "<!DOCTYPE html>…",
  "encoding": "text"
}
```

- `format`: `html` hoặc `other`. Có thể thêm `ext` (`pdf`, `docx`…) để N1 khỏi phải đoán.
- `encoding`: `text` cho HTML (Puppeteer trả DOM đã dựng, là chuỗi), `base64` cho tài liệu.
- `final_url` không bắt buộc. Thiếu thì lấy `url`.

Phương án B: body thô. `Content-Type` là kiểu của tài liệu, url đi trong header `X-Url`. Không phải
base64 nên nhẹ hơn với tệp lớn, nhưng lệch với quy ước "dùng JSON".

Phản hồi, gợi ý **đồng bộ**: N1 bóc tách, gọi `/add` của N8, rồi mới trả lời. N5 biết ngay thành
hay hỏng, và không cần thêm hàng đợi.

```json
200 {"status": "ok", "url": "…", "n_out_links": 57, "aggregator": "ok"}
422 {"status": "error", "url": "…", "error": "pdf không có lớp chữ (cần OCR)"}
502 {"status": "error", "url": "…", "error": "Tổng hợp không nhận: …"}
```

Đổi lại, N5 bị giữ trong lúc N1 xử lý. Tika đọc một pdf lớn có thể mất vài giây. Hiện có hai mô-đun
Tải về cùng gửi vào, nên N1 phải nhận nhiều request song song.

### 4.2 Gọi `POST /add` của Tổng hợp (cổng 3300)

Giữ đúng khoá trên slide, chỉ sửa `out-links` thành **mảng** cặp:

```json
{
  "url": "https://hust.edu.vn/tin-tuc1",
  "format": "html",
  "title": "Tin tức 1",
  "content": "Nội dung tin tức bóc tách được",
  "intro": "Sapo nếu có",
  "out-links": [
    ["https://hust.edu.vn/tin-tuc2", "Xem thêm thông báo tuyển sinh năm 2026 tại đây."],
    ["https://hust.edu.vn/files/quyet-dinh.pdf", "Quyết định số 123 (tải về)."]
  ]
}
```

Bản ghi `other`: `{"url", "format": "other", "ext": "pdf", "content"}`. Bỏ `title` và `out-links`,
đúng như slide.

### 4.3 Quy tắc bóc cho html

| Trường | Quy tắc gợi ý |
|---|---|
| `title` | Thứ tự ưu tiên: `itemprop=headline` → `og:title` → JSON-LD `headline` → `h1` gần khối nội dung → `<title>` bỏ hậu tố tên site (" - ĐH Bách khoa"). |
| `content` | Chỉ chữ của khối nội dung chính, bỏ menu/header/footer/sidebar. Giữ xuống dòng giữa các đoạn. |
| `intro` | `meta description` / `og:description`, hoặc đoạn đậm đầu tiên của khối. Rỗng nếu không có. |
| `out-links` | **Mọi** `<a href>` trong trang, tính cả menu (lý do ở mục 3, dòng 6). Đổi sang url tuyệt đối theo `final_url`, bỏ `#…`. Bỏ `mailto:`, `tel:`, `javascript:`. Khử trùng theo url. **Không** lọc miền: việc đó của N8. |
| văn bản liên kết | Đi lên phần tử khối gần nhất chứa thẻ a (`p`, `li`, `td`, `h1`–`h6`, `figcaption`). Cắt ra câu chứa thẻ a, ranh giới câu là `.`, `!`, `?`, `…`, `;` hoặc xuống dòng. Link đứng một mình (menu) thì lấy chữ của thẻ a, rồi `title`/`aria-label`, rồi `alt` của ảnh bên trong. |

Thẻ `<img src>` **không** vào `out-links`. Repo này từng gộp `src` vào danh sách link, và ảnh nhúng
`/uploads/` làm danh sách phình từ 14k lên 24k dòng mà không thêm trang nào mới.

### 4.4 Quy tắc cho other

Lấy chữ bằng Apache Tika: pdf, doc/docx, xls/xlsx, ppt/pptx. Pdf có quá ít chữ so với số trang thì
coi là bản quét và trả lỗi "cần OCR", không gửi chuỗi rỗng sang N8. Kiểu không đọc được thì trả
`unsupported`.

### 4.5 `GET /ping`

Làm giống N5: `{"status": "đang chờ"}` hoặc `{"status": "đang xử lý", "busy": 2}`.

## 5. Dùng lại được gì trong repo

Phần bóc tách trong `hust-search` đã làm gần hết các việc trên. Nó đã chạy trên kho thật, nhưng
đang nằm trong một tiến trình chung và ghi thẳng vào Mongo.

| Việc | Code sẵn có | Khác với đặc tả |
|---|---|---|
| Khối nội dung, khử khuôn | `extract/ContentBlock.java`, `Template.java` | Khớp cột `content` (mục 4.3) |
| Tiêu đề | `Fields.title()` | Thứ tự ưu tiên giống mục 4.3 |
| Link | `Links.collect()` thu mọi `<a>` **trước** khi dọn cây | `toPublicLinks()` chỉ lấy cạnh **trong khối nội dung**. Cần thêm `navLinks` thì mới khớp mục 3, dòng 6. |
| Văn bản liên kết | `Links.describeAnchor()` | Mới chỉ lấy chữ trong thẻ a, **chưa** lấy cả câu |
| `intro` | không có | Phải viết mới |
| Tài liệu | `DocumentText.extractText(byte[], ext)` (Tika, có cờ cần OCR) | Khớp mục 4.4 |
| Chuẩn hoá url | `store/Url.java` (bản sao của `crawl_all.norm`) | Nên dùng chung với N8 (mục 3, dòng 13) |

Nếu tách thành dịch vụ riêng, chỉ cần thêm một lớp HTTP bọc ngoài: nhận `/parse`, gọi
`Extractor.extract()` hoặc `DocumentText.extractText()`, đổi kết quả sang JSON ở mục 4.2 rồi POST
sang N8.
