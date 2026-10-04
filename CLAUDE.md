# di-project

Bài tập môn Tích hợp dữ liệu (IT5420). Repo chứa tài liệu môn học và hai project code.

## Bố cục

```
hust-crawler/     engine crawl + soát kho (chạy độc lập, không cần docker)
hust-search/      project Maven Java 21: HTTP API + UI + bóc tách + Mongo + Lucene — xem hust-search/README.md
docker-compose.yml  dựng search (Java) + crawler (Python, service riêng) + mongo
job-di/           project tích hợp tin tuyển dụng — CÓ .git RIÊNG, đừng add vào repo này
*.pdf, *.docx     slide và đề bài, để untracked
OneDrive_*/       tài liệu tải về, để untracked
```

## Stack tìm kiếm

```bash
docker compose up -d --build                     # từ thư mục gốc repo → http://localhost:8000
hust-search/tests/integration.sh                 # 33 kiểm tra đường đi thật
hust-search/tests/integration_bt.sh              # 19 kiểm tra bóc tách/Mongo/đồ thị
cd hust-search && mvn -B test                    # JUnit; test Mongo cần MONGO_URL (mặc định localhost:27017)
```

Ba dịch vụ: `search` (Java 21, **một tiến trình**: HTTP API + giao diện + bóc tách bằng jsoup + Tika +
driver Mongo + Lucene 9.11, cổng 8000), `crawler` (Python, chỉ `crawlctl.py` nghe start/stop/status trong
mạng docker, cổng 8090 không mở ra host) và `mongo` (không mở cổng). Java bóc tách HTML (`extract/`: khối
nội dung, trường, đồ thị liên kết, tệp) rồi ghi vào Mongo; `/api/index/run` đọc Mongo đẩy vào Lucene
ngay trong tiến trình. `/api/crawl/*` chỉ chuyển tiếp sang service `crawler`.
Compose giữ `name: hust-search` để không đổi tên volume (đổi tên project là mất dữ liệu cũ hiện ra).
Hợp đồng HTTP (đường dẫn, tham số, khoá JSON) giữ nguyên bản Python cũ; lỗi là `{"detail": "<chuỗi>"}`,
riêng lỗi tìm kiếm (`/api/search`: q rỗng, cú pháp sai) giữ `{"error": ...}` vì giao diện đọc `d.error`.
Kế hoạch port và quyết định: `hust-search/KE-HOACH-PORT-JAVA.md`; bóc tách: `hust-search/KE-HOACH-BOC-TACH.md`,
lược đồ: `hust-search/SCHEMA.md`. Thuật toán bóc tách + đồ thị (sơ đồ Mermaid): `hust-search/THUAT-TOAN-BOC-TACH.md`.
Tên package/class/biến/method Java dùng tiếng Anh (`extract/`, `store/`, `Extractor`, `RawStore`, `Pipeline`…);
khoá JSON của API, tên collection/trường Mongo và chuỗi hiển thị giữ nguyên tiếng Việt cũ vì là hợp đồng với giao diện/dữ liệu.
Sơ đồ luồng dữ liệu và từng thuật toán: `BAO-CAO-KY-THUAT.md`. Giao diện có tab "Bóc tách khối"
(`POST /api/extract/url`) nhận url bất kỳ: lấy trong kho hoặc tải từ web, bóc tách, lưu Mongo + Lucene, rồi
hiện trường, nội dung và liên kết đã bóc; tab "Đồ thị liên kết" có nút "Tải & bóc tách". Kho
`hust-crawler/data` được mount vào `search` ở `/data` và vào `crawler` ở `/app/data`, nên **sửa
`crawl_all.py` không cần build lại image** (chỉ cần dừng rồi chạy lại mẻ crawl).

**`Url.java` là bản sao của `crawl_all.norm/dedup_key/kind_of`.** Sửa bên nào thì sửa bên kia và chạy lại
`UrlGoldenTest` (khớp 100% `src/test/resources/golden/url.tsv`) — lệch là lỗi "thiếu 17 link" kiểu `http://` vs `https://`.

Test: 32 (pytest engine) + 115 (JUnit Java: url/kho, bóc tách khớp bản Python trên kho thật, Tika, Mongo thật,
HTTP). Đã chạy trên MongoDB thật và kho thật (03/10/2026). Bản Java không crawl được trang dựng bằng JS
(đã bỏ Playwright, ví dụ `work.hust.edu.vn`); `DocumentText.java` dùng **một** `AutoDetectParser` dùng chung vì dựng
mới mỗi tệp mất ~1 s.

Chỉ `hust-crawler/` được version. `job-di/` là repo lồng: `git add job-di/` sẽ tạo
gitlink rỗng (thư mục hiện trên GitHub nhưng bấm vào không có gì).

## hust-crawler

Ba tầng tách rời, tải và parse không ràng buộc nhau:

| File | Việc |
|---|---|
| `crawl_all.py` | bò toàn site, lưu HTML thô base64 trong JSONL nén gzip |
| `read_raw.py` | mở kho thô: thống kê, tìm, soát thiếu, xuất danh sách link |
| `crawl_hust.py` | wrapper có parse: bóc bài ra 17 trường JSONL/CSV |

## Đang làm tới đâu (cập nhật 22/08/2026)

Chia **ba việc rời nhau**:

| | Việc | Trạng thái |
|---|---|---|
| 1a | Lấy link `hust.edu.vn` | **XONG** — 13.745 link, hàng đợi danh mục cạn |
| 1b | Lấy link bên trong 51 subdomain | **MỚI 2/51** — `library` và `svbk` chạy thử 5-6 trang |
| 2 | Tải nội dung bài | **còn 4.216 bài**, ~3 giờ |

`data/raw/N1-links` có 14.597 link / 52 host. Nhưng **852 link subdomain phần
lớn chỉ là cửa vào** tìm từ trang chính — 49 host mới có đúng 1-2 link. Đừng
đọc con số 52 host thành "đã phủ 52 site".

Kho hiện có 2.070 trang / 201 MB HTML thô, 5.640 bài đã phát hiện.

Nhóm subdomain đáng crawl: `bulletin tuyendung work research svbk library ts
ctsv qldt jst dlib soict sem see smse sami fed sep`. Bỏ `mail`, `e`, `ctt-sis`,
`demo` — cổng đăng nhập hoặc trang rỗng.

```bash
cd hust-crawler
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

# xem đang ở đâu
.venv/bin/python read_raw.py --stats
.venv/bin/python read_raw.py --audit

# VIỆC 2: tải nốt nội dung bài
.venv/bin/python crawl_all.py --resume --prefer article

# sau mỗi mẻ dài: soát rồi cập nhật danh sách link
.venv/bin/python read_raw.py --fix-roots     # chuyên mục rơi khỏi hàng đợi?
.venv/bin/python read_raw.py --audit         # chuyên mục tải thiếu trang?
.venv/bin/python read_raw.py --check         # kho lệch state?
.venv/bin/python read_raw.py --links         # xuất lại N1-links
.venv/bin/python read_raw.py --verify-links  # soát độc lập file link

# VIỆC 1b: lấy link các subdomain (danh sách ở data/raw/subdomains.txt)
for h in bulletin tuyendung work research svbk library ts ctsv qldt jst dlib; do
  .venv/bin/python crawl_all.py --site $h.hust.edu.vn --only listing --max-pages 400
done
.venv/bin/python read_raw.py --links     # gộp mọi kho vào N1-links
```

Dữ liệu ở `hust-crawler/data/` — **gitignored**, đừng commit (kho HTML thô hàng
trăm MB). Mỗi site một kho riêng `data/raw-<host>`. Sinh lại được bằng `--resume`.

`N1-links` cố ý **không có đuôi file** — người dùng đặt tên vậy. `--links` tìm
file `*links*` không đuôi trong `data/raw` mà ghi đè, đừng đẻ file mới bên cạnh.

## Chia dữ liệu cho người khác

`data/` gitignore nên code và kho đi hai đường. Đóng/mở gói bằng
`hust-crawler/hustdata` (`export` / `import` / `info` / `check`), hướng dẫn đầy
đủ ở `hust-crawler/CHIA-DU-LIEU.md`. Gói ra `GOI-DU-LIEU/` ở gốc repo — cũng gitignore.
Index Lucene, MongoDB và `data/files/` không đi kèm gói — dựng lại từ kho (index khoảng hai phút).

Không trộn được hai kho crawl song song trên cùng một host: hai `state.json`
khác nhau ghép lại thì hàng đợi hết khớp. Chia việc theo site thì được, vì mỗi
site một thư mục kho riêng `data/raw-<host>`.

## Ràng buộc phải nhớ khi sửa crawler

**Site chặn ~20-25 request/phút.** Đo bằng cách bắn thử từng nhịp; vượt là HTTP
429 kèm `Retry-After: ~30`. `--delay` mặc định 2,5s, **đừng hạ dưới 2**. Nhịp tự
dò: dính 429 thì nhân 1,5 và cả đàn cùng nghỉ; yên 25 request thì rút 10%. Thêm
luồng không nhanh hơn vì nhịp khoá chung — `--workers` 2 là đủ.

**Shard gzip phải flush từng dòng.** Flush thưa thì kill cứng làm mất dữ liệu đã
ghi (đo được: 301/301 dòng cứu được khi flush từng dòng, 0 khi không flush).

**Khoá khử trùng là ĐOẠN CUỐI đường dẫn, không phải con số cuối url.** Con số
`-654601.html` không duy nhất — ba bài khác hẳn nhau cùng mang số đó. Dùng
`dedup_key()`, đừng dùng `art_id()` làm khoá.

**Chuyên mục có thể biến mất khỏi kế hoạch crawl.** Từng mất 111 chuyên mục
(gồm `/vi/news/tin-tuc-su-kien/` 295 trang và cả phần tiếng Anh) do một lần
`--seed-file` xoá frontier. Sau mỗi mẻ dài chạy `read_raw.py --fix-roots` và
`--audit` để soát.

**Danh sách link chỉ lấy `href`, không lấy `src`.** Gộp `src` vào thì ảnh nhúng
`/uploads/` làm file phình từ 14k lên 24k dòng mà chẳng thêm link nào đi tới được.

**Mọi nguồn url phải đi qua `crawl_all.norm()`.** `--links` và `--verify-links`
mà chuẩn hoá khác nhau thì báo lệch giả — từng thấy "thiếu 17 link" chỉ vì một
bên giữ `http://` còn bên kia đổi sang `https://`.

**Crawler chỉ đi trong MỘT host.** Nên link subdomain không bao giờ vào hàng đợi
của nó; muốn có thì phải bóc lại `href` từ HTML đã lưu (`--subdomains`,
`--links`) hoặc crawl riêng subdomain đó bằng `--site`.

## Vận hành

**Dừng crawler:** lấy pid rồi `kill -TERM <pid>`. **Đừng `pkill -f crawl_all.py`**
— nó khớp cả dòng lệnh shell đang gõ và giết nhầm terminal.

`ps -A -o pid=,command= | grep "[c]rawl_all.py"` — pid thật là tiến trình
`Python crawl_all.py`, không phải vỏ shell bọc ngoài.

**Git:** đang làm trên nhánh `hust-crawler`, remote `mariorenger/di-project`.
Commit message viết tiếng Việt cho khớp với comment trong code.
