# Kế hoạch: bóc tách nội dung, đồ thị liên kết, MongoDB — trong `hust-search`

Đề bài (26/9):

1. Cài đặt thuật toán tìm khối nội dung.
2. Xác định yêu cầu bóc tách:
   - trang: tiêu đề, ngày đăng, tác giả, nội dung, nguồn giới thiệu;
   - tệp tài liệu: nội dung văn bản, nguồn giới thiệu;
   - tệp ảnh: nguồn giới thiệu;
   - đồ thị liên kết: `nguồn --> đích : văn bản mô tả`.
3. Lưu vào MongoDB? Tự xây dựng lược đồ + mô tả?

**Phạm vi:** toàn bộ việc làm nằm trong `hust-search/` (tầng API Python, dịch vụ
Lucene, giao diện, docker-compose). `hust-crawler/` **giữ nguyên**, chỉ đóng vai
nguồn HTML thô, được mount vào container `api` ở `/crawler`.

Mọi ước lượng thời gian, ngưỡng và quy mô ở đây là **phỏng đoán**, chưa đo trên
dữ liệu thật (container dùng để viết tài liệu này không có `data/` và không vào
được hust.edu.vn).

---

## 1. Hiện trạng `hust-search` so với đề

Luồng hiện tại: `kho thô → api/main.py extract() → POST lucene:8081/bulk → index`.

| Hạng mục đề bài | Đã có trong `hust-search` | Thiếu |
|---|---|---|
| Khối nội dung | `extract()`: `.bodytext` → `main` → `body` | Không có thuật toán. Site không phải NukeViet thì lấy **cả `body`** (menu, footer, sidebar lọt vào index). |
| Tiêu đề | `headline` → `og:title` → `<title>` | Không cắt hậu tố tên site; không thử `h1`, JSON-LD. |
| Ngày đăng | `datePublished` → `rec["lastmod"]` (kho thô **không có** `lastmod`, nhánh này gần như luôn rỗng) | Không có `article:published_time`, `<time>`, regex ngày VN. |
| Tác giả | **Không có** — `extract()`, `PublicDocument`, Lucene đều không có trường này | Toàn bộ. |
| Nguồn giới thiệu | Không | Toàn bộ (chỉ có chiều đi ra). |
| Đồ thị liên kết | `outgoing_links()` — `{url, text}` trong khối nội dung; README ghi rõ "chỉ phục vụ hiển thị, chưa đưa vào chỉ mục" | Không lưu, không có chiều ngược, không có ảnh, url không qua `crawl_all.norm()`. |
| Tệp tài liệu | Không (README mục 10: "Không tải file đính kèm") | Tải, bóc chữ, index. |
| Tệp ảnh | `don_html()` giữ `img src` cho xem trước, nhưng không lưu thành dữ liệu | Danh mục ảnh + nguồn. |
| MongoDB | Không. Lưu trữ duy nhất là index Lucene (volume `lucene-index`) | Toàn bộ. |
| Test | `tests/test_api.py` 5 test, `IndexTest.java` 29 test, `integration.sh` 33 kiểm tra | Test cho mọi phần mới. |

Điểm thuận lợi đã có sẵn:
- `api` mount `../hust-crawler:/crawler` và `PYTHONPATH=/crawler` → import được
  `crawl_all.norm()` (quy tắc CLAUDE.md: mọi url phải qua `norm()`).
- `api` mount `./api:/app` → sửa code Python không cần build lại image
  (nhưng **thêm thư viện** vào `requirements.txt` thì phải `docker compose build api`).
- `_alive()` biết crawler có đang chạy không → dùng để chặn tải tệp chạy chồng.

---

## 2. Các quyết định đã chốt (30/9)

| Câu hỏi | Chốt |
|---|---|
| "Nguồn giới thiệu" nghĩa là gì | **Trang nào trỏ tới đối tượng này, bằng dòng chữ gì** — tức các cạnh `nguồn --> X` trong đồ thị. Dòng "Nguồn: …" trong bài chỉ bóc thêm thành trường phụ `cited_source`. |
| Có dùng MongoDB | **Có.** Không bắt buộc nhưng chọn dùng. |
| Tải tệp ở host ngoài (Google Drive…) | **Không.** Chỉ ghi cạnh trỏ tới, không tải, không bóc chữ. |
| OCR PDF scan | **Không.** Vẫn gắn cờ `needs_ocr` để biết tệp nào không có lớp chữ. |
| Đồ thị gồm những cạnh nào | **Chưa chốt** — xem giải thích và đề xuất ở mục 5.4. |

---

## 3. Kiến trúc mới của `hust-search`

```
/crawler/data/raw*/pages-*.jsonl.gz          (kho thô của crawler, chỉ đọc)
        │
        │  api: boc_tach/  (khối nội dung, trường, liên kết, ảnh)
        ▼
   MongoDB  pages · links · images · documents          ◄── api: /api/files/* tải + bóc chữ tệp
        │
        │  api: /api/index/run  (đọc từ Mongo thay vì bóc lại HTML)
        ▼
   Lucene   kind = page | document, thêm field author
        │
        ▼
   Giao diện: tìm kiếm · xem trước + "được giới thiệu bởi" · tab Đồ thị · tab Tệp
```

MongoDB thành tầng lưu kết quả bóc tách, nằm giữa kho thô và Lucene. Lucene vẫn
chỉ lo index và tìm; không phải bóc lại HTML mỗi lần index lại.

### 3.1. File thay đổi / thêm mới

```
hust-search/
  docker-compose.yml          + service mongo (image chính thức, volume mongo-data)
  api/
    requirements.txt          + pymongo; + pdfminer.six, python-docx, openpyxl, python-pptx
    main.py                   extract() gọi boc_tach; route mới (mục 7)
    db.py                     kết nối Mongo, tạo $jsonSchema validator + index lúc khởi động
    boc_tach/
      __init__.py
      khoi.py                 thuật toán tìm khối nội dung            (mục 4)
      truong.py               tiêu đề / ngày / tác giả kèm nguồn gốc  (mục 5.1)
      lien_ket.py             cạnh a[href] + img[src] qua norm()      (mục 5.3, 5.4)
      tep.py                  bóc chữ pdf/docx/xlsx/pptx               (mục 5.2)
      khuon.py                bảng tần suất khối lặp theo host        (mục 4.2 lớp 2)
    static/index.html         tab Đồ thị liên kết, tab Tệp, khối "Được giới thiệu bởi"
  lucene/src/main/java/vn/hust/search/
    Index.java                + field author (TextField), kind (StringField, lọc được)
    SearchServer.java         + tham số kind cho /search, /list
  lucene/src/test/.../IndexTest.java   + test field mới
  tests/
    test_boc_tach.py          test thuật toán + trường
    fixtures/html/            20-50 trang mẫu nhiều host (gzip)
    fixtures/nhan_khoi/       nhãn tay khối nội dung cho trang subdomain
    danh_gia_khoi.py          script đo P/R/F1 (mục 4.3)
    integration.sh            + kiểm tra Mongo, đồ thị, tệp
  SCHEMA.md                   mô tả lược đồ Mongo (phần "mô tả" của đề)
```

`hust-crawler/`: **không sửa**. `crawl_hust.py: parse_article()` vẫn là bản
riêng của crawler; không kéo nó vào đây.

---

## 4. Thuật toán tìm khối nội dung (`api/boc_tach/khoi.py`)

### 4.1. Yêu cầu

- Chạy trên mọi host trong họ `hust.edu.vn` (CMS khác nhau), không cần selector
  riêng từng site.
- Trả về: nút DOM được chọn (đường dẫn CSS), điểm, văn bản sạch, HTML sạch (dùng
  lại `don_html()`), `method` = `selector` / `heuristic` / `fallback`.
- Có số đo định lượng.

### 4.2. Thuật toán — ba lớp

Đây là **đề xuất thiết kế**, ghép hai họ ý tưởng đã có trong tài liệu:
mật độ chữ / mật độ link trên DOM (CETD — Sun, Song, Liao, SIGIR 2011;
Boilerpipe — Kohlschütter và cs., WSDM 2010) và khử khuôn mẫu theo cả site
(Site Style Tree — Yi, Liu, Li, KDD 2003). Tên bài báo ghi theo trí nhớ, kiểm
lại trước khi trích dẫn.

**Lớp 1 — dọn cây.** Bỏ `script style noscript iframe form svg button input
select`, thẻ `hidden` / `style="display:none"`, comment.

**Lớp 2 — khử khuôn theo host (`khuon.py`).** Kho có nhiều trang mỗi host nên
đếm được khối nào lặp:
1. Mỗi trang lấy các khối lá; vân tay = sha1 văn bản đã chuẩn hoá (thường hoá,
   gộp khoảng trắng, số → `0`).
2. Đếm theo host. Khối có mặt trên > θ₁ số trang (khởi điểm 30%, **dò lại**) →
   `template`, bỏ trước khi chấm điểm.
3. Host < 20 trang thì bỏ qua lớp này — không đủ mẫu.

Lưu bảng đếm vào Mongo collection `templates` (một document mỗi host), dựng một
lần qua `POST /api/extract/templates`, dùng lại cho `/api/fetch` lẻ.

**Lớp 3 — chấm điểm nút.** Với mỗi ứng viên (`div section article main td`):

| Đặc trưng | Ý nghĩa |
|---|---|
| `C` | số ký tự văn bản |
| `LC` | số ký tự trong `<a>` |
| `P` | số đoạn `<p>` có ≥ 1 câu |
| `Q` | số dấu câu `. , ; : ? !` |

```
mật_độ_link = LC / max(C, 1)
điểm(n) = (C - LC) · (1 - mật_độ_link)^α + β·P + γ·Q
          × phạt nếu class/id khớp nav|menu|footer|header|sidebar|comment|share|related|breadcrumb|banner|widget
          × thưởng nếu khớp content|article|post|entry|detail|bodytext
```

Chọn nút điểm cao nhất, nới sang anh em cùng cha có điểm ≥ δ·điểm_max và mật độ
link thấp. α, β, γ, δ **dò trên tập đánh giá**.

**Thứ tự áp dụng trong `extract()`:** host có selector đã kiểm chứng (`.bodytext`
cho hust.edu.vn) → `method="selector"`; không có hoặc selector trả rỗng →
heuristic; heuristic thất bại → `body` với `method="fallback"`.

### 4.3. Đánh giá (`tests/danh_gia_khoi.py`)

- **Nhãn vàng miễn phí:** bài hust.edu.vn có `.bodytext` → chạy heuristic *giả
  vờ không biết selector* rồi so.
- **Nhãn tay:** 20-30 trang subdomain khác CMS, lưu `tests/fixtures/nhan_khoi/`.
- **Độ đo:** precision / recall / F1 trên túi âm tiết.
- **Mốc so:** (i) cả `body` — đúng hành vi hiện tại của `extract()`,
  (ii) chỉ lớp 3, (iii) lớp 2 + 3. Tuỳ chọn so thêm `trafilatura` làm tham chiếu
  ngoài, không thay phần tự cài.
- **Đo ảnh hưởng tới tìm kiếm:** index hai bản (cũ / mới) rồi so kết quả một bộ
  truy vấn cố định (vd. các truy vấn đang có trong `integration.sh`) — xem menu
  còn làm nhiễu kết quả không. Đây là lý do đặt thuật toán ở `hust-search`.

---

## 5. Yêu cầu bóc tách từng loại

### 5.1. Trang (`truong.py`)

Mỗi trường là chuỗi ưu tiên, lưu `*_src` (lấy từ đâu) để đo và báo cáo.

| Trường | Chuỗi ưu tiên |
|---|---|
| `title` | `headline` → `og:title` → JSON-LD → `h1` gần khối nội dung → `<title>` cắt hậu tố site (`" - "`, `" \| "`) |
| `published_at` | `datePublished` → `meta[article:published_time]` → JSON-LD → `<time datetime>` → regex `dd/mm/yyyy [hh:mm]` ngay trên khối nội dung → `null` |
| `author` | microdata `author` → `meta[name=author]` → JSON-LD `author.name` → dòng cuối khối `Tác giả:` / `Người viết:` / `Tin, ảnh:` / `Bài, ảnh:` (cắt khỏi content) → `null` |
| `content` | văn bản khối nội dung + HTML sạch |
| `cited_source` | dòng `Nguồn:` / `Theo …` (cách hiểu B) |
| nguồn giới thiệu | không lưu trong trang — truy vấn `links` theo `dst` (mục 5.4) |

Đầu ra thêm: bảng % trường khác `null` theo host (`GET /api/extract/coverage`).

### 5.2. Tệp tài liệu (`tep.py` + route `/api/files/*`)

**Danh mục (offline, từ HTML đã có):** `a[href]` đuôi `pdf doc docx xls xlsx
ppt pptx`; url có `download=1`; bản ghi kho thô có `html_b64 = null` và
`content_type` là pdf/word/excel (byte chưa được lưu). **Chỉ tải host
`*.hust.edu.vn`**; host ngoài (Google Drive…) chỉ ghi cạnh, `dst_kind = "external"`.

**Tải — `POST /api/files/fetch` (chạy nền, cùng kiểu `/api/crawl/start`):**
- **từ chối (409) khi crawler đang chạy** (`_alive()`), và ngược lại
  `/api/crawl/start` từ chối khi đang tải tệp — hai tiến trình mỗi bên 2,5 s là
  ~48 req/phút, gấp đôi ngưỡng chặn;
- nhịp ≥ 2,5 s, 429 thì lùi theo `Retry-After`;
- trần kích thước (vd. 50 MB), ghi `skipped_too_large`;
- byte lưu `/crawler/data/files/<sha1>.<đuôi>` (thư mục đã gitignore, cùng chỗ
  với kho); chạy lại thì bỏ qua url đã có trong Mongo.

Ước lượng: 197 url của host chính ≈ 8 phút (theo README crawler). Subdomain
chưa biết.

**Bóc chữ:**

| Loại | Thư viện | Ghi chú |
|---|---|---|
| pdf | `pdfminer.six` | PyMuPDF nhanh hơn nhưng AGPL |
| docx / xlsx / pptx | `python-docx` / `openpyxl` / `python-pptx` | |
| doc/xls/ppt cũ | bỏ, `status="unsupported"` | LibreOffice headless làm image nặng thêm nhiều |

Cờ rủi ro: `needs_ocr` (PDF scan, ≈ 0 ký tự/trang), `encoding_suspect` (bảng mã
cũ TCVN3/VNI, tỉ lệ ký tự có dấu hợp lệ thấp bất thường). **Không làm OCR** —
tệp `needs_ocr` vẫn có bản ghi và nguồn giới thiệu, chỉ không có `text`.

**Tìm được:** tệp có chữ được index vào Lucene với `kind="document"`.

### 5.3. Ảnh (`lien_ket.py`)

Không tải ảnh — đề chỉ cần nguồn giới thiệu. Bóc offline: `img[src]`,
`img[data-src]`, `og:image`, microdata `image`. Văn bản mô tả: `alt` → `title`
→ `figcaption`. Ảnh trong thân bài → cạnh `embed` ở `links`; logo/icon lặp khắp
host (đường dẫn `/themes/`, `width/height ≤ 16`, hoặc lặp theo lớp 2) → `nav_links`
và `images.is_template = true` — đánh dấu, không xoá.

### 5.4. Đồ thị liên kết (`lien_ket.py`)

#### Đồ thị biểu diễn cái gì

- **Nút** = một url: trang, tệp tài liệu, ảnh, hoặc trang ở site ngoài.
- **Cạnh** `A --> B : "chữ"` = trang A có một thẻ `<a href=B>chữ</a>` (hoặc
  `<img src=B alt="chữ">`). "Văn bản mô tả" là dòng chữ người viết trang A dùng
  để giới thiệu B.

Ví dụ **minh hoạ** (url và chữ bịa cho dễ hình dung, không lấy từ kho thật):

```
                              menu "Tuyển sinh"  (có trên MỌI trang)
      mọi trang hust.edu.vn ─────────────────────────────► /vi/tuyen-sinh/

 /vi/tuyen-sinh/  ──"Điểm chuẩn năm 2026"──►  .../diem-chuan-...-656013.html
                                                     │
                   ┌──"Xem chi tiết tại đây"─────────┤  (trong thân bài)
                   ▼                                 │
       /uploads/.../diem-chuan-2026.pdf              │
                                                     └──(img alt="Lễ khai giảng")──► /uploads/.../anh1.jpg
```

Đọc ngược mũi tên là ra **nguồn giới thiệu**:
- `diem-chuan-2026.pdf` được giới thiệu bởi bài điểm chuẩn, bằng chữ "Xem chi tiết tại đây";
- bài điểm chuẩn được giới thiệu bởi trang `/vi/tuyen-sinh/`, bằng chữ "Điểm chuẩn năm 2026";
- `/vi/tuyen-sinh/` được giới thiệu bởi menu của cả site.

Nghĩa là **đồ thị chính là cách tính "nguồn giới thiệu"** cho cả ba loại (trang,
tệp, ảnh) — không phải một việc riêng rẽ.

#### Hai loại cạnh khác hẳn nhau về ý nghĩa

| | Cạnh nội dung | Cạnh khuôn (menu, footer, sidebar) |
|---|---|---|
| Nằm ở | trong khối nội dung (mục 4) | ngoài khối, lặp trên nhiều trang |
| Ý nghĩa | người viết bài **chủ động** giới thiệu B | cấu trúc điều hướng của site |
| Số lượng | vài đến vài chục cạnh mỗi trang | cả trăm link mỗi trang × mọi trang |
| Ví dụ | bài → tệp PDF đính kèm | mọi trang → "Tuyển sinh" |

Nếu lưu cạnh khuôn theo từng trang thì mỗi link menu thành hàng nghìn cạnh giống
hệt nhau: "nguồn giới thiệu" của `/vi/tuyen-sinh/` sẽ là một danh sách hàng
nghìn trang, không ai đọc được. Quy mô thô có thể lên hàng triệu cạnh — **ước
lượng, chưa đo**.

#### Đề xuất (chờ xác nhận)

Lưu hai tầng:

1. **`links` — cạnh nội dung, giữ từng cạnh:** `src` (trang), `dst`, `type`
   (`href` / `embed`), `text`, `dst_kind` (`page` / `document` / `image` /
   `external`), `count`, `src_host`, `dst_host`.
2. **`nav_links` — cạnh khuôn, gộp mỗi host một bản:** `host`, `dst`, `text`,
   `n_pages` (xuất hiện trên bao nhiêu trang), `sample_src` (vài trang ví dụ).
   Nguồn giới thiệu của `/vi/tuyen-sinh/` khi đó đọc được: *"menu của
   hust.edu.vn, chữ 'Tuyển sinh', có trên 1.980 trang"* (số minh hoạ).

Cạnh được xếp vào tầng nào là nhờ thuật toán khối nội dung (mục 4) và bảng khối
lặp (lớp 2) — thêm một lý do để làm mục 4 trước.

Mọi url qua `crawl_all.norm()`. Văn bản mô tả: chữ `<a>` → `title` →
`aria-label` → `alt` của ảnh con; với ảnh là `alt` → `title` → `figcaption`.

`outgoing_links` trong schema public = cạnh `href` trong `links` của trang đó →
giữ tương thích test cũ.

#### Đồ thị dùng để làm gì

- **Nguồn giới thiệu** cho trang / tệp / ảnh (yêu cầu chính của đề).
- **Tệp và ảnh nào được giới thiệu nhiều nhất**, trang nào trỏ tới nhiều tệp nhất.
- **Liên kết giữa các site:** subdomain nào trỏ sang subdomain nào (gộp nút theo host).
- **Tuỳ chọn cho tìm kiếm:** chữ mô tả của cạnh trỏ vào là một cách người khác
  "gọi tên" trang đó — index thêm làm trường phụ (anchor text); bậc vào / PageRank
  làm tín hiệu cho `ranking=enhanced`.
- **Xuất** `GET /api/graph/edges.csv` (`source,target,text`) để vẽ bằng Gephi /
  networkx.

---

## 6. Lược đồ MongoDB (bản nháp — viết đầy đủ trong `SCHEMA.md`)

```js
pages {
  _id: "<url chính>",            // qua norm(); bài dùng url chính theo dedup_key
  aliases: [url], host, lang, kind,
  title, title_src, published_at, published_at_src, modified_at,
  author, author_src, cited_source,
  content: { text, html, word_count, block: { path, score, method } },
  section, breadcrumb: [str], via,
  raw: { sha1, fetched_at },        // truy ngược về kho thô
  extractor_version, extracted_at
}
documents {
  _id: "<url>", host, ext, mime, size, sha1, status,
  text, n_pages, needs_ocr, encoding_suspect, error, fetched_at, extractor_version
}
images    { _id: "<url>", host, alts: [str], is_template }
links     { _id: sha1(src|dst|type|text), src, dst, type, text, dst_kind,
            count, src_host, dst_host }                  // cạnh nội dung
nav_links { _id: sha1(host|dst|type|text), host, dst, type, text, dst_kind,
            n_pages, sample_src: [url] }                  // cạnh khuôn, gộp theo host
templates { _id: "<host>", n_pages, blocks: { <vân tay>: số trang } }
```

Ràng buộc bằng `$jsonSchema` (phần "tự xây dựng lược đồ"), tạo trong `db.py`
lúc `api` khởi động. Index: `links {dst:1}`, `links {src:1}`, `nav_links {dst:1}`,
`pages {host:1, published_at:-1}`.

Nguồn giới thiệu của bất kỳ trang / tệp / ảnh nào:

```js
db.links.find({ dst: "<url>" }, { src: 1, text: 1 })            // ai giới thiệu trong bài
db.nav_links.find({ dst: "<url>" }, { host: 1, text: 1, n_pages: 1 })  // menu nào trỏ tới
```

Nguồn sự thật là `links` + `nav_links`, không chép mảng `referrers` vào từng bản
ghi — nạp lại không bị lệch. Ghi bằng upsert theo `_id` nên chạy lại không nhân đôi.

`docker-compose.yml`: thêm service `mongo` (image `mongo`, volume `mongo-data`,
healthcheck), `api` thêm `depends_on: mongo` và biến `MONGO_URL`. Không mở cổng
Mongo ra ngoài nếu không cần (stack hiện đã không có xác thực — README mục 10).

---

## 7. API và giao diện

| Method | Đường dẫn | Việc |
|---|---|---|
| POST | `/api/extract/templates` | dựng bảng khối lặp theo host (lớp 2) |
| POST | `/api/extract/run` | kho thô → `boc_tach` → Mongo (`pages`, `links`, `images`), chạy nền |
| GET | `/api/extract/status` | tiến độ |
| GET | `/api/extract/coverage` | % trường đầy đủ theo host, tỉ lệ `method` |
| POST | `/api/files/fetch` | tải tệp tài liệu, chạy nền, 409 nếu crawler đang chạy |
| POST | `/api/files/extract` | bóc chữ tệp đã tải → `documents` |
| GET | `/api/referrers?url=` | nguồn giới thiệu (cạnh đi vào) của trang / tệp / ảnh |
| GET | `/api/graph/out?url=` | cạnh đi ra |
| GET | `/api/graph/stats` | số nút, số cạnh, top bậc vào |
| GET | `/api/graph/edges.csv` | xuất đồ thị |
| POST | `/api/index/run` | **đổi:** đọc từ Mongo (`pages` + `documents`) thay vì bóc HTML |

Giữ nguyên hợp đồng cũ: `PublicDocument` chỉ **thêm** trường có mặc định
(`author`, `kind`), nên `tests/fixtures/corpus.json` và `integration.sh` cũ vẫn
chạy. `/api/fetch` lẻ ghi thêm vào Mongo.

Lucene (`Index.java`): thêm `author` (TextField, lưu), `kind` (StringField +
DocValues, lọc được). Đổi schema → `docker compose build lucene` và index lại
(README mục 9).

Giao diện (`static/index.html`):
- kết quả tìm: hiện tác giả, ngày, nhãn `Trang`/`Tệp`; bộ lọc `kind`;
- xem trước: khối **"Được giới thiệu bởi"** (gọi `/api/referrers`);
- tab **Đồ thị liên kết**: nhập url → bảng cạnh vào / ra kèm văn bản mô tả;
- tab **Tệp & ảnh**: danh sách tệp (trạng thái, cờ `needs_ocr`) và ảnh, mỗi dòng có nguồn.

---

## 8. Vì sao MongoDB (đã chọn)

| | MongoDB | Chỉ Lucene như hiện nay | PostgreSQL | Neo4j |
|---|---|---|---|---|
| Bản ghi lồng nhau, trường khác nhau giữa site | tự nhiên | lưu được nhưng không truy vấn cấu trúc | JSONB | vụng |
| Ràng buộc lược đồ | `$jsonSchema` | không | mạnh nhất | yếu |
| Truy vấn đồ thị nhiều bước | `$graphLookup`, hạn chế | không | CTE đệ quy | mạnh nhất |
| Thêm vào stack | 1 service | 0 | 1 service | 1 service |

Lucene không hợp để lưu quan hệ "ai trỏ tới ai", nên cần thêm một kho. Truy vấn
cần cho đề bài (nguồn giới thiệu = cạnh đi vào một bước, thống kê bậc, xuất
CSV) đều là truy vấn một bước — MongoDB làm tốt. Phân tích nhiều bước (đường đi,
PageRank) nếu cần thì xuất CSV sang networkx, không đòi hỏi đổi kho.

---

## 9. Thứ tự làm

Ước lượng thô cho một người, chưa tính gỡ lỗi trên dữ liệu thật.

| # | Việc | Phụ thuộc | Ước lượng | Xong khi |
|---|---|---|---|---|
| 0 | Lấy 20-50 trang mẫu nhiều host vào `tests/fixtures/html/`; tách `extract()` thành gói `boc_tach` không đổi hành vi | — | 0,5 ngày | 5 test cũ của `test_api.py` vẫn xanh |
| 1 | Thuật toán khối nội dung + `danh_gia_khoi.py` | 0 | 2-3 ngày | bảng P/R/F1 ba mốc |
| 2 | Bóc trường trang (tác giả, ngày…) | 1 | 1 ngày | bảng coverage theo host |
| 3 | Mongo: service, `db.py`, validator, `SCHEMA.md`, `/api/extract/*` | 0 (song song với 1) | 1 ngày | chạy `extract/run` hai lần không nhân đôi |
| 4 | Đồ thị + ảnh, `/api/referrers`, `/api/graph/*` | 1, 3 | 1 ngày | `edges.csv`, thống kê |
| 5 | `/api/index/run` đọc từ Mongo; Lucene thêm `author`, `kind` | 2, 3 | 1 ngày | JUnit + integration xanh |
| 6 | Tệp: danh mục → tải (không chồng crawler) → bóc chữ → index `kind=document` | 3, 4 | 1-1,5 ngày + thời gian tải | `documents`, tỉ lệ `needs_ocr` |
| 7 | Giao diện: referrers, tab Đồ thị, tab Tệp & ảnh | 4, 5, 6 | 1 ngày | demo được |
| 8 | README `hust-search`, BAO-CAO, CLAUDE.md (số test, dịch vụ mới) | tất cả | 0,5 ngày | — |

Việc 1-5 làm được trên kho đang có (~2.070 trang). Khi crawler tải thêm bài /
subdomain thì chỉ cần chạy lại `extract/run` và `index/run`, không sửa code.

Test cần thêm (đặt tên theo lỗi thật như quy ước repo): khối chọn đúng
`.bodytext` trên fixture NukeViet khi tắt selector; không chọn nhầm menu trên
fixture subdomain; ngày `dd/mm/yyyy` → ISO; dòng "Tác giả:" bị cắt khỏi content;
cạnh `http://` và `https://` không tách đôi (qua `norm()`); `extract/run` hai lần
không nhân bản ghi; `/api/files/fetch` trả 409 khi crawler đang chạy;
`/api/referrers` trả đúng trang nguồn và văn bản mô tả.

---

## 10. Còn cần chốt

1. Đồ thị lưu hai tầng `links` (cạnh nội dung, từng cạnh) + `nav_links` (cạnh
   menu/footer, gộp theo host) như mục 5.4 — đồng ý không?
