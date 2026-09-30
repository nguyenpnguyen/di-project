# Lược đồ MongoDB (phần "tự xây dựng lược đồ + mô tả" của đề)

Ràng buộc `$jsonSchema` nằm ở `api/db.py` (`SCHEMAS`), được tạo lúc `api` khởi
động. Đây là những gì **đã làm**; phần tệp tài liệu (`documents`) mới có lược đồ,
chưa có mã tải/bóc chữ. Chưa kiểm chứng validator trên MongoDB thật (test dùng
mongomock, không thực thi `$jsonSchema`).

Luồng: `kho thô → boc_tach → Mongo`. Chạy lại `POST /api/extract/run` không nhân
đôi bản ghi (`pages` ghi theo `_id`; `links` xoá theo `src` rồi ghi lại;
`nav_links`, `images` dựng lại từ đầu).

## pages — một bài / trang
| Trường | Ý nghĩa |
|---|---|
| `_id` | url đã qua `crawl_all.norm()`; bài trùng theo `dedup_key()` gộp về url gặp đầu tiên |
| `aliases` | các url khác của cùng bài |
| `host`, `lang`, `kind` | host; `vi`/`en` theo tiền tố `/en/`; `kind_of()` của crawler |
| `title`, `title_src` | tiêu đề và nguồn lấy: `headline`, `og:title`, `json-ld`, `h1`, `title` |
| `published_at`, `published_at_src` | `YYYY-MM-DD` hoặc rỗng; nguồn: `datePublished`, `article:published_time`, `json-ld`, `time`, `regex` |
| `author`, `author_src` | `microdata`, `meta`, `json-ld`, `text-line` (dòng "Tác giả:" cuối bài); bỏ giá trị chung như `admin` |
| `cited_source` | dòng "Nguồn: …" / "Theo …" cuối bài (trường phụ) |
| `content.text/html/word_count` | văn bản khối nội dung, HTML rút gọn (≤ 40 KB), số từ |
| `content.block` | `path` (đường CSS), `score`, `method` = `selector` / `heuristic` / `fallback` |
| `raw.sha1/fetched_at` | truy ngược về bản ghi kho thô |
| `extractor_version`, `extracted_at` | phiên bản bộ bóc tách và thời điểm chạy |

## links — cạnh nội dung `src --> dst : text`
Cạnh nằm **trong** khối nội dung: người viết chủ động giới thiệu `dst`.
`_id = sha1(src|dst|type|text)`; `type` = `href` (thẻ `a`) hoặc `embed` (`img`,
`og:image`); `dst_kind` = `page | document | image | external` (host ngoài họ
`hust.edu.vn` luôn là `external`); `count` số lần lặp trong trang; `src_host`, `dst_host`.

## nav_links — cạnh khuôn, gộp theo host
Cạnh **ngoài** khối nội dung (menu, footer, sidebar) và ảnh logo/icon (đường dẫn
`/themes/`, `/templates/`, `/assets/` hoặc rộng/cao ≤ 16 px). Mỗi `(host, dst, type,
text)` một bản ghi với `n_pages` (số trang có cạnh này) và `sample_src` (≤ 3 trang ví dụ).

## images — danh mục ảnh
`_id` = url ảnh, `alts` = chữ mô tả từng gặp trong bài, `is_template` = true nếu
chỉ xuất hiện ở cạnh khuôn. Ảnh không được tải.

## templates — bảng khối lặp theo host (lớp 2 của thuật toán khối)
`{_id: host, n_pages, blocks: {vân tay: số trang}}`. Chỉ giữ khối có mặt trên
≥ max(3, 5% số trang) để document không vượt 16 MB. Host < 20 trang thì
không dùng để khử khuôn.

## documents — tệp tài liệu (CHƯA có mã)
`_id` url, `ext`, `mime`, `size`, `sha1`, `status`, `text`, `n_pages`,
`needs_ocr`, `encoding_suspect`, `error`, `fetched_at`, `extractor_version`.

## Truy vấn "nguồn giới thiệu"
```js
db.links.find({dst: "<url>"}, {src: 1, text: 1})                        // trong bài
db.nav_links.find({dst: "<url>"}, {host: 1, text: 1, n_pages: 1})       // menu / footer
```
API: `GET /api/referrers?url=` (tự đổi bí danh về trang chính), `/api/graph/out`,
`/api/graph/stats`, `/api/graph/edges.csv`, `/api/extract/coverage`.

Index: `links{dst}`, `links{src}`, `nav_links{dst}`, `pages{host,published_at}`, `pages{aliases}`.
