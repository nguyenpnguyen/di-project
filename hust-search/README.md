# HUST Crawl & Search

Crawl `hust.edu.vn` và các subdomain, index bằng **Apache Lucene thuần**, tìm
kiếm qua giao diện web. Đóng gói bằng docker compose.

Tài liệu này viết để **người hoặc AI khác đọc rồi sửa được ngay**: mỗi phần đều
nói rõ file nào làm gì, vì sao chọn cách đó, và chỗ nào từng hỏng.

---

## 1. Chạy trong 3 lệnh

```bash
cd hust-search
docker compose up -d --build          # lần đầu ~5 phút (build maven + playwright)
open http://localhost:8000            # giao diện
```

Kiểm tra sống:

```bash
curl -s localhost:8000/api/health     # {"api":true,"lucene":true,...}
./tests/integration.sh                # 33 kiểm tra đường đi thật
```

Lần đầu index chưa có gì. Vào tab **Bảng điều khiển** bấm **Index thêm vào kho**,
hoặc:

```bash
curl -X POST localhost:8000/api/index/run -H 'content-type: application/json' -d '{"batch":200}'
```

Dừng: `docker compose down` (index nằm ở volume `lucene-index`, không mất).

---

## 2. Cây thư mục

```
DI/
├── hust-crawler/                  ENGINE CRAWL — chạy được độc lập, không cần docker
│   ├── crawl_all.py               bò toàn site, lưu HTML thô base64 vào JSONL
│   ├── read_raw.py                đọc/soát kho: stats, audit, links, rebuild-state…
│   ├── render.py                  tải bằng Chromium thật khi requests lấy hụt
│   ├── crawl_hust.py              wrapper có parse (bóc bài ra 17 trường) — độc lập
│   ├── hustctl                    lệnh gọn: start / stop / status / resume / file
│   ├── crawl_subdomains.sh        quét lần lượt nhiều subdomain
│   ├── tests/test_engine.py       32 test cho engine
│   └── data/                      KHO DỮ LIỆU (gitignored)
│       ├── raw/                   kho của hust.edu.vn
│       │   ├── pages-*.jsonl.gz   mỗi dòng một trang, HTML ở trường html_b64
│       │   ├── state.json         hàng đợi + đã tải + bảng khoá bài
│       │   ├── N1-links           danh sách link, mỗi dòng một link, KHÔNG có đuôi
│       │   ├── subdomains.txt     58 host thuộc hust.edu.vn
│       │   └── sample20.txt, sample10hard.txt
│       └── raw-<host>/            mỗi subdomain một kho riêng, cùng cấu trúc
│
└── hust-search/                   STACK DOCKER
    ├── docker-compose.yml         2 dịch vụ: lucene (Java) + api (Python)
    ├── lucene/                    DỊCH VỤ TÌM KIẾM — Java 21 + Lucene 9.11
    │   ├── pom.xml
    │   ├── Dockerfile             build đa tầng: maven → JRE
    │   └── src/
    │       ├── main/java/vn/hust/search/
    │       │   ├── Index.java         bọc Lucene: put/commit/search/stats/reset
    │       │   └── SearchServer.java  HTTP bằng com.sun.net.httpserver của JDK
    │       └── test/java/.../IndexTest.java   29 test
    ├── api/                       ĐIỀU KHIỂN + GIAO DIỆN — Python FastAPI
    │   ├── main.py                crawl start/stop/status, index, search, stats
    │   ├── static/index.html      giao diện một trang, không framework
    │   ├── requirements.txt
    │   └── Dockerfile             nền image playwright (có sẵn Chromium)
    ├── tests/fixtures/corpus.json corpus mẫu theo schema public
    ├── tests/test_api.py          5 test parser/schema bằng unittest
    └── tests/integration.sh       33 kiểm tra trên stack đang chạy
```

**Chia việc giữa hai ngôn ngữ:** Python bóc chữ từ HTML (BeautifulSoup và bộ
selector cho hust.edu.vn đã kiểm chứng ở tầng crawl), Java chỉ lo đúng việc của
Lucene là index và tìm. Nhờ vậy phía Java không cần jsoup, không cần biết gì về
cấu trúc site.

---

## 3. Luồng dữ liệu

```
   web hust.edu.vn
        │  crawl_all.py  (nhịp tự dò, robots.txt, render khi cần)
        ▼
   data/raw*/pages-*.jsonl.gz        HTML thô base64, chưa parse
        │  api/main.py extract()     BeautifulSoup → schema public
        │                             {url,title,content,host,section,published_at,outgoing_links}
        ▼
   POST lucene:8081/bulk             updateDocument theo Term(url) → không đẻ trùng
        ▼
   index Lucene (volume)
        │  GET /search?q=&ranking=tfidf
        ▼
   giao diện: tô <mark>, bấm mở tab mới
```

Ba tầng tách rời có chủ đích: **tải là phần đắt và bị rate-limit, parse thì rẻ
và hay phải sửa**. Có kho thô rồi thì sửa selector hay index lại bao nhiêu lần
cũng không phải đụng lại mạng.

---

## 4. Dùng crawler

### 4.1. Lệnh gọn — `hustctl`

```bash
cd hust-crawler
./hustctl status                     # đang chạy gì, mỗi site tải/chờ bao nhiêu
./hustctl resume                     # chạy tiếp mẻ dở
./hustctl links-only                 # chỉ lấy link, không tải nội dung bài
./hustctl file data/sample20.txt     # crawl đúng danh sách link trong file
./hustctl log 40                     # 40 dòng log cuối
./hustctl stop                       # dừng mềm: đóng shard, ghi state rồi thoát
./hustctl kill                       # chỉ khi stop không ăn
```

`hustctl` ghi pid ra `data/crawler.pid` nên dừng đúng tiến trình.
**Đừng `pkill -f crawl_all.py`** — nó khớp cả dòng lệnh shell đang gõ và giết
nhầm terminal.

### 4.2. Crawl theo file danh sách link

```bash
python crawl_all.py --resume --from-file data/sample20.txt --allow-domain hust.edu.vn
```

| Tham số | Ý nghĩa |
|---|---|
| `--from-file F` | crawl đúng các url trong F (bỏ dòng trống và dòng `#`) |
| `--follow` | bò tiếp theo link tìm được; mặc định chỉ tải đúng danh sách |
| `--allow-domain D` | nhận mọi host thuộc D, cần khi file trộn nhiều subdomain |

**Luôn kèm `--resume`** khi kho đã có dữ liệu. Xem mục 8.4 vì sao.

### 4.3. Trang khó — render bằng trình duyệt thật

```bash
python crawl_all.py --resume --from-file data/sample10hard.txt \
   --allow-domain hust.edu.vn --render auto --insecure
```

| Tham số | Ý nghĩa |
|---|---|
| `--render never` | mặc định, chỉ dùng requests |
| `--render auto` | chỉ render khi trang **có vẻ bị chặn hoặc rỗng do JS** |
| `--render always` | render mọi trang, chậm hơn nhiều |
| `--insecure` | bỏ kiểm chứng chỉ TLS (vài subdomain thiếu chứng chỉ trung gian) |

`render.py` quyết định "có vẻ bị chặn" bằng `looks_blocked()`: status 403/429/503,
hoặc HTML chứa dấu hiệu chặn bot, hoặc trang gần như không có link và rất ít chữ.

Đây là công cụ đọc trang công khai mà JavaScript mới dựng ra. Gặp captcha thật
thì trả về nguyên trạng để ghi nhận là bị chặn, không tìm cách giải.

### 4.4. Site khác / subdomain

```bash
python crawl_all.py --site svbk.hust.edu.vn --only listing
./crawl_subdomains.sh bulletin tuyendung work        # quét lần lượt
```

Mỗi site một kho riêng `data/raw-<host>/`. Kiểu phân trang tự nhận theo 6 mẫu
(`/page-N/`, `/page/N/`, `?page=N`, `?paged=N`, `/trang-N/`, `/pN/`) và crawler
dùng **đúng khuôn url mà chính trang đó in ra** nên CMS khác không phải sửa code.

### 4.5. Soát kho

```bash
python read_raw.py --stats           # kho có gì
python read_raw.py --audit           # chuyên mục nào tải thiếu trang
python read_raw.py --fix-roots       # chuyên mục nào rơi khỏi hàng đợi
python read_raw.py --check           # state và shard có lệch nhau không
python read_raw.py --links           # xuất lại N1-links (gộp mọi kho + subdomain)
python read_raw.py --verify-links    # soát độc lập: bóc lại href từ HTML mà đối chiếu
python read_raw.py --rebuild-state   # CỨU HỘ: dựng lại state.json từ shard + N1-links
```

Bốn lệnh đầu trả lời bốn câu hỏi khác nhau, đừng nhầm — xem mục 8.

---

## 5. API

Tất cả dưới `http://localhost:8000`.

| Method | Đường dẫn | Việc |
|---|---|---|
| GET | `/` | giao diện |
| GET | `/api/health` | api và lucene có sống không |
| GET | `/api/stats` | mỗi site tải/chờ bao nhiêu, dung lượng, số link |
| POST | `/api/crawl/start` | chạy một mẻ crawl |
| POST | `/api/crawl/stop` | SIGTERM rồi chờ 30s, cùng lắm mới kill |
| GET | `/api/crawl/status` | đang chạy không, log 12 dòng cuối |
| POST | `/api/index/run` | đọc kho → bóc chữ → đẩy vào Lucene |
| POST | `/api/index/documents` | nhận corpus JSON theo schema public |
| GET | `/api/index/stats` | số tài liệu, dung lượng index, theo host |
| GET | `/api/search?q=&size=&host=&ranking=` | kết quả kèm đoạn đã tô `<mark>` |
| POST | `/api/fetch` | tải một URL, trả document và index ngay |

```bash
# crawl tiếp, trần 200 trang
curl -X POST localhost:8000/api/crawl/start -H 'content-type: application/json' \
     -d '{"mode":"resume","max_pages":200}'

# chỉ lấy link của một subdomain
curl -X POST localhost:8000/api/crawl/start -H 'content-type: application/json' \
     -d '{"mode":"listing","site":"svbk.hust.edu.vn","max_pages":300}'

# tải một bài, bóc document và index ngay
curl -X POST localhost:8000/api/fetch \
     -H 'content-type: application/json' \
     -d '{"url":"https://hust.edu.vn/vi/news/example.html"}'

# index một corpus JSON (reset=true nếu muốn dựng lại từ đầu)
curl -X POST localhost:8000/api/index/documents \
     -H 'content-type: application/json' --data @tests/fixtures/corpus.json

# tìm theo TF-IDF của Lucene
curl -s --get localhost:8000/api/search \
     --data-urlencode "q=diem chuan" --data-urlencode "ranking=tfidf" \
     --data-urlencode "sort=score"
```

### Schema tài liệu public

`/api/fetch` trả `document`, và `/api/index/documents` nhận cùng một cấu trúc:

```json
{
  "url": "https://hust.edu.vn/vi/news/example.html",
  "title": "Tiêu đề bài viết",
  "content": "Nội dung văn bản đã bóc tách",
  "host": "hust.edu.vn",
  "section": "Tin tức",
  "published_at": "2026-09-11",
  "outgoing_links": [
    {"url": "https://hust.edu.vn/admissions/", "text": "Thông tin tuyển sinh"}
  ]
}
```

`url` và `outgoing_links[].url` chỉ nhận HTTP/HTTPS; request corpus tối đa 5.000
tài liệu. `title` tối đa 500 ký tự, `content` tối đa 200.000 ký tự,
`published_at` rỗng hoặc dạng `YYYY-MM-DD`. `host` rỗng sẽ được suy từ hostname;
URL trùng trong một request giữ bản cuối. Các link đi ra được chuẩn hóa bằng
`urljoin`, bỏ fragment, lọc scheme nguy hiểm và khử trùng. Mảng link chỉ phục vụ
output/hiển thị, chưa đưa vào chỉ mục Lucene.

### Xếp hạng

`ranking=tfidf` là mặc định: Lucene dùng `ClassicSimilarity`, tìm trên
`title_kd` và `text_kd` với boost bằng nhau. Điểm dùng ý tưởng
`TF × IDF × document norm`; query không dấu vẫn tìm được nội dung có dấu.
`ranking=enhanced` giữ các thưởng cụm, loại trang, độ dài và độ mới hiện có.

`sort=score` mới là thứ tự theo điểm; khi đó UI gọi đúng tên điểm theo chế độ.
`sort=date` sắp ngày mới trước và không gọi số điểm đó là TF-IDF.

Lucene cũng nghe trực tiếp ở `localhost:8081` (`/bulk`, `/search`, `/stats`,
`/reset`, `/health`) — tiện khi cần gỡ lỗi riêng tầng index.

---

## 6. Giao diện

Một file `api/static/index.html`, không framework, không bước build.

* **Tìm kiếm** — gõ từ khoá, lọc theo site và chọn `TF-IDF` hoặc `Nâng cao`.
  Phần khớp được **Lucene** tô `<mark>` (màu bơ pastel), bấm tiêu đề mở trang
  gốc ở tab mới; khi xếp theo điểm, giao diện ghi rõ điểm TF-IDF.
* **Tải một trang** — trả tiêu đề, nội dung, URL đầy đủ và văn bản mô tả của
  link đi ra; nội dung thu gọn mặc định và có nút tải JSON.
* **Bảng điều khiển** — số trang crawl, số tài liệu index, số link, dung lượng;
  bảng từng site; nút chạy/dừng crawl; nút index thêm hoặc dựng lại; log trực tiếp.

Tô sáng làm ở **server chứ không phải trình duyệt**: Lucene biết chính xác token
nào khớp sau khi phân tích, còn JS phía trình duyệt chỉ so chuỗi thô nên sẽ trượt
các trường hợp hoa/thường hay dấu câu dính liền.

Màu: nền giấy ấm `#fbf9f6`, chấm phá mint / blush / sky / lilac pastel, chữ
**Be Vietnam Pro** cho tiếng Việt và **Lora** cho tiêu đề.

### Kịch bản demo 5–7 phút

1. Mở tab **Tải một trang**, dán URL bài viết, bấm **Tải và index**; chỉ ra tiêu
   đề, nội dung thu gọn và bảng URL/link mô tả, rồi bấm **Tải JSON** nếu cần.
2. Nạp corpus cố định:

   ```bash
   curl -X POST localhost:8000/api/index/documents \
        -H 'content-type: application/json' --data @tests/fixtures/corpus.json
   ```

3. Tìm không dấu theo TF-IDF:

   ```bash
   curl -s --get localhost:8000/api/search \
        --data-urlencode 'q=tuyen sinh ky thuat' \
        --data-urlencode 'ranking=tfidf' --data-urlencode 'sort=score'
   ```

   Chỉ ra `ranking`, điểm giảm dần, đoạn `<mark>` và lựa chọn ranking trong UI.

---

## Hướng dẫn chạy theo yêu cầu đề bài

### A. Khởi động

```bash
cd hust-search
docker compose up -d --build
curl -s http://localhost:8000/api/health
open http://localhost:8000
```

Chỉ tiếp tục khi response health có `"api":true` và `"lucene":true`. Index
nằm trong volume Docker `lucene-index`; `docker compose down` không xóa volume.

### B. Luồng thu thập dữ liệu

Trên giao diện: chọn **Tải một trang** → dán URL bài viết đầy đủ
(`http://` hoặc `https://`) → bấm **Tải và index**. Kết quả phải có tiêu đề,
nội dung, số link đi ra, URL đầy đủ và văn bản mô tả. Nút **Tải JSON** lưu đúng
`document` theo schema public.

Có thể chạy bằng lệnh:

```bash
curl -s -X POST http://localhost:8000/api/fetch \
  -H 'content-type: application/json' \
  -d '{"url":"https://THAY-BANG-URL-BAI-VIET-THAT"}' | python3 -m json.tool
```

Trong response, kiểm tra các trường:

```text
response.document.title
response.document.content
response.document.outgoing_links[].url
response.document.outgoing_links[].text
```

Trang tải lẻ được ghi vào `hust-crawler/data/raw-adhoc/` và index ngay. Máy chủ
giữ khoảng cách tối thiểu 3 giây giữa hai lần tải; HTTP 429 nghĩa là website
đang giới hạn nhịp.

### C. Luồng JSON → chỉ mục → truy vấn

Dùng corpus cố định để trình diễn không phụ thuộc website:

```bash
curl -s -X POST http://localhost:8000/api/index/documents \
  -H 'content-type: application/json' \
  --data @tests/fixtures/corpus.json | python3 -m json.tool
```

Sau đó tìm theo TF-IDF:

```bash
curl -s --get http://localhost:8000/api/search \
  --data-urlencode 'q=fixturealpha' \
  --data-urlencode 'ranking=tfidf' \
  --data-urlencode 'sort=score' \
  --data-urlencode 'size=10' | python3 -m json.tool
```

Response cần có `ranking: "tfidf"`, `sort: "score"`, danh sách `hits`,
`score` và `fragments` chứa `<mark>`. Dùng `q=diem chuan` để chứng minh truy
vấn không dấu vẫn tìm được nội dung có dấu. Luôn dùng `sort=score` khi cần
khẳng định thứ tự TF-IDF; `sort=date` chỉ sắp theo ngày.

Trên giao diện, chọn tab **Tìm kiếm**, chọn **TF-IDF**, nhập cùng truy vấn và
giữ **Xếp theo độ khớp**. Chọn **Nâng cao** chỉ khi muốn trình diễn chế độ
`ranking=enhanced`, không gọi đó là TF-IDF thuần.

### D. Index lại kho crawler

```bash
# thêm tài liệu mới vào index
curl -X POST http://localhost:8000/api/index/run \
  -H 'content-type: application/json' -d '{"reset":false,"batch":200}'

# xóa index rồi dựng lại từ toàn bộ kho raw
curl -X POST http://localhost:8000/api/index/run \
  -H 'content-type: application/json' -d '{"reset":true,"batch":150}'
```

`reset=true` chỉ được dùng khi chủ động dựng lại; không dùng
`docker compose down -v` nếu muốn giữ volume index.

### E. Kiểm tra trước khi trình diễn

```bash
./tests/integration.sh
cd lucene && docker run --rm -v "$PWD":/w -v hust-m2:/root/.m2 \
  -w /w maven:3.9-eclipse-temurin-21 mvn -B test
```

Kết quả hiện tại: **33 integration, 29 JUnit, 5 API và 32 crawler test đạt**.

---

## 7. Test

```bash
# engine (32 test)
cd hust-crawler && .venv/bin/python -m pytest tests -q

# parser/schema API (5 test, không gọi mạng thật)
cd hust-search && docker run --rm -v "$PWD":/workspace -w /workspace \
    hust-search-api:latest python tests/test_api.py -v

# Lucene (29 test)
cd hust-search/lucene && docker run --rm -v "$PWD":/w -v hust-m2:/root/.m2 \
    -w /w maven:3.9-eclipse-temurin-21 mvn -B test

# tích hợp (33 kiểm tra, cần stack đang chạy)
cd hust-search && ./tests/integration.sh
```

Trạng thái gần nhất: **32 engine + 5 API + 29 Lucene + 33 tích hợp = 99 đạt, 0 hỏng**.

Mỗi test ứng với một lỗi đã gặp thật, tên test nói rõ lỗi đó — sửa code mà làm
đỏ test nào thì đọc tên test là biết mình vừa phá cái gì.

### Sample chạy thật

| Mẫu | File | Kết quả |
|---|---|---|
| 20 link mục còn thiếu | `data/sample20.txt` | **20/20 tải được**, 7 MB, 0,8 phút |
| 10 link khó | `data/sample10hard.txt` | **10/10 render được** bằng Chromium |

10 link khó gồm: SPA (`work`), TLS thiếu chứng chỉ trung gian (`tuyendung`),
site đã tắt (`bulletin`), cổng đăng nhập (`ctt-sis`), phân trang `?page=`
(`library`), và 5 subdomain khác. Với `requests` thuần thì `work` chỉ ra 6 KB
khung rỗng và `tuyendung` hỏng hẳn; qua trình duyệt thì lần lượt 35 KB và 58 KB.

---

## 8. Những chỗ từng hỏng — đọc trước khi sửa

### 8.1. Site chặn ~20-25 request/phút

Đặt nhịp 2,2 req/s thì crawler chạy được 0,55 trang/s. `sample <pid>` cho thấy
cả 5 luồng nằm 100% trong `time.sleep`: server trả 429 kèm `Retry-After: 30`,
mà code cũ cho **mỗi luồng ngủ riêng** rồi lại ùa vào.

Nay nhịp **tự dò**: dính 429 thì nhân 1,5 và **cả đàn cùng nghỉ**; yên 25 request
thì rút 10%. `--delay` mặc định 2,5s, **đừng hạ dưới 2**. Thêm luồng không nhanh
hơn vì nhịp khoá chung.

### 8.2. Shard gzip phải flush từng dòng

Flush thưa 25 dòng thì kill cứng làm mất dữ liệu đã ghi. Đo bằng script tự
`kill -9`: flush từng dòng cứu được **301/301** dòng, flush thưa 300, không flush 0.

### 8.3. Khoá khử trùng là ĐOẠN CUỐI url, không phải con số cuối

Ba bài khác hẳn nhau cùng mang đuôi `-654601.html`. Dùng con số làm khoá thì mất
159 bài. Dùng `dedup_key()`, đừng dùng `art_id()` làm khoá.

### 8.4. `--from-file` mà quên `--resume` sẽ xoá sạch state

State chỉ được nạp khi có `--resume`, nên chạy `--from-file` trần thì state khởi
tạo rỗng rồi `save_state()` cuối mẻ ghi đè lên file — mất luôn hàng đợi 4.220 url.

Đã sửa: **luôn nạp state nếu file có**, `--resume` giờ chỉ quyết định có gieo lại
hạt giống hay không. Lỡ mất thì `read_raw.py --rebuild-state` dựng lại từ shard
và `N1-links`.

### 8.5. Lucene 9 là multi-release JAR, đừng shade

Gói phẳng bằng maven-shade làm mất `META-INF/versions/19/` và mất
`MemorySegmentIndexInputProvider`; chạy trên Java 21 ném `LinkageError` ngay khi
mở index. `pom.xml` dùng jar mỏng + thư mục `lib/`, giữ nguyên jar gốc của Lucene.

### 8.6. Highlighter cần MỘT QueryScorer dùng chung

Tạo hai cái riêng cho `Highlighter` và `SimpleSpanFragmenter` thì cái của
fragmenter không bao giờ được init và `getBestFragments` ném NullPointerException.

### 8.7. Playwright sync API gắn với luồng tạo ra nó

Dùng chung một browser giữa các luồng thì ném *"cannot switch to a different
thread"*. `render.py` cho **mỗi luồng một browser** qua `threading.local()`.
Ngoài ra `networkidle` hay timeout với trang có long-poll, nên có bước lùi về
`domcontentloaded`.

### 8.8. Chuyên mục có thể biến mất khỏi kế hoạch crawl

Từng mất 111 chuyên mục (gồm `tin-tuc-su-kien` 295 trang và cả phần tiếng Anh).
Sau mỗi mẻ dài chạy `--fix-roots` rồi `--audit`.

### 8.9. Danh sách link chỉ lấy `href`, không lấy `src`

Gộp `src` vào thì ảnh nhúng `/uploads/` làm file phình từ 14k lên 24k dòng mà
không thêm link nào đi tới được.

---

## 9. Muốn sửa thì sửa ở đâu

| Muốn | Sửa file | Chỗ nào |
|---|---|---|
| Thêm kiểu phân trang mới | `crawl_all.py` | `PAGE_PATTERNS` |
| Đổi luật bỏ qua url | `crawl_all.py` | `SKIP_SEG`, `SKIP_QUERY`, `ASSET` |
| Đổi cách nhận diện bài viết | `crawl_all.py` | `ART_ID`, `dedup_key()`, `kind_of()` |
| Đổi ngưỡng "trang cần render" | `render.py` | `looks_blocked()` |
| Đổi cách bóc tiêu đề/nội dung | `api/main.py` | `extract()` |
| Đổi trọng số xếp hạng | `Index.java` | `MultiFieldQueryParser`, map boost |
| Đổi cách tô sáng | `Index.java` | `SimpleHTMLFormatter("<mark>", "</mark>")` |
| Đổi màu, bố cục | `api/static/index.html` | khối `:root` ở đầu `<style>` |
| Thêm endpoint | `api/main.py` | thêm route FastAPI |

Thay đổi `ClassicSimilarity` hoặc schema field thì phải dựng lại image và index:

```bash
docker compose build lucene && docker compose up -d
curl -X POST localhost:8000/api/index/run \
     -H 'content-type: application/json' -d '{"reset":true,"batch":150}'
```

Sửa Java thì `docker compose build lucene && docker compose up -d`.
Sửa Python phía api thì `docker compose restart api` (code đã COPY vào image;
muốn sửa nóng thì mount thêm `./api:/app`).
Sửa `crawl_all.py` không cần build lại: thư mục `hust-crawler` được mount vào
container ở `/crawler`.

---

## 10. Giới hạn đã biết

* **Chưa tải nội dung hết**: lần rebuild baseline có 3.406 tài liệu index trên ~5.600 bài đã phát hiện.
  Chạy tiếp bằng `./hustctl resume` (~3 giờ ở nhịp 24 trang/phút).
* **Subdomain mới chạm 11/51 host**, phần lớn chỉ mới trang chủ.
* **Không tải file đính kèm** — PDF/DOC chỉ lập danh mục url trong `assets.txt`.
* **Phân tích tiếng Việt ở mức âm tiết**: `StandardAnalyzer` tách theo chuẩn
  Unicode nên "điểm chuẩn" thành hai token. Tìm cụm vẫn đúng, nhưng chưa có tách
  từ ghép. Muốn tốt hơn thì cắm một `Analyzer` tiếng Việt vào `Index.java`.
* **Không có xác thực**: stack mở cổng 8000/8081 không mật khẩu, chỉ dùng ở máy
  cá nhân hoặc mạng nội bộ.

---

## 11. Ghi chú về việc thu thập

Dữ liệu là thông tin công khai trên cổng thông tin của trường, dùng cho bài tập
môn học. `User-Agent` để nguyên dạng có thông tin liên hệ:

```
hust-research-crawler/1.0 (nghien cuu mon IT5420; lien he: student@sis.hust.edu.vn)
```

Tôn trọng `robots.txt`, tôn trọng `Retry-After`, `--delay` không dưới 2 giây.
`--insecure` chỉ bật khi đã biết chắc site đó là thật — bỏ kiểm chứng chỉ là mất
bảo đảm chống giả mạo.
