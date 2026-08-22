# di-project

Bài tập môn Tích hợp dữ liệu (IT5420). Repo chứa tài liệu môn học và hai project code.

## Bố cục

```
hust-crawler/     crawler cho hust.edu.vn — project chính, xem hust-crawler/README.md
job-di/           project tích hợp tin tuyển dụng — CÓ .git RIÊNG, đừng add vào repo này
*.pdf, *.docx     slide và đề bài, để untracked
OneDrive_*/       tài liệu tải về, để untracked
```

Chỉ `hust-crawler/` được version. `job-di/` là repo lồng: `git add job-di/` sẽ tạo
gitlink rỗng (thư mục hiện trên GitHub nhưng bấm vào không có gì).

## hust-crawler

Ba tầng tách rời, tải và parse không ràng buộc nhau:

| File | Việc |
|---|---|
| `crawl_all.py` | bò toàn site, lưu HTML thô base64 trong JSONL nén gzip |
| `read_raw.py` | mở kho thô: thống kê, tìm, soát thiếu, xuất danh sách link |
| `crawl_hust.py` | wrapper có parse: bóc bài ra 17 trường JSONL/CSV |

```bash
cd hust-crawler
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

.venv/bin/python crawl_all.py --resume                  # crawl tiếp
.venv/bin/python crawl_all.py --resume --only listing   # chỉ chốt danh mục url
.venv/bin/python read_raw.py --stats                    # kho có gì
.venv/bin/python read_raw.py --audit                    # chuyên mục nào còn thiếu trang
.venv/bin/python read_raw.py --links                    # xuất danh sách link
```

Dữ liệu ở `hust-crawler/data/` — **gitignored**, đừng commit (kho HTML thô hàng
trăm MB). Sinh lại được bằng `--resume`.

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

## Vận hành

**Dừng crawler:** lấy pid rồi `kill -TERM <pid>`. **Đừng `pkill -f crawl_all.py`**
— nó khớp cả dòng lệnh shell đang gõ và giết nhầm terminal.

`ps -A -o pid=,command= | grep "[c]rawl_all.py"` — pid thật là tiến trình
`Python crawl_all.py`, không phải vỏ shell bọc ngoài.

**Git:** đang làm trên nhánh `hust-crawler`, remote `mariorenger/di-project`.
Commit message viết tiếng Việt cho khớp với comment trong code.
