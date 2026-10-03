# Báo cáo kỹ thuật: hệ thống Crawl + Bóc tách + Tìm kiếm hust.edu.vn

Môn Tích hợp dữ liệu — IT5420. Tài liệu này mô tả **công nghệ dùng** và **cách
hệ thống hoạt động ở mức code**, dùng để báo cáo kỹ thuật. Nguồn là mã nguồn
trong `hust-crawler/` và `hust-search/`. Chỗ nào là ước lượng hay chưa kiểm
chứng thì ghi rõ ngay tại chỗ.

Sơ đồ viết bằng **Mermaid**: GitHub tự vẽ khi xem file. Trình soạn thảo không
hỗ trợ Mermaid thì dán khối code vào https://mermaid.live để xem.

**Mục lục**

1. [Tổng quan kiến trúc](#1-tổng-quan-kiến-trúc)
2. [Crawl](#2-crawl--công-nghệ-và-cách-hoạt-động)
3. [Bóc tách nội dung](#3-bóc-tách-nội-dung--khối-trường-đồ-thị)
4. [Tệp tài liệu và ảnh](#4-tệp-tài-liệu-và-ảnh)
5. [Lưu trữ MongoDB](#5-lưu-trữ-mongodb)
6. [Đánh chỉ mục và tìm kiếm (Lucene)](#6-đánh-chỉ-mục-và-tìm-kiếm--lucene)
7. [Tầng API và giao diện](#7-tầng-api-và-giao-diện)
8. [Kiểm thử](#8-kiểm-thử)
9. [Giới hạn đã biết](#9-giới-hạn-đã-biết)
10. [Bản đồ file](#10-bản-đồ-file-để-tra-cứu-nhanh)

---

## 1. Tổng quan kiến trúc

Hệ thống gồm hai project, nối với nhau bằng file trên đĩa. `hust-crawler/` chạy
độc lập, không cần docker. `hust-search/` là một stack docker ba dịch vụ.

```mermaid
flowchart LR
    WEB(["hust.edu.vn<br/>+ subdomain"])

    subgraph CR["hust-crawler/ (Python, chạy độc lập)"]
        CA["crawl_all.py<br/>nhịp tự dò, robots.txt"]
        RD["render.py<br/>Chromium khi requests hụt"]
        RR["read_raw.py<br/>soát kho"]
    end

    KHO[("data/raw*/<br/>pages-*.jsonl.gz<br/>HTML thô base64")]

    subgraph ST["hust-search/ (docker compose)"]
        direction TB
        API["api — FastAPI, cổng 8000<br/>boc_tach · trich · tep_job"]
        MG[("mongo<br/>không mở cổng")]
        LC["lucene — Java 21 + Lucene 9.11<br/>cổng 8081"]
    end

    UI["Trình duyệt<br/>static/index.html"]
    TEP[("data/files/<br/>tệp pdf/docx…")]

    WEB -->|"HTTP, ≤ 25 req/phút"| CA
    CA -.->|"trang dựng bằng JS"| RD
    CA --> KHO
    RR -.->|"đọc, đối chiếu"| KHO
    KHO -->|"mount /crawler, chỉ đọc"| API
    API -->|"pages, links, nav_links,<br/>images, documents, templates"| MG
    MG -->|"/api/index/run"| API
    API -->|"POST /bulk"| LC
    API -->|"tải tệp, nhịp 2,5 s"| WEB
    API --> TEP
    UI <-->|"REST JSON"| API
    API <-->|"/search, /doc"| LC
```

**Nguyên tắc xuyên suốt: tách phần đắt (tải trang, bị chặn nhịp) khỏi phần rẻ
và hay phải sửa (bóc tách, đánh chỉ mục, xếp hạng).** Crawler chỉ ghi HTML thô,
không parse. Bóc tách, ghi Mongo và index có thể chạy lại bao nhiêu lần cũng
không tốn thêm request nào tới hust.edu.vn.

Dữ liệu đi qua bốn tầng, mỗi tầng một dạng:

```mermaid
flowchart LR
    A["HTML thô<br/>(kho JSONL)"] -->|"boc_tach"| B["Bản ghi có cấu trúc<br/>(MongoDB)"]
    B -->|"lucene_tu_mongo"| C["Tài liệu index<br/>(Lucene)"]
    C -->|"search + highlight"| D["Kết quả<br/>(JSON → giao diện)"]
    B -->|"referrers, graph"| D
```

**Phân chia theo ngôn ngữ:**

| Ngôn ngữ | Việc | Vì sao |
|---|---|---|
| Python (`requests`, `BeautifulSoup4`, `lxml`) | Crawl thô | Kiểm soát chính xác nhịp gọi; khử trùng đặc thù NukeViet |
| Python (`BeautifulSoup4`, `pymongo`, `pdfminer.six`, `python-docx`…) | Bóc tách HTML, đồ thị liên kết, bóc chữ tệp, ghi Mongo | Cùng ngôn ngữ với crawler, dùng chung `crawl_all.norm()` / `dedup_key()` |
| Java 21 (`Lucene 9.11` core, không Elasticsearch/Solr) | Đánh chỉ mục, tìm, xếp hạng, highlight | Đề bài yêu cầu dùng Lucene thuần |
| Python (FastAPI) | Điều phối: gọi crawler, chạy job nền, gọi Lucene, phục vụ giao diện | Lớp mỏng, không chứa logic tìm kiếm |

---

## 2. Crawl — công nghệ và cách hoạt động

### 2.1. Công nghệ dùng

| Thành phần | Công nghệ | File |
|---|---|---|
| Tải trang (đường chính) | `requests` (Session + connection pool) | `crawl_all.py` |
| Parse HTML để tìm link/phân trang | `BeautifulSoup4` + `lxml` | `crawl_all.py` |
| Tuân thủ robots.txt | `urllib.robotparser.RobotFileParser` | `crawl_all.py` |
| Lưu trữ thô | JSON Lines nén `gzip`, chia shard theo số dòng | `Store` trong `crawl_all.py` |
| Trạng thái/resume | `state.json` | `crawl_all.py` |
| Render JS khi `requests` lấy hụt | **Playwright** (Chromium headless) | `render.py` |
| Wrapper có parse (17 trường) | `BeautifulSoup4` + microdata schema.org | `crawl_hust.py` |
| Đọc/soát kho | thuần Python | `read_raw.py` |

Không dùng framework crawl có sẵn (Scrapy…) vì hai lý do: cần kiểm soát chính
xác nhịp gọi (site chặn khắt khe, xem 2.4) và cần logic khử trùng đặc thù của
NukeViet (2.5).

### 2.2. Cấu trúc site mục tiêu

hust.edu.vn chạy **NukeViet 4** (nhận diện qua cookie `nv4s_*`).

| Nguồn | Vị trí | Đặc điểm |
|---|---|---|
| Sitemap | `/sitemap.xml` → 34 sitemap con theo chuyên mục × ngôn ngữ | có `<lastmod>`, nhưng bị cắt ở 1000 url cho `news`, 7 sitemap rỗng |
| Trang danh mục | `/vi/news/<slug>/page-N/`, 6 bài/trang | `div.news_column div.panel-body` |
| Metadata bài | microdata `[itemprop=headline\|author\|datePublished\|dateModified\|image]` | gắn với dữ liệu nên ít đổi theo giao diện |
| Nội dung bài | `div.bodytext` | |
| Khoá bài (sai lầm ban đầu) | đuôi url `...-654601.html` | **không duy nhất** — xem 2.5 |

### 2.3. Vòng đời crawl (BFS có ưu tiên)

Lớp `Crawler` giữ ba cấu trúc lõi:

```python
self.frontier: collections.deque   # hàng đợi (url, depth, via) chưa tải
self.queued:   set[str]            # MỌI url đã từng vào hàng đợi — chống lặp
self.done:     dict[str, int]      # url -> HTTP status đã tải
```

**`push(url, depth, via)`** là cổng vào duy nhất của hàng đợi:

```mermaid
flowchart TD
    IN(["push(url, depth, via)"]) --> N["u = norm(url)<br/>http→https, bỏ www, bỏ fragment…"]
    N --> S{"u hợp lệ, trong phạm vi<br/>và chưa có trong queued?"}
    S -- không --> X1(["bỏ"])
    S -- có --> L{"--lang khác all và<br/>u thuộc ngôn ngữ kia?"}
    L -- có --> X1
    L -- không --> R{"robots.txt cho phép?"}
    R -- không --> X1
    R -- có --> K["key = dedup_key(u)<br/>(đoạn cuối đường dẫn)"]
    K --> D{"key đã gắn với<br/>url khác?"}
    D -- có --> AL["aliases[url_chính] += u<br/>queued += u<br/>KHÔNG tải"]
    D -- không --> Q["by_key[key] = u · queued += u<br/>origin.setdefault(u, via)"]
    Q --> O{"--only listing<br/>và u là bài?"}
    O -- có --> X2(["chỉ ghi nhận link,<br/>không vào hàng đợi"])
    O -- không --> P{"đúng loại được ưu tiên?<br/>(--prefer listing | article)"}
    P -- có --> F1["frontier.appendleft — đi trước"]
    P -- không --> F2["frontier.append — đi sau"]
```

Mặc định ưu tiên trang danh mục (phủ hết chuyên mục trước, danh sách url đầy
đủ sớm nhất). `--prefer article` đảo lại để có bài đọc được ngay nếu phải dừng
giữa chừng. Lý do có hai chế độ là bài học thực tế: một mẻ dở dang toàn trang
danh mục thì kho gần như chưa có bài nào.

**Vòng lặp chính `run()`:**

```mermaid
flowchart LR
    A{"frontier còn và<br/>chưa đủ --max-pages?"} -- có --> B["pop()"]
    B --> C["fetch(url)<br/>chờ lượt nhịp chung (2.4)"]
    C --> D["visit()<br/>phân loại, mở rộng link,<br/>sinh page-N (2.6)"]
    D --> E["store.add(rec)<br/>ghi 1 dòng, flush ngay (2.7)"]
    E --> F{"đủ --checkpoint trang?"}
    F -- có --> G["save_state()"] --> A
    F -- không --> A
    A -- không --> H(["đóng shard, ghi state"])
```

`visit()` luôn quét mọi `<a href>` của trang vừa tải để đẩy thêm url nội bộ —
"lưới vét cuối cùng": trang nào có người trỏ tới thì cuối cùng cũng được thăm.

### 2.4. Nhịp tự dò (rate limiting)

**Đo được:** hust.edu.vn chặn ở khoảng **20-25 request/phút**, vượt là HTTP 429
kèm `Retry-After: ~30`. Ngưỡng đo bằng cách bắn thử ở các nhịp khác nhau rồi đếm
số 429 — site không công bố.

**Lỗi ban đầu:** mỗi luồng tự `sleep` riêng khi dính 429 rồi ùa vào lại, nên lúc
nào cũng có một luồng đang "chịu phạt". Tốc độ thực chỉ 0,55 trang/s dù đặt
2,2 request/s. Profiler (`sample <pid>` trên macOS) cho thấy 100% luồng nằm trong
`time.sleep`.

**Cơ chế hiện tại:** một khoá `pace` chung cho mọi luồng, cùng hai biến chung
`_next_at` (lượt kế tiếp) và `_pause_until` (nghỉ phạt).

```mermaid
sequenceDiagram
    participant W1 as Luồng 1
    participant P as Khoá nhịp chung
    participant W2 as Luồng 2
    participant S as hust.edu.vn
    W1->>P: _wait_turn()
    P-->>W1: tới lượt (delay ≈ 2,5 s ± 15%)
    W1->>S: GET trang A
    S-->>W1: 429, Retry-After 30
    W1->>P: _slow_down(): delay × 1,5<br/>_pause_until = now + 30
    W2->>P: _wait_turn()
    Note over P,W2: Luồng 2 cũng phải chờ hết 30 s —<br/>không ai "lách" vào lúc site đang phạt
    P-->>W2: tới lượt
    W2->>S: GET trang B
    S-->>W2: 200
    W2->>P: _speed_up(): yên 25 request liền<br/>thì delay × 0,9 (không dưới --delay)
```

429 không tính là lỗi của url (không mất bài, không tăng đếm lỗi), chỉ là tín
hiệu phải chờ. Kết quả đo: ổn định ~24 trang/phút, 0 lần 429 ở các mẻ gần nhất.
`--workers` mặc định 2 vì nhịp đã khoá chung — thêm luồng không nhanh hơn.

### 2.5. Khử trùng bài viết — bài toán entity resolution

NukeViet in cùng một bài dưới nhiều url tuỳ chuyên mục người dùng đang đứng
(`/su-kien-noi-bat/...` và `/khoa-hoc-cong-nghe-dmst/...`). Không khử trùng thì
~45% request là tải lại nội dung đã có.

**Giả định sai ban đầu:** dùng con số cuối url (`art_id()`, ví dụ `654601`) làm
khoá. Đo thực tế thấy **ba bài khác hẳn nhau** cùng mang đuôi `-654601.html` —
hậu quả 159 bài thật bị đánh dấu nhầm là bí danh và không được tải.

**Khoá đúng — `dedup_key()`:** lấy **đoạn cuối đường dẫn** (slug kèm số), bỏ phần
chuyên mục ở giữa. Kiểm chứng hai chiều bằng cách tải thật và so `sha1` phần
nội dung:

| Giả thuyết | Kiểm chứng | Kết quả |
|---|---|---|
| Cùng đoạn cuối, khác chuyên mục = một bài | so `sha1(bodytext)` | giống hệt → đúng |
| Cùng con số cuối, khác slug = một bài | so tiêu đề + độ dài | khác hẳn → sai |

```mermaid
flowchart LR
    U1["/vi/news/su-kien-noi-bat/<b>diem-chuan-654601.html</b>"] --> K1["dedup_key = diem-chuan-654601.html"]
    U2["/vi/news/tuyen-sinh/<b>diem-chuan-654601.html</b>"] --> K1
    U3["/vi/news/tin-tuc/<b>hoc-phi-654601.html</b>"] --> K2["dedup_key = hoc-phi-654601.html"]
    K1 --> B1["một bài: U1 là url chính,<br/>U2 vào aliases"]
    K2 --> B2["bài khác — dù cùng số 654601"]
```

Bài học đúng tinh thần entity resolution: **một trường trông như ID chưa chắc
là ID — phải kiểm chứng bằng nội dung.** Hai bảng trong `state.json`:
`by_key` (khoá → url chính) và `aliases` (url chính → các url phụ, không tải).
Tầng bóc tách (3.6) dùng lại đúng `dedup_key()` để gộp bản trùng trong Mongo.

### 2.6. Phân trang — đọc số trang cuối, không "bấm trang sau"

NukeViet luôn in link tới trang phân trang cuối trong HTML (dù widget rút gọn
phần giữa thành `...`). `expand_pagination()` chỉ cần **một** trang gốc chuyên
mục:

```mermaid
flowchart LR
    A["Tải /vi/news/tuyen-sinh/"] --> B["Quét a[href] khớp<br/>/page-(\d+)/ cùng gốc"]
    B --> C["N = max(số trang),<br/>chặn bởi --max-pages-per-cat"]
    C --> D["push page-2 … page-N<br/>một vòng for, không đệ quy"]
    D --> E["Mỗi page-k tải về<br/>→ push các bài trên trang"]
```

Chạy lại trên các `page-k` đã tải cho ra 0 url mới (đã nằm trong `queued`) — đó
là lưới an toàn: chuyên mục nào không in số trang cuối thì BFS từ trang giữa vẫn
tự bù, chỉ chậm hơn. Crawler nhận 6 khuôn phân trang (`/page-N/`, `/page/N/`,
`?page=N`, `?paged=N`, `/trang-N/`, `/pN/`) và dùng đúng khuôn trang tự in ra,
nên chạy được trên CMS subdomain khác mà không sửa code — đã thử trên
`svbk.hust.edu.vn` (có sitemap) và `library.hust.edu.vn` (không sitemap, tự suy ra
`?page=265`).

### 2.7. Lưu trữ thô — `Store` (JSONL + gzip, chia shard)

Mỗi dòng JSONL là một trang đã tải:

```json
{"url": "...", "status": 200, "kind": "article", "article_id": "656013",
 "depth": 2, "via": "...", "fetched_at": "...", "sha1": "...",
 "encoding": "utf-8", "html_b64": "<base64 của đúng byte HTML gốc>"}
```

`html_b64` giữ **nguyên byte gốc** kèm `sha1` để đối chiếu — đổi cách bóc tách
không bao giờ phải tải lại.

**Flush từng dòng.** Bản đầu flush gzip mỗi 25 dòng; kill cứng hai lần khiến
`read_raw.py --check` thấy 184 url "đã tải theo state nhưng không có trong shard".
Thí nghiệm (ghi 400 dòng, `kill -9` ở dòng 300):

| Kiểu flush | Đọc lại được sau kill -9 |
|---|---|
| từng dòng | 301/301 |
| 25 dòng/lần | 300/301 |
| không flush | 0/301 |

Crawler bắt `SIGTERM`/`SIGHUP` để đóng shard và ghi `state.json` đúng cách. Dừng
bằng `kill -TERM <pid>`, không dùng `pkill -f` (khớp cả dòng lệnh shell và giết
nhầm terminal).

### 2.8. `state.json` — cho phép `--resume` an toàn

```json
{
  "done": {"url": 200}, "frontier": [["url", 1, "via"]],
  "queued": ["url"], "by_key": {"slug": "url_chinh"},
  "aliases": {"url_chinh": ["url_phu"]}, "assets": ["url"],
  "origin": {"url": "nơi phát hiện lần đầu"}, "errors": []
}
```

`origin` là bảng lineage riêng: `via` bị ghi đè khi tải lại (vd. qua
`--seed-file`), còn `origin` dùng `setdefault` nên luôn giữ nơi phát hiện đầu tiên.

State luôn được nạp nếu file có; `--resume` chỉ quyết định có gieo lại hạt giống
hay không. (Trước đây chạy `--from-file` thiếu `--resume` làm mất hàng đợi 4.220
url; `read_raw.py --rebuild-state` dựng lại từ shard + `N1-links`.)

### 2.9. `render.py` — Playwright khi `requests` lấy hụt

Bật qua `--render auto|always|never`. `looks_blocked()` coi là "có vẻ bị chặn"
khi status 403/429/503, HTML có dấu hiệu chặn bot, hoặc trang gần như không có
link/chữ. Playwright sync API gắn với luồng tạo ra nó, nên mỗi luồng một browser
riêng qua `threading.local()`. Gặp captcha thật thì ghi nhận là bị chặn, không
tìm cách giải.

### 2.10. `crawl_hust.py` và `read_raw.py`

`crawl_hust.py` đọc lại kho thô (không tải lại) và bóc mỗi bài ra **17 trường**
phẳng (`id, url, lang, title, section, breadcrumb, author, published_at,
modified_at, summary, content, word_count, thumbnail, images, attachments,
crawled_at, lastmod, source`), xuất `.jsonl` và `.csv` (`utf-8-sig`). Đây là bản
riêng của crawler, độc lập với tầng bóc tách của `hust-search` (mục 3).

`read_raw.py` soát kho bằng nguồn độc lập:

| Lệnh | Câu hỏi trả lời | Cách làm |
|---|---|---|
| `--check` | kho và `state.json` có lệch không? | so `done` với số dòng thật trong shard |
| `--audit` | chuyên mục nào tải thiếu trang? | so trang gốc chuyên mục với các `page-N` đã tải |
| `--fix-roots` | chuyên mục nào rơi khỏi cả `done` lẫn `frontier`? | `mồ côi = queued − done − frontier − aliases`, suy ngược tập chuyên mục từ url bài |
| `--verify-links` | `N1-links` có thiếu link nào có thật trong HTML? | bóc lại mọi `href`, cả hai bên qua cùng `norm()` |
| `--rebuild-state` | cứu hộ khi `state.json` hỏng | dựng lại từ shard + `N1-links` |

Đã xảy ra: `--fix-roots` phát hiện **111 chuyên mục** biến mất khỏi kế hoạch
crawl (gồm `tin-tuc-su-kien` 295 trang) sau một lần `--seed-file` xoá frontier.
`--verify-links` từng báo "thiếu 17 link" — hoá ra lỗi ở phép đo (một bên giữ
`http://`, bên kia đổi `https://`), sửa bằng cách bắt mọi nơi gọi đúng một hàm
`norm()`.

---

## 3. Bóc tách nội dung — khối, trường, đồ thị

Nằm ở `hust-search/api/boc_tach/`. Cửa vào duy nhất là
`boc_tach(html, url, khuon)`; `api/main.py: extract()` chỉ giải base64 rồi gọi
hàm này, nên `/api/fetch`, `/api/index/run` (nguồn kho thô) và
`/api/extract/run` (ghi Mongo) dùng **cùng một bộ bóc tách**.

### 3.1. Thứ tự các bước trong `boc_tach()`

Thứ tự có chủ ý: liên kết và JSON-LD phải lấy **trước** khi dọn cây, vì dọn cây
xoá mất `<script>` (chứa JSON-LD) và `<nav>`/`<footer>` (chứa cạnh khuôn).

```mermaid
flowchart TD
    H(["HTML thô + url"]) --> S["BeautifulSoup(lxml)"]
    S --> J["truong.json_ld()<br/>đọc JSON-LD trong &lt;script&gt;"]
    S --> R["lien_ket.thu_thap()<br/>mọi a[href], img[src|data-src], og:image<br/>mỗi cạnh giữ tham chiếu tới thẻ của nó"]
    S --> M["meta: article:section,<br/>tác giả từ microdata/meta/JSON-LD"]
    J & R & M --> K["khoi.tim_khoi()<br/>dọn cây → khử khuôn → chọn khối (3.2)"]
    K --> T["truong: tiêu đề, ngày đăng,<br/>dòng 'Tác giả:' / 'Nguồn:' cuối khối (3.4)"]
    K --> C["lien_ket.chia(raw, khối)<br/>cạnh trong khối / ngoài khối (3.5)"]
    T & C --> OUT(["dict: title, text, html, date, author,<br/>cited_source, block, links, nav_links,<br/>outgoing_links"])
    T -.->|"không có tiêu đề lẫn chữ"| NONE(["None — bỏ trang"])
```

### 3.2. Thuật toán tìm khối nội dung (`khoi.py`)

Ghép hai họ ý tưởng: mật độ chữ / mật độ link trên DOM (CETD — Sun, Song, Liao,
SIGIR 2011; Boilerpipe — Kohlschütter và cs., WSDM 2010) và khử khuôn theo cả site
(Site Style Tree — Yi, Liu, Li, KDD 2003). Tên bài báo ghi theo trí nhớ, cần kiểm
lại trước khi trích dẫn. Cách cài là bản đơn giản hoá tự viết, không chép mã.

**Ba lớp:**

```mermaid
flowchart TD
    A(["soup"]) --> L1["<b>Lớp 1 — dọn cây</b> (don_cay)<br/>bỏ script, style, noscript, iframe, form, svg, button,<br/>input, select, nav, footer, aside, template;<br/>bỏ thẻ hidden / display:none và comment"]
    L1 --> L2["<b>Lớp 2 — khử khuôn theo host</b> (khuon.bo_khuon)<br/>xoá khối lá có vân tay nằm trong bảng khuôn của host (3.3)"]
    L2 --> SEL{"host có selector<br/>đã kiểm chứng?<br/>(hust.edu.vn → .bodytext)"}
    SEL -- "có và khớp, có chữ" --> MS(["method = selector"])
    SEL -- "không / không khớp" --> TK["<b>Lớp 3 — chấm điểm</b><br/>_thong_ke(): tính C, LC, P, Q cho mọi nút,<br/>một lượt từ lá lên gốc"]
    TK --> E{"body có chữ?"}
    E -- không --> FB(["method = fallback<br/>lấy cả body"])
    E -- có --> N0["node = body"]
    N0 --> CH{"node có con ứng viên?<br/>(div, section, article, main,<br/>td, table, tbody, tr, form)"}
    CH -- không --> HE(["method = heuristic<br/>khối = node"])
    CH -- có --> BEST["tốt = con có điểm × hệ số cao nhất"]
    BEST --> TH{"điểm(tốt) ≥ 0,65 × điểm(node)?"}
    TH -- "có: chữ dồn về con này" --> DOWN["node = tốt"] --> CH
    TH -- "không: chữ trải đều nhiều con" --> HE
```

**Công thức điểm** (hằng số trong `khoi.py`):

```
C  = số ký tự chữ của nút          LC = số ký tự nằm trong <a>
P  = số <p> có ≥ 25 ký tự và ≥ 1 dấu câu
Q  = số dấu câu . , ; : ? ! …

mật_độ_link = LC / max(C, 1)
điểm(n)     = (C − LC) · (1 − mật_độ_link)^α + β·P + γ·Q        α = 2, β = 30, γ = 1
hệ số tên   = ×1,3 nếu class/id khớp content|article|post|entry|detail|bodytext|main|news-body|noi-dung
              ×0,3 nếu khớp nav|menu|footer|header|sidebar|comment|share|related|breadcrumb|
                          banner|widget|social|advert|popup|modal|pagination|tag
ngưỡng đi xuống: δ = 0,65
```

Trực giác: khối nội dung có nhiều chữ thường, ít chữ trong link, nhiều đoạn văn.
Đi từ `body` xuống, mỗi bậc chọn con tốt nhất; còn giữ được ≥ 65% điểm của cha
thì đi tiếp, không thì dừng — vì khi đó chữ đang trải đều nhiều con (ví dụ thân
bài chia thành nhiều `div` anh em), chọn một con sẽ cắt mất nội dung.

Ví dụ thật trên trang mẫu WordPress tổng hợp (`tests/fixtures/html/wordpress.test/00.html`),
số lấy từ `/api/extract/explain`:

| Bậc | Cha (điểm) | Ngưỡng 65% | Con tốt nhất (điểm) | Kết luận |
|---|---|---|---|---|
| 1 | `body` (363) | 236 | `div.container` (411) | đi xuống |
| 2 | `div.container` (411) | 267 | `article.post` (832, ×1,3); `div.widget-area` chỉ 3 | đi xuống |
| 3 | `article.post` (640) | 416 | `div.entry-content` (790, ×1,3) | đi xuống — hết con ứng viên, chọn khối này |

Kết quả: còn 391/890 ký tự của trang (dọn cây bỏ 109, phần ngoài khối bỏ 390).

**Đo lường:** `tests/danh_gia_khoi.py` đo precision/recall/F1 trên túi âm tiết, so
với cả `body`. **Chỉ mới đo trên trang tổng hợp** (`tests/sinh_mau.py`), chưa đo
trên trang thật. Các hằng số α, β, γ, δ là khởi điểm, chưa được dò.

**Xem trực quan:** tab **Bóc tách khối** trên giao diện vẽ lại đúng các bước
này cho từng trang (mục 7.2).

### 3.3. Khử khuôn theo host (`khuon.py`)

Menu, footer, banner lặp gần như nguyên xi trên mọi trang cùng site. Khối văn
bản xuất hiện trên quá nhiều trang của một host thì là khuôn.

```mermaid
flowchart LR
    P["Mỗi trang của host"] --> KL["Khối lá: p, li, td, div, h1-h6…<br/>không chứa khối con"]
    KL --> VT["Vân tay = sha1(chữ thường hoá,<br/>gộp khoảng trắng, số → 0)[:16]"]
    VT --> DEM["Đếm theo host:<br/>vân tay → số trang có nó"]
    DEM --> M[("Mongo templates<br/>{_id: host, n_pages, blocks}")]
    M --> Q{"host ≥ 20 trang?"}
    Q -- không --> NO["không khử khuôn<br/>(không đủ mẫu)"]
    Q -- có --> KH["khuôn = vân tay có mặt<br/>trên > 30% số trang"]
    KH --> BO["bo_khuon(): xoá các khối lá đó<br/>trước khi chấm điểm"]
```

Bảng dựng một lần bằng `POST /api/extract/templates`. Để document không vượt
16 MB, chỉ lưu khối có mặt trên ≥ max(3, 5% số trang). Ngưỡng 30% và 20 trang là
khởi điểm, chưa dò trên kho thật.

### 3.4. Bóc trường của trang (`truong.py`)

Mỗi trường là một chuỗi ưu tiên; nguồn lấy được ghi vào `*_src` để đo độ phủ.

```mermaid
flowchart LR
    subgraph TD1["title"]
        direction TB
        t1["microdata headline"] --> t2["og:title"] --> t3["JSON-LD headline"] --> t4["h1 trong/gần khối"] --> t5["&lt;title&gt; cắt hậu tố site"]
    end
    subgraph TD2["published_at"]
        direction TB
        d1["microdata datePublished"] --> d2["article:published_time"] --> d3["JSON-LD datePublished"] --> d4["&lt;time datetime&gt;"] --> d5["regex dd/mm/yyyy<br/>quanh khối"]
    end
    subgraph TD3["author"]
        direction TB
        a1["microdata author"] --> a2["meta name=author"] --> a3["JSON-LD author.name"] --> a4["dòng cuối khối:<br/>'Tác giả:', 'Tin, ảnh:'…"]
    end
```

Bỏ giá trị tác giả chung chung (`admin`, `webmaster`…). Dòng "Nguồn: …" / "Theo
…" cuối bài thành trường phụ `cited_source`. Ngày chuẩn hoá về `YYYY-MM-DD`.
Độ phủ theo host xem ở `GET /api/extract/coverage`.

### 3.5. Đồ thị liên kết (`lien_ket.py`)

**Nút** là một url (trang, tệp, ảnh, trang ngoài). **Cạnh** `A --> B : "chữ"`
nghĩa là trang A có `<a href=B>chữ</a>` (hoặc `<img src=B alt="chữ">`). Đọc ngược
mũi tên ra **nguồn giới thiệu** — nên đồ thị chính là cách trả lời yêu cầu "nguồn
giới thiệu" cho cả trang, tệp và ảnh.

Hai loại cạnh khác hẳn nhau về ý nghĩa, và thuật toán khối (3.2) là thứ tách
chúng:

```mermaid
flowchart TD
    E(["Một cạnh lấy từ thu_thap()"]) --> IN{"thẻ của cạnh nằm<br/>trong khối nội dung?<br/>(hoặc og:image)"}
    IN -- không --> NAV["<b>cạnh khuôn</b>"]
    IN -- có --> FN{"ảnh khuôn?<br/>đường dẫn /themes/ /templates/ /assets/<br/>hoặc rộng/cao ≤ 16 px"}
    FN -- có --> NAV
    FN -- không --> CT["<b>cạnh nội dung</b>"]
    CT --> KIND["dst_kind = loai_dich(dst)<br/>ngoài họ hust.edu.vn → external<br/>pdf/doc(x)/xls(x)/ppt(x), download=1 → document<br/>jpg/png/gif/webp/svg… → image<br/>còn lại → page"]
    KIND --> LK[("links<br/>từng cạnh: src, dst, type, text, count")]
    NAV --> NL[("nav_links<br/>gộp theo (host, dst, type, text):<br/>n_pages, sample_src ≤ 3")]
```

| | Cạnh nội dung (`links`) | Cạnh khuôn (`nav_links`) |
|---|---|---|
| Nằm ở | trong khối nội dung | ngoài khối, lặp trên nhiều trang |
| Ý nghĩa | người viết **chủ động** giới thiệu B | cấu trúc điều hướng của site |
| Lưu | từng cạnh theo trang | gộp mỗi host một bản, đếm số trang |

Lý do gộp cạnh khuôn: lưu theo từng trang thì mỗi link menu thành hàng nghìn
cạnh giống hệt; "nguồn giới thiệu" của `/vi/tuyen-sinh/` sẽ là danh sách hàng
nghìn trang không ai đọc được. Gộp lại thì đọc được: *"menu của hust.edu.vn, chữ
'Tuyển sinh', có trên N trang"*.

Ví dụ **minh hoạ** (url và chữ đặt ra cho dễ hình dung, không lấy từ kho thật):

```mermaid
flowchart LR
    ALL["mọi trang hust.edu.vn"] -. "menu: Tuyển sinh" .-> TS["/vi/tuyen-sinh/"]
    TS -- "Điểm chuẩn năm 2026" --> BAI["…/diem-chuan-2026.html"]
    BAI -- "Xem chi tiết tại đây" --> PDF["/uploads/diem-chuan-2026.pdf"]
    BAI -- "img alt: Lễ khai giảng" --> IMG["/uploads/anh1.jpg"]
    BAI -- "Bộ GD&ĐT" --> EXT["moet.gov.vn/…"]
    classDef doc fill:#fdf0c8,stroke:#a8871f
    classDef img fill:#f6d9d5,stroke:#c2705f
    classDef ext fill:#eceef1,stroke:#6f7180
    class PDF doc
    class IMG img
    class EXT ext
```

Văn bản mô tả của cạnh: chữ của `<a>` → `title` → `aria-label` → `alt` của ảnh
con; với ảnh: `alt` → `title` → `figcaption`. Mọi url đi qua `crawl_all.norm()`.
`outgoing_links` của schema public là cạnh `href` trong khối, mỗi url một dòng.

### 3.6. Ghi kho thô vào Mongo (`trich.chay_extract`)

```mermaid
flowchart TD
    R(["mỗi bản ghi kho thô"]) --> N["url = norm(url)<br/>key = dedup_key(url)"]
    N --> D{"key đã gặp?"}
    D -- có --> AL["thêm url vào aliases<br/>của trang chính, bỏ qua"]
    D -- không --> B["boc_tach(html, url, khuôn của host)"]
    B --> OK{"có kết quả?"}
    OK -- không --> SK["skipped += 1"]
    OK -- có --> BUF["bộ đệm: page + các cạnh nội dung<br/>gom nav_links và ảnh vào bộ nhớ"]
    BUF --> F{"đủ 200 trang?"}
    F -- có --> W["xả: links.delete_many(src ∈ lô)<br/>pages.replace_one(upsert)<br/>links.insert_many"]
    F -- không --> R
    W --> R
    R -. "hết kho" .-> END["xả lô cuối<br/>nav_links, images: xoá rồi ghi lại toàn bộ"]
```

Chạy lại không nhân đôi bản ghi: `pages` ghi theo `_id`; `links` xoá theo `src`
rồi ghi lại; `nav_links` và `images` là bảng tổng hợp nên dựng lại từ đầu.

---

## 4. Tệp tài liệu và ảnh

### 4.1. Tệp tài liệu (`tep_job.py`, `boc_tach/tep.py`)

Ba bước, chạy nền qua API, mỗi tệp đi qua các trạng thái:

```mermaid
stateDiagram-v2
    [*] --> pending: danh_muc()<br/>cạnh nội dung dst_kind=document<br/>hoặc bản ghi kho có content-type pdf/office
    [*] --> unsupported: đuôi doc/xls/ppt cũ
    pending --> error: robots.txt cấm / HTTP ≥ 400 / lỗi mạng
    pending --> skipped_too_large: tệp lớn hơn 50 MB
    pending --> unsupported: tải về nhưng định dạng không hỗ trợ
    pending --> da_tai: tai()<br/>ghi data/files/sha1.ext
    da_tai --> ok: boc_chu()<br/>pdfminer / python-docx / openpyxl / python-pptx
    da_tai --> error: lỗi bóc / mất tệp
    ok --> [*]: có chữ → index kind=document
```

(`da_tai` là `status = pending` đã có `sha1`; trong Mongo không có trạng
thái riêng.)

- Chỉ tải host `*.hust.edu.vn`; host ngoài (Google Drive…) chỉ có cạnh trong đồ thị.
- Nhịp 2,5 s, 429 thì chờ theo `Retry-After`, tôn trọng robots.txt.
- `POST /api/files/fetch` **trả 409 khi crawler đang chạy**: hai tiến trình mỗi
  bên 2,5 s là ~48 request/phút, gấp đôi ngưỡng site chặn.
- **Không OCR.** PDF dưới 20 ký tự/trang (thường là bản scan) gắn cờ `needs_ocr` và để `text` rỗng; chữ nghi sai bảng
  mã cũ (TCVN3/VNI) gắn `encoding_suspect`. Tệp vẫn có bản ghi và nguồn giới thiệu.

### 4.2. Ảnh

Không tải ảnh — đề chỉ cần nguồn giới thiệu. `images` là danh mục: `alts` gom mọi
chữ mô tả từng gặp; `is_template = true` nếu ảnh chỉ xuất hiện ở cạnh khuôn
(logo, icon).

---

## 5. Lưu trữ MongoDB

Lược đồ ràng buộc bằng `$jsonSchema` trong `api/db.py`, tạo lúc `api` khởi động.
Mô tả đầy đủ từng trường ở `hust-search/SCHEMA.md`.

```mermaid
erDiagram
    pages ||--o{ links : "src"
    links }o--|| pages : "dst (dst_kind=page)"
    links }o--o| documents : "dst (dst_kind=document)"
    links }o--o| images : "dst (dst_kind=image)"
    nav_links }o--o| pages : "dst"
    templates ||--o{ pages : "khử khuôn theo host"

    pages {
        string _id "url chính, qua norm()"
        array aliases "url khác cùng bài"
        string host
        string title "và title_src"
        string published_at "và published_at_src"
        string author "và author_src"
        object content "text, html, word_count, block"
        object raw "sha1, fetched_at"
    }
    links {
        string _id "sha1(src|dst|type|text)"
        string src
        string dst
        string type "href | embed"
        string text "văn bản mô tả"
        string dst_kind "page|document|image|external"
        int count
    }
    nav_links {
        string _id "sha1(host|dst|type|text)"
        string host
        string dst
        string text
        int n_pages
        array sample_src "≤ 3 trang"
    }
    images {
        string _id "url ảnh"
        array alts
        bool is_template
    }
    documents {
        string _id "url tệp"
        string status "pending|ok|unsupported|skipped_too_large|error"
        string text
        bool needs_ocr
        bool encoding_suspect
    }
    templates {
        string _id "host"
        int n_pages
        object blocks "vân tay → số trang"
    }
```

Nguồn giới thiệu của một url bất kỳ là hai truy vấn một bước:

```js
db.links.find({dst: "<url>"}, {src: 1, text: 1})                     // ai giới thiệu trong bài
db.nav_links.find({dst: "<url>"}, {host: 1, text: 1, n_pages: 1})    // menu nào trỏ tới
```

`GET /api/referrers` tự đổi bí danh về trang chính trước khi truy vấn. Không chép
mảng "referrers" vào từng bản ghi — nguồn sự thật là `links` + `nav_links`, nạp
lại không bị lệch.

**Vì sao MongoDB:** Lucene không hợp để lưu quan hệ "ai trỏ tới ai". Các truy vấn
đề bài cần (cạnh vào một bước, đếm bậc, xuất CSV) đều là truy vấn một bước. Phân
tích nhiều bước (đường đi, PageRank) thì xuất `GET /api/graph/edges.csv` sang
networkx/Gephi. So sánh với các lựa chọn khác ở `hust-search/KE-HOACH-BOC-TACH.md` mục 8.

**Chưa kiểm chứng:** test dùng `mongomock`, không thực thi `$jsonSchema`; phần bóc
tách chưa từng chạy trên MongoDB thật với kho thật.

---

## 6. Đánh chỉ mục và tìm kiếm — Lucene

### 6.1. Công nghệ dùng

| Thành phần | Công nghệ | File |
|---|---|---|
| Lõi tìm kiếm | **Apache Lucene 9.11.1** thuần | `lucene/` |
| Ngôn ngữ | Java 21 | |
| HTTP | `com.sun.net.httpserver` có sẵn trong JDK | `SearchServer.java` |
| JSON | Jackson `jackson-databind` 2.17.2 | |
| Build | Maven, jar mỏng + `lib/` (**không shade** — 6.7) | `pom.xml` |

### 6.2. Nạp index

```mermaid
flowchart TD
    A(["POST /api/index/run<br/>source = auto | mongo | raw"]) --> S{"source?"}
    S -- mongo --> MG["trich.lucene_tu_mongo()"]
    S -- auto --> C{"Mongo lên và<br/>pages có dữ liệu?"}
    C -- có --> MG
    C -- không --> RAW["bóc lại từ kho thô:<br/>extract(rec) = boc_tach()"]
    S -- raw --> RAW
    MG --> P["mọi pages → kind=page<br/>documents status=ok có chữ → kind=document"]
    RAW --> L
    P --> L["lucene_document(): map trường"]
    L --> B["POST lucene:8081/bulk<br/>theo lô (mặc định 200)"]
    B --> U["Index.put(): updateDocument(Term(url), doc)<br/>ghi đè theo url, không đẻ trùng"]
    U --> CM["commit() → SearcherManager.maybeRefresh()<br/>tìm được ngay"]
```

`POST /api/index/documents` nhận thẳng corpus JSON theo schema public (không qua
crawler) — dùng cho demo độc lập với mạng (`tests/fixtures/corpus.json`, tối đa
5.000 tài liệu/request, validate bằng Pydantic). `POST /api/fetch` tải một url,
bóc, ghi kho `raw-adhoc`, ghi Mongo và index ngay; máy chủ giữ ≥ 3 giây giữa hai
lần tải.

### 6.3. Schema tài liệu Lucene (`Index.java`)

| Trường | Kiểu | Lưu | Ý nghĩa |
|---|---|---|---|
| `url` | `StringField` | có | khoá cập nhật |
| `title`, `text` | `TextField`, `StandardAnalyzer` | có | bản **còn dấu** |
| `title_kd`, `text_kd` | `TextField`, analyzer bỏ dấu | không | bản **bỏ dấu**, để khớp truy vấn không dấu |
| `host` | `StringField` + `SortedDocValuesField` | có | lọc theo site, đếm theo host |
| `section` | `TextField` | có | chuyên mục |
| `author` | `TextField` | có | tác giả — hiện chỉ lưu và hiển thị, chưa đưa vào truy vấn |
| `kind` | `StringField` | có | `page` / `document`, lọc được |
| `date` / `date_num` | `StringField` / `LongPoint` + `NumericDocValuesField` | có / không | hiển thị; lọc khoảng và sắp theo ngày (`0` = không rõ) |
| `sig` | `StoredField` | có | vân tay SimHash 64-bit (6.6) |
| `html` | `StoredField` | có | HTML đã dọn, chỉ để xem trước |

Mỗi trường chữ index hai lần (còn dấu / bỏ dấu) bằng `PerFieldAnalyzerWrapper`.
`Fold.bo_dau()` tự viết: chuẩn hoá NFD, bỏ ký tự `NON_SPACING_MARK`, xử lý tay
`đ`/`Đ` (NFD không tách được). Hai bản dùng chung `StandardTokenizer` nên token
cùng vị trí, truy vấn cụm chạy đúng trên cả hai.

### 6.4. Một lượt tìm kiếm

```mermaid
flowchart TD
    Q(["GET /search?q=&ranking=&sort=&host=&kind=&from=&to="]) --> R{"ranking?"}
    R -- tfidf --> T["dungTruyVanTfidf()<br/>ClassicSimilarity trên title_kd + text_kd,<br/>trọng số bằng nhau, AND"]
    R -- enhanced --> EN["dungTruyVan(), AND<br/>nhánh còn dấu: title 3,0 · section 1,5 · text 1,0<br/>nhánh bỏ dấu: title_kd 2,0 · text_kd 0,7<br/>+ thưởng cụm liền nhau (themCum)<br/>+ thưởng cặp âm tiết (themCumDoi)"]
    T & EN --> F["loc(): host, kind, khoảng ngày<br/>bằng FILTER — không góp điểm"]
    F --> S["tìm, cửa sổ = min(1000, max(5 × cần, 50))"]
    S --> Z{"0 kết quả?"}
    Z -- có --> V["vét lại bằng OR<br/>đòi khớp quá nửa số âm tiết (itNhat 0,5)"] --> X
    Z -- không --> X["xepLai(): chỉ enhanced mới<br/>nhân điểm nền Rank.diemNen()"]
    X --> G["gopTrung(): gộp bản trùng nội dung<br/>bằng SimHash, TRƯỚC khi cắt trang"]
    G --> H["cắt [from, from+size)<br/>doanTrich(): Highlighter + một QueryScorer dùng chung → &lt;mark&gt;"]
    H --> OUT(["hits: url, title, host, date, author, kind,<br/>score, fragments, duplicates"])
```

**Chế độ `tfidf`** (mặc định) là TF-IDF cổ điển thuần, dùng để trình bày đúng
thuật toán, không pha tín hiệu ngoài.

**Chế độ `enhanced`** thêm: hai nhánh còn dấu / bỏ dấu (gõ đủ dấu thì cả hai cùng
khớp nên luôn xếp trên); thưởng cụm vì `StandardAnalyzer` cắt tiếng Việt theo âm
tiết ("kỹ thuật" thành 2 token); thưởng từng cặp âm tiết liền nhau cho câu dài; và
nhân điểm nền `Rank.diemNen()` theo loại trang (`/page-N/` 0,50 · cửa vào chuyên
mục 0,70 · bài `.html` 1,00), độ dài (`0,75 + 0,25 × min(1, ký_tự/1200)`) và độ mới
(`0,92 + 0,16 × e^(−tuổi/3)`). Biên độ cố ý nhẹ (~0,35×).

**Vét lại khi không ra gì:** ngưỡng "quá nửa" chứ không phải OR trần (OR trần khớp
đúng một âm tiết phổ biến sẽ lôi về hàng nghìn bài), và không phải 2/3 (mỗi từ
tiếng Việt 2 âm tiết bị gõ thừa đã chiếm 2/4 token của câu ngắn).

### 6.5. Highlight

Tô `<mark>` làm ở Lucene, không ở trình duyệt: Lucene biết chính xác token nào
khớp sau khi phân tích. `Highlighter` và `SimpleSpanFragmenter` phải dùng **chung
một** `QueryScorer` — tạo hai cái thì cái của fragmenter không bao giờ được init và
ném `NullPointerException`.

### 6.6. Gộp bản trùng — SimHash (`Sig.java`)

Cùng một bài được phát ở `/vi/news/...` và `/vi/news/savefile/...`, lệch vài chữ ở
phần khung trang — băm thường cho hai giá trị khác hẳn.

```mermaid
flowchart LR
    A["văn bản"] --> B["bỏ dấu, thường hoá,<br/>shingle 3 từ liền nhau"]
    B --> C["mỗi shingle → FNV-1a 64-bit"]
    C --> D["mỗi bit: bầu 1 nếu số shingle<br/>có bit đó = 1 nhiều hơn = 0"]
    D --> E["vân tay 64 bit<br/>(&lt; 100 shingle → 0 = đừng gộp)"]
    E --> F{"Hamming ≤ 3 bit?"}
    F -- có --> G["cùng một bài: giữ url 'gốc hơn'<br/>(ít dấu / hơn, bằng thì ngắn hơn),<br/>url kia vào duplicates"]
    F -- không --> H["hai bài khác nhau"]
```

Ngưỡng 3 bit đo thực nghiệm: từ 106 shingle trở lên, hai bản của cùng một bài
(thêm khung trang) lệch đúng 3 bit; hai bài khác chủ đề lệch từ 18 bit.

### 6.7. Vấn đề build — Lucene 9 là multi-release JAR

`maven-shade-plugin` làm mất `META-INF/versions/19/` (chứa
`MemorySegmentIndexInputProvider`), chạy trên Java 21 ném `LinkageError` khi mở
index. `pom.xml` build jar mỏng + `maven-dependency-plugin` chép jar phụ thuộc vào
`lib/`, classpath khai trong manifest.

### 6.8. `SearchServer.java`

`HttpServer` của JDK, `Executors.newFixedThreadPool(8)`. Endpoint: `/bulk`,
`/search`, `/list`, `/dict`, `/posting`, `/doc`, `/stats`, `/reset`, `/health`.
Lỗi cú pháp truy vấn trả **400** chứ không phải 500.

---

## 7. Tầng API và giao diện

### 7.1. Job nền và dây chuyền bóc tách

Các việc dài (dựng khuôn, bóc tách, tải tệp, bóc chữ) chạy trên một luồng nền,
**mỗi lúc một job** (`_chay_nen`, gọi trùng trả 409). Tiến độ đọc ở
`GET /api/extract/status`; số liệu từng bước ở `GET /api/extract/overview`.

```mermaid
flowchart LR
    S1["① POST /api/extract/templates<br/>dựng bảng khuôn"] --> S2["② POST /api/extract/run<br/>kho thô → pages, links,<br/>nav_links, images"]
    S2 --> S3["③ POST /api/files/fetch<br/>danh mục + tải tệp<br/>(409 nếu crawler chạy)"]
    S3 --> S4["④ POST /api/files/extract<br/>bóc chữ tệp"]
    S4 --> S5["POST /api/index/run<br/>Mongo → Lucene"]
```

Bước ① không bắt buộc (không có bảng khuôn thì lớp 2 bỏ qua), nhưng nên chạy
trước ② để khử khuôn có hiệu lực.

### 7.2. Giao diện (`api/static/index.html`)

Một file tĩnh, không framework, không bước build. Đồ thị và biểu đồ vẽ bằng SVG/CSS
tự viết, không tải thư viện ngoài.

```mermaid
flowchart LR
    subgraph UI["Các tab"]
        T1["Tìm kiếm"]
        T2["Tải một trang"]
        T3["Duyệt tất cả"]
        T4["Chỉ mục ngược"]
        T5["Bóc tách khối"]
        T6["Đồ thị liên kết"]
        T7["Tệp &amp; ảnh"]
        T8["Bảng điều khiển"]
    end
    T1 --> E1["/api/search · /api/preview"]
    T2 --> E2["/api/fetch"]
    T3 --> E3["/api/index/list"]
    T4 --> E4["/api/index/dict · /posting"]
    T5 --> E5["/api/extract/explain"]
    T6 --> E6["/api/referrers · /api/graph/out<br/>/api/graph/stats · edges.csv"]
    T7 --> E7["/api/files · /api/images"]
    T8 --> E8["/api/stats · /api/crawl/*<br/>/api/index/* · /api/extract/*"]
    T1 -. "nút Nguồn giới thiệu" .-> T6
    T1 -. "nút Khối nội dung" .-> T5
    T5 -. "Xem đồ thị quanh trang" .-> T6
    T6 -. "Xem khối của trang" .-> T5
```

Ba phần vẽ trực quan cho bóc tách và đồ thị:

- **Bóc tách khối** (`/api/extract/explain`): chạy lại bước chọn khối trên HTML thô
  trong kho (không gọi lại site; có Mongo thì dùng thêm bảng khuôn). Vẽ ① phễu số
  ký tự còn lại sau mỗi lớp, ② các bậc đi xuống cây — thanh điểm của cha và từng con,
  vạch ngưỡng 65%, câu kết luận "đi xuống" / "dừng", ③ thanh chia liên kết trong khối
  (cạnh nội dung) / ngoài khối (cạnh khuôn). `tim_khoi()` nhận tham số `vet` tuỳ
  chọn để ghi các bậc này; không truyền thì kết quả như cũ (có test).
- **Đồ thị liên kết:** đồ thị hình sao bằng SVG — trái là trang trỏ tới, giữa là url
  đang xem, phải là nơi nó trỏ đi; màu theo loại đích, menu/footer nét đứt; tối đa
  12 ô mỗi cột, phần dư gộp "+N nữa"; bấm một ô để chuyển tâm. Dạng bảng vẫn còn
  trong "Xem dạng bảng".
- **Bảng điều khiển:** bốn bước ở 7.1 vẽ thành dải ô nối mũi tên, mỗi ô ghi số liệu
  đã có, sáng viền khi đang chạy.

Ảnh chụp các phần này chỉ mới thử trên dữ liệu giả (vài trang tự tạo + mongomock),
chưa thử trên kho thật. `/api/extract/explain` phải quét kho thô để tìm HTML của url
(có lọc thô bằng chuỗi trước khi giải mã JSON); chưa đo tốc độ trên kho ~200 MB.

### 7.3. Đóng gói Docker

```yaml
services:
  lucene:  build ./lucene, volume lucene-index:/index, cổng 8081, healthcheck /health
  mongo:   image mongo:7.0, volume mongo-data, KHÔNG mở cổng (chưa có xác thực)
  api:     build ./api, depends_on lucene + mongo (healthy), cổng 8000
           volumes: ../hust-crawler:/crawler   (sửa crawl_all.py không cần build lại)
                    ./api:/app                 (sửa API/giao diện không cần build lại)
```

Image `api` dựng trên nền Playwright (có sẵn Chromium) để dùng cho crawl
`--render`. `docker compose down` (không `-v`) giữ nguyên volume index và Mongo.

---

## 8. Kiểm thử

| Bộ test | Số lượng | Công cụ | Việc kiểm |
|---|---|---|---|
| Engine crawler | 32 | `pytest` | `norm`, `dedup_key`, `expand_pagination`, nhịp, flush, resume |
| Python phía api | 64 | `pytest` + `mongomock`, không gọi mạng thật | parser, schema public (5); khối, khuôn, trường, đồ thị, vết thuật toán (25); Mongo, route bóc tách/đồ thị/explain (21); bóc chữ tệp, tải bằng `httpx.MockTransport` (13) |
| Lucene | 30 | JUnit 5 | index, tìm, highlight, gộp trùng, xếp hạng, `author`/`kind` |
| Tích hợp tìm kiếm | 33 kiểm tra | bash `tests/integration.sh` | đường đi thật trên stack đang chạy |
| Tích hợp bóc tách | `tests/integration_bt.sh` | bash | job nền, Mongo, đồ thị, tệp — đã chạy 17/17 trên Lucene thật + API với mongomock + corpus tổng hợp |

Quy ước: mỗi test đặt tên theo đúng lỗi thật đã gặp — test nào đỏ thì đọc tên là
biết vừa phá lại chuyện gì.

**Chưa chạy:** stack docker đầy đủ với MongoDB thật và kho thật — nên chưa kiểm
chứng `$jsonSchema`, hiệu năng, và độ chính xác thuật toán khối trên trang thật.

---

## 9. Giới hạn đã biết

**Crawler**
- Subdomain: mới crawl link bên trong 2/51 host (`library`, `svbk`); phần lớn host
  khác chỉ có link "cửa vào" từ trang chính.
- Đường tải chính không chạy JavaScript; Playwright chỉ dùng khi bật `--render`.
- Ngưỡng nhịp đo tại một thời điểm; nhịp tự dò tự chỉnh nhưng không tức thời.

**Bóc tách**
- Thuật toán khối chỉ mới đo trên trang tổng hợp; hằng số chưa dò.
- Không OCR; định dạng `doc/xls/ppt` cũ là `unsupported`; không tải tệp ở host ngoài.
- `nav_links` có thể phình (gồm cả liên kết ngoài khối nhưng riêng từng trang, vd.
  "tin liên quan"); chưa đo trên kho thật.

**Tìm kiếm**
- `StandardAnalyzer` tách tiếng Việt theo âm tiết, chưa tách từ ghép (chỉ bù bằng
  thưởng cụm ở `enhanced`).
- `author` và chữ mô tả của cạnh đi vào (anchor text) chưa được dùng để xếp hạng;
  đồ thị chưa dùng làm tín hiệu kiểu PageRank.
- Không có xác thực — cổng 8000/8081 mở không mật khẩu, chỉ dùng máy cá nhân hoặc
  mạng nội bộ.

---

## 10. Bản đồ file để tra cứu nhanh

| Muốn hiểu/sửa gì | File | Hàm/lớp chính |
|---|---|---|
| Vòng lặp crawl, hàng đợi | `hust-crawler/crawl_all.py` | `Crawler.run/push/pop/fetch/visit` |
| Nhịp tự dò | `hust-crawler/crawl_all.py` | `_wait_turn/_slow_down/_speed_up` |
| Khử trùng bài | `hust-crawler/crawl_all.py` | `dedup_key()` — KHÔNG dùng `art_id()` |
| Phân trang | `hust-crawler/crawl_all.py` | `expand_pagination()`, `PAGE_PATTERNS` |
| Lưu trữ thô | `hust-crawler/crawl_all.py` | `Store` |
| Render JS | `hust-crawler/render.py` | `looks_blocked()` |
| Soát kho | `hust-crawler/read_raw.py` | `--audit/--check/--fix-roots/--verify-links` |
| Cửa vào bóc tách | `hust-search/api/boc_tach/__init__.py` | `boc_tach()`, `giai_thich()` |
| Chọn khối nội dung | `hust-search/api/boc_tach/khoi.py` | `tim_khoi()`, `_diem()`, `ALPHA/BETA/GAMMA/DELTA` |
| Khử khuôn | `hust-search/api/boc_tach/khuon.py` | `van_tay()`, `tap_khuon()`, `bo_khuon()` |
| Tiêu đề / ngày / tác giả | `hust-search/api/boc_tach/truong.py` | `tieu_de()`, `ngay_dang()`, `tac_gia_meta()` |
| Đồ thị liên kết | `hust-search/api/boc_tach/lien_ket.py` | `thu_thap()`, `chia()`, `loai_dich()` |
| Bóc chữ tệp | `hust-search/api/boc_tach/tep.py`, `api/tep_job.py` | `bong_chu()`, `danh_muc()`, `tai()`, `boc_chu()` |
| Kho thô → Mongo, Mongo → Lucene | `hust-search/api/trich.py` | `chay_extract()`, `dung_templates()`, `lucene_tu_mongo()` |
| Lược đồ Mongo | `hust-search/api/db.py`, `SCHEMA.md` | `SCHEMAS` |
| Route bóc tách/đồ thị/tệp | `hust-search/api/routes_bt.py` | `/api/extract/*`, `/api/referrers`, `/api/graph/*`, `/api/files` |
| Route crawl/index/tìm | `hust-search/api/main.py` | `index_run`, `index_documents`, `fetch_one`, `tim_ban_ghi` |
| Schema Lucene, xếp hạng | `hust-search/lucene/.../Index.java` | `put()`, `dungTruyVanTfidf/dungTruyVan`, `loc()`, `gopTrung()` |
| Điểm nền | `hust-search/lucene/.../Rank.java` | `diemNen()` |
| Bỏ dấu | `hust-search/lucene/.../Fold.java` | `bo_dau()` |
| SimHash | `hust-search/lucene/.../Sig.java` | `vanTay()`, `lech()`, `cungMotBai()` |
| HTTP Lucene | `hust-search/lucene/.../SearchServer.java` | — |
| Giao diện | `hust-search/api/static/index.html` | `veKhoi()`, `veDoThiSao()`, `veDayChuyen()` |
| Đóng gói | `hust-search/docker-compose.yml`, `lucene/pom.xml` | — |
