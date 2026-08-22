# Crawler hust.edu.vn

Bộ công cụ thu thập dữ liệu từ cổng thông tin Đại học Bách khoa Hà Nội, làm cho
môn Tích hợp dữ liệu (IT5420). Gồm ba tầng tách rời:

```
crawl_all.py   → tải thô toàn site, lưu HTML nguyên xi (base64) vào JSONL
read_raw.py    → mở kho thô: thống kê, tìm kiếm, đối chiếu khuyết, bung ra file
crawl_hust.py  → wrapper có parse: bóc bài viết ra bản ghi phẳng JSONL/CSV
```

Tách ba tầng vì **tải là phần đắt và bị rate-limit, parse thì rẻ và hay phải sửa**.
Có kho thô rồi thì sửa selector bao nhiêu lần cũng không phải đụng lại mạng —
đúng tinh thần wrapper trong bài giảng: nguồn dữ liệu và bộ bóc tách là hai thứ
khác nhau, đổi bộ bóc tách không được kéo theo đổi cách lấy dữ liệu.

---

## 1. Cài đặt

```bash
cd hust-crawler
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt      # requests, beautifulsoup4, lxml
```

---

## 2. Crawl được những gì

*(số liệu chốt ở mục 3)*

Kho thô nằm ở `data/raw/`, gồm ba loại trang:

| Loại (`kind`) | Là gì | Dùng làm gì |
|---|---|---|
| `listing` | trang gốc chuyên mục, vd `/vi/news/tin-tuc-su-kien/` | phát hiện bài + biết chuyên mục có bao nhiêu trang |
| `listing-page` | trang phân trang, vd `.../page-42/` | nguồn phát hiện bài chính |
| `article` | bài viết, vd `...-656013.html` | nội dung thật |

Phủ 30 nhóm chuyên mục theo menu, cả tiếng Việt lẫn tiếng Anh:

```
vi/news   vi/sinh-vien   vi/su-kien-noi-bat   vi/nghien-cuu   vi/dao-tao
vi/tuyen-sinh   vi/hop-tac-doi-ngoai   vi/lich-lam-viec   vi/van-ban
vi/tai-chinh-dai-hoc   vi/he-thong-nhan-dien-thuong-hieu   vi/tai-nguyen-so
vi/co-cau-to-chuc-bai-viet   vi/ba-cong-khai   vi/about   vi/du-an-sahep-1
vi/van-bang-so-huu-tri-tue   vi/san-pham-khoa-hoc-cong-nghe   vi/chuoi-su-kien-70-nam
en/news   en/cooperation   en/academics   en/campus-life   en/research
en/admissions   en/about   en/organization   en/quality-assurance   en/work-with-us
```

---

## 3. Kết quả thực tế

Số liệu chốt lúc dừng crawl (kiểm chứng lại bất cứ lúc nào bằng
`read_raw.py --stats` và `read_raw.py --check`):

```
10 shard | 946 trang khác nhau | 88 MB HTML thô | 42 MB trên đĩa sau nén
theo loại  : article 389 | listing 332 | listing-page 225
theo status: 200 cho cả 946 trang, 0 lỗi
```

**Phủ 30 nhóm chuyên mục**, cả tiếng Việt lẫn tiếng Anh:

| Nhóm | Trang | Nhóm | Trang |
|---|---|---|---|
| `vi/news` | 268 | `en/news` | 28 |
| `vi/sinh-vien` | 121 | `en/cooperation` | 14 |
| `vi/nghien-cuu` | 100 | `vi/van-ban` | 10 |
| `vi/lich-lam-viec` | 99 | `vi/tai-chinh-dai-hoc` | 7 |
| `vi/su-kien-noi-bat` | 96 | `vi/he-thong-nhan-dien-thuong-hieu` | 7 |
| `vi/dao-tao` | 54 | `vi/co-cau-to-chuc-bai-viet` | 6 |
| `vi/hop-tac-doi-ngoai` | 38 | `en/*` (7 nhóm còn lại) | 5 mỗi nhóm |
| `vi/tuyen-sinh` | 33 | các nhóm nhỏ khác | 2-4 mỗi nhóm |

Kèm theo:

* **192 file đính kèm** đã lập danh mục url trong `assets.txt` (166 `.pdf`,
  18 `.docx`, 7 `.doc`, 1 `.rar`) — bóc ra từ HTML đã lưu, không tốn request nào.
* **4.185 bài** đã phát hiện và **988 url phụ** trỏ cùng bài, nằm trong
  `state.json`. Đây **chưa** phải toàn bộ site — xem mục 4.
* **0 trang khuyết**, **0 lần dính 429** ở các mẻ chạy cuối.

Cách 946 trang này được lấy, theo ba mẻ:

| Mẻ | Cách chạy | Kết quả |
|---|---|---|
| 1 | BFS ưu tiên trang danh sách | 557 trang danh sách, phủ hết chuyên mục và phát hiện 4.185 bài |
| 2 | `--seed-file` lấy mẫu bài theo tỉ lệ từng chuyên mục | 389 bài, 16,2 phút |
| 3 | `--seed-file missing.txt` vá chỗ khuyết | 11 trang |

Mẻ 2 lấy mẫu **theo tỉ lệ** (chuyên mục có 1.271 bài thì lấy 111, chuyên mục có
2 bài thì lấy cả 2, sàn 5 bài cho nhóm nhỏ) để mẫu phản ánh đúng phân bố thật
chứ không dồn vào một chuyên mục.

---

## 4. Có đủ chưa, và cần gì mới đủ

### Đo "vũ trụ" dữ liệu

Không thể biết chính xác site có bao nhiêu bài, nhưng đếm được từ hai phía:

| Cách đếm | Con số | Ghi chú |
|---|---|---|
| Tổng URL trong 34 sitemap | **3.933** | thiếu, xem bên dưới |
| Bài đã phát hiện qua BFS | **4.185** | chưa phải toàn bộ, xem dưới |
| URL phụ trỏ cùng một bài | 988 | cùng slug, chỉ khác chuyên mục |
| Trang danh sách đã biết | ~830 | mỗi trang 6 bài |
| **Trang danh sách đã tải** | **223 (27%)** | phần còn lại chưa nở phân trang |

### Phần phát hiện CHƯA xong

Đây là chỗ dễ tưởng nhầm là đã đủ. Dừng crawl giữa chừng để lại **18 trang gốc
chuyên mục chưa tải**, mà mỗi trang gốc chưa tải nghĩa là **toàn bộ phân trang
của nó chưa hề được sinh ra**. Đo bằng 18 request:

| Chuyên mục chưa tải trang gốc | Số trang | ≈ bài |
|---|---|---|
| `/vi/news/hoat-dong-chung/` | **259** | ~1.554 |
| `/vi/news/tuyen-sinh-dao-tao-cong-tac-sinh-vien/` | **123** | ~738 |
| `/vi/news/cong-tac-dang-va-doan-the/` | **107** | ~642 |
| 15 chuyên mục còn lại (`van-ban`, `media`, `events`, `du-an`…) | 1-2 mỗi cái | ~90 |
| **Tổng** | **505** | **~3.030** |

Con số ~3.030 là **trần trên**, không phải số bài mới: bài xuất hiện ở nhiều
chuyên mục nên phần lớn sẽ trùng với 4.185 bài đã biết. Muốn biết chính xác thì
phải tải 505 trang danh sách đó rồi khử trùng — khoảng 21 phút.

Cả 18 url này vẫn nằm trong `frontier`, nên `--resume` sẽ tự tải, không mất gì.

**Chỉ đọc sitemap là không đủ**, ba lý do:

1. `sitemap-vi.news.xml` bị cắt **đúng 1000 URL** — con số tròn trịa nghĩa là bị
   giới hạn, không phải hết bài. Riêng chuyên mục `tin-tuc-su-kien` đã có 295
   trang × 6 bài ≈ 1.770 bài.
2. **Bảy sitemap rỗng hẳn** (`<urlset/>` trống): `vi.van-ban`, `vi.media`,
   `vi.videoclips`, `vi.san-pham-khoa-hoc-cong-nghe`, `vi.van-bang-so-huu-tri-tue`,
   `en.siteterms`, `en.media-english`. Các module này chỉ vào được qua menu.
3. Hai chuyên mục lớn nhất theo phân trang là `hop-tac-doi-ngoai-truyen-thong`
   (109 trang ≈ 654 bài) và `khoa-hoc-cong-nghe-dmst` (90 trang ≈ 540 bài) —
   sitemap không phản ánh đúng khối lượng này.

### Cần bao lâu mới đủ

Trần tốc độ **không nằm ở code mà ở site**: hust.edu.vn chặn quanh **20-25
request/phút** (mục 7). Thực đo **24 trang/phút**. Từ đó:

| Mục tiêu | Còn phải tải | Thời gian ở 24 trang/phút |
|---|---|---|
| **Chốt xong phần phát hiện** (505 trang danh sách của 18 chuyên mục) | 505 | **~21 phút** |
| Nốt 3.796 bài đã biết | 3.796 | ~2,6 giờ |
| Cả hai việc trên | ~4.300 | ~3,0 giờ |
| Kể cả bài mới lòi ra từ 505 trang kia | ~5.000-7.300 | 3,5-5 giờ |
| Tải luôn 192 file đính kèm | +192 | +8 phút |

Nếu chỉ làm được một việc, làm việc đầu: 21 phút để biết site thật sự có bao
nhiêu bài, thay vì đoán.

Lệnh chạy tiếp, an toàn khi ngắt giữa chừng:

```bash
.venv/bin/python crawl_all.py --resume --prefer article
```

`--prefer article` để nếu phải dừng sớm thì trong kho có bài đọc được ngay, thay
vì toàn trang danh sách (bài học ở mục 7).

### Nếu không thể crawl thêm

Kho hiện tại vẫn dùng được cho bài tập tích hợp dữ liệu, vì:

* **Đủ đa dạng để làm wrapper**: có bài của cả 30 nhóm chuyên mục, cả vi lẫn en,
  cả bài tin tức lẫn bài văn bản có file đính kèm — đủ mọi biến thể cấu trúc HTML
  mà bộ bóc tách phải chịu được.
* **Danh mục URL đã có sẵn 4.185 bài** trong `state.json`, đủ để lấy thêm bất cứ
  lúc nào mà không phải bò lại từ đầu.
* **Bổ khuyết được bất cứ lúc nào** — `--resume` đọc `frontier` và đi tiếp.

Nhưng đừng nhầm là *phát hiện đã xong*: còn 18 trang gốc chuyên mục chưa tải,
kéo theo ~505 trang danh sách chưa nở ra (xem bảng ở trên). Muốn chốt con số
"site có bao nhiêu bài" thì phải chạy hết phần đó trước.

Thứ **không** làm được nếu thiếu dữ liệu: thống kê theo thời gian trên toàn bộ
kho (vd. số bài mỗi tháng qua các năm), vì mẫu hiện tại thiên về bài mới.

---

## 5. Cấu trúc dữ liệu

### 5.1. Kho thô — `data/raw/`

```
pages-0001.jsonl.gz   mỗi dòng một trang; HTML thô nằm ở trường html_b64
pages-0002.jsonl.gz   ... mỗi shard 250 trang
state.json            hàng đợi + đã tải + bảng khoá bài, dùng cho --resume
manifest.json         tổng kết mẻ chạy gần nhất
assets.txt            192 url file đính kèm (pdf/doc/xls) đã lập danh mục, không tải
missing.txt           read_raw.py --check sinh ra khi có url khuyết; hết khuyết thì tự xoá
links                 read_raw.py --links: MỌI url đã biết, mỗi dòng một link, không đuôi file
sample_articles.txt   danh sách url dùng cho mẻ lấy mẫu bài
```

**Một dòng trong `pages-*.jsonl.gz`:**

```json
{
  "url":          "https://hust.edu.vn/vi/news/tin-tuc-su-kien/diem-chuan-...-656013.html",
  "final_url":    null,
  "status":       200,
  "content_type": "text/html; charset=utf-8",
  "kind":         "article",
  "article_id":   "656013",
  "depth":        2,
  "via":          "https://hust.edu.vn/vi/news/tin-tuc-su-kien/page-1/",
  "fetched_at":   "2026-08-20T19:13:50",
  "size":         114613,
  "sha1":         "a1f2065e9eac6d5b3a4046228a3d23dd5b94bf95",
  "encoding":     "utf-8",
  "html_b64":     "PCFET0NUWVBFIGh0bWw+CiAgICA8aHRtbCBsYW5nPSJ2aSI..."
}
```

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `url` | str | URL đã chuẩn hoá (bỏ fragment, bỏ `fbclid`/`utm_*`/`PHPSESSID`) |
| `final_url` | str/null | chỉ khác null khi bị redirect |
| `status` | int | mã HTTP; 404/403/410 vẫn được lưu để biết url nào hỏng |
| `content_type` | str | lấy nguyên từ header |
| `kind` | str | `article` / `listing` / `listing-page` / `other`, suy từ dạng URL |
| `article_id` | str/null | số cuối URL `...-656013.html` — **khoá thật của bài** |
| `depth` | int | cách hạt giống mấy bước; 0 = hạt giống |
| `via` | str | trang nào dẫn tới đây, để lần ngược đường đi |
| `fetched_at` | str | ISO, giờ máy chạy crawl |
| `size` | int | số byte HTML gốc |
| `sha1` | str | băm của HTML gốc, để đối chiếu sau khi giải mã |
| `encoding` | str | bảng mã do requests đoán, cần khi decode |
| `html_b64` | str/null | **base64 của đúng byte gốc**; null nếu trang không phải HTML |

Lấy lại HTML:

```python
import gzip, json, base64, hashlib
for line in gzip.open("data/raw/pages-0001.jsonl.gz", "rt", encoding="utf-8"):
    rec = json.loads(line)
    html = base64.b64decode(rec["html_b64"])
    assert hashlib.sha1(html).hexdigest() == rec["sha1"]   # đúng byte gốc
    text = html.decode(rec["encoding"] or "utf-8", "replace")
```

Shard nén gzip còn **~17%** dung lượng. Muốn `.jsonl` trần thì thêm `--no-gzip`.

**`state.json`:**

| Khoá | Nội dung |
|---|---|
| `done` | `{url: http_status}` — url đã tải xong |
| `frontier` | `[[url, depth, via], …]` — hàng đợi còn lại, để `--resume` |
| `queued` | mọi url đã từng vào hàng đợi, chống lặp |
| `by_key` | `{slug_bài: url_chính}` — bảng khử trùng bài (xem 7.4) |
| `aliases` | `{url_chính: [url_phụ, …]}` — cùng bài, khác slug chuyên mục |
| `assets` | url file đính kèm đã thấy |
| `errors` | 500 lỗi gần nhất, mỗi lỗi kèm url và lý do |
| `pages_written`, `shards` | đối chiếu với kho |

### 5.2. Kho đã parse — `data/*.jsonl` + `.csv`

Do `crawl_hust.py` sinh ra, 17 trường:

```json
{
  "id": "656013", "url": "...", "lang": "vi",
  "title": "Điểm chuẩn Đại học Bách khoa Hà Nội năm 2026",
  "section": "Tin tức - sự kiện",
  "breadcrumb": ["Trang chủ", "Tin Tức", "Tin tức - sự kiện"],
  "author": "Ban Tuyển sinh - Hướng nghiệp",
  "published_at": "2026-08-09T14:13:19+07:00",
  "modified_at": "2026-08-09T14:13:19+07:00",
  "summary": "…", "content": "…", "word_count": 892,
  "thumbnail": "https://hust.edu.vn/uploads/...jpg",
  "images": ["…"], "attachments": ["….pdf"],
  "crawled_at": "2026-08-20T19:00:18", "lastmod": "2026-08-09T14:13:19+07:00",
  "source": "hust.edu.vn"
}
```

Đo trên mẫu tin tức: **16/17 trường đầy đủ 100%**; riêng `attachments` chỉ có ở
bài loại văn bản/tuyển sinh (đúng bản chất dữ liệu, không phải lỗi bóc tách).

File `.csv` là bản dẹt của cùng dữ liệu, bỏ các trường mảng, mã hoá `utf-8-sig`
để Excel mở không vỡ tiếng Việt.

---

## 6. Cách chạy

### 6.1. Tải thô

```bash
# crawl toàn site (vài giờ, ngắt lúc nào cũng được)
.venv/bin/python crawl_all.py

# chạy thử nhanh
.venv/bin/python crawl_all.py --max-pages 300

# chạy tiếp lần trước
.venv/bin/python crawl_all.py --resume

# ưu tiên bài viết thay vì trang danh sách
.venv/bin/python crawl_all.py --resume --prefer article

# chỉ tiếng Việt
.venv/bin/python crawl_all.py --lang vi
```

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `--max-pages` | 0 (∞) | trần số trang mỗi mẻ |
| `--max-depth` | 6 | độ sâu tối đa tính từ hạt giống |
| `--max-pages-per-cat` | 400 | trần số trang phân trang nở ra cho một chuyên mục |
| `--lang` | all | `vi` / `en` / `all` |
| `--prefer` | listing | `listing` phủ chuyên mục trước, `article` lấy bài trước |
| `--delay` | 2.5 | nhịp nhanh nhất, giây/request, **đừng hạ dưới 2** |
| `--max-delay` | 30 | trần nhịp khi bị chặn liên tục |
| `--max-429` | 8 | số lần chịu 429 cho một url trước khi bỏ |
| `--workers` | 2 | nhịp đã khoá chung nên thêm luồng không nhanh hơn |
| `--shard-size` | 200 | số trang mỗi file shard |
| `--no-gzip` | tắt | ghi `.jsonl` trần |
| `--checkpoint` | 50 | cứ bấy nhiêu trang thì ghi `state.json` |
| `--resume` | tắt | đi tiếp từ `state.json` |
| `--seed-file` | – | chỉ tải đúng danh sách url trong file, không bò tiếp |

### 6.2. Đọc kho thô

```bash
.venv/bin/python read_raw.py --stats                    # kho có gì
.venv/bin/python read_raw.py --list --kind article      # liệt kê bài đã tải
.venv/bin/python read_raw.py --get 656013 > bai.html    # lấy HTML một bài
.venv/bin/python read_raw.py --grep "điểm chuẩn"        # tìm trong nội dung
.venv/bin/python read_raw.py --extract data/html_files  # bung ra file .html rời
.venv/bin/python read_raw.py --assets                   # lập danh mục pdf/doc/xls
.venv/bin/python read_raw.py --links                    # xuất mọi url ra file 'links'
.venv/bin/python read_raw.py --check                    # đối chiếu kho với state
```

Đọc được **ngay trong lúc crawler đang chạy**: shard cuối đang ghi dở thì đọc tới
đâu trả tới đó.

### 6.3. Đối chiếu khuyết và vá

`state.json` ghi "url này tải rồi", shard ghi nội dung thật. Hai thứ có thể lệch
nếu tiến trình bị kill cứng. Đối chiếu:

```bash
.venv/bin/python read_raw.py --check
# → in ra: state khai bao nhiêu, shard thực có bao nhiêu, khuyết bao nhiêu
# → ghi data/raw/missing.txt

.venv/bin/python crawl_all.py --resume --seed-file data/raw/missing.txt
```

Nên chạy `--check` một lần sau mỗi mẻ crawl dài.

### 6.4. Wrapper có parse

```bash
.venv/bin/python crawl_hust.py --limit 30                     # 30 tin mới nhất
.venv/bin/python crawl_hust.py --since 2026-01-01 --limit 200 # theo mốc ngày
.venv/bin/python crawl_hust.py --sections news tuyen-sinh nghien-cuu
.venv/bin/python crawl_hust.py --mode category --category tin-tuc-su-kien --pages 5
.venv/bin/python crawl_hust.py --list-sitemaps                # xem có chuyên mục nào
```

Có `--incremental` (đọc `data/state.json`, bỏ qua bài `lastmod` không đổi ngay từ
khâu lọc url, không tốn request) và `--since` cắt theo mốc ngày.

### 6.5. Dừng crawler đang chạy

```bash
ps -A -o pid=,command= | grep "[c]rawl_all.py"    # lấy pid
kill -TERM <pid>                                  # đóng shard + ghi state rồi thoát
```

**Đừng dùng `pkill -f crawl_all.py`** — nó khớp cả dòng lệnh shell đang gõ và
giết nhầm chính terminal của mình.

---

## 7. Khảo sát site và những vấn đề đã gặp

### 7.1. Cấu trúc site

hust.edu.vn chạy **NukeViet 4** (nhận ra qua cookie `nv4s_*`). Ba thứ khai thác được:

| Thứ cần lấy | Nằm ở đâu |
|---|---|
| Danh sách url | `/sitemap.xml` → 34 sitemap con theo chuyên mục × ngôn ngữ, có `<lastmod>` |
| Danh sách theo chuyên mục | `/vi/news/<slug>/page-N/`, 6 bài/trang, thẻ `div.news_column div.panel-body` |
| Tiêu đề, tác giả, ngày | microdata schema.org: `[itemprop=headline\|author\|datePublished\|dateModified\|image]` |
| Nội dung | `div.bodytext` |
| Chuyên mục | `<meta property="article:section">` + breadcrumb `[itemprop=itemListElement]` |
| Khoá bài viết | đuôi url `...-656013.html` |

Microdata đáng tin hơn CSS class: class đổi theo giao diện, còn `itemprop` gắn với
dữ liệu. Chỉ dùng CSS selector khi microdata không có.

`robots.txt` chặn `/admin/`, `/users/`, `/modules/`, `/data/`, `/includes/`,
`/install/`, `/statistics/` — phần tin tức được phép. Crawler tuân thủ qua
`urllib.robotparser`.

### 7.2. Site chặn ở ~20-25 request/phút

Đây là phát hiện tốn công nhất. Đặt nhịp 2,2 req/s (5 luồng × 0,45s), crawler
chạy được **0,55 trang/s** — chậm gấp bốn lần chính con số mình đặt ra.

Truy nguyên bằng `sample <pid>` (profiler có sẵn của macOS): cả 5 luồng nằm
**100% trong `time.sleep`**, không phải mạng (server trả trong 0,8s, 5 request
song song xong trong 2,5s) cũng không phải parse. Kiểm chứng bằng `curl`:

```
status=429 time=1.29s retry_after=23
status=429 time=0.75s retry_after=22
```

Nguyên nhân: server trả `HTTP 429` kèm `Retry-After: ~30`, mà code cũ cho **mỗi
luồng ngủ riêng** rồi lại ùa vào tiếp — lúc nào cũng có luồng đang chịu phạt.

Đo ngưỡng bằng cách bắn thử từng nhịp:

| Giãn cách | Kết quả |
|---|---|
| 1,0s | 14/14 lọt |
| 1,6s | 10 lọt / 4 chặn, chặn từ request thứ 11 |
| 2,2s | 14/14 lọt |

Chỗ 1,6s bị chặn còn 2,2s thì không, cho thấy limiter đếm theo **cửa sổ trượt**
(cộng dồn cả request của phép đo trước) chứ không theo khoảng cách hai request.
Ngưỡng thực: khoảng 20-25 request mỗi phút.

Bản hiện tại:

* nhịp **tự dò** — dính 429 thì nhân nhịp 1,5 lần, yên được 25 request thì rút
  nhịp lại 10%, tự ổn định quanh mức site chịu được;
* dính 429 thì **cả đàn cùng nghỉ** đến hết `Retry-After`, không luồng nào lách;
* 429 **không tính là lỗi** của url, chỉ là phải chờ, nên không mất bài;
* `--workers` mặc định 2, vì nhịp đã khoá chung thì thêm luồng không nhanh hơn.

Kết quả: chạy 24 trang/phút liên tục, **0 lần dính 429**.

### 7.3. Shard phải flush từng dòng

Bản đầu flush gzip 25 dòng một lần cho đỡ tốn. Kill cứng tiến trình hai lần thì
`read_raw.py --check` chỉ ra **184 url state khai đã tải nhưng shard không có
HTML** — một shard nặng 2 MB mà chỉ đọc lại được 60 dòng, phần sau nằm trong
buffer của bộ nén, mất trắng.

Đo bằng script tự `kill -9` giữa chừng sau khi ghi 301 dòng:

| Kiểu flush | Đọc lại được |
|---|---|
| từng dòng | **301** |
| 25 dòng một lần | 300 |
| không flush | 0 |

Bản hiện tại flush từng dòng — nhịp có 24 trang/phút thì tiết kiệm ấy chẳng đáng
gì, đổi lại mất tối đa một dòng. Thêm bắt `SIGTERM`/`SIGHUP` để `pkill` hay tắt
máy vẫn kịp đóng shard và ghi state.

### 7.4. Một bài, nhiều URL — và cái bẫy khoá trùng

NukeViet viết lại đường dẫn theo chuyên mục người dùng đang đứng, nên cùng một
bài tồn tại ở nhiều url. Cần khử trùng, nếu không gần 20% request là tải lại thứ
đã có. Câu hỏi là: **khử theo khoá nào?**

Chọn sai một lần, và đây là bài học đắt nhất của cả dự án.

**Giả định ban đầu (SAI):** con số cuối url `...-656013.html` là id bài duy nhất.
Nghe rất hợp lý, và với chuyên mục tin tức thì đúng thật. Nhưng đo trên site:

| URL | Tiêu đề | Độ dài body |
|---|---|---|
| `.../thong-bao-tuyen-dung-nam-654601.html` | THÔNG BÁO TUYỂN DỤNG NĂM 2023 | 6.035 ký tự |
| `.../crystal-associate-programme-...-654601.html` | CRYSTAL ASSOCIATE PROGRAMME 2024 | 2.063 ký tự |
| `.../sahep-cung-bach-khoa-...-654601.html` | SAHEP cùng Bách khoa nâng cao chất lượng | 5.607 ký tự |

**Ba bài hoàn toàn khác nhau**, cùng đuôi `-654601.html`, mỗi bài có `og:url`
riêng và không hề redirect. Con số cuối url **không duy nhất**.

Hậu quả: 1.147 url bị đánh dấu "alias" và không tải, trong đó **159 là bài khác
nhau bị bỏ nhầm** — mất trắng nếu không phát hiện ra.

**Khoá đúng: đoạn cuối đường dẫn, tức slug kèm số.** Kiểm chứng cả hai chiều
bằng cách tải thật rồi so sha1 của phần nội dung:

| Trường hợp | Kết quả đo | Kết luận |
|---|---|---|
| Cùng đoạn cuối, khác chuyên mục | body sha1 **giống hệt** (`52c3381844f5`) | một bài → khử trùng |
| Cùng số, khác slug | tiêu đề và nội dung khác hẳn | bài khác → phải giữ cả hai |

Nên `dedup_key()` bỏ qua phần chuyên mục ở giữa url nhưng bắt buộc slug phải
khớp. Sau khi sửa: 4.185 bài thật, 988 url phụ (trùng thật), 159 bài được cứu về.

Bài học rút ra cho môn tích hợp dữ liệu: **một khoá "trông như id" chưa chắc là
id**. Phải kiểm chứng bằng nội dung — hai bản ghi cùng khoá thì nội dung có
giống nhau không — chứ không suy từ hình dạng url. Đây mới đúng là *entity
resolution*: xác định thực thể bằng bằng chứng, không bằng giả định.

### 7.5. Thứ tự đi quyết định mẻ dở dang có gì

Ban đầu tôi cho trang danh sách đi trước (`appendleft`) để phủ hết chuyên mục.
Đúng về mặt phát hiện, nhưng khi dừng crawl ở 550 trang thì **cả 550 đều là trang
danh sách, không có bài nào** — kho gần như vô dụng nếu không chạy tiếp.

Nên có `--prefer`: `listing` (mặc định, phủ chuyên mục trước) hoặc `article`
(dừng sớm vẫn có bài đọc được). Chạy dài thì để mặc định, chạy ngắn thì
`--prefer article`.

### 7.6. Sitemap rỗng

Bảy sitemap con là `<urlset/>` trống. Chương trình báo rõ *"rỗng — chuyên mục này
phải dùng --mode category"* thay vì im lặng trả 0 bài rồi để người dùng tưởng
crawler hỏng.

---

## 8. Lần ra `page-N` và các trang con bằng cách nào

### 8.1. Phân trang: đọc thẳng số trang cuối, không bấm "trang sau"

NukeViet in sẵn khối phân trang vào HTML, và **luôn có link tới trang cuối**.
Đây là HTML thật lấy từ kho, chuyên mục `khoa-hoc-cong-nghe-dmst` (90 trang):

```html
<ul class="pagination">
  <li class="disabled"><a href="javascript:void(0)">«</a></li>
  <li class="active"><a href="javascript:void(0)">1</a></li>
  <li><a href="https://hust.edu.vn/vi/news/khoa-hoc-cong-nghe-dmst/page-2/"  rel="next">2</a></li>
  <li class="disabled"><span>...</span></li>
  <li><a href="https://hust.edu.vn/vi/news/khoa-hoc-cong-nghe-dmst/page-90/" rel="next">90</a></li>
  <li><a href="https://hust.edu.vn/vi/news/khoa-hoc-cong-nghe-dmst/page-2/"  rel="next">»</a></li>
</ul>
```

Widget rút gọn phần giữa thành `...`, nhưng **số 90 vẫn nằm đó**. Nên chỉ cần
tải đúng một trang gốc chuyên mục là biết nó có bao nhiêu trang, rồi sinh thẳng
`page-2` → `page-90` mà không phải bấm 89 lần "trang sau".

Đo trên kho, hai dạng widget:

| Chuyên mục | Số trang | Số link `page-N` in ra |
|---|---|---|
| `/vi/news/hop-tac-doi-ngoai-truyen-thong/` | 109 | 2 (`page-2` và `page-109`) |
| `/vi/news/khoa-hoc-cong-nghe-dmst/` | 90 | 2 (`page-2` và `page-90`) |
| `/vi/sinh-vien/sinh-vien-hien-tai/` | 25 | 2 |
| `/vi/hop-tac-doi-ngoai/tin-tuc-hoc-bong/` | 7 | 6 (in đủ 2,3,4,5,6,7) |

Chuyên mục nhỏ thì in hết, chuyên mục lớn thì in `2` và số cuối — cả hai trường
hợp `max()` đều ra đúng trang cuối.

Code làm việc đó (`expand_pagination`):

```python
PAGE_N = re.compile(r"/page-(\d+)/?$")

def expand_pagination(self, url, soup, depth):
    base = PAGE_N.sub("/", urlparse(url).path)          # .../page-7/ -> .../
    nums = [int(m.group(1)) for a in soup.select("a[href]")
            for m in [PAGE_N.search(urlparse(urljoin(url, a["href"])).path)]
            if m and urlparse(urljoin(url, a["href"])).path.startswith(base)]
    if not nums:
        return 0
    last = min(max(nums), self.a.max_pages_per_cat)     # chặn trần cho an toàn
    for n in range(2, last + 1):
        self.push(f"{BASE}{base}page-{n}/", depth + 1, url)
```

**Phân trang KHÔNG đệ quy, nội dung thì có.** Đây là chỗ dễ hiểu nhầm nhất:

```
tải 1 lần  /vi/news/khoa-hoc-cong-nghe-dmst/
              ↓ đọc widget, số lớn nhất = 90
           sinh bằng vòng for: page-2, page-3, … page-90
              ↓ đẩy cả 89 url vào hàng đợi ngay lúc đó
           mỗi page-N tải về  → bóc ~6 bài  → đẩy vào hàng đợi   ← chỗ đệ quy
```

Không có chuyện "tải `page-2` để biết `page-3` ở đâu". Bằng chứng trên kho: 89
trang `page-2..page-90` đã tải, **cả 89 đều mang `via` = đúng một url** là trang
gốc chuyên mục.

Code *vẫn* chạy `expand_pagination` mỗi khi gặp trang danh sách, kể cả `page-46`.
Nhưng widget ở đó chỉ in ra số đã nằm sẵn trong hàng đợi nên ra **0 url mới**:

| Trang | Widget in ra | Kết quả |
|---|---|---|
| `page-2` | `[3, 90]` | đã có trong `queued` → 0 url mới |
| `page-46` | `[45, 47, 90]` | đã có → 0 url mới |
| `page-90` | `[89]` | đã có → 0 url mới |

Chạy thừa nhưng không hại, và đổi lại là lưới an toàn: chuyên mục nào widget
không in số cuối thì `page-5` sẽ lòi ra `page-6..page-10`, rồi `page-10` lòi tiếp.

Ba chi tiết đáng nói:

* **`startswith(base)`** — trên một trang có cả link phân trang của chuyên mục
  khác (khối "tin liên quan" ở sidebar). Lọc theo đường dẫn gốc để không nở nhầm
  phân trang của chuyên mục hàng xóm.
* **`min(..., max_pages_per_cat)`** — phòng trường hợp trang in ra số trang vô lý.
* **Tự lành nếu widget không in số cuối.** Giả sử có chuyên mục chỉ in `1..5`,
  crawler vẫn tải `page-5`, và widget trên `page-5` lại in ra dải tiếp theo. BFS
  bù được phần thiếu, chỉ chậm hơn.

### 8.2. Trang con: bốn nguồn cộng lại

Không dựa vào một nguồn nào duy nhất, vì nguồn nào cũng thiếu một mảng:

1. **Sitemap** — 34 file, 3.933 url. Đầy đủ nhất về bài lẻ, nhưng bị cắt ở 1000
   url với `news` và rỗng hẳn ở 7 chuyên mục.
2. **Menu trên trang chủ** — `/vi/` và `/en/` có `<nav class="second-nav">` chứa
   116 link, phủ toàn bộ cây menu hai cấp. Đây là nguồn *duy nhất* dẫn tới các
   module có sitemap rỗng (`van-ban`, `media`, `videoclips`,
   `san-pham-khoa-hoc-cong-nghe`, `van-bang-so-huu-tri-tue`). 15 đường dẫn này
   được ghi cứng trong `seed()` để chắc chắn không bỏ sót.
3. **Nở phân trang** — mục 8.1.
4. **Bò theo mọi link nội bộ** — trên mỗi trang đã tải, lấy hết `<a href>`,
   chuẩn hoá rồi đẩy vào hàng đợi. Đây là lưới vét cuối cùng: trang nào có ai đó
   trỏ tới thì kiểu gì cũng vào hàng đợi.

Mỗi url vào hàng đợi đều đi qua bốn cửa lọc:

```python
norm(u)          # tuyệt đối hoá, bỏ #fragment, bỏ fbclid/utm_*/PHPSESSID
in_scope(u)      # đúng host hust.edu.vn; bỏ .pdf/.jpg/...; bỏ /feeds/ /rss/ /seek/ /print/
robots.can_fetch # tôn trọng robots.txt
u in queued      # đã từng vào hàng đợi thì thôi
dedup_key(u)     # cùng slug bài -> ghi vào aliases, không tải lại
```

### 8.3. Lưu vết nguồn gốc (lineage)

`depth` và `via` được ghi vào từng bản ghi, nên lúc nào cũng lần ngược được
"trang này tìm ra từ đâu". Nhưng `via` một mình thì hỏng khi tải lại: bài lấy qua
`--seed-file` sẽ mang `via = "seed-file"`, xoá mất dấu chuyên mục đã phát hiện ra nó.

Nên `state.json` có thêm bảng `origin` — `{url: nơi phát hiện lần đầu}` — và bản
ghi lấy `via` từ bảng này thay vì từ lần tải hiện tại. Hiện có **4.184 url** trong
bảng, ví dụ:

```
/vi/about/cac-giai-doan-lich-su-191736.html   ← phát hiện tại /sitemap-vi.about.xml
/vi/news/khoa-hoc-cong-nghe-dmst/page-37/     ← phát hiện tại /vi/news/khoa-hoc-cong-nghe-dmst/
```

Với bài tập tích hợp dữ liệu thì đây chính là *data lineage*: mỗi bản ghi trong
kho biết mình đến từ nguồn nào, qua đường nào.

---

## 9. Giới hạn đã biết

* **Không tải file đính kèm.** PDF/DOC/ảnh chỉ được *lập danh mục* url
  (192 file, xem `assets.txt`), không tải nội dung. Muốn tải thì viết thêm một
  vòng đọc `assets.txt` — cùng nhịp rate-limit, 192 file ≈ 8 phút.
* **Trang không phải HTML chỉ lưu metadata.** Endpoint kiểu
  `/vi/lich-lam-viec/export/?...` trả về PDF/DOCX; bản ghi vẫn có `url`, `status`,
  `content_type`, `sha1` nhưng `html_b64` là `null`. `read_raw.py --check` tính
  chúng là "có bản ghi", không phải khuyết.
* **Không chạy JavaScript.** Trang nào dựng nội dung bằng JS thì crawler chỉ thấy
  khung rỗng. Đã kiểm tra: phần tin tức là HTML tĩnh nên không ảnh hưởng.
* **`/70year/index.html` nặng 7 MB** — một trang chuyên đề dựng sẵn, tải được
  nhưng chiếm chỗ bất thường so với trang thường (~130 KB).
* **Mẫu thiên về bài mới.** Sitemap sắp theo `lastmod` giảm dần nên bài cũ nằm ở
  cuối hàng đợi.
* **Nhịp phụ thuộc thời điểm.** Ngưỡng 20-25 req/phút đo lúc 19h; giờ cao điểm
  có thể khác. Nhịp tự dò sẽ tự điều chỉnh.

---

## 10. Ghi chú về việc thu thập

Dữ liệu thu được là thông tin công khai trên cổng thông tin của trường, dùng cho
bài tập môn học. `User-Agent` để nguyên dạng có thông tin liên hệ:

```
hust-research-crawler/1.0 (nghien cuu mon IT5420; lien he: student@sis.hust.edu.vn)
```

để phía trường biết ai đang truy cập và liên hệ được nếu cần. Tôn trọng
`robots.txt`, tôn trọng `Retry-After`, và đừng hạ `--delay` xuống dưới 2 giây.
