# Kế hoạch: port tầng API Python sang Java — `hust-search` thành một project Java

Lập ngày 03/10/2026. Trạng thái: **đang làm** trên nhánh `port-java`; quyết định đã chốt ở mục 1.

Mục tiêu:

- `hust-search/` chỉ còn **một project Maven, Java 21**, chạy trong một tiến trình: HTTP API + giao
  diện + bóc tách + MongoDB + Lucene. Thư mục `api/` (Python) bị xoá.
- Xử lý tệp tài liệu bằng **Apache Tika** (thay pdfminer, python-docx, openpyxl, python-pptx).
- **Bỏ Playwright.**
- **Crawler chạy riêng** trong một service docker của nó; `docker-compose.yml` chuyển lên thư mục
  cha `di-project/` để dựng cả hai.
- **Hợp đồng HTTP giữ nguyên**: cùng đường dẫn, tham số, khoá JSON, mã lỗi. Giao diện
  `static/index.html` và hai script tích hợp chạy lại được mà gần như không sửa — đó là lưới an
  toàn chính của cả đợt port.

Không nằm trong phạm vi: crawler (`hust-crawler/`) vẫn là Python; thuật toán bóc tách giữ nguyên ý
tưởng và hằng số (port, không cải tiến); không thêm PageRank / anchor text (việc riêng, làm sau).

---

## 0. Hiện trạng (đo ngày 03/10/2026)

| Phần | Kích thước | Ghi chú |
|---|---|---|
| `api/main.py` | 695 dòng | 16 endpoint: crawl, index, search, fetch, preview, health, stats |
| `api/routes_bt.py` | 400 dòng | 15 endpoint: extract, files, images, referrers, graph |
| `api/trich.py` | 258 | kho thô → bóc tách → Mongo |
| `api/tep_job.py` | 133 | danh mục tệp, tải tệp (robots.txt, nhịp 2,5 s), bóc chữ |
| `api/db.py` | 90 | `$jsonSchema` + index Mongo |
| `api/boc_tach/` | 751 | khối nội dung, khuôn, trường, liên kết, dọn HTML, bóc tệp |
| `api/so_sanh.py` | 503 | CLI so chỉ mục bóc cũ / bóc mới, cần 2 instance Lucene |
| `lucene/` (Java) | 1.321 | Index, Rank, Fold, Sig, SearchServer (JDK `HttpServer`, cổng 8081) |
| Test Python | 82 test | api 5, boc_tach 25, mongo 22, extract_url 11, so_sanh 6, tep 13 |
| Test Java | 31 JUnit | `IndexTest` |

Những chỗ API Python dính vào crawler — phải xử lý khi tách:

1. **Import thẳng `crawl_all`**: `norm()`, `dedup_key()`, `kind_of()`, `UA` (dùng trong
   `html_sach.py`, `trich.py`, `tep_job.py`, `main.py`). Sang Java phải viết lại, và hai bản phải
   cho kết quả **giống hệt** (CLAUDE.md: "mọi nguồn url phải đi qua `crawl_all.norm()`").
2. **Chạy crawler bằng subprocess**: `/api/crawl/start|stop|status` gọi `python crawl_all.py`
   ngay trong container `api`.
3. **Playwright KHÔNG phải phụ thuộc chết** (sửa lại nhận định trước đó): `hust-crawler/render.py`
   dùng nó cho `--render auto|always`, và tab Crawl có ô chọn `render`. Crawler chạy được nhờ image
   `api` dựng trên `mcr.microsoft.com/playwright/python`. `render.py` tự lùi êm khi thiếu Playwright
   ("Thiếu Playwright thì module vẫn nạp được"), nên bỏ được — cái giá là các trang SPA như
   `work.hust.edu.vn` sẽ không crawl được nữa.
4. **Chặn hai bên đạp nhịp nhau**: `/api/files/fetch` từ chối khi crawler đang chạy, và
   `/api/crawl/start` từ chối khi đang tải tệp (hai bên cộng lại vượt ngưỡng ~20-25 request/phút).

---

## 1. Quyết định (đã chốt 03/10/2026)

| # | Câu hỏi | Đã chọn | Phương án bị loại |
|---|---|---|---|
| Q1 | Điều khiển crawler sau khi tách | **A.** Service crawler chạy `crawlctl.py` (~80 dòng, chỉ stdlib `http.server`) mở `/start /stop /status` trong mạng nội bộ docker; Java chuyển tiếp `/api/crawl/*` sang đó. Giữ được tab Crawl và cả hai chiều chặn nhịp. | B. Bỏ hẳn `/api/crawl/*` và tab Crawl. |
| Q2 | Tệp `doc / xls / ppt` | **Bật**: Tika đọc được định dạng OLE2 cũ nhờ POI. Tài liệu đang `unsupported` chuyển về `pending` để tải lại / bóc lại. | Giữ `unsupported`. |
| Q3 | `so_sanh.py` | **Bỏ hẳn tính năng so sánh**: không port, xoá `so_sanh.py`, `test_so_sanh.py` và profile `compare` (`lucene-cu`, `lucene-moi`). | Port sang Java. |
| Q4 | Thư viện HTTP | **JDK `com.sun.net.httpserver`** như `SearchServer.java` đang dùng + một router nhỏ. | Javalin. |
| Q5 | Cổng 8081 (Lucene HTTP) | **Bỏ.** Lucene chạy trong cùng tiến trình với API; chỉ còn cổng 8000. | Giữ route thô dưới `/lucene/*`. |

---

## 2. Kiến trúc đích

### 2.1. Bố cục thư mục

```
di-project/
  docker-compose.yml            ← chuyển từ hust-search/ lên
  docker-compose.compass.yml    ← chuyển lên theo
  hust-crawler/
    Dockerfile                  MỚI: python:3.12-slim + requests/bs4/lxml, KHÔNG Playwright
    crawlctl.py                 MỚI (Q1-A): start/stop/status, logic chuyển từ main.py sang
    crawl_all.py …              giữ nguyên
  hust-search/
    pom.xml                     ← từ lucene/pom.xml, thêm phụ thuộc
    Dockerfile                  ← từ lucene/Dockerfile
    static/index.html           ← từ api/static/ (mount vào container, sửa UI không cần build)
    src/main/java/vn/hust/search/
      Index.java Rank.java Fold.java Sig.java      giữ nguyên
      Main.java                 MỚI: mở Index + Mongo, dựng HttpServer :8000 (thay SearchServer)
      web/Http.java             router, đọc query/body, gửi JSON, lỗi {"detail": "..."}
      web/ApiTimKiem.java       /api/search, /api/index/*, /api/preview, /api/health, /api/stats, /api/fetch
      web/ApiBocTach.java       /api/extract/*, /api/files*, /api/images, /api/referrers, /api/graph/*
      web/ApiCrawl.java         chuyển tiếp /api/crawl/* sang service crawler
      web/ViecNen.java          một việc nền tại một thời điểm (thay _job / _chay_nen)
      kho/Url.java              norm, dedupKey, kindOf, pageOf — bản sao của crawl_all
      kho/Kho.java              đọc shard gzip, timBanGhi, tatCaBanGhi, ghi raw-adhoc
      kho/TaiVe.java            tải lẻ theo nhịp 3 s
      boctach/BocTach.java Khoi.java Khuon.java Truong.java LienKet.java HtmlSach.java Tep.java
      mongo/Db.java Trich.java TepJob.java Robots.java
    src/main/resources/mongo-schema.json   SCHEMAS + INDEXES của db.py, dạng JSON
    src/test/java/…  src/test/resources/{html/, tep/, golden/}
    tests/integration.sh integration_bt.sh  giữ, chỉ sửa đường dẫn compose
```

`api/` và `lucene/` biến mất sau khi cắt chuyển (giai đoạn 9).

### 2.2. Ánh xạ thư viện

| Python | Java | Ghi chú |
|---|---|---|
| FastAPI + uvicorn + pydantic | JDK `HttpServer` + Jackson (đã có) | kiểm tra hợp lệ viết tay trong record `PublicDocument` |
| BeautifulSoup + lxml | **jsoup** | khác biệt ngữ nghĩa — xem mục 4 |
| pymongo | **mongodb-driver-sync** | |
| pdfminer, python-docx, openpyxl, python-pptx | **tika-core + tika-parsers-standard-package** (bản 3.x, dò bản mới nhất lúc làm) | tắt OCR |
| httpx / requests | `java.net.http.HttpClient` | có sẵn trong JDK |
| `urllib.robotparser` | `mongo/Robots.java` viết tay ~40 dòng | phải khớp luật **khớp-dòng-đầu-tiên** của Python, không phải khớp-dài-nhất kiểu Google |
| mongomock | **Testcontainers (mongo:7.0)** | lợi thêm: lần đầu `$jsonSchema` được thử trên Mongo thật |
| pytest | JUnit 5 (đã có) | |

Giữ cách đóng gói hiện tại: jar gốc nằm trong `lib/`, **không shade** (ghi chú trong `pom.xml`: Lucene
là multi-release JAR, shade làm mất `META-INF/versions`). Tika cũng có jar multi-release nên cùng lý do.

### 2.3. Docker compose ở thư mục cha

```yaml
services:
  search:
    build: ./hust-search
    depends_on: {mongo: {condition: service_healthy}}
    ports: ["8000:8000"]
    volumes:
      - lucene-index:/index
      - ./hust-crawler/data:/data          # đọc kho thô; ghi raw-adhoc/ và files/
      - ./hust-search/static:/app/static   # sửa giao diện không phải build lại
    environment:
      DATA_DIR: /data
      MONGO_URL: mongodb://mongo:27017
      CRAWLER_URL: http://crawler:8090
  crawler:
    build: ./hust-crawler
    volumes: ["./hust-crawler:/app"]        # sửa crawl_all.py không phải build lại (như hiện nay)
    command: ["python", "crawlctl.py", "--port", "8090"]
    # không mở cổng ra host; chỉ search gọi được
  mongo:
    image: mongo:7.0                        # giữ nguyên khối hiện tại
volumes: {lucene-index: {}, mongo-data: {}}
```

Volume `lucene-index` giữ tên cũ. Nhưng compose chuyển thư mục thì **tên project đổi**
(`hust-search` → `di-project`), nên volume thực tế cũng đổi tên (`hust-search_lucene-index` →
`di-project_lucene-index`), và dữ liệu Mongo/index cũ sẽ không tự hiện ra. Có hai cách: đặt
`name: hust-search` ở đầu file compose để giữ tên cũ, hoặc dựng lại (index khoảng 2 phút; Mongo phải
chạy lại templates → extract → files). **Đề xuất:** dùng `name: hust-search` trong lúc chuyển, sau
khi cắt chuyển xong mới đổi tên.

---

## 3. Hợp đồng HTTP — giữ nguyên, kiểm từng endpoint

Quy tắc chung:

- Lỗi luôn là `{"detail": "<chuỗi>"}` — giao diện đọc `.detail` ở 13 chỗ, `integration_bt.sh`
  bắt chuỗi `"detail"`. FastAPI trả `detail` dạng **mảng** khi pydantic từ chối; Java trả **chuỗi**
  (giao diện chỉ in ra, nên chuỗi còn dễ đọc hơn).
- Tên tham số giữ nguyên, kể cả `from_` (FastAPI không đổi tên nó).
- Khoá JSON tiếng Việt giữ nguyên: `loai`, `nguon`, `luu`, `truong`, `noi_dung`, `lien_ket`, `pheu`, `bac`…
- Executor: dùng `Executors.newVirtualThreadPerTaskExecutor()` (Java 21). `/api/extract/url` có thể
  đứng chờ nhịp 3 s cộng thời gian tải; với pool cố định 8 luồng như `SearchServer` hiện nay, vài
  lượt tải lẻ là đủ chặn mọi lượt tìm kiếm.

| Endpoint | Java | Thay đổi |
|---|---|---|
| `POST /api/crawl/start` `stop`, `GET /api/crawl/status` | `ApiCrawl` → `crawler:8090` | `render ≠ never` trả 400 "không còn hỗ trợ render (đã bỏ Playwright)"; kiểm việc nền `files` **ở phía Java** trước khi chuyển tiếp |
| `GET /api/stats` | `ApiTimKiem` | đọc `state.json` từ volume `/data` như cũ |
| `POST /api/index/run` | `ApiTimKiem` | gọi thẳng `Index`, không qua `/bulk` |
| `POST /api/index/documents` | `ApiTimKiem` | kiểm tra hợp lệ của `PublicDocument` port sang Java (url http/https, kind, cắt title 500 / content 200.000, ngày YYYY-MM-DD, cần title hoặc content), tối đa 5.000 tài liệu |
| `GET /api/index/stats` `list` `dict` `posting`, `GET /api/search`, `GET /api/preview` | `ApiTimKiem` | gọi thẳng `Index` (trước là chuyển tiếp HTTP) |
| `POST /api/fetch` | `ApiTimKiem` + `TaiVe` | |
| `GET /api/health` | `ApiTimKiem` | `lucene` luôn `true`; thêm `mongo`, `crawler` (gọi được `crawler:8090` không) |
| `GET /` , `/static/*` | `Http` | phục vụ từ `STATIC_DIR` |
| `POST /api/extract/templates` `run`, `GET status` `coverage` `explain` `overview` | `ApiBocTach` | |
| `POST /api/extract/url` | `ApiBocTach` | |
| `POST /api/files/fetch` | `ApiBocTach` | "crawler đang chạy?" hỏi `crawler:8090/status` thay cho `_alive()` |
| `POST /api/files/extract`, `GET /api/files` `images` `referrers` `graph/out` `graph/stats` | `ApiBocTach` | |
| `GET /api/graph/edges.csv` | `ApiBocTach` | gửi chunked (`sendResponseHeaders(200, 0)`), không dồn cả file vào bộ nhớ |

Trước khi port, chụp lại phản hồi của mọi endpoint GET trên stack Python đang chạy
(`tests/golden/api/*.json`) để so tự động với bản Java (giai đoạn 0).

---

## 4. Những chỗ dễ lệch khi port (đọc trước khi viết dòng nào)

Thuật toán chọn khối được chỉnh trên **cây DOM của lxml và ngữ nghĩa chuỗi của Python**. Port
"nhìn giống" mà bỏ qua các điểm dưới đây thì điểm từng khối, vân tay khuôn và văn bản đều lệch.

| # | Python | Java — bẫy | Cách xử lý |
|---|---|---|---|
| 1 | `get_text(" ", strip=True)` nối **từng** mảnh text bằng một dấu cách: `a<b>b</b>` → `"a b"` | jsoup `Element.text()` cho ra `"ab"` | Viết `HtmlSach.textBs4(el)`: duyệt các `TextNode` con cháu, strip từng mảnh, bỏ mảnh rỗng, nối bằng `" "`. **Không bao giờ dùng `text()`** trong bóc tách |
| 2 | `\s` trong regex Python 3 khớp cả NBSP ` ` và khoảng trắng Unicode | `\s` của Java chỉ là ASCII | Mọi `Pattern` dùng cờ `UNICODE_CHARACTER_CLASS` (hoặc `(?U)`). `&nbsp;` gặp khắp nơi trong HTML NukeViet — sai chỗ này là `clean_space`, đếm chữ, vân tay khuôn và regex ngày đều lệch |
| 3 | `re.I` với chữ Việt; `.lower()` | `CASE_INSENSITIVE` mặc định chỉ ASCII | Dùng `CASE_INSENSITIVE \| UNICODE_CASE`; `toLowerCase(Locale.ROOT)` |
| 4 | Nội dung `<script>` là `NavigableString` (`s.string`) | jsoup để nội dung script/style trong `DataNode`, `text()` trả rỗng | JSON-LD đọc bằng `script.data()` |
| 5 | `decompose()`; cờ `t.decomposed` khi duyệt danh sách chụp sẵn | `remove()` không có cờ; phần tử đã gỡ vẫn còn con | Kiểm "còn gắn vào cây" bằng cách đi ngược `parent()` lên tới `Document` |
| 6 | `id(node)` làm khoá dict | | `IdentityHashMap<Element, int[]>` |
| 7 | `soup.find_all(True)` theo thứ tự tài liệu, không gồm gốc | `getAllElements()` gồm cả `Document` | bỏ phần tử gốc |
| 8 | `str(body)` để dọn HTML, cắt 40.000 ký tự | jsoup mặc định pretty-print, thụt lề | `outputSettings().prettyPrint(false)` |
| 9 | `node["class"]` là list theo thứ tự | `classNames()` là `LinkedHashSet` — giữ thứ tự | dùng được, nhưng `duong_dan` phải nối bằng `.` như cũ |
| 10 | lxml (libxml2) sửa HTML lỗi theo kiểu riêng | jsoup dựng cây theo thuật toán HTML5 | Không sửa được, chỉ **đo**: cổng so khớp ở giai đoạn 3 |
| 11 | `len(str)` đếm code point | `String.length()` đếm UTF-16 | Chữ Việt nằm trong BMP nên gần như không lệch; cắt `[:500]` thì dùng hàm cắt không chẻ cặp surrogate |
| 12 | `urljoin` / `urlparse` dễ dãi | `java.net.URI` ném lỗi khi gặp dấu cách, chữ có dấu, `|`… | `Url.norm` tự tách chuỗi theo RFC 3986, chỉ dùng `URI.resolve` khi chuỗi hợp lệ; kiểm bằng bộ golden 100% (giai đoạn 2) |
| 13 | `netloc.lower().replace("www.", "")` thay **mọi chỗ** trong netloc, kể cả cổng | | chép đúng hành vi (kể cả chỗ trông như lỗi) — khớp crawler quan trọng hơn đúng |
| 14 | `json.dumps(path)[1:-1]` để lọc thô trong `tim_ban_ghi`: `ensure_ascii` mặc định thoát chữ Việt thành `\uXXXX` chữ thường | | sinh **cả** hai dạng khoá: thô và thoát `\u` chữ thường |
| 15 | `bytes.decode(enc, "replace")` | `new String(b, cs)` cũng thay ký tự hỏng, nhưng tên bảng mã lạ thì ném lỗi | bọc `try` rồi lùi về UTF-8 |
| 16 | `gzip.open` đọc shard đang ghi dở; `raw-adhoc` mở chế độ `"at"` nên **một file có nhiều gzip member** | `GZIPInputStream` đọc được nhiều member; gặp đuôi cụt thì ném `EOFException` | bắt `EOFException`, giữ các dòng đã đọc, sang shard sau |
| 17 | `round(x, 2)` (làm tròn kiểu banker) | | `Math.round(x * 100) / 100.0`; lệch ở chữ số thứ 3 không ảnh hưởng gì |
| 18 | `_id` của `links` = `sha1("src\|dst\|type\|text")` UTF-8 | | công thức giữ nguyên ⇒ dữ liệu Mongo cũ vẫn khớp, chạy lại không nhân đôi. Có test vector |

---

## 5. Kế hoạch theo giai đoạn

Mỗi giai đoạn kết thúc ở trạng thái **build xanh, stack Python cũ vẫn chạy được** cho tới giai đoạn 9.
Code Java mới nằm trong `hust-search/src/`, song song với `api/`.

### Giai đoạn 0 — Chụp mốc từ bản Python (0,5 ngày)

Bản Python còn sống là "đáp án". Chụp lại trước khi đụng vào bất cứ thứ gì.

1. `tests/xuat_golden.py` (script dùng một lần, xoá ở giai đoạn 9) sinh vào `src/test/resources/golden/`:
   - `url.tsv` — `input, base, norm, dedup_key, kind_of` cho **mọi** `href` trong kho thô (bóc lại
     từ HTML đã lưu) cộng `N1-links`, cộng các ca biên viết tay (`javascript:`, `//host`, `?q`, `#`,
     dấu cách, chữ có dấu, `www.` giữa netloc, cổng, `//` lặp, query rác `utm_*`/`fbclid`/`PHPSESSID`).
     Dự kiến vài chục nghìn dòng; nén gzip.
   - `boc_tach.jsonl.gz` — kết quả `boc_tach()` của **mọi trang trong kho**, có và không có bảng
     khuôn: title, date, author, cited_source, section, block (path, method, score), sha1 của text,
     text, tập cạnh `(dst, type, text, dst_kind, count)`, số cạnh khuôn.
   - `khuon.json` — bảng `templates` dựng từ kho (để Java kiểm `dem_host` / `tap_khuon`).
   - `tep.jsonl` — `bong_chu()` trên mọi tệp trong `data/files/`: status, n_pages, needs_ocr, độ dài, 300 ký tự đầu.
   - `robots/` — robots.txt thật của các host hust + kết quả `can_fetch` cho các url tệp.
2. Chụp phản hồi các endpoint GET (`/api/stats`, `/api/index/stats`, `/api/search` với 25 truy vấn
   mặc định chép từ `so_sanh.py` trước khi xoá × 2 ranking, `/api/extract/coverage`, `/api/graph/stats`,
   `/api/referrers` mẫu, `/api/files`, `/api/images`) → `tests/golden/api/`.
3. Gắn tag git `truoc-port-java`.

### Giai đoạn 1 — Dựng project Maven gộp (0,5 ngày)

1. `git mv lucene/pom.xml lucene/src lucene/Dockerfile hust-search/` (`lucene/target`, `lucene.log` bỏ).
2. Thêm vào `pom.xml`: `jsoup`, `mongodb-driver-sync`, `tika-core`, `tika-parsers-standard-package`;
   phần test: `testcontainers` + `mongodb` (testcontainers), `junit-jupiter` (đã có).
3. Surefire: test gắn `@Tag("mongo")` **không** chạy mặc định (bước `mvn package` trong Docker
   build không có docker); chạy bằng `mvn verify -Pmongo` trên máy có docker.
4. `Main.java` tạm thời chỉ thay `SearchServer` (cùng route cũ) để chắc build/Docker vẫn chạy;
   31 JUnit hiện có vẫn xanh.

### Giai đoạn 2 — `kho/`: url và kho thô (1 ngày)

1. `Url.java`: `norm(url, base)`, `norm(url)` (base mặc định `https://hust.edu.vn`), `dedupKey`,
   `kindOf`, `pageOf` (6 mẫu phân trang), hằng `UA`. Javadoc ghi rõ: **bản sao của
   `crawl_all.py`, sửa bên này thì sửa bên kia và chạy lại `url.tsv`**.
2. `Kho.java`: `khoDirs()`, `hostOf()`, `records(dir)` (lười, chịu shard cụt), `tatCaBanGhi()`
   (bỏ url trùng), `timBanGhi(Set<String>)` (lọc thô theo chuỗi rồi mới parse JSON), `ghiKho(rec)`
   vào `raw-adhoc/pages-0001.jsonl.gz`, flush từng dòng (ràng buộc trong CLAUDE.md).
3. `TaiVe.java`: nhịp tối thiểu 3 s giữa hai lần tải lẻ (khoá chung), UA trình duyệt, theo redirect,
   429 → `HttpError(429)`, ≥ 400 → 502, lỗi kết nối → 502 "không tải được: …".

**Cổng qua:** `UrlGoldenTest` khớp **100%** `url.tsv`. Lệch một dòng là dừng lại sửa — đây đúng là loại
lỗi "thiếu 17 link" CLAUDE.md đã ghi.
`KhoTest`: shard cụt giữa chừng, nhiều gzip member, dòng JSON hỏng, thoát `\u` trong lọc thô.

### Giai đoạn 3 — `boctach/`: bóc tách HTML (2 ngày) — phần rủi ro nhất

Thứ tự port theo phụ thuộc, mỗi file có test riêng trước khi sang file sau:

1. `HtmlSach`: `joinHttp`, `cleanSpace`, `textBs4` (bẫy 1, 2), `donHtml` (bẫy 8; giữ danh sách thẻ
   và thuộc tính, cắt 40.000).
2. `Khuon`: `vanTay` (sha1 16 hex của chuỗi lower, số → `0`), `khoiLa`, `vanTayTrang`, `demHost`,
   `tapKhuon`, `boKhuon`; hằng `NGUONG_TRANG = 0.30`, `TOI_THIEU_TRANG = 20`.
3. `Khoi`: `donCay`, `duongDan`, `thongKe` (C, LC, P, Q từ lá lên gốc), `diem`, `heSo`, `soChu`,
   `nhan`, `timKhoi` (selector theo host → heuristic đi xuống → fallback), `ghiBac` cho
   `/api/extract/explain`. Hằng `ALPHA=2, BETA=30, GAMMA=1, DELTA=0.65` và hai regex
   `PHAT` / `THUONG` chép nguyên.
4. `Truong`: `jsonLd` (bẫy 4), `tieuDe`, `catHauTo`, `chuanNgay`, `ngayDang`, `tacGiaMeta`,
   `dongTacGiaNguon` (gỡ dòng tác giả/nguồn khỏi khối — sửa cây tại chỗ, bẫy 5).
5. `LienKet`: `trongHoHust`, `loaiDich`, `thuThap` (trước khi dọn cây), `chia`, `canhRaCongKhai`.
6. `BocTach`: `bocTach(html, url, khuon)` → record, và `giaiThich(...)`. Hằng
   `VERSION = "2"` (bản Python là `"1"`), để `coverage` và `extractor_version` phân biệt được trang do bản nào bóc.

Test: port 25 test của `test_boc_tach.py` dùng `tests/fixtures/html` (chuyển sang `src/test/resources/html`).

**Cổng qua (`SoKhopTest`, chạy trên `boc_tach.jsonl.gz`)** — ngưỡng đề xuất, chỉnh sau lần đo đầu:

| Trường | Ngưỡng khớp |
|---|---|
| `block.method`, `title`, `date`, `author`, `cited_source`, `section` | khớp chính xác ≥ 99% số trang |
| `block.path` | khớp chính xác ≥ 97% |
| `text` | Jaccard theo âm tiết ≥ 0,98 trên ≥ 97% số trang |
| tập cạnh nội dung `(dst, type)` | khớp chính xác ≥ 98% |

Test in ra 20 trang lệch nặng nhất kèm `block.path` của hai bên, để xem lệch do bẫy nào (thường là
bẫy 1 hoặc 2) hay do cây HTML5 khác cây libxml2 (bẫy 10 — chấp nhận nếu nằm trong ngưỡng).
`danh_gia_khoi.py` port thành một test in P/R/F1 của 3 mốc (body / lớp 3 / lớp 2+3) trên bộ mẫu
tổng hợp; số Java phải bằng số Python ± 0,01.

### Giai đoạn 4 — `Tep.java` bằng Tika (0,5 ngày)

```java
// phác thảo
AutoDetectParser p = new AutoDetectParser();
BodyContentHandler h = new BodyContentHandler(TOI_DA_KY_TU);   // 500.000, quá thì Tika ném lỗi giới hạn → cắt, vẫn ok
ParseContext ctx = new ParseContext();
PDFParserConfig pdf = new PDFParserConfig(); pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
ctx.set(PDFParserConfig.class, pdf);
p.parse(new ByteArrayInputStream(data), h, meta, ctx);
```

- Giữ nguyên hình dạng kết quả: `{status, text, n_pages, needs_ocr, encoding_suspect, error}`.
- `n_pages` lấy từ metadata: `xmpTPg:NPages` (pdf), `meta:page-count` (docx), `meta:slide-count`
  (pptx); xlsx thì đếm sheet. Không có thì 0.
- Giữ luật `needs_ocr` (pdf có ít hơn 20 ký tự mỗi trang → bỏ chữ, gắn cờ), giữ heuristic
  `nghi_sai_bang_ma` (TCVN3/VNI) và bước chuẩn hoá khoảng trắng.
- Q2: `HO_TRO = {pdf, docx, xlsx, pptx, doc, xls, ppt}`, `CU` rỗng.
- Tệp hỏng → `status=error`, không ném lỗi ra ngoài (Tika ném `TikaException` / `SAXException` /
  `IOException`, và POI đôi khi ném `RuntimeException` — bắt cả).
- Tika không có OCR trong image (không cài tesseract) nên `NO_OCR` chỉ để chắc chắn.

Test: port 13 test của `test_tep.py`. Python đang sinh tệp mẫu lúc chạy test (python-docx…); sinh
một lần bằng Python rồi **commit tệp mẫu** vào `src/test/resources/tep/` (vài KB), cộng một tệp
`.doc` và `.xls` cho Q2.
**Cổng qua:** trên `tep.jsonl`, cùng `status` ≥ 98%, chữ bóc ra không ngắn hơn bản pdfminer quá 10%
trên ≥ 95% số tệp. Tika và pdfminer ngắt dòng khác nhau là bình thường.

### Giai đoạn 5 — `mongo/`: lưu trữ và các việc nền (1 ngày)

1. `Db.java`: đọc `mongo-schema.json` (chép `SCHEMAS` + `INDEXES` từ `db.py` sang JSON —
   `Document.parse` gọn hơn dựng BSON bằng code), `init()` = `create`/`collMod` kèm validator +
   tạo index, chạy lại nhiều lần không sao. Kết nối với `serverSelectionTimeoutMS=3000`; Mongo chưa
   lên thì API vẫn chạy, các route Mongo trả 503 (giữ hành vi hiện tại).
2. `Trich.java`: `dungTemplates`, `khuonTheoHost`, `dungBanGhi`, `ghiMotTrang`, `ghiPhuMotTrang`,
   `luceneTuMongo`, `tepTuMongo`, `chayExtract` (bộ đệm 200 trang, bí danh qua `dedupKey`, dựng lại
   `nav_links` và `images` mỗi lần chạy), `coverage`. Hằng `TOI_THIEU_DEM = 3`, `NGUONG_GIU = 0.05`.
3. `TepJob.java`: `danhMuc`, `tai` (robots.txt, nhịp 2,5 s, 429 thì chờ `Retry-After`, trần 50 MB),
   `bocChu`.
4. `Robots.java`: tách nhóm theo `User-agent`, `Allow`/`Disallow` theo tiền tố, **dòng khớp đầu tiên
   thắng** như `urllib.robotparser`; không tải được robots.txt thì coi như cho phép. Kiểm bằng
   `golden/robots/`.
5. Kiểu số khi ghi: `count`, `n_pages`, `size`, `word_count` ghi `Integer`/`Long`, `score` ghi
   `Double` — khớp `bsonType` của lược đồ.
6. Q2: lệnh chuyển dữ liệu một lần — `documents` có `status: unsupported` mà `ext ∈ {doc, xls, ppt}`
   chuyển về `pending` (đã có `sha1` thì vào thẳng bước bóc chữ, chưa có thì tải).

Test: port 22 test của `test_mongo.py` sang Testcontainers (`@Tag("mongo")`). Thêm test **ghi bản
ghi sai lược đồ bị từ chối** — mongomock không chạy `$jsonSchema` nên đến giờ phần này chưa từng
được kiểm (CLAUDE.md: "phần bóc tách chưa từng chạy trên MongoDB thật").

### Giai đoạn 6 — `web/`: HTTP (1 ngày)

1. `Http.java`: bảng route `(method, path) → handler`; đọc query (giải mã UTF-8, kiểu int/bool/
   float với giá trị mặc định), body JSON → record; ánh xạ `HttpError(code, detail)` → JSON
   `{"detail"}`; lỗi khác → 500 kèm log; phục vụ `static/` (chặn `..`); executor virtual thread.
2. `ViecNen.java`: một việc nền tại một thời điểm (`templates | extract | files | files-extract`),
   trạng thái `{running, what, done, started, result, error, elapsed_sec}` — đúng khoá
   `/api/extract/status` đang trả.
3. `ApiTimKiem`, `ApiBocTach`, `ApiCrawl`: theo bảng ở mục 3. Lỗi nghiệp vụ dùng lại đúng câu chữ
   tiếng Việt của bản Python (giao diện hiện nguyên văn).
4. `Main.java`: mở `Index` từ `INDEX_DIR`, thử `Db.init`, dựng server ở `PORT` (mặc định 8000).

Test: port 5 test của `test_api.py` (kiểm tra hợp lệ `PublicDocument`, ánh xạ sang tài liệu Lucene)
và 11 test của `test_extract_url.py`. Bản Python thay `main.tai_ve` bằng mock; Java truyền vào
`ApiBocTach` một `Function<String, PhanHoi>` để tải (chỉ dùng kiểu có sẵn của JDK, không thêm
interface riêng cho test).
`ApiGoldenTest` (`@Tag("stack")`) gọi bản Java đang chạy rồi so với `tests/golden/api/`: cùng khoá
JSON, cùng kiểu dữ liệu; số liệu được phép lệch trong ngưỡng của giai đoạn 3.

### Giai đoạn 7 — Service crawler và compose ở thư mục cha (0,5 ngày)

1. `hust-crawler/Dockerfile`: `python:3.12-slim`, `pip install -r requirements.txt` (requests, bs4,
   lxml — không có Playwright). Code mount vào `/app`, `data/` nằm trong đó.
2. `hust-crawler/crawlctl.py` (Q1-A): chuyển `crawl_start` / `crawl_stop` / `crawl_status` từ
   `main.py` sang, gần như nguyên văn (`subprocess.Popen`, SIGTERM rồi chờ 30 s mới kill, đọc 12
   dòng cuối log, ghi `data/crawler.pid`). Phục vụ bằng `http.server` của stdlib, chỉ nghe trong
   mạng docker. `hustctl` trên máy host vẫn dùng được như cũ.
3. Chuyển `docker-compose.yml` + `docker-compose.compass.yml` lên `di-project/` (mục 2.3), bỏ
   profile `compare` (Q3).
4. Giao diện: bỏ ô chọn `render` ở tab Crawl (`index.html` dòng 424 và dòng 991).
5. Sửa chỗ trỏ đường dẫn: `tests/integration*.sh` (`docker compose` chạy từ thư mục cha, hoặc
   `-f ../docker-compose.yml`), `hust-crawler/hustdata` dòng 181, `README.md`, `CLAUDE.md`.

### Giai đoạn 8 — Chạy tích hợp trên kho thật (1 ngày)

1. `docker compose up -d --build` từ `di-project/`.
2. `tests/integration.sh` (33 kiểm tra) và `tests/integration_bt.sh` (bóc tách / Mongo / đồ thị /
   tệp) — đây là **lần đầu** phần bóc tách chạy trên MongoDB thật và kho thật, nên chờ sẵn những
   lỗi đã có từ bản Python mà chưa ai thấy (lược đồ từ chối, document vượt 16 MB ở `templates`…).
   Lỗi nào có ở cả bản Python thì ghi lại, sửa ở bản Java.
3. `mvn verify -Pmongo` và `ApiGoldenTest`.
4. Đo nhanh: thời gian `extract/run` cả kho, `index/run`, bộ nhớ đỉnh (Tika + Lucene + jsoup) để
   đặt `-Xmx` (dự kiến 1 GB thay cho 512 MB).

### Giai đoạn 9 — Cắt chuyển và dọn (0,5 ngày)

1. Dựng lại toàn bộ bằng bản Java: `extract/templates` → `extract/run` → (Q2) chuyển `documents`
   → `files/fetch` → `files/extract` → `index/run?reset=true`.
2. So kết quả tìm kiếm 25 truy vấn × 2 ranking với ảnh chụp ở giai đoạn 0: overlap@10 trung bình
   ≥ 0,8 (đề xuất); truy vấn nào dưới 0,5 thì xem tay.
3. Xoá `api/`, `lucene/`, `tests/*.py` (kể cả `xuat_golden.py`), `requirements.txt`; bỏ cổng 8081.
4. Cập nhật tài liệu: `README.md`, `CLAUDE.md` (bố cục, lệnh chạy, số test, "sửa `crawl_all.py`
   không cần build lại"), `THUAT-TOAN-BOC-TACH.md` mục "file nào làm gì" (`.py` → `.java`),
   `KE-HOACH-BOC-TACH.md` (ghi chú đã port), `BAO-CAO-KY-THUAT.md` (sơ đồ luồng: bỏ chặng HTTP
   Python → Java). `SCHEMA.md` không đổi — lược đồ giữ nguyên.
5. Bỏ `name: hust-search` trong compose nếu muốn đổi tên project (mục 2.3).

---

## 6. Ước lượng

| Giai đoạn | Ngày |
|---|---|
| 0 Chụp mốc | 0,5 |
| 1 Maven gộp | 0,5 |
| 2 Url + kho | 1 |
| 3 Bóc tách | 2 |
| 4 Tika | 0,5 |
| 5 Mongo | 1 |
| 6 HTTP | 1 |
| 7 Crawler + compose | 0,5 |
| 8 Tích hợp | 1 |
| 9 Cắt chuyển | 0,5 |
| **Cộng** | **8,5** |

Con số này cao hơn ước lượng 3-5 ngày đưa ra lúc đầu. Phần tăng thêm nằm ở các cổng so khớp (giai
đoạn 0, 2, 3), test Mongo thật và service crawler — chính những thứ làm cho bản port đáng tin. Phần
viết code thuần vẫn khoảng 4-5 ngày.

## 7. Rủi ro

| Rủi ro | Mức | Giảm thiểu |
|---|---|---|
| Bóc tách lệch do jsoup ≠ lxml và ngữ nghĩa chuỗi | cao | bảng bẫy ở mục 4, `SoKhopTest` trên cả kho, ngưỡng rõ ràng |
| `Url.norm` Java lệch `crawl_all.norm` theo thời gian | cao | `url.tsv` 100%; javadoc hai bên trỏ sang nhau; sửa crawler thì sinh lại vector |
| Tika làm image nặng (~+70 MB) và khởi động chậm | thấp | chấp nhận; đo ngày 03/10: `hust-search-api` (nền Playwright) 3,04 GB, `hust-search-lucene` 505 MB — gộp lại cộng Tika vẫn dưới 1 GB |
| PDF bệnh làm Tika treo hoặc ăn hết heap | trung bình | trần 50 MB/tệp (đã có), bóc chữ chạy trong việc nền; nếu gặp thật thì thêm timeout cho mỗi tệp |
| Mất khả năng crawl trang SPA (`work.hust.edu.vn`) khi bỏ Playwright | trung bình | ghi rõ trong README; cần lại thì dựng image crawler riêng có Playwright, không đụng tới Java |
| Đổi tên project compose làm "mất" volume | trung bình | `name: hust-search` trong lúc chuyển (mục 2.3) |
| Lỗi tiềm ẩn chỉ lộ ra trên Mongo thật | trung bình | giai đoạn 5 (Testcontainers) và giai đoạn 8 |

## 8. Thứ tự commit dự kiến (nhánh `port-java`)

1. `Chụp mốc golden từ bản Python trước khi port`
2. `Gộp lucene/ thành project Maven ở hust-search/`
3. `Port norm/dedup_key/kind_of và đọc kho thô sang Java`
4. `Port bóc tách khối, trường, liên kết sang jsoup`
5. `Bóc chữ tệp bằng Tika, nhận cả doc/xls/ppt`
6. `Port lớp MongoDB: lược đồ, trích, tải tệp`
7. `HTTP API Java thay FastAPI, giữ nguyên hợp đồng`
8. `Service crawler riêng, compose lên thư mục cha, bỏ Playwright`
9. `Cắt chuyển sang bản Java, xoá api/ Python, cập nhật tài liệu`
