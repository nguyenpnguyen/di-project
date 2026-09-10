# Chia việc: code đi đường git, dữ liệu đi đường riêng

Kho crawl nằm ở `hust-crawler/data/` và **bị `.gitignore` chặn**. Đó là chủ ý,
không phải sót: kho hiện đã 149 MB HTML thô và còn phình theo mỗi mẻ crawl, đẩy
lên git là repo phồng vĩnh viễn — git giữ lại mọi phiên bản của mọi file nhị
phân, xoá đi cũng không nhỏ lại.

Nên hai thứ đi hai đường:

| | Đi bằng gì | Ai cũng có |
|---|---|---|
| Code (`crawl_all.py`, `read_raw.py`, `hust-search/`) | git push / pull | có |
| Kho HTML thô + hàng đợi crawl (`data/`) | gói `.tar.gz` gửi tay | phải xin |
| Index Lucene | **không gửi** — dựng lại tại chỗ trong ~2 phút | tự dựng |

Index cố ý không nằm trong gói: nó chỉ là dữ liệu phái sinh từ kho, mà lược đồ
index còn đổi theo code (đã đổi bốn lần trong lúc làm), gửi kèm thì rất dễ rơi
vào cảnh index cũ không hiểu code mới.

---

## 1. Người gửi: đẩy code

```bash
cd hust-crawler          # hoặc hust-search, cùng một repo
git status               # data/ không được hiện ở đây; hiện ra là .gitignore hỏng
git add -A
git commit -m "Mô tả việc vừa làm"
git push origin hust-crawler
```

Repo hiện làm trên nhánh `hust-crawler`, remote `mariorenger/di-project`.

Nhắc lại hai cái bẫy đã gặp:

- `job-di/` là **repo lồng, có `.git` riêng**. `git add job-di/` sẽ tạo một
  gitlink rỗng: trên GitHub thấy thư mục nhưng bấm vào không có gì. Đừng add.
- Nếu `git status` hiện file trong `data/`, dừng lại kiểm `.gitignore` trước khi
  commit. Lỡ commit rồi thì gỡ bằng `git rm -r --cached data` chứ đừng chỉ xoá.

## 2. Người gửi: đóng gói dữ liệu

```bash
cd hust-crawler
./hustctl stop                       # dừng crawler trước, để state.json không ghi dở
./hustdata export                    # ra một file .tar.gz + .sha256 + .manifest.txt
```

Gói rơi vào `GOI-DU-LIEU/` ngay gốc repo, cạnh `hust-crawler/` và `hust-search/`.
Thư mục này nằm trong `.gitignore` nên không bao giờ lọt lên git. Cố ý không để
mặc định ra Desktop hay `/tmp`: gói 120 MB quăng lung tung rồi quên là thành rác
trong máy, còn `/tmp` thì mất sạch khi khởi động lại.

Muốn để chỗ khác thì truyền thư mục: `./hustdata export ~/Desktop`.

Cần cắt nhỏ để gửi qua chỗ giới hạn dung lượng:

```bash
./hustdata export --split 45m
```

Ra `hust-data-<ngày>.tar.gz.part-aa`, `-ab`, `-ac`… **Gửi tất cả các phần**, kèm
cả `.sha256` và `.manifest.txt`.

Gói có gì:

- `data/raw*/pages-*.jsonl.gz` — HTML thô, mỗi dòng một trang, base64
- `data/raw*/state.json` — đã tải url nào, còn url nào trong hàng đợi
- `data/raw/N1-links` — danh sách link đã bóc ra
- `data/subdomains.txt`, `data/soict_canbo.txt` — các danh sách hạt giống
- `data/MANIFEST.txt` — bản kê: ngày giờ, số trang mỗi kho, sha256 từng file

Gói **không** có: log crawl, file pid, index Lucene, `.venv`. Chúng chỉ có nghĩa
trên máy sinh ra; mang sang máy khác chỉ gây hiểu nhầm "đang có mẻ chạy".

Xem trước gói mà không cần giải nén:

```bash
./hustdata info ../GOI-DU-LIEU/hust-data-20260910-2100.tar.gz
```

## 3. Người nhận: dựng lại từ đầu

### 3.1 Lấy code

```bash
git clone <url-repo> di-project
cd di-project
git checkout hust-crawler
```

### 3.2 Dựng môi trường Python

```bash
cd hust-crawler
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

### 3.3 Mở gói dữ liệu

Đặt gói (và các file `.sha256`, `.manifest.txt`) vào đâu cũng được, rồi:

```bash
./hustdata import ~/Downloads/hust-data-20260910-2100.tar.gz
```

Gói bị cắt nhỏ thì chỉ cần trỏ vào **phần đầu**, script tự ghép:

```bash
./hustdata import ~/Downloads/hust-data-20260910-2100.tar.gz.part-aa
```

Lệnh này sẽ:

1. ghép các phần nếu là gói cắt nhỏ;
2. đối chiếu sha256 — gói trăm mấy MB đi qua Drive hay Zalo rất hay đứt giữa
   chừng mà **vẫn giải nén được một phần**, nên bước này không thừa;
3. nếu máy đã có kho cũ thì **dời sang `data.truoc-khi-import-<ngày>/`** chứ
   không đè — `state.json` của hai máy không trộn được, mà đè nhầm lên mẻ crawl
   của chính mình thì không có đường lùi;
4. giải nén rồi in ra kho nào bao nhiêu trang, còn bao nhiêu url chờ.

Kiểm lại bất cứ lúc nào:

```bash
./hustdata check
```

## 4. Người nhận: crawl tiếp

```bash
./hustctl status                 # xem đang ở đâu
./hustctl resume                 # chạy tiếp đúng chỗ mẻ trước dừng
./hustctl log 30                 # xem log
./hustctl stop                   # dừng mềm: đóng shard, ghi state
```

Chạy tay thì tương đương:

```bash
.venv/bin/python crawl_all.py --resume --prefer article --delay 3 --workers 2
```

**Đừng hạ `--delay` xuống dưới 2.** Site chặn quanh 20–25 request/phút; vượt là
HTTP 429 kèm `Retry-After: ~30`. Mẻ gần nhất chạy `--delay 3` được 3.343 trang
mà không dính lần 429 nào. Thêm luồng cũng không nhanh hơn vì nhịp khoá chung
cho cả đàn — `--workers 2` là đủ.

Sau mỗi mẻ dài, soát lại:

```bash
.venv/bin/python read_raw.py --fix-roots     # chuyên mục có rơi khỏi hàng đợi không
.venv/bin/python read_raw.py --audit         # chuyên mục nào tải thiếu trang
.venv/bin/python read_raw.py --check         # kho có lệch state không
.venv/bin/python read_raw.py --links         # xuất lại N1-links
```

## 5. Người nhận: index và tìm kiếm

Index không đi kèm gói, phải dựng lại — mất khoảng hai phút cho 3.000 tài liệu.

```bash
cd ../hust-search
docker compose up -d --build          # lần đầu; Java build cỡ 1 phút
```

Rồi dựng index từ kho vừa import:

```bash
curl -X POST http://localhost:8000/api/index/run \
     -H 'content-type: application/json' -d '{"reset":true,"batch":150}'
```

hoặc bấm **Dựng lại từ đầu** ở tab Bảng điều khiển của http://localhost:8000.

Kiểm tra:

```bash
./tests/integration.sh                # 25 kiểm tra đường đi thật
```

Sau này crawl thêm thì chỉ cần **Index thêm vào kho** (`{"reset":false}`) — nó
không xoá index cũ, tài liệu trùng url được ghi đè chứ không đẻ bản mới. Nhưng
nó vẫn quét lại toàn kho mỗi lần, chưa có mốc "đã index tới đâu".

## 6. Gửi ngược lại

Người nhận crawl thêm rồi muốn trả dữ liệu về: làm đúng mục 2, gửi gói mới.
Đầu nhận chạy `./hustdata import` — kho cũ của mình tự được dời sang một bên.

**Không trộn được hai kho crawl song song.** Hai máy cùng chạy `--resume` sẽ
sinh hai `state.json` khác nhau, ghép lại thì hàng đợi và danh sách đã-tải không
còn khớp. Nên chia việc theo site: một người chạy `hust.edu.vn`, người kia chạy
subdomain bằng `--site library.hust.edu.vn` — mỗi site một thư mục kho riêng
`data/raw-<host>`, gói lại rồi bỏ vào nhau thì không đụng gì nhau.

---

## Tra nhanh

| Lệnh | Làm gì |
|---|---|
| `./hustdata export [thư-mục] [--split 45m]` | đóng gói kho để gửi, mặc định ra `GOI-DU-LIEU/` |
| `./hustdata import <gói hoặc .part-aa>` | mở gói, có kiểm sha256 |
| `./hustdata info <gói>` | xem gói có gì, không cần giải nén |
| `./hustdata check` | soát kho đang có tại chỗ |
| `./hustctl status / resume / stop / log` | điều khiển crawler |
| `./hustctl index` | đẩy kho vào Lucene |
