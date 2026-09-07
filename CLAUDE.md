# di-project

Bài tập môn Tích hợp dữ liệu (IT5420). Repo chứa tài liệu môn học và hai project code.

## Bố cục

```
hust-crawler/     engine crawl + soát kho (chạy độc lập, không cần docker)
hust-search/      stack docker: Lucene (Java) + API/UI (Python) — xem hust-search/README.md
job-di/           project tích hợp tin tuyển dụng — CÓ .git RIÊNG, đừng add vào repo này
*.pdf, *.docx     slide và đề bài, để untracked
OneDrive_*/       tài liệu tải về, để untracked
```

## Stack tìm kiếm

```bash
cd hust-search && docker compose up -d --build   # http://localhost:8000
./tests/integration.sh                           # 16 kiểm tra đường đi thật
```

Hai dịch vụ: `lucene` (Java 21 + Lucene 9.11, cổng 8081) và `api` (FastAPI +
giao diện, cổng 8000). Python bóc chữ từ HTML rồi đẩy sang Java; Java chỉ lo
index và tìm kiếm. Kho `hust-crawler/data` được mount vào container ở `/crawler`
nên **sửa `crawl_all.py` không cần build lại image**.

Test: 32 (pytest engine) + 8 (JUnit Lucene) + 16 (tích hợp) = **56 đạt**.

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
