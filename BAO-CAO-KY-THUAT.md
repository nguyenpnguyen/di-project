# Báo cáo kỹ thuật: hệ thống Crawl + Tìm kiếm hust.edu.vn

Môn Tích hợp dữ liệu — IT5420. Tài liệu này mô tả **công nghệ dùng** và **cách
hệ thống hoạt động ở mức code**, dùng để báo cáo kỹ thuật. Nguồn: mã nguồn thật
trong `hust-crawler/` và `hust-search/`, không suy diễn.

---

## 1. Tổng quan kiến trúc

Hệ thống gồm hai project độc lập, nối với nhau bằng file trên đĩa:

```
┌─────────────────────┐        ┌───────────────────────────────────────────┐
│   hust-crawler/      │        │              hust-search/ (docker)        │
│   (Python, tự chạy,  │        │                                            │
│    không cần docker) │        │  ┌──────────┐        ┌──────────────────┐ │
│                       │──────▶│  │ api       │──────▶│ lucene            │ │
│  crawl_all.py         │ đọc   │  │ (FastAPI, │  HTTP  │ (Java 21,         │ │
│  → data/raw*/         │ JSONL │  │  Python)  │  JSON  │  Lucene 9.11)     │ │
│    pages-*.jsonl.gz   │ thô   │  │  cổng 8000│        │  cổng 8081        │ │
│                       │       │  └──────────┘        └──────────────────┘ │
└─────────────────────┘        └───────────────────────────────────────────┘
```

**Nguyên tắc thiết kế xuyên suốt cả hai project: tách phần đắt/rate-limited
(tải trang) ra khỏi phần rẻ/hay sửa (bóc chữ, đánh chỉ mục, xếp hạng).** Vì
vậy: crawler chỉ ghi HTML thô, không parse; việc bóc chữ và index có thể chạy
lại bao nhiêu lần cũng không tốn thêm request nào tới hust.edu.vn.

**Phân chia theo ngôn ngữ, mỗi ngôn ngữ đúng một việc:**

| Ngôn ngữ | Việc | Vì sao |
|---|---|---|
| Python (`requests`, `BeautifulSoup4`, `lxml`) | Crawl thô + bóc chữ từ HTML | Có sẵn selector cho hust.edu.vn, đã kiểm chứng ở tầng crawl |
| Java 21 (`Lucene 9.11` core, không Elasticsearch/Solr) | Đánh chỉ mục + tìm kiếm + xếp hạng + highlight | Đề bài yêu cầu dùng Lucene thuần |
| Python (FastAPI) | Điều phối: gọi crawler, gọi Lucene, expose API + giao diện | Một lớp mỏng nối hai bên, không chứa logic tìm kiếm |

---

## 2. Crawl — công nghệ và cách hoạt động

### 2.1. Công nghệ dùng

| Thành phần | Công nghệ | File |
|---|---|---|
| Tải trang (đường chính) | `requests` (HTTP client đồng bộ, có Session + connection pool) | `crawl_all.py` |
| Parse HTML để tìm link/phân trang | `BeautifulSoup4` + parser `lxml` | `crawl_all.py` |
| Tuân thủ robots.txt | `urllib.robotparser.RobotFileParser` (thư viện chuẩn) | `crawl_all.py` |
| Lưu trữ thô | JSON Lines (`.jsonl`) nén `gzip`, sharding theo số dòng | `Store` trong `crawl_all.py` |
| Trạng thái/resume | JSON đơn (`state.json`) | `crawl_all.py` |
| Render JS khi `requests` lấy hụt | **Playwright** (Chromium thật, headless) | `render.py` |
| Wrapper có parse (17 trường) | `BeautifulSoup4` + microdata schema.org | `crawl_hust.py` |
| Đọc/soát kho | thuần Python, không thư viện ngoài | `read_raw.py` |

Không dùng framework crawl có sẵn (Scrapy, …) — engine tự viết, khoảng 800
dòng, vì hai lý do trong code: cần kiểm soát chính xác nhịp gọi (rate-limit
site chặn ở mức khắt khe, xem 2.4) và cần logic khử trùng đặc thù của
NukeViet (2.5) mà framework tổng quát không có sẵn.

### 2.2. Cấu trúc site mục tiêu

hust.edu.vn chạy **NukeViet 4** (nhận diện qua cookie `nv4s_*`). Bốn nguồn dữ
liệu khai thác được, ghi trong `crawl_all.py`:

| Nguồn | Vị trí | Đặc điểm |
|---|---|---|
| Sitemap | `/sitemap.xml` → 34 sitemap con theo chuyên mục × ngôn ngữ | có `<lastmod>`, nhưng bị cắt ở 1000 url cho `news`, 7 sitemap rỗng |
| Trang danh mục | `/vi/news/<slug>/page-N/`, 6 bài/trang | thẻ `div.news_column div.panel-body` |
| Metadata bài viết | microdata schema.org `[itemprop=headline\|author\|datePublished\|dateModified\|image]` | đáng tin hơn CSS class vì gắn với dữ liệu, không đổi theo giao diện |
| Nội dung bài | `div.bodytext` | |
| Khoá bài (sai lầm ban đầu) | đuôi url `...-654601.html` | xem 2.5 — **không phải khoá duy nhất** |

### 2.3. Vòng đời crawl (BFS có trọng số)

Lớp `Crawler` (crawl_all.py:245) giữ 3 cấu trúc dữ liệu lõi:

```python
self.frontier: collections.deque   # hàng đợi URL chưa tải, (url, depth, via)
self.queued:   set[str]            # MỌI url đã từng vào hàng đợi — chống lặp
self.done:     dict[str, int]      # url -> http status đã tải
```

**`push(url, depth, via)`** — cổng vào duy nhất của hàng đợi, mọi URL phải đi
qua 5 bước lọc theo đúng thứ tự trong code:

```python
u = norm(url)                          # 1. chuẩn hoá
if not u or not in_scope(u) or u in self.queued: return   # 2. phạm vi + chống trùng URL
if self.a.lang != "all": ...           # 3. lọc theo --lang nếu có
if self.robots and not self.robots.can_fetch(UA, u): return   # 4. robots.txt
key = dedup_key(u)                     # 5. khử trùng BÀI (khác khử trùng url)
```

Sau khi qua lọc, URL được đẩy vào `frontier` ở **đầu hoặc cuối** deque tuỳ
loại (`kind_of()` phân loại `article` / `listing` / `listing-page` / `other`)
và tuỳ cờ `--prefer`:

```python
first = k in ("listing", "listing-page") if self.a.prefer == "listing" else k == "article"
(self.frontier.appendleft if first else self.frontier.append)((u, depth, via))
```

Đây là **BFS ưu tiên**, không phải BFS thuần: mặc định ưu tiên trang danh mục
lên trước (phủ hết chuyên mục trước khi tải bài, để danh mục URL đầy đủ sớm
nhất), `--prefer article` thì đảo lại (ưu tiên có nội dung đọc được ngay nếu
phải dừng giữa chừng). Lý do tách hai chế độ: một lần chạy dở dang toàn trang
danh mục thì kho "gần như vô dụng" (README mục 7.5) — bài học thực tế, không
phải thiết kế lý thuyết trước.

**Vòng lặp chính `run()`** (đơn giản hoá):

```
while frontier không rỗng và chưa đủ --max-pages:
    url, depth, via = pop()
    resp, err = fetch(url)              # có rate-limit, xem 2.4
    rec = visit(url, depth, via, resp)  # phân loại, trích metadata, mở rộng link
    store.add(rec)                      # ghi 1 dòng JSONL
    mỗi --checkpoint trang: save_state()
```

`visit()` sau khi tải xong một trang: nếu là trang danh mục thì gọi
`expand_pagination()` để sinh toàn bộ `page-2..page-N` (2.6); luôn quét mọi
`<a href>` trên trang để đẩy thêm URL nội bộ vào hàng đợi — đây là "lưới vét
cuối cùng" đảm bảo trang nào có người trỏ tới thì cuối cùng cũng được thăm.

### 2.4. Rate limiting tự dò (phần kỹ thuật quan trọng nhất)

**Phát hiện đo được:** hust.edu.vn chặn ở khoảng **20-25 request/phút**, vượt
ngưỡng là HTTP 429 kèm header `Retry-After: ~30`. Ngưỡng này được đo bằng thí
nghiệm bắn thử (curl, gửi N request cách nhau các khoảng thời gian khác nhau,
đếm số 429) — không suy đoán từ tài liệu, vì site không công bố.

**Lỗi ban đầu:** mỗi luồng tự `sleep` riêng khi bị 429, rồi tự ùa vào lại —
kết quả là lúc nào cũng có ít nhất một luồng đang "chịu phạt", tốc độ thực đo
được chỉ 0,55 trang/s dù đặt nhịp 2,2 request/s (chậm gấp 4 lần). Xác định
bằng `sample <pid>` (profiler hệ thống macOS): 100% các luồng nằm trong
`time.sleep`, không phải nghẽn mạng hay parse.

**Cơ chế hiện tại**, cài trong `Crawler`:

```python
def _wait_turn(self):                      # gọi trước MỌI request
    with self.pace:                         # khoá chung cho tất cả luồng
        wait = max(self._next_at - now, self._pause_until - now, 0.0)
        if wait: time.sleep(wait)
        self._next_at = now + self.delay * random.uniform(0.85, 1.15)

def _slow_down(self, retry_after):          # gọi khi dính 429
    self.delay = min(self.delay * 1.5, self.a.max_delay)   # nhân nhịp 1,5x
    self._pause_until = now + retry_after                    # CẢ ĐÀN cùng nghỉ
    self._ok_streak = 0

def _speed_up(self):                        # gọi sau mỗi request thành công
    self._ok_streak += 1
    if self._ok_streak >= 25 and self.delay > self.a.delay:
        self.delay = max(self.a.delay, self.delay * 0.9)     # yên 25 request thì rút 10%
```

Điểm mấu chốt: `_pause_until` là biến **chia sẻ** giữa mọi luồng qua khoá
`self.pace`, nên khi một luồng dính 429 thì *toàn bộ* luồng khác cũng chờ tới
hết `Retry-After` trước khi gửi request tiếp — không có luồng nào "lách" vào
đúng lúc site vừa nghỉ phạt luồng kia. 429 không tính là lỗi của URL (không
mất bài, không tăng biến đếm lỗi) — chỉ là tín hiệu "phải chờ".

Kết quả đo trên thực tế: chạy ổn định ~24 trang/phút, **0 lần dính 429** ở các
mẻ chạy gần nhất. `--workers` mặc định 2 vì nhịp đã khoá chung — thêm luồng
không giúp nhanh hơn.

### 2.5. Khử trùng bài viết — bài toán entity resolution thật

NukeViet in lại cùng một bài dưới nhiều URL khác nhau tuỳ theo chuyên mục
người dùng đang đứng (ví dụ cùng bài xuất hiện ở cả `/su-kien-noi-bat/...` và
`/khoa-hoc-cong-nghe-dmst/...`). Không khử trùng thì ~45% request là tải lại
nội dung đã có.

**Giả định sai ban đầu:** dùng con số cuối URL (`art_id()`, ví dụ `654601`
trong `...-654601.html`) làm khoá bài, vì "nhìn giống ID". Đo thực tế phát
hiện **ba bài hoàn toàn khác nhau** cùng mang đuôi `-654601.html` (tiêu đề,
độ dài nội dung, `og:url` đều khác, không redirect nhau) — hậu quả là 159 bài
thật bị đánh dấu nhầm thành "alias" và không được tải.

**Khoá đúng — `dedup_key()`:** lấy **đoạn cuối đường dẫn** (slug kèm số),
bỏ qua phần chuyên mục ở giữa URL, thay vì chỉ lấy con số. Kiểm chứng hai
chiều bằng cách tải thật và so `sha1` của phần nội dung:

| Giả thuyết | Kiểm chứng | Kết quả |
|---|---|---|
| Cùng đoạn cuối, khác chuyên mục = một bài | so `sha1(bodytext)` hai URL | giống hệt → đúng |
| Cùng con số cuối, khác slug = một bài | so tiêu đề + độ dài | khác hẳn → sai |

Bài học ghi trong code/README, đúng tinh thần *entity resolution* của môn
học: **một trường "trông như ID" chưa chắc là ID — phải kiểm chứng bằng nội
dung, không suy từ hình dạng chuỗi.**

Cơ chế khử trùng vận hành qua hai bảng trong `state.json`:

```python
self.by_key:  dict[str, str]        # slug bài -> URL đầu tiên gặp (url "chính")
self.aliases: dict[str, list[str]]  # url chính -> [url phụ, ...], KHÔNG tải
```

### 2.6. Phân trang — đọc số trang cuối, không "bấm trang sau"

NukeViet luôn in link tới trang phân trang cuối cùng trong HTML (dù widget có
thể rút gọn phần giữa thành `...`). `expand_pagination()` chỉ cần tải **một**
trang gốc chuyên mục, đọc số lớn nhất xuất hiện trong widget, rồi sinh thẳng
toàn bộ `page-2` → `page-N` bằng một vòng `for`:

```python
PAGE_N = re.compile(r"/page-(\d+)/?$")

def expand_pagination(self, url, soup, depth):
    base = PAGE_N.sub("/", urlparse(url).path)
    nums = [int(m.group(1)) for a in soup.select("a[href]")
            for m in [PAGE_N.search(urlparse(urljoin(url, a["href"])).path)]
            if m and urlparse(urljoin(url, a["href"])).path.startswith(base)]
    if not nums: return 0
    last = min(max(nums), self.a.max_pages_per_cat)     # trần an toàn
    for n in range(2, last + 1):
        self.push(f"{BASE}{base}page-{n}/", depth + 1, url)
```

Việc này **không đệ quy** ở bước phân trang (không cần tải `page-2` để biết
`page-3` ở đâu) — chỉ nội dung mỗi trang mới đệ quy (mỗi `page-N` tải về lại
lòi ra bài viết, đẩy tiếp vào hàng đợi). `expand_pagination` vẫn chạy lại trên
mọi trang danh mục đã tải (kể cả `page-46`) nhưng do các URL đó đã nằm trong
`queued`, kết quả là 0 URL mới — đây là **lưới an toàn**: nếu một chuyên mục
có widget không in số trang cuối (hiếm), BFS từ các trang giữa vẫn tự bù được
phần thiếu, chỉ chậm hơn.

Do dùng đúng khuôn URL mà trang tự in ra (thử 6 mẫu: `/page-N/`, `/page/N/`,
`?page=N`, `?paged=N`, `/trang-N/`, `/pN/`), cùng engine chạy được trên các
CMS subdomain khác nhau mà không cần sửa code — thử thật trên
`svbk.hust.edu.vn` (có sitemap) và `library.hust.edu.vn` (không sitemap, tự
suy ra `?page=265`).

### 2.7. Lưu trữ thô — `Store` (JSONL + gzip, sharded)

```python
class Store:
    def __init__(self, outdir, shard_size=200, use_gzip=True): ...
    def _roll(self):      # mở shard mới khi đủ shard_size dòng
    def add(self, rec):   # ghi 1 dòng, FLUSH NGAY (xem lý do dưới)
    def close(self):
```

Mỗi dòng JSONL là một trang đã tải:

```json
{"url": "...", "status": 200, "kind": "article", "article_id": "656013",
 "depth": 2, "via": "...", "fetched_at": "...", "sha1": "...",
 "encoding": "utf-8", "html_b64": "<base64 của đúng byte HTML gốc>"}
```

`html_b64` giữ **nguyên byte gốc** (không phải text đã decode) kèm `sha1` để
đối chiếu — tách lưu trữ thô khỏi mọi quyết định parse sau này (đổi selector
không phải tải lại).

**Flush từng dòng, không gộp batch.** Đây là một quyết định kỹ thuật được đo
thực nghiệm: bản đầu flush gzip mỗi 25 dòng, kill cứng tiến trình 2 lần khiến
`read_raw.py --check` phát hiện 184 URL "đã tải theo state nhưng không có
trong shard" — dữ liệu nằm trong buffer nén, mất khi kill. Script tự kiểm
(ghi 400 dòng, `kill -9` ở dòng 300):

| Kiểu flush | Đọc lại được sau kill -9 |
|---|---|
| từng dòng | 301/301 |
| 25 dòng/lần | 300/301 |
| không flush | 0/301 |

Ở nhịp 24 trang/phút, chi phí flush thường xuyên là không đáng kể so với rủi
ro mất dữ liệu. Crawler còn bắt `SIGTERM`/`SIGHUP` để đóng shard + ghi
`state.json` đúng cách khi bị dừng giữa chừng (`kill -TERM <pid>`, không dùng
`pkill -f` vì lệnh đó khớp cả dòng lệnh shell và giết nhầm terminal).

### 2.8. `state.json` — cho phép `--resume` an toàn

```json
{
  "done": {"url": 200}, "frontier": [["url", depth, "via"], ...],
  "queued": ["url", ...], "by_key": {"slug": "url_chinh"},
  "aliases": {"url_chinh": ["url_phu", ...]}, "assets": ["url", ...],
  "origin": {"url": "nơi phát hiện lần đầu"}, "errors": [...]
}
```

`origin` là bảng lineage riêng (khác `via`): `via` chỉ ghi nguồn của lần tải
*hiện tại*, nên nếu một URL được tải lại qua `--seed-file` thì `via` sẽ bị ghi
đè thành `"seed-file"`, xoá mất vết chuyên mục đã phát hiện ra nó lần đầu.
`origin` giữ nguyên giá trị gốc (`setdefault`), nên mọi bản ghi luôn lần
ngược được "tìm ra từ đâu" — đúng khái niệm *data lineage* trong tích hợp dữ
liệu.

### 2.9. `render.py` — Playwright khi `requests` lấy hụt

Một số subdomain là SPA hoặc thiếu chứng chỉ TLS trung gian khiến `requests`
trả về khung rỗng hoặc lỗi hẳn. `render.py` dùng **Playwright** (Chromium
headless thật) cho các trang đó, kích hoạt qua `--render auto|always|never`.
`looks_blocked()` quyết định "có vẻ bị chặn": status 403/429/503, dấu hiệu
chặn bot trong HTML, hoặc trang gần như không có link/chữ.

Vấn đề kỹ thuật đã gặp: Playwright sync API gắn với đúng luồng đã tạo ra
browser — dùng chung một browser giữa nhiều luồng ném lỗi *"cannot switch to a
different thread"*. Giải quyết bằng `threading.local()`: mỗi luồng có browser
riêng của chính nó.

### 2.10. `crawl_hust.py` — wrapper có parse

Lớp mỏng đọc lại kho thô (không tải lại mạng) và bóc mỗi bài ra **17 trường**
phẳng: `id, url, lang, title, section, breadcrumb, author, published_at,
modified_at, summary, content, word_count, thumbnail, images, attachments,
crawled_at, lastmod, source`. Xuất ra `.jsonl` và `.csv` (`utf-8-sig` để Excel
mở không vỡ dấu). Có `--incremental` đọc `state.json` để bỏ qua bài
`lastmod` không đổi ngay từ bước lọc URL — không tốn request.

### 2.11. `read_raw.py` — công cụ soát kho độc lập

Không tin một nguồn dữ liệu duy nhất là nguyên tắc vận hành xuyên suốt:

| Lệnh | Câu hỏi trả lời | Cách làm |
|---|---|---|
| `--check` | kho và `state.json` có lệch không? | so `done` với số dòng thật trong shard |
| `--audit` | chuyên mục nào tải thiếu trang? | so trang gốc chuyên mục với các `page-N` đã tải |
| `--fix-roots` | chuyên mục nào rơi khỏi cả `done` và `frontier`? | `mồ_côi = queued - done - frontier - aliases`, rồi suy ngược tập chuyên mục từ chính URL bài (`roots = {url.rsplit('/',1)[0]+'/'}`) |
| `--verify-links` | file `N1-links` có thiếu link nào có thật trong HTML đã lưu? | bóc lại **mọi** `href` từ HTML gốc, so với `N1-links`, cả hai đi qua cùng hàm `norm()` |
| `--rebuild-state` | cứu hộ khi `state.json` hỏng | dựng lại từ shard JSONL + `N1-links` |

Ví dụ thực tế đã xảy ra: `--fix-roots` từng phát hiện **111 chuyên mục** biến
mất khỏi kế hoạch crawl (gồm `tin-tuc-su-kien` 295 trang) sau một lần chạy
`--seed-file` vô tình xoá frontier. `--verify-links` từng báo "thiếu 17 link"
— hoá ra lỗi ở phép đo (một bên giữ `http://`, bên kia đổi `https://`), sửa
bằng cách bắt buộc mọi nơi chuẩn hoá URL đều gọi đúng một hàm `norm()`.

---

## 3. Search — công nghệ và cách hoạt động

### 3.1. Công nghệ dùng

| Thành phần | Công nghệ | File |
|---|---|---|
| Lõi tìm kiếm | **Apache Lucene 9.11.1** thuần (không Elasticsearch/Solr) | `lucene/` |
| Ngôn ngữ lõi | Java 21 | |
| HTTP nội bộ tầng Lucene | `com.sun.net.httpserver` (có sẵn trong JDK, không thêm framework) | `SearchServer.java` |
| Đọc/ghi JSON tầng Lucene | Jackson `jackson-databind` 2.17.2 | |
| Build | Maven, `maven-jar-plugin` + `maven-dependency-plugin` (jar mỏng + `lib/`, **không shade** — xem 3.7) | `pom.xml` |
| Tầng điều phối + giao diện | Python **FastAPI** | `api/main.py` |
| Bóc chữ từ HTML | `BeautifulSoup4` (dùng lại đúng selector đã kiểm chứng ở crawler) | `api/main.py` |
| Giao diện | 1 file HTML tĩnh, không framework JS | `api/static/index.html` |
| Đóng gói | Docker Compose, 2 service (`lucene`, `api`) | `docker-compose.yml` |

### 3.2. Luồng dữ liệu tổng thể

```
data/raw*/pages-*.jsonl.gz  (HTML thô base64, từ crawler)
        │  api/main.py: records() đọc từng dòng, giải base64
        ▼
extract(rec) -> dict        BeautifulSoup bóc {url,title,text,html,host,section,date,outgoing_links}
        │  lucene_document(): map sang field tiếng Việt-hoá nội bộ (url,title,text,host,section,date,html)
        ▼
POST http://lucene:8081/bulk   (mảng JSON, tối đa theo batch, mặc định 200)
        │  Index.put(): updateDocument(Term("url"), doc)  — ghi đè theo url, không đẻ trùng
        ▼
Lucene IndexWriter → commit() → SearcherManager.maybeRefresh()
        │
        ▼
GET /api/search?q=&ranking=&sort=   → SearchServer.search() → Index.search(Truy)
        │
        ▼
JSON: {hits:[{url,title,host,section,date,score,fragments:[...<mark>...],duplicates:[...]}]}
```

### 3.3. Schema tài liệu Lucene (`Index.java`)

Mỗi tài liệu được index với các trường sau (constant `F_*` trong `Index.java`):

| Trường Lucene | Kiểu Lucene | Lưu (`Store`) | Ý nghĩa |
|---|---|---|---|
| `url` | `StringField` | có | khoá cập nhật (`Term`) |
| `title`, `text` | `TextField`, `StandardAnalyzer` | có | bản **còn dấu** |
| `title_kd`, `text_kd` | `TextField`, analyzer bỏ dấu riêng | **không** | bản **bỏ dấu**, chỉ để khớp truy vấn không dấu |
| `host` | `StringField` + `SortedDocValuesField` | có | lọc theo site, đếm theo host |
| `section` | `TextField` | có | chuyên mục |
| `date` | `StringField` (`YYYY-MM-DD`) | có | hiển thị |
| `date_num` | `LongPoint` + `NumericDocValuesField` | không | dạng số `yyyymmdd` để lọc khoảng & sort theo ngày; `0` = không rõ ngày |
| `sig` | `StoredField` (hex) | có | vân tay SimHash 64-bit, để gộp hai URL cùng một bài (3.6) |
| `html` | `StoredField` | có | HTML đã dọn, chỉ để xem trước, không đưa vào chỉ mục tìm kiếm |

**Mỗi trường chữ được index hai lần** (bản có dấu và bản bỏ dấu) bằng
`PerFieldAnalyzerWrapper`:

```java
this.analyzer = new PerFieldAnalyzerWrapper(chuan, Map.of(
        F_TITLE_KD, khongDau, F_TEXT_KD, khongDau));
```

Đây là kỹ thuật cốt lõi để giải quyết tiếng Việt: `StandardAnalyzer` giữ
nguyên dấu, nên gõ không dấu ("diem chuan") vốn không khớp được bài "điểm
chuẩn". Thêm một trường song song bỏ dấu (`Fold`, xem 3.4) giải quyết việc đó
mà không cần Analyzer tiếng Việt chuyên dụng nào.

**Ghi/cập nhật tài liệu — `updateDocument`:**

```java
writer.updateDocument(new Term(F_URL, url), doc);
```

dùng `updateDocument` (xoá-rồi-thêm theo khoá) thay vì `addDocument`: chạy lại
`index/run` nhiều lần trên cùng kho crawl (ví dụ sau khi crawl thêm) không đẻ
tài liệu trùng, vì mỗi `url` luôn chỉ còn đúng một bản mới nhất.

**Đọc gần thời gian thực — `SearcherManager`:** tài liệu vừa `commit()` là
tìm thấy ngay, không cần đóng/mở lại `IndexWriter`.

### 3.4. Xử lý tiếng Việt không dấu — `Fold.java`

Tự viết, không dùng thư viện bỏ dấu ngoài:

```java
public static String bo_dau(String s) {
    String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);   // tách chữ có dấu = chữ gốc + dấu rời
    // vứt hết ký tự NON_SPACING_MARK (dấu rời)
    // riêng 'đ'/'Đ' xử lý tay vì Unicode coi là chữ cái riêng, NFD không tách được
}
```

`Fold.Filter` là một `TokenFilter` áp bộ lọc này lên từng token **sau khi**
`StandardTokenizer` đã tách từ — tokenizer dùng chung giữa hai bản (có dấu /
không dấu) là chủ ý, để hai trường sinh token ở cùng vị trí, phục vụ truy vấn
cụm từ (phrase query) chạy đúng trên cả hai bản.

### 3.5. Xếp hạng — hai chế độ `tfidf` và `enhanced`

**`ranking=tfidf` (mặc định):** dùng thuần `ClassicSimilarity` của Lucene
(công thức TF × IDF × document-length-norm cổ điển), tìm trên `title_kd` +
`text_kd` (bản bỏ dấu) với trọng số bằng nhau:

```java
private Query dungTruyVanTfidf(String q, Operator op, boolean noiLong) {
    return nhanh(new String[]{F_TITLE_KD, F_TEXT_KD},
            Map.of(F_TITLE_KD, 1.0f, F_TEXT_KD, 1.0f), khongDau, Fold.bo_dau(q), op);
}
```

Đây là chế độ dùng để "chứng minh" thuật toán TF-IDF cổ điển của Lucene khi
báo cáo/demo — không pha thêm tín hiệu ngoài.

**`ranking=enhanced`:** ghép nhiều tín hiệu, cài trong `dungTruyVan()`:

1. **Hai nhánh song song** (còn dấu, trọng số `title=3.0 / section=1.5 /
   text=1.0`; và bỏ dấu, trọng số nhẹ hơn `title_kd=2.0 / text_kd=0.7`) nối
   bằng `SHOULD` với `minimumNumberShouldMatch(1)` — gõ đủ dấu thì cả hai
   nhánh cùng khớp, điểm cộng dồn nên luôn xếp trên gõ không dấu.
2. **Thưởng cụm liền nhau** (`themCum`, `PhraseQuery` với `slop`): vì
   `StandardAnalyzer` cắt tiếng Việt theo âm tiết, "kỹ thuật" thành 2 token
   rời — không thưởng cụm thì bài chỉ tình cờ có "kỹ" và "thuật" cách xa nhau
   cũng khớp ngang bài có đúng cụm "kỹ thuật" liền kề.
3. **Thưởng theo cặp âm tiết liền nhau** (`themCumDoi`): với câu dài như "kỹ
   thuật máy tính" (4 âm tiết), đòi khớp *cả bốn* liền mạch gần như không bài
   nào đạt; xét từng cặp liền nhau ("kỹ thuật", "thuật máy", "máy tính") thì
   bài có nhiều cặp đúng được cộng nhiều lần — không cần từ điển từ ghép
   tiếng Việt.
4. **Nhân điểm nền `Rank.diemNen()`** (chỉ ở `enhanced`, không áp dụng khi
   sort theo ngày) — ba tín hiệu tính từ chính tài liệu, không từ truy vấn:
   - loại trang theo URL (`/page-N/` → 0.50, bài `.html` → 1.00, cửa vào
     chuyên mục → 0.70) — lý do: 82% kho crawl là trang mục lục, mà mục lục
     nhắc lại tiêu đề hàng chục bài nên vốn được Lucene chấm điểm rất cao;
   - độ dài nội dung (đường cong thoải `0.75 + 0.25 × min(1, ký_tự/1200)`) —
     bài dài thường là nội dung thật, vài chục chữ thường là khung trang;
   - độ mới (`0.92 + 0.16 × e^(-tuổi/3)`) — tin mới nhỉnh hơn tin cũ, không có
     ngày thì trung lập.
   Biên độ tổng cộng cố ý nhẹ (~0,35×) — đủ đảo hai kết quả sát điểm, không
   đủ đẩy bài lạc đề lên đầu.

**Vét lại khi không ra kết quả** (`itNhat` — "ít nhất"): nếu truy vấn AND
không khớp gì (thường vì người dùng gõ thừa một từ, ví dụ kèm chức danh/năm),
tự động chạy lại với `OR` nhưng ép `minimumNumberShouldMatch` = **quá nửa**
số token thay vì rơi thẳng về OR trần (OR trần khớp đúng 1 âm tiết phổ biến
sẽ lôi về hàng nghìn bài không liên quan). Ngưỡng đặt ở 1/2 (không phải 2/3)
vì mỗi từ tiếng Việt 2 âm tiết bị thừa đã chiếm 2/4 token của câu ngắn.

**Lọc theo host/ngày** dùng `BooleanClause.Occur.FILTER` (không phải `MUST`)
— lọc chỉ có/không, không được góp vào điểm số, tránh tài liệu trùng điều
kiện lọc bị tính điểm cao giả tạo.

### 3.6. Gộp bản trùng khi hiển thị kết quả — SimHash (`Sig.java`)

Kho crawl có kiểu trùng riêng: cùng một bài được NukeViet phát ở cả
`/vi/news/...` và `/vi/news/savefile/...`, lệch nhau vài chữ ở phần khung
trang (lượt xem, ngày sinh trang) — băm thường (`sha1`/`md5`) sẽ cho hai giá
trị hoàn toàn khác nên không gộp được.

**SimHash tự cài (64-bit), không dùng thư viện:**

1. bỏ dấu, thường hoá, cắt thành shingle 3-từ liền nhau;
2. băm mỗi shingle bằng FNV-1a 64-bit;
3. mỗi bit được "bầu" 1 nếu nhiều shingle có bit đó = 1 hơn = 0.

Hai văn bản gần giống nhau cho ra hai vân tay chỉ lệch vài bit (đo Hamming
distance), khác hẳn băm thường (đổi 1 chữ là băm thường đổi sạch). Ngưỡng
gộp: **≤ 3 bit khác trong 64 bit** — số này đo thực nghiệm (thêm phần khung
trang vào bản thứ hai của cùng một bài, đo độ lệch bit theo độ dài bài: từ
106 shingle trở lên độ lệch đứng yên ở 3 bit, còn hai bài khác chủ đề lệch từ
18 bit) — và chỉ tin cậy với bài ≥ 100 shingle (bài ngắn hơn thì vân tay trả
về 0, nghĩa là "đừng gộp").

Khi hiển thị, bản đại diện được chọn theo "URL gốc hơn" (ít đoạn `/` hơn,
bằng thì ngắn hơn thắng) — quy tắc chung, không hard-code riêng chữ
"savefile", nên cũng đúng cho các biến thể URL khác như `/amp/`, `/print/`.
Các URL trùng còn lại đưa vào `duplicates` của kết quả, không hiển thị riêng.

### 3.7. Vấn đề build đã gặp — Lucene 9 là multi-release JAR

`pom.xml` **cố ý không dùng `maven-shade-plugin`** để gói jar phẳng: Lucene 9
là *multi-release JAR* (có `META-INF/versions/19/` chứa
`MemorySegmentIndexInputProvider` dùng API mới của Java 19+); gói shade làm
mất cấu trúc multi-release đó, chạy trên Java 21 sẽ ném `LinkageError` ngay
khi mở index. Giải pháp: build jar mỏng (chỉ code của project) +
`maven-dependency-plugin` copy nguyên các jar phụ thuộc vào thư mục `lib/`,
classpath khai trong manifest (`Class-Path: lib/...`).

### 3.8. `SearchServer.java` — HTTP tối giản

Dùng `com.sun.net.httpserver.HttpServer` có sẵn trong JDK, **không kéo thêm
framework HTTP nào** — cả service Java chỉ phụ thuộc Lucene + Jackson. 6
endpoint (`/bulk`, `/search`, `/doc`, `/stats`, `/reset`, `/health`), mỗi
route là một tham chiếu method (`app::bulk`), executor là
`Executors.newFixedThreadPool(8)`. Lỗi cú pháp truy vấn của người dùng trả về
HTTP 400 (không phải 500) — phân biệt lỗi input với lỗi hệ thống.

### 3.9. Tầng điều phối Python (`api/main.py`, FastAPI)

Vai trò: **không chứa logic tìm kiếm** — chỉ đọc kho crawl, bóc chữ, gọi HTTP
sang Lucene, và expose lại thành REST API + giao diện.

**`extract(rec)`** — biến 1 bản ghi thô (crawler) thành 1 tài liệu để index:

```python
def extract(rec):
    if not rec.get("html_b64") or (rec.get("status") or 0) >= 400:
        return None                                   # bỏ trang lỗi / không phải HTML
    html = base64.b64decode(rec["html_b64"]).decode(rec.get("encoding") or "utf-8", "replace")
    soup = BeautifulSoup(html, "lxml")
    title = prop("headline") or meta(property="og:title") or soup.title...
    body  = soup.select_one(".bodytext") or soup.select_one("main") or soup.body
    # bỏ script/style/nav/footer trong body trước khi lấy text
    text  = re.sub(r"\s+", " ", body.get_text(" ", strip=True))
    return {"url":..., "title": title[:500], "text": text[:200_000],
            "html": don_html(body, rec["url"]), "host":..., "section":...,
            "date":..., "outgoing_links": outgoing_links(body, rec["url"])}
```

Dùng lại **đúng selector đã kiểm chứng ở tầng crawl** (`.bodytext`, microdata
`itemprop`) — không phát sinh bộ selector thứ hai.

**`don_html()`** dựng bản HTML rút gọn để xem trước: bỏ toàn bộ
`script/style/form/iframe/nav/footer/header` và mọi thuộc tính trừ
`href/src/alt`, chỉ giữ một danh sách thẻ cố định (`p, h1-6, ul, li, table,
img, a, div, ...`). Lý do kỹ thuật: nếu giữ nguyên `class` gốc thì phải kéo cả
CSS của hust.edu.vn về mới hiển thị đúng — mà kéo CSS nghĩa là bắn thêm nhiều
request sang site khi người dùng xem trước, đúng vào hành vi đang bị
rate-limit (2.4). Bỏ hết class rồi tự tô bằng CSS riêng của giao diện thì
trang xem trước không phát sinh request nào ngoài ảnh.

**Endpoint `POST /api/index/run`** — pipeline nạp index từ kho crawl:

```python
for d in kho_dirs():                 # mọi thư mục data/raw*, không chỉ raw/ chính
    for rec in records(d):
        if rec["url"] in seen: continue     # khử trùng theo url trong 1 lượt chạy
        doc = extract(rec)
        if not doc: skipped += 1; continue
        batch.append(lucene_document(doc))
        if len(batch) >= req.batch:         # gửi theo lô, mặc định 200
            POST lucene:8081/bulk
```

**`POST /api/index/documents`** nhận trực tiếp một corpus JSON theo **schema
public** (không cần đi qua crawler) — dùng để demo độc lập với mạng thật
(`tests/fixtures/corpus.json`), giới hạn 5.000 tài liệu/request, validate bằng
Pydantic (`PublicDocument`): `url` chỉ nhận http/https, `title` ≤ 500 ký tự,
`content` ≤ 200.000 ký tự, `published_at` rỗng hoặc `YYYY-MM-DD`.

**`POST /api/fetch`** — tải một URL bất kỳ ngay lúc gọi API (không qua batch
crawl), bóc tài liệu, ghi vào `data/raw-adhoc/` và index luôn — phục vụ demo
"dán URL → thấy kết quả" trên giao diện. Máy chủ giữ khoảng cách tối thiểu 3
giây giữa hai lần gọi (áp lại đúng nguyên tắc rate-limit của 2.4).

### 3.10. Giao diện (`api/static/index.html`)

Một file tĩnh, không framework JS, không bước build. Ba tab: **Tìm kiếm**
(chọn `tfidf`/`enhanced`, lọc theo host, đoạn trích được tô `<mark>`), **Tải
một trang** (gọi `/api/fetch`), **Bảng điều khiển** (gọi `/api/stats`,
`/api/crawl/*`, `/api/index/*`).

**Điểm kỹ thuật đáng chú ý: tô sáng (`<mark>`) làm ở phía Lucene, không phải
JS trình duyệt** — Lucene biết chính xác token nào khớp *sau khi đã phân
tích* (qua `Highlighter` + `QueryScorer` dùng chung cho cả `Highlighter` và
`SimpleSpanFragmenter` — tạo hai instance riêng sẽ khiến fragmenter không bao
giờ được init và ném `NullPointerException`), còn JS phía trình duyệt chỉ so
chuỗi thô nên sẽ trượt các trường hợp hoa/thường hoặc dấu câu dính liền.

### 3.11. Đóng gói Docker

```yaml
services:
  lucene:
    build: ./lucene
    volumes: ["lucene-index:/index"]        # index bền qua các lần down/up
    ports: ["8081:8081"]
    healthcheck: wget /health
  api:
    build: ./api
    depends_on: {lucene: {condition: service_healthy}}
    volumes:
      - ../hust-crawler:/crawler             # mount thư mục crawler — sửa crawl_all.py
                                              #   không cần build lại image
      - ./api:/app                           # sửa API/giao diện, chỉ cần restart
    ports: ["8000:8000"]
    environment: {LUCENE_URL: http://lucene:8081}
```

`api` image nền là Playwright (có sẵn Chromium) để tái dùng cho crawl
`--render`. `docker compose down` (không `-v`) giữ nguyên volume index.

---

## 4. Kiểm thử

| Bộ test | Số lượng | Công cụ | Việc kiểm |
|---|---|---|---|
| Engine crawler | 32 | `pytest` | `norm`, `dedup_key`, `expand_pagination`, rate-limit, flush, resume |
| Lucene | 29 | JUnit 5 | index/search/highlight/dedup/ranking, mỗi test ứng với một lỗi từng gặp |
| API/schema | 5 | `unittest`, không gọi mạng thật | `extract()`, validate schema public |
| Tích hợp | 33 | bash (`tests/integration.sh`) | đường đi thật trên stack đang chạy (health, crawl, index, search) |

**Tổng: 99 test đạt.** Quy ước đặt tên: mỗi test tên theo đúng lỗi thật đã
từng gặp — sửa code làm đỏ test nào thì đọc tên là biết vừa phá lại chuyện gì
(ví dụ lỗi flush gzip, lỗi khoá khử trùng, lỗi multi-release JAR, lỗi
QueryScorer dùng hai lần).

---

## 5. Giới hạn đã biết

**Crawler:**
- Không tải nội dung file đính kèm (PDF/DOC) — chỉ lập danh mục URL.
- Không chạy JavaScript ở đường tải chính (`requests`); chỉ dùng Playwright
  khi rõ ràng cần (`--render auto/always`), vì chậm hơn nhiều.
- Subdomain: mới thật sự crawl sâu 2/51 host, phần lớn chỉ có link "cửa vào".
- Nhịp rate-limit đo tại một thời điểm, có thể khác vào giờ cao điểm — nhịp tự
  dò sẽ tự điều chỉnh lại nhưng không đảm bảo tức thời.

**Search:**
- `StandardAnalyzer` tách tiếng Việt ở mức âm tiết, chưa tách từ ghép thật
  (chỉ bù bằng thưởng cụm liền nhau ở `enhanced`, không phải phân tích ngôn
  ngữ học).
- Chưa index hết kho crawl (tuỳ thời điểm chạy `index/run` gần nhất).
- Không có xác thực — cổng 8000/8081 mở không mật khẩu, chỉ dùng máy cá nhân
  hoặc mạng nội bộ.
- `outgoing_links` chỉ phục vụ hiển thị (trang "Tải một trang"), chưa đưa vào
  chỉ mục hay dùng để xếp hạng kiểu PageRank.

---

## 6. Bản đồ file để tra cứu nhanh

| Muốn hiểu/sửa gì | Xem file | Hàm/lớp chính |
|---|---|---|
| Vòng lặp crawl, hàng đợi | `hust-crawler/crawl_all.py` | `Crawler.run/push/pop/fetch/visit` |
| Rate limiting | `hust-crawler/crawl_all.py` | `_wait_turn/_slow_down/_speed_up` |
| Khử trùng bài | `hust-crawler/crawl_all.py` | `dedup_key()`, KHÔNG dùng `art_id()` |
| Sinh URL phân trang | `hust-crawler/crawl_all.py` | `expand_pagination()`, `PAGE_PATTERNS` |
| Lưu trữ thô | `hust-crawler/crawl_all.py` | `Store` |
| Render JS | `hust-crawler/render.py` | `looks_blocked()` |
| Soát kho | `hust-crawler/read_raw.py` | `--audit/--check/--fix-roots/--verify-links` |
| Bóc chữ HTML → tài liệu | `hust-search/api/main.py` | `extract()`, `don_html()` |
| API + pipeline index | `hust-search/api/main.py` | `index_run/index_documents/fetch_one` |
| Schema Lucene, ghi tài liệu | `hust-search/lucene/.../Index.java` | `put()` |
| Xếp hạng TF-IDF/enhanced | `hust-search/lucene/.../Index.java` | `dungTruyVanTfidf/dungTruyVan`, `Rank.java` |
| Bỏ dấu tiếng Việt | `hust-search/lucene/.../Fold.java` | `bo_dau()` |
| Gộp bài trùng (SimHash) | `hust-search/lucene/.../Sig.java` | `vanTay()`, `cungMotBai()` |
| Highlight `<mark>` | `hust-search/lucene/.../Index.java` | `doanTrich()` |
| HTTP endpoints Lucene | `hust-search/lucene/.../SearchServer.java` | — |
| Giao diện | `hust-search/api/static/index.html` | — |
| Đóng gói | `hust-search/docker-compose.yml`, `lucene/pom.xml` | — |
