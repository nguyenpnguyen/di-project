# Kế hoạch: bóc tách nội dung + đồ thị liên kết + lưu MongoDB

Đề bài (26/9):

1. Cài đặt thuật toán tìm khối nội dung.
2. Xác định yêu cầu bóc tách:
   - trang: tiêu đề, ngày đăng, tác giả, nội dung, nguồn giới thiệu;
   - tệp tài liệu: nội dung văn bản, nguồn giới thiệu;
   - tệp ảnh: nguồn giới thiệu;
   - đồ thị liên kết: `nguồn --> đích : văn bản mô tả`.
3. Lưu vào MongoDB? Tự xây dựng lược đồ + mô tả?

Tài liệu này ghi lại (a) repo đang có gì cho từng mục, (b) thiếu gì, (c) làm theo
thứ tự nào. Mọi con số ước lượng thời gian ở đây là **phỏng đoán**, chưa đo.

---

## 1. Khảo sát: repo đang có gì

| Hạng mục đề bài | Đã có | Thiếu |
|---|---|---|
| Khối nội dung | Chỉ có selector cứng `.bodytext` (NukeViet), rơi về `main` rồi `body` (`hust-search/api/main.py: extract()`). | Không có thuật toán. Với site không phải NukeViet thì lấy **cả `body`** — gồm menu, footer, sidebar. |
| Tiêu đề / ngày / tác giả | `crawl_hust.py: parse_article()` đọc microdata `headline`, `datePublished`, `author`, dòng "Tác giả:". | Chỉ đúng với NukeViet. `extract()` bên API **bỏ qua tác giả**. Hai hàm bóc chữ trùng lặp, lệch nhau. |
| Nội dung trang | Có, qua selector. | Phụ thuộc mục 1. |
| Nguồn giới thiệu (trang) | Trường `via` trong kho thô = trang **phát hiện đầu tiên**. | Chỉ một nguồn, không có văn bản mô tả, không có các trang khác cũng trỏ tới. |
| Tệp tài liệu | `assets.txt` (197 url trên `hust.edu.vn`), chỉ lấy từ `a[href]` cùng host. | **Không tải file nào.** Endpoint xuất file (`html_b64 = null`, content-type pdf/docx) chỉ lưu metadata, không lưu byte. Chưa có bộ bóc chữ pdf/docx/xlsx. |
| Tệp ảnh | Không. | `img[src]` bị bỏ có chủ đích (`read_raw.hrefs_in`, CLAUDE.md: "danh sách link chỉ lấy href"). Phải bóc lại từ HTML đã lưu — **không cần mạng**. |
| Đồ thị liên kết | `outgoing_links()` trong API: `{url, text}` của link **trong khối nội dung**, chỉ để hiển thị. | Không lưu thành đồ thị, không có chiều ngược (ai trỏ tới trang này), không phủ ảnh/tệp. |
| MongoDB | Không có. Dữ liệu ở JSONL gzip + index Lucene. | Toàn bộ. |

Hiện trạng dữ liệu (theo CLAUDE.md, 22/08): kho 2.070 trang / 201 MB HTML thô,
còn 4.216 bài chưa tải, subdomain mới crawl 2/51. Container Claude dùng để viết
tài liệu này **không có `data/` và không vào được hust.edu.vn**, nên chưa đo
được gì trên dữ liệu thật — các chỗ ghi "đo lại" phải chạy trên máy có kho.

---

## 2. Cách hiểu đề — chỗ còn mơ hồ

**"Nguồn giới thiệu"** — có ít nhất hai cách hiểu:

- (A) *Trang nào trỏ tới đối tượng này, bằng dòng chữ gì.* Đây là cách hiểu duy
  nhất áp dụng được cho **ảnh** (ảnh không tự ghi nguồn), và khớp với mục "đồ
  thị liên kết" ngay bên dưới. → **Kế hoạch theo cách hiểu này**: nguồn giới
  thiệu của X = các cạnh `nguồn --> X` trong đồ thị.
- (B) *Dòng trích nguồn trong bài* ("Nguồn: VnExpress", "Theo …"). Rẻ, nên bóc
  thêm thành trường phụ `cited_source`, nhưng không coi là nghĩa chính.

Nên hỏi lại giảng viên. Đổi cách hiểu thì chỉ đổi truy vấn, không đổi lược đồ.

**"Lưu vào MongoDB?"** — dấu hỏi cho thấy chưa chốt. Xem mục 7 để cân nhắc.

---

## 3. Kiến trúc đề xuất

Giữ nguyên nguyên tắc của repo: **tải và bóc tách tách rời**. Mọi bước bóc tách
chạy offline trên kho thô, ra JSONL; MongoDB chỉ là đích nạp cuối cùng. Sửa
thuật toán thì chạy lại, không đụng mạng.

```
data/raw*/pages-*.jsonl.gz ──┐
                             ├─ extract_all.py ─► data/extracted/pages.jsonl
                             │                    data/extracted/links.jsonl
                             │                    data/extracted/images.jsonl
data/raw*/ (danh mục tệp) ───┴─ fetch_files.py ─► data/files/<sha1>.<đuôi> + files.jsonl
                                      │
                                      └─ extract_files.py ─► data/extracted/documents.jsonl

data/extracted/*.jsonl ─── load_mongo.py ─► MongoDB (pages, documents, images, links)
```

File mới (tên chỉ là đề xuất):

```
hust-crawler/
  boc_tach/
    khoi.py          thuật toán tìm khối nội dung            (mục 4)
    truong.py        tiêu đề / ngày / tác giả kèm nguồn gốc  (mục 5.1)
    lien_ket.py      bóc cạnh a[href], img[src]              (mục 5.4)
    tep.py           bóc chữ pdf/docx/xlsx/pptx               (mục 5.2)
  extract_all.py     CLI: kho thô -> data/extracted/
  fetch_files.py     CLI: tải tệp tài liệu theo danh mục
  load_mongo.py      CLI: JSONL -> MongoDB, tạo validator + index
  SCHEMA.md          mô tả lược đồ (phần "mô tả" của đề)
  tests/test_boc_tach.py
```

Việc dọn nợ đi kèm: `api/main.py: extract()` và `crawl_hust.py: parse_article()`
chuyển sang gọi `boc_tach`. Container `api` đã mount `hust-crawler` ở `/crawler`
nên import được, không phải build lại image.

---

## 4. Thuật toán tìm khối nội dung (việc lớn nhất)

### 4.1. Yêu cầu

- Chạy được trên **mọi** host trong họ `hust.edu.vn` (51 subdomain, CMS khác
  nhau), không cần selector riêng từng site.
- Trả về: nút DOM được chọn (đường dẫn CSS/XPath), điểm, văn bản sạch, HTML sạch,
  và `method` (`selector` / `heuristic` / `fallback`) để truy vết.
- Có đánh giá định lượng, không chỉ "nhìn thấy đúng".

### 4.2. Thuật toán đề xuất — ba lớp

Đây là **đề xuất thiết kế**, không phải thuật toán chuẩn duy nhất. Nó ghép hai ý
đã có trong tài liệu nghiên cứu:

- *mật độ chữ / mật độ link trên cây DOM* — họ phương pháp như CETD (Sun, Song,
  Liao, SIGIR 2011) và Boilerpipe (Kohlschütter và cs., WSDM 2010);
- *khử khuôn mẫu theo cả site* — ý tưởng Site Style Tree (Yi, Liu, Li, KDD 2003):
  khối lặp trên nhiều trang cùng site là khuôn, không phải nội dung.

(Tên bài báo ghi theo trí nhớ — kiểm tra lại trước khi trích dẫn trong báo cáo.)

**Lớp 1 — dọn cây.** Bỏ `script style noscript iframe form svg button input
select`, thẻ có `hidden`/`display:none` inline, comment HTML.

**Lớp 2 — khử khuôn theo host (lợi thế riêng của repo này).** Kho có hàng nghìn
trang mỗi host, nên đếm được khối nào lặp:

1. Với mỗi trang, lấy các khối lá (phần tử khối không chứa phần tử khối con),
   dấu vân tay = sha1 của văn bản đã chuẩn hoá (thường hoá, gộp khoảng trắng,
   thay số bằng `0`).
2. Đếm tần suất theo host. Khối xuất hiện trên > θ₁ trang của host (khởi điểm
   θ₁ = 30%, **phải dò lại**) → đánh dấu `template`, loại trước khi chấm điểm.
3. Host có < N trang (vd. < 20, nhiều subdomain chỉ có 1-2 link) thì bỏ qua
   lớp này — không đủ mẫu thống kê.

Lượt 1 chỉ đếm, lượt 2 mới bóc; lưu bảng đếm ra `data/extracted/template-<host>.json`.

**Lớp 3 — chấm điểm nút DOM.** Với mỗi nút ứng viên (`div section article main
td`), tính từ con cháu:

| Đặc trưng | Ý nghĩa |
|---|---|
| `C` | số ký tự văn bản |
| `LC` | số ký tự nằm trong `<a>` |
| `T` | số thẻ con cháu |
| `P` | số `<p>` / đoạn có ≥ 1 câu |
| `Q` | số dấu câu `. , ; : ? !` |

Điểm (khởi điểm, các hệ số **dò trên tập đánh giá**):

```
mật_độ_link = LC / max(C, 1)
điểm(n) = (C - LC) · (1 - mật_độ_link)^α  +  β·P  +  γ·Q
         nhân thêm hệ số phạt nếu class/id khớp
         nav|menu|footer|header|sidebar|comment|share|related|breadcrumb|banner|widget
         nhân thêm hệ số thưởng nếu khớp content|article|post|entry|detail|bodytext
```

Chọn nút điểm cao nhất rồi **nới sang anh em** cùng cha có điểm ≥ δ·điểm_max và
mật độ link thấp (bài bị chia nhiều `div` liền nhau). Cách nới này giống
Readability; đây là lựa chọn thiết kế, cần so với phương án không nới.

**Selector đã biết đi trước.** Host nào đã kiểm chứng selector (`.bodytext` trên
NukeViet) thì dùng selector, `method = "selector"`; heuristic là phương án dự
phòng và là đường chính cho site lạ. Luôn ghi cả hai kết quả khi đánh giá.

### 4.3. Đánh giá

- **Nhãn vàng miễn phí:** trên bài `hust.edu.vn`, `.bodytext` là khối nội dung
  đúng. Chạy heuristic *giả vờ không biết selector*, so với `.bodytext`.
- **Nhãn tay:** 20-30 trang từ subdomain khác CMS (`svbk`, `library`, …), gán
  nhãn khối nội dung bằng tay, lưu `tests/fixtures/`.
- **Độ đo:** precision / recall / F1 trên túi token (âm tiết) giữa văn bản bóc
  được và văn bản vàng.
- **Mốc so sánh:** (i) lấy cả `body`, (ii) chỉ lớp 3, (iii) lớp 2 + 3.
  Tuỳ chọn: `trafilatura` hoặc `readability-lxml` làm mốc tham chiếu bên ngoài —
  chỉ để so, không thay cho phần "tự cài đặt".
- Ghi kết quả vào README mục mới, kèm các ca hỏng điển hình.

---

## 5. Yêu cầu bóc tách từng loại

### 5.1. Trang

Mỗi trường là một **chuỗi ưu tiên**; lưu thêm `*_src` cho biết lấy từ đâu (để
đo và để báo cáo).

| Trường | Chuỗi ưu tiên |
|---|---|
| `title` | microdata `headline` → `og:title` → JSON-LD `headline` → `h1` gần khối nội dung nhất → `<title>` cắt hậu tố tên site (`… - Đại học Bách khoa Hà Nội`, `… \| …`) |
| `published_at` | microdata `datePublished` → `meta[property=article:published_time]` → JSON-LD `datePublished` → `<time datetime>` → regex ngày VN (`dd/mm/yyyy [hh:mm]`) trong vùng **ngay trên khối nội dung** → `null`. Chuẩn hoá ISO 8601, giữ `+07:00` nếu có. |
| `modified_at` | tương tự với `dateModified` |
| `author` | microdata `author` → `meta[name=author]` → JSON-LD `author.name` → dòng cuối khối khớp `Tác giả:`, `Người viết:`, `Tin, ảnh:`, `Bài, ảnh:` → `null`. Cắt dòng đó khỏi `content`. |
| `content` | văn bản khối nội dung (mục 4) + HTML sạch (dùng lại `don_html()`) |
| `cited_source` | dòng `Nguồn:` / `Theo …` cuối bài (cách hiểu B) |
| nguồn giới thiệu | **không lưu trong trang** — truy vấn cạnh `dst = url` trong `links` (mục 5.4). Giữ `via` làm "nơi phát hiện đầu tiên". |

Khử trùng: bài dùng `dedup_key()` làm khoá (không dùng `art_id()` — CLAUDE.md),
gộp `aliases` từ `state.json`. Chỉ bóc bản ghi `status < 400` có `html_b64`.

Độ đầy đủ: in bảng % trường khác `null` theo host — README hiện có số này cho
`crawl_hust.py` (16/17 trường 100% trên tin tức), đo lại với bộ mới.

### 5.2. Tệp tài liệu

**Bước 1 — lập danh mục (offline).** Bóc lại từ mọi HTML đã lưu:
- `a[href]` có đuôi `pdf doc docx xls xlsx ppt pptx` (bỏ ảnh, css, js);
- url khớp `SKIP_QUERY` (`download=1`) — là file dù không có đuôi;
- bản ghi kho có `html_b64 = null` và `content_type` là pdf/word/excel (endpoint
  xuất file — byte chưa được lưu, phải tải lại).

Phạm vi: host thuộc `*.hust.edu.vn` thì tải; host ngoài (Google Drive, …) chỉ
ghi cạnh, **không tải** — cần chốt lại (mục 9).

**Bước 2 — tải (`fetch_files.py`).** Cùng luật với crawler:
- nhịp ≥ 2 s, dính 429 thì lùi theo `Retry-After` (dùng lại logic
  `Crawler._wait_turn/_slow_down/_speed_up` trong `crawl_all.py`, đừng viết lại);
- **không chạy song song với `crawl_all.py` trên cùng host**: nhịp khoá theo
  tiến trình, hai tiến trình cùng 2,5 s là ~48 req/phút, gấp đôi ngưỡng chặn;
- trần kích thước mỗi file (vd. 50 MB), ghi `skipped_too_large`;
- lưu byte ra `data/files/<sha1>.<đuôi>`, sổ `files.jsonl` flush từng dòng,
  `--resume` bỏ qua url đã có.

Ước lượng: 197 url của host chính ≈ 8 phút (README mục 9). Số tệp ở subdomain
chưa biết.

**Bước 3 — bóc chữ (`extract_files.py`).** Thư viện đề xuất (chưa cài, chưa thử
trên file thật):

| Loại | Thư viện | Ghi chú |
|---|---|---|
| pdf | `pdfminer.six` hoặc `pypdf` | PyMuPDF nhanh hơn nhưng giấy phép AGPL — cân nhắc |
| docx | `python-docx` | kể cả bảng |
| xlsx | `openpyxl` | ghép ô thành dòng tab |
| pptx | `python-pptx` | chữ trong shape + ghi chú |
| doc/xls/ppt cũ | `libreoffice --headless --convert-to` | hoặc bỏ, đánh dấu `unsupported` |

Rủi ro cần soát:
- **PDF scan** (không có lớp chữ): số ký tự/trang ≈ 0 → `needs_ocr = true`. OCR
  (`tesseract` + gói `vie`) là tuỳ chọn, chạy sau.
- **Bảng mã cũ** (TCVN3/VNI) cho ra chữ lỗi: đo tỉ lệ ký tự có dấu tiếng Việt
  hợp lệ, thấp bất thường → `encoding_suspect = true`.

Nguồn giới thiệu của tệp = cạnh `dst = url tệp` trong `links`, văn bản mô tả là
chữ của thẻ `<a>`.

### 5.3. Tệp ảnh

Không cần tải ảnh — đề chỉ yêu cầu nguồn giới thiệu. Bóc offline từ HTML đã lưu:
- `img[src]` (và `data-src` cho ảnh lazy-load), `meta[property=og:image]`,
  microdata `image`;
- văn bản mô tả = `alt` → `title` → `figcaption` bao quanh → rỗng;
- cờ `in_content` (ảnh nằm trong khối nội dung hay ở khuôn trang);
- lọc nhiễu: ảnh xuất hiện trên > θ₁ trang của host (logo, icon), đường dẫn
  `/themes/`, `width/height` ≤ 16. Không xoá — đánh dấu `is_template = true` để
  truy vấn tự lọc.

Tuỳ chọn: gửi HEAD lấy `Content-Type`/`Content-Length` — tốn quota rate-limit,
nên để sau.

### 5.4. Đồ thị liên kết

Một cạnh = `(nguồn, đích, loại, văn bản mô tả)`.

| Trường | Giá trị |
|---|---|
| `src` | url trang chứa link (đã `norm()`) |
| `dst` | url đích, **qua `crawl_all.norm()`** (CLAUDE.md: chuẩn hoá khác nhau là báo lệch giả) |
| `type` | `href` (a) / `embed` (img) |
| `text` | chữ trong `<a>` → `title` → `aria-label` → `alt` của ảnh con; với `img` là alt/caption |
| `dst_kind` | `page` / `document` / `image` / `external` / `other` |
| `in_content` | cạnh nằm trong khối nội dung (mục 4) |
| `is_template` | cạnh lặp theo khuôn host (menu, footer) |
| `count` | số lần cặp này xuất hiện trong trang |
| `src_host`, `dst_host` | để lọc nội bộ / liên site |

Khoá khử trùng: `(src, dst, type, text)`.

Quy mô: menu hust.edu.vn in cả trăm link mỗi trang, nên số cạnh thô có thể lên
hàng triệu với ~10k trang — **ước lượng, chưa đo**. Vì thế giữ hết nhưng gắn
`is_template`; mặc định truy vấn/xuất chỉ lấy `in_content = true`.

Đầu ra thêm: `edges.csv` dạng `source,target,text` để mở bằng Gephi / networkx.
Thống kê tối thiểu: số nút, số cạnh, bậc vào cao nhất, số thành phần liên thông.
PageRank là tuỳ chọn (BAO-CAO mục 5 đã ghi là giới hạn chưa làm).

---

## 6. Lược đồ MongoDB (bản nháp)

Bốn collection, ràng buộc bằng `$jsonSchema` (phần "tự xây dựng lược đồ"), mô tả
từng trường trong `SCHEMA.md` (phần "mô tả").

```js
pages {
  _id: "<url chính>",                 // url sau norm(); bài dùng url chính theo dedup_key
  dedup_key, aliases: [url],
  host, lang, kind,                   // kind lấy từ kho thô
  title, title_src,
  published_at, published_at_src,     // ISO 8601 hoặc null
  modified_at,
  author, author_src,
  cited_source,
  content: { text, html, word_count,
             block: { path, score, method } },   // method: selector|heuristic|fallback
  section, breadcrumb: [str],
  via,                                // trang phát hiện đầu tiên
  raw: { sha1, fetched_at, shard },   // truy ngược về kho thô
  extractor_version, extracted_at
}

documents {
  _id: "<url>", host, ext, mime, size, sha1, status,
  text, pages, needs_ocr, encoding_suspect,
  error, fetched_at, extractor_version
}

images {
  _id: "<url>", host, alts: [str], is_template,
  first_seen_on                        // tham khảo; nguồn đầy đủ nằm ở links
}

links {
  _id: sha1(src|dst|type|text),
  src, dst, type, text, dst_kind,
  in_content, is_template, count, src_host, dst_host
}
```

Index: `links.{dst: 1}` (nguồn giới thiệu), `links.{src: 1}`, `pages.{host: 1,
published_at: -1}`, text index trên `pages.title/content.text` nếu cần tìm trong
Mongo (tìm kiếm chính vẫn là Lucene).

"Nguồn giới thiệu" của bất kỳ đối tượng nào:

```js
db.links.find({ dst: "<url>", is_template: false }, { src: 1, text: 1 })
```

Lưu một nguồn sự thật (collection `links`) thay vì chép mảng `referrers` vào
từng trang: tránh lệch khi nạp lại. Nếu giao diện cần nhanh thì thêm view
`$lookup`, không nhân bản dữ liệu.

`load_mongo.py`: upsert theo `_id` (chạy lại không nhân đôi), tạo validator +
index nếu chưa có, in số bản ghi bị validator từ chối.

Hạ tầng: thêm service `mongo` (image chính thức `mongo`, có volume) vào
`hust-search/docker-compose.yml`; `load_mongo.py` nhận `--mongo-uri` để chạy từ
máy host. Phần bóc tách vẫn chạy không cần docker, đúng như `hust-crawler` hiện nay.

---

## 7. Có nên dùng MongoDB? (đề xuất, không phải kết luận)

| | MongoDB | Giữ JSONL + Lucene | PostgreSQL | Neo4j |
|---|---|---|---|---|
| Bản ghi lồng nhau (aliases, block, *_src) | tự nhiên | tự nhiên | JSONB được | vụng |
| Trường khác nhau giữa các site | dễ | dễ | cần JSONB | dễ |
| Ràng buộc lược đồ | `$jsonSchema` | không có | mạnh nhất | yếu |
| Truy vấn đồ thị nhiều bước | `$graphLookup`, hạn chế | tự viết | CTE đệ quy | mạnh nhất |
| Thêm hạ tầng | 1 service | không | 1 service | 1 service |

Nếu môn học không bắt buộc hệ quản trị cụ thể thì MongoDB hợp lý cho dữ liệu
bán cấu trúc này, với điều kiện JSONL vẫn là nguồn gốc (nạp lại được bất cứ lúc
nào). Nếu trọng tâm chấm điểm là phân tích đồ thị thì Neo4j đáng cân nhắc hơn.
Tôi không biết yêu cầu chấm của môn — cần hỏi giảng viên.

---

## 8. Thứ tự làm và phụ thuộc

Thời gian là ước lượng thô cho một người, chưa tính gỡ lỗi trên dữ liệu thật.

| # | Việc | Phụ thuộc | Ước lượng | Xong khi |
|---|---|---|---|---|
| 0 | Lấy 30-50 bản ghi mẫu nhiều host vào `tests/fixtures/`, gom `extract()`/`parse_article()` về `boc_tach` | — | 0,5 ngày | test cũ vẫn xanh |
| 1 | Thuật toán khối nội dung (lớp 1-3) + bộ đánh giá | 0 | 2-3 ngày | có bảng P/R/F1 so 3 mốc |
| 2 | Bóc trường trang (mục 5.1) | 1 | 1 ngày | bảng % đầy đủ theo host |
| 3 | Đồ thị liên kết + ảnh (5.3, 5.4) | 1 (cho `in_content`) | 1 ngày | `links.jsonl`, `edges.csv`, thống kê |
| 4a | Danh mục tệp (5.2 bước 1) | 3 | 0,5 ngày | danh sách url tệp theo host |
| 4b | Tải tệp | 4a; **không chạy cùng lúc với crawl trên cùng host** | chạy nền | `files.jsonl` |
| 4c | Bóc chữ tệp | 4b | 1 ngày | `documents.jsonl`, tỉ lệ `needs_ocr` |
| 5 | Lược đồ Mongo + `load_mongo.py` + `SCHEMA.md` | 2, 3, 4c (lược đồ viết được sớm) | 1 ngày | nạp lại 2 lần không nhân đôi |
| 6 | Cập nhật README, BAO-CAO, CLAUDE.md (số test) | tất cả | 0,5 ngày | — |

Việc 1-3 làm được ngay trên 2.070 trang đang có; khi Việc 2 (tải nốt 4.216 bài)
và 1b (subdomain) xong thì **chỉ cần chạy lại** `extract_all.py`, không sửa code.

Test cần thêm (đặt tên theo lỗi thật như quy ước của repo): khối nội dung trên
fixture NukeViet khớp `.bodytext`; khối không chọn nhầm menu trên fixture
subdomain; ngày `dd/mm/yyyy` → ISO; dòng "Tác giả:" bị cắt khỏi content; cạnh
dùng `norm()` (http/https không tách đôi); nạp Mongo hai lần không nhân bản ghi.

---

## 9. Câu hỏi cần chốt trước khi làm

1. "Nguồn giới thiệu" theo cách hiểu (A) hay (B) (mục 2)?
2. MongoDB là bắt buộc hay tuỳ chọn? Có yêu cầu phân tích đồ thị không?
3. Tệp ở host ngoài (Google Drive…) có phải tải và bóc chữ không?
4. Đồ thị: nộp toàn bộ cạnh hay chỉ cạnh trong khối nội dung?
5. Có cần OCR PDF scan không?
