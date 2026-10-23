# Đặc tả dịch vụ "Đọc nội dung" (N1)

Bản nháp, 23/10/2026. Dịch vụ này đóng gói lõi bóc tách `hust-search/src/main/java/vn/hust/search/extract/`
thành một microservice, theo thiết kế trong slide nhóm *"Thiết kế hệ thống thu thập dựa trên kiến trúc
dịch vụ nhỏ"* (Google Slides).

Mỗi điều trong tài liệu mang một nhãn cho biết nó lấy từ đâu:

- **[Slide]**: slide ghi như vậy.
- **[Code]**: hành vi hiện tại của `extract/`, đọc từ mã nguồn. Đường dẫn tính từ `hust-search/src/main/java/vn/hust/search/`.
- **[Đề xuất]**: chưa ai quyết. Đây là gợi ý để chốt.
- **[Cần chốt]**: phụ thuộc vào nhóm khác. Danh sách ở mục 9.
- **[Đã chốt]**: nhóm N1 đã quyết.

---

## 1. Vị trí trong hệ thống [Slide]

```
Điều khiển ──2──▶ Tải về ×2 ──3──▶ ĐỌC NỘI DUNG ──4──▶ Tổng hợp ──5──▶ Lưu trữ ──▶ Mongo
  (BAT,5200)       (N5,8800)        (N1, 6600)         (N8,3300)       (N10,2200)
     ▲                                                     │
     └─────────────────────────6───────────────────────────┘
```

- Đọc nội dung nhận tài liệu đã tải từ Tải về.
- Với html, nó bóc **tiêu đề, nội dung văn bản, liên kết đi ra kèm văn bản liên kết** ("câu hoàn
  thiện chứa thẻ a").
- Với tài liệu khác (other), nó bóc **nội dung văn bản**.
- Kết quả được đẩy sang Tổng hợp qua API. Mọi mô-đun nói chuyện bằng HTTP, dữ liệu là JSON.

Số cổng trên slide không ghi rõ của mô-đun nào. Cổng 6600 cho N1 (và các cổng khác ngoài 5200) là
tôi ghép theo vị trí trên hình vẽ, nên còn phải xác nhận.

**Ngoài phạm vi của N1:**

| Việc | Ai làm |
|---|---|
| Tải trang, chạy JS (Puppeteer) | N5 |
| Lọc url trong miền hust, loại url đã có | N8 |
| Ghi Mongo | N10 |
| Chỉ mục Lucene | `hust-search`, không thuộc thiết kế này |

### 1.1 Chia việc về link giữa N1 và N8 [Đã chốt, theo Slide]

Chữ "tổng hợp link" dễ bị hiểu thành một việc, thật ra là hai việc, mỗi bên làm một:

| | **N1, Đọc nội dung: bóc link** | **N8, Tổng hợp: tổng hợp link** |
|---|---|---|
| Đầu vào | HTML của **cả trang** | `out-links` do N1 gửi |
| Làm gì | Tìm mọi `<a href>`, đổi sang url tuyệt đối theo `final_url`, chuẩn hoá (mục 2.3), lấy văn bản liên kết (mục 4.2), khử trùng **trong một trang** | Lọc url thuộc miền hust, loại url đã có (khử trùng **giữa các trang**), gửi url mới cho Điều khiển, gửi cạnh đồ thị sang Lưu trữ |
| Không làm | Lọc theo miền, quyết định url nào được crawl tiếp | Parse HTML |
| Slide | "Đầu ra … liên kết đi ra cùng với văn bản liên kết" | "Lọc url - lấy trong miền hust. Loại url đã có", `/add` nhận `out-links` |

Lý do không để N8 tự bóc link từ `content`:

- `content` là chữ thuần, thẻ `<a href>` đã mất (mục 2.1, bước 5).
- HTML rút gọn của lõi (`Extraction.html()`) chỉ có khối nội dung, không có menu và phân trang
  (mục 4.1), và bị cắt ở 40.000 ký tự.
- Muốn N8 bóc thì N1 phải chuyển nguyên HTML thô sang, và N8 phải parse HTML thêm một lần.

## 2. Lõi: `extract/` làm gì [Code]

Lõi là thư viện **không trạng thái**. Mỗi lần gọi chỉ xử lý một tài liệu và không đọc hay ghi gì
bên ngoài. Nó gồm 7 tệp trong `extract/` và một tệp `store/Url.java` mà `extract/` dùng tới. Thư viện
ngoài cần có: jsoup 1.23.2, jackson-databind 2.17.2 (đọc JSON-LD), Tika 4.1.0. Lõi **không** cần
Mongo hay Lucene.

### 2.1 Nhánh html: `Extractor.extract(html, url, templateFps)`

Thứ tự trong `Extractor.java`:

1. Parse HTML bằng jsoup.
2. Đọc JSON-LD và thu **mọi** `<a href>` của trang (`Links.collect`). Bước này chạy trước khi dọn
   cây, nên menu và footer vẫn còn nguyên.
3. Chọn khối nội dung (`ContentBlock.findBlock`). Cây bị sửa tại chỗ qua ba lớp:
   - Lớp 1, dọn cây: bỏ comment, các thẻ `script style noscript iframe form svg button input select
     nav footer aside template`, và mọi phần tử có `hidden` hoặc `display:none`.
   - Lớp 2, khử khuôn theo host: chỉ chạy khi `templateFps` không rỗng (mục 5).
   - Lớp 3, chọn khối: với host `hust.edu.vn`, lấy phần tử `.bodytext` nếu có chữ. Các host khác
     chấm điểm nút theo mật độ chữ, mật độ link, số đoạn văn và dấu câu, rồi đi từ `body` xuống.
     Chi tiết ở `docs/THUAT-TOAN-BOC-TACH.md` mục 2.
4. Bóc trường (`Fields`):
   - Tiêu đề: `itemprop=headline` → `og:title` → JSON-LD `headline` → `h1` trong khối (hoặc tới 4
     cấp cha) → `h1` đầu trang → `<title>` đã cắt hậu tố tên site.
   - Còn có ngày đăng, tác giả, nguồn trích dẫn, chuyên mục. Dòng "Tác giả: …" và "Nguồn: …" ở cuối
     khối bị cắt ra khỏi nội dung.
5. Nội dung lấy từ chữ của khối. Mọi khoảng trắng, **kể cả xuống dòng**, gộp thành một dấu cách,
   nên nội dung chỉ là **một dòng**.
6. Chia link thành **cạnh nội dung** (nằm trong khối) và **cạnh khuôn** (ngoài khối: menu, footer,
   sidebar). Ảnh `<img>` và `og:image` đi vào loại cạnh `embed`, tách khỏi `href`.

`extract()` trả `null` khi không có cả tiêu đề lẫn chữ.

Giới hạn độ dài: tiêu đề 500 ký tự, nội dung 200.000 ký tự, HTML rút gọn 40.000 ký tự. Đơn vị là
code point.

### 2.2 Nhánh tài liệu: `DocumentText.extractText(bytes, ext)`

- Đọc được `pdf docx xlsx pptx doc xls ppt`, dùng **một** `AutoDetectParser` chung. Dựng parser mất
  khoảng 1 giây, và theo comment trong code thì dùng chung nhiều luồng được.
- Trạng thái trả về: `ok`, `unsupported` (đuôi không hỗ trợ) hoặc `error` (tệp hỏng, hoặc nội dung
  thật ra là chữ thường hay octet-stream).
- Pdf có dưới 20 ký tự mỗi trang thì gắn cờ `needs_ocr` và trả chữ rỗng. Không có OCR.
- Có cờ `encoding_suspect`, đoán văn bản dùng bảng mã cũ TCVN3/VNI. Đây là heuristic.
- Chữ giữ xuống dòng, tối đa 500.000 ký tự.
- Cách chọn `ext` (lấy từ `web/ApiExtract.java`): đuôi trong url trước, không có thì đổi từ MIME qua
  `DocumentText.MIME_TO_EXT`.

### 2.3 Chuẩn hoá url: `Url.norm(href, base)`

Mọi link ra đều đi qua hàm này:

| Quy tắc | Ví dụ / ghi chú |
|---|---|
| Giải link tương đối theo `base` | |
| Bỏ link không phải http(s) | `mailto:`, `tel:`, `javascript:`, link chỉ có `#…` |
| Bỏ fragment | |
| Gộp `//` trong path | |
| Bỏ tham số theo dõi | `fbclid utm_source utm_medium utm_campaign gidzl PHPSESSID` |
| Chữ thường host, xoá mọi chuỗi `www.` | |
| **Ép mọi link sang `https`** | Site chỉ chạy `http` sẽ nhận link `https` không tải được. `ApiExtract` phải thử lại bằng `http` khi gặp đúng lỗi này. N5 cần biết điều này. |

Link trỏ về chính trang đang bóc bị bỏ. `Url.java` là bản sao của `crawl_all.norm()` (xem
`CLAUDE.md`), nên N8 muốn khử trùng khớp với N1 thì nên dùng chung quy tắc này.

## 3. API

### 3.1 `POST /parse` [Đề xuất trên nền Slide]

Slide chỉ ghi `post: http://ip:cổng/parse` và "http content - dữ liệu được tải về". Phần dưới đây là đề xuất.

Request chỉ chở **tài liệu thô** do N5 tải về. `title`, `content` và `out-links` là **đầu ra**: N1 tự
bóc chúng từ `content` của request rồi gửi sang N8 (mục 3.2). Vì vậy chúng không có mặt trong request.

```
N5 ──POST /parse {url, content: "<html>…"}──▶ N1 ──POST /add {url, title, content, out-links}──▶ N8
N5 ◀────────────── 200 {status, n_out_links} ── N1
```

**Request**, `Content-Type: application/json`:

```json
{
  "url": "https://hust.edu.vn/vi/news/tin-tuc-su-kien/abc-654601.html",
  "final_url": "https://hust.edu.vn/vi/news/tin-tuc-su-kien/abc-654601.html",
  "format": "html",
  "content_type": "text/html; charset=utf-8",
  "encoding": "text",
  "content": "<!DOCTYPE html>…"
}
```

| Khoá | Bắt buộc | Ý nghĩa |
|---|---|---|
| `url` | có | url Điều khiển giao. Gửi lại nguyên văn cho N8 để N8 đánh dấu đã xong. |
| `final_url` | không | url sau chuyển hướng, chỉ gửi khi khác `url`. Dùng làm gốc để giải link tương đối. |
| `format` | có | `html` hoặc `other` [Slide] |
| `content_type` | không | Header `Content-Type` của phản hồi. Nhánh other dùng nó để đoán `ext` khi url không có đuôi. |
| `encoding` | có | `text`: `content` là chuỗi (HTML từ Puppeteer). `base64`: byte của tài liệu. |
| `content` | có | Nội dung đã tải |

**Response** [Đề xuất]: đồng bộ. N1 bóc xong, gọi `/add` của N8 xong rồi mới trả lời, để N5 biết
ngay là thành hay hỏng.

| Mã | Khi nào | Body |
|---|---|---|
| 200 | Bóc được và N8 đã nhận | `{"status":"ok","url":…,"format":…,"n_out_links":57,"chars":12034}` |
| 400 | Thiếu khoá, base64 hỏng, `url` không phải http(s) (`Url.norm` trả `null`) | `{"detail":"…"}` |
| 413 | `content` vượt giới hạn kích thước (mục 6) | `{"detail":"…"}` |
| 415 | Nhánh other ra `unsupported` | `{"detail":"không đọc được tệp .xyz"}` |
| 422 | html: `extract()` trả `null`. other: `error`, hoặc `needs_ocr` | `{"detail":"…","url":…}` |
| 502 | Gọi N8 thất bại | `{"detail":"Tổng hợp không nhận: …"}` |

Lỗi dùng khoá `detail` cho giống quy ước của `hust-search` (`CLAUDE.md`). Không bắt buộc.

### 3.2 Gọi `POST /add` của Tổng hợp [Slide + Đề xuất]

Khoá lấy theo slide. Hai chỗ khác slide:

- `out-links` là **mảng các cặp**. Ví dụ trên slide chỉ có một cặp phẳng.
- Thêm `intro`, vì Mongo trên slide có trường này.

```json
{
  "url": "https://hust.edu.vn/tin-tuc1",
  "format": "html",
  "title": "Tin tức 1",
  "content": "Nội dung tin tức bóc tách được",
  "intro": "Sapo nếu có",
  "out-links": [
    ["https://hust.edu.vn/tin-tuc2", "Xem thông báo tuyển sinh năm 2026 tại đây."],
    ["https://hust.edu.vn/files/qd-123.pdf", "Quyết định số 123."]
  ]
}
```

Bản ghi other:

```json
{"url": "https://hust.edu.vn/files/qd-123.pdf", "format": "other", "ext": "pdf", "content": "…"}
```

### 3.3 `GET /ping` [Đề xuất]

Giống cách N5 làm trên slide: `{"status": "đang chờ"}`, hoặc `{"status": "đang xử lý", "dang_xu_ly": 2}`.

## 4. Ánh xạ từ lõi sang JSON

| Khoá gửi N8 | Lấy từ | Hiện trạng [Code] | Cần sửa |
|---|---|---|---|
| `url` | `url` của request | `extract()` nhận url đã `Url.norm` làm gốc giải link | Gửi N8 url gốc. Dùng `Url.norm(final_url ?? url)` làm gốc giải link. |
| `format` | request | n/a | |
| `title` | `Extraction.title()` | Có, tối đa 500 ký tự | |
| `content` (html) | `Extraction.text()` | Có, một dòng, tối đa 200.000 ký tự | [Cần chốt] Có cần giữ xuống dòng giữa các đoạn không. Muốn giữ thì phải sửa cách lấy chữ. |
| `content` (other) | `DocumentText.Result.text()` | Có, giữ xuống dòng | |
| `ext` (other) | `extensionFromUrl` / `MIME_TO_EXT` | Có, nằm trong `ApiExtract` | Chuyển vào lớp dịch vụ |
| `intro` | `og:description`, `meta name=description` | **Chưa có** | Viết mới. `Fields.meta()` đã đọc được thẻ meta. |
| `out-links[i][0]` | `Links.collect` → cạnh `href` | Có. Nhưng `Extraction.outgoingLinks()` **chỉ lấy cạnh trong khối**. | Lấy **mọi** cạnh `href` (cạnh nội dung + cạnh khuôn), khử trùng theo url. Lý do ở mục 4.1. |
| `out-links[i][1]` | `Links.describeAnchor` | Chỉ có chữ trong thẻ a, rồi `title`/`aria-label`, rồi `alt` của ảnh | Lấy **câu** chứa thẻ a (mục 4.2) |
| có thể thêm: `date`, `author`, `section` | `Extraction` | Có | [Cần chốt] N8 có nhận không |

### 4.1 Vì sao `out-links` phải lấy cả cạnh khuôn

Mục này nối tiếp cách chia việc ở mục 1.1: N1 gửi **mọi** link của trang, N8 chọn link nào dùng.

Ở bước 6 trên slide, N8 lấy url mới từ `out-links` để gửi Điều khiển. Link chuyên mục, menu và phân
trang đều nằm ngoài khối nội dung. Nếu chỉ gửi cạnh trong khối thì các link đó không bao giờ tới
Điều khiển, và crawl không lan ra được.

Đây là suy luận từ luồng trên slide, tôi chưa chạy thử. [Đề xuất] Nếu N8 cần phân biệt hai loại
link (đồ thị nội dung với đồ thị khuôn), thêm phần tử thứ ba vào mỗi cặp: `"noi_dung"` hoặc `"khuon"`.

Ảnh (cạnh `embed`) **không** đưa vào `out-links`. Repo này từng gộp `src` của ảnh vào danh sách link,
và ảnh nhúng `/uploads/` làm danh sách phình từ 14k lên 24k dòng mà không thêm trang nào tải được
(`CLAUDE.md`). Link `href` tới pdf/docx thì **giữ**, vì đó là cách tài liệu tới được N5 rồi quay lại
N1 ở nhánh other.

### 4.2 "Câu hoàn thiện chứa thẻ a" [Đề xuất]

Slide không định nghĩa thế nào là câu. Đề xuất:

1. Đi lên phần tử khối gần nhất chứa thẻ a: `p li td th dd h1–h6 figcaption blockquote`.
2. Lấy chữ của khối đó bằng `HtmlUtil.textBs4` để cách khoảng giống phần còn lại của lõi.
3. Cắt ra câu chứa vị trí của thẻ a. Ranh giới câu là `.`, `!`, `?`, `…` hoặc `;`.
4. Nếu câu tìm được trùng chữ của thẻ a (link đứng một mình, như menu) thì giữ cách cũ của
   `describeAnchor`.
5. Cắt câu ở một độ dài tối đa, ví dụ 300 ký tự.

Lưu ý: `Links.collect` chạy trước khi dọn cây, nên khi đó khối cha vẫn còn đủ chữ.

## 5. Khử khuôn và chuyện trạng thái

Lớp 2 cần `templateFps`, tức tập vân tay khuôn của cả host. [Code] Tập này do
`mongo/Pipeline.buildTemplates()` đếm trên toàn kho: khối lặp trên hơn 30% số trang là khuôn, và host
phải có ít nhất 20 trang. Kết quả lưu ở collection `templates`. `/parse` chỉ nhận từng trang một
nên không có sẵn tập này.

[Đề xuất] **Bản đầu truyền tập rỗng.** Dịch vụ không có trạng thái. Lý do:

- Với `hust.edu.vn`, khối được chọn bằng selector `.bodytext`. Selector chạy sau lớp khử khuôn,
  nhưng chọn ra cùng một phần tử dù có khử khuôn hay không, nên nội dung gần như không đổi.
  Ngoại lệ: khối lặp nằm ngay trong `.bodytext` thì vẫn bị xoá khi có khử khuôn.
- Subdomain mới crawl phần lớn chưa tới 20 trang, nên lớp này vốn đã không chạy với chúng
  (`docs/THUAT-TOAN-BOC-TACH.md` mục 8, điểm 3).
- `BlockEvaluationTest` (150 trang **tổng hợp**, không phải trang thật) đo F1 = 0,974 khi chỉ có lớp
  3 và 0,962 khi có lớp 2+3. Tức là trên bộ mẫu này, khử khuôn không làm kết quả tốt hơn. Số này
  không nói gì về trang thật trên hust.edu.vn.

Nếu sau này cần khử khuôn, có hai hướng:

- **Nạp sẵn:** đọc tệp vân tay theo host dựng từ kho hiện có lúc khởi động (biến môi trường
  `TEMPLATES_FILE`).
- **Cộng dồn:** N1 tự đếm vân tay theo host. Dịch vụ thành có trạng thái, và mấy trang đầu của mỗi
  host vẫn chưa được khử khuôn.

## 6. Yêu cầu phi chức năng [Đề xuất]

| Mục | Đề xuất | Căn cứ |
|---|---|---|
| Cổng | 6600, đọc từ biến `PORT` | Ghép từ hình vẽ slide |
| Đồng thời | Pool luồng cố định (ví dụ 4). Hai N5 cùng gửi vào. | Lõi không trạng thái, jsoup tạo mới mỗi lần gọi, parser Tika dùng chung [Code] |
| Khởi động | Gọi Tika một lần lúc khởi động để dựng parser, `/ping` chỉ báo sẵn sàng sau đó | Dựng `AutoDetectParser` mất ~1 s [Code] |
| Kích thước body | Tối đa ví dụ 50 MB, vượt thì trả 413 | Base64 làm tệp to thêm khoảng 33% |
| Thời gian | Timeout khi gọi N8 (ví dụ 10 s), thử lại một lần | Chưa đo thời gian Tika đọc pdf lớn |
| Cấu hình | `PORT`, `AGGREGATOR_URL` (vd. `http://n8:3300/add`), `TEMPLATES_FILE` (tuỳ chọn) | |
| HTTP server | `com.sun.net.httpserver`, giống `web/Http.java` | Không phải thêm thư viện |
| Đóng gói | Module Maven riêng (`hust-reader`?) chứa `extract/` + `store/Url.java`. Docker image dựng hai tầng giống `hust-search/Dockerfile`. | |
| Nhật ký | Mỗi request một dòng: url, format, mã trả, thời gian, số link | |

Về chia sẻ code [Đề xuất]: chép `extract/` sang module mới sẽ đẻ ra hai bản phải sửa song song.
Sẵn đã có một cặp phải giữ khớp nhau là `Url.java` ↔ `crawl_all.py`. Gọn hơn là tách `extract/` và
`Url.java` thành module thư viện, để `hust-search` và dịch vụ mới cùng phụ thuộc vào.

## 7. Kiểm thử

Test sẵn có trên lõi, chạy lại được không cần sửa:

- `ExtractorTest`
- `PythonParityTest`: khớp bản Python trên kho thật
- `BlockEvaluationTest`
- `DocumentTextTest` và `DocumentTextGoldenTest`, với mẫu tệp trong `src/test/resources/tep/`
- `UrlGoldenTest`

Test cần thêm [Đề xuất]:

- **Hợp đồng `/parse`:** mỗi mã trả ở mục 3.1 có ít nhất một ca, dùng mẫu sẵn có trong
  `src/test/resources/` (các thư mục `html/*.test/`, `tep/co-chu.pdf`, `tep/khong-chu.pdf`).
- **Gọi N8:** dựng một server giả nhận `/add`, kiểm tra JSON đúng mục 3.2. Kiểm tra cả lúc server giả
  trả lỗi thì `/parse` ra 502.
- **`out-links`:** trang có menu thì link menu phải có mặt (mục 4.1). Link tương đối giải đúng theo
  `final_url`. Link ảnh không có mặt.
- **Văn bản liên kết:** link giữa đoạn văn ra đúng cả câu, link menu ra chữ của thẻ a.

## 8. Việc phải làm trên lõi

1. Hàm mới (ví dụ `Links.outLinks`) trả mọi cạnh `href`, khử trùng theo url, kèm văn bản liên kết là
   câu (mục 4.1, 4.2). Giữ `outgoingLinks()` như cũ để `hust-search` không đổi hành vi.
2. Bóc `intro`.
3. Chuyển cách chọn `ext` từ `ApiExtract` vào lõi hoặc lớp dịch vụ.
4. Lớp dịch vụ: `/parse`, `/ping`, client gọi N8, cấu hình.

## 9. Cần chốt với nhóm khác

| # | Câu hỏi | Hỏi ai |
|---|---|---|
| 1 | Body `/parse` theo mục 3.1 (JSON, tài liệu base64) được không? Hay N5 muốn gửi body thô? | N5 |
| 2 | N5 có gửi `final_url` (`page.url()` của Puppeteer) không? | N5 |
| 3 | `/parse` đồng bộ (chờ N8 nhận xong) được không? N5 đặt timeout bao lâu? | N5 |
| 4 | N8 xác nhận cách chia ở mục 1.1: N1 gửi mọi link chưa lọc, N8 tự lọc miền và khử trùng. `out-links` là mảng cặp như mục 3.2 có được không? Có cần phần tử thứ ba `noi_dung`/`khuon` không? | N8 |
| 5 | N8 có muốn nhận `intro`, `date`, `author`, `section` không? | N8, N10 |
| 6 | `format` của bản ghi other là `other` kèm `ext`, hay là đuôi tệp luôn (slide Mongo ghi `docx`)? | N8, N10 |
| 7 | N8 khử trùng url theo quy tắc nào? Có dùng chung `Url.norm` (mục 2.3), kể cả việc ép `https` không? | N8 |
| 8 | Pdf cần OCR, trang rỗng: chỉ trả lỗi cho N5, hay cũng báo N8 để không giao lại url đó? | N5, N8 |
| 9 | `content` có cần giữ xuống dòng giữa các đoạn không? | N8, N10 |
| 10 | Xác nhận cổng 6600 | người vẽ slide |
