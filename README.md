
# Tích hợp thông tin tuyển dụng theo kỹ năng

Hệ tích hợp dữ liệu kiểu **mediator – wrapper**: gom tin tuyển dụng từ nhiều nguồn khác nhau về một
lược đồ thống nhất, chuẩn hoá kỹ năng theo ontology ESCO, gộp tin trùng giữa các nguồn, và cho phép
tìm kiếm theo kỹ năng có phân cấp (tìm "lập trình" ra được cả tin cần Python, Java, Go).

---

## 1. Kết luận kiến trúc hiện tại

### 1.1 Sơ đồ tổng thể

```
┌── Nguồn ────────────┐   ┌── Tầng wrapper ──┐   ┌── Tầng mediator ─────────┐   ┌── Tầng khai thác ─┐
│ VietJobs   (CSV)    │   │ FIELD_MAP        │   │ chuẩn hoá giá trị        │   │ OpenSearch        │
│ HF job_skill (JSONL)│──▶│ schema matching  │──▶│ entity matching + fusion │──▶│ FastAPI           │
│ TopCV     (crawler) │   │ profiling        │   │ trích chọn & chuẩn hoá   │   │ Giao diện quản trị│
│ Vieclam24h(crawler) │   │ → global schema  │   │ kỹ năng theo ontology    │   │                   │
│ ESCO      (ontology)│   └──────────────────┘   └──────────────────────────┘   └───────────────────┘
└─────────────────────┘             │                        │                            │
                                    ▼                        ▼                            ▼
                              PostgreSQL: raw_job     PostgreSQL: job, skill,       OpenSearch: index jobs
                              (bản gốc, JSONB)        job_skill, mapping, log       (skills, skills_expanded)
```

### 1.2 Bốn tầng

| Tầng | Làm gì | Nằm ở đâu |
|---|---|---|
| **Nguồn** | 5 nguồn, 3 kiểu: dataset công khai, crawler HTML, ontology chuẩn | `data/`, `app/wrappers/crawler.py` |
| **Wrapper** | Che sự khác biệt lược đồ. Mỗi nguồn khai báo `FIELD_MAP` + hàm chuẩn hoá | `app/wrappers/` |
| **Mediator** | Chuẩn hoá giá trị, gộp trùng, hoà giải xung đột, chuẩn hoá kỹ năng | `app/pipeline/` |
| **Khai thác** | API tìm kiếm + giao diện quản trị vòng đời dữ liệu | `app/api/` |

### 1.3 Công nghệ đang dùng

| Thành phần | Công nghệ | Vì sao chọn |
|---|---|---|
| CSDL chính | **PostgreSQL 16** | Vừa quan hệ vừa có `JSONB` để giữ nguyên bản ghi gốc; `WITH RECURSIVE` xử lý cây kỹ năng; `pg_trgm` cho so khớp mờ ngay trong DB |
| Tìm kiếm | **OpenSearch 2.13** | Lọc theo nhiều kỹ năng cùng lúc, đếm gộp (facet) theo kỹ năng và nguồn |
| Đồ thị | **Neo4j 5** (tuỳ chọn, đã có trong compose) | Vẽ quan hệ kỹ năng khi cần trình bày trực quan |
| Backend | **FastAPI + Uvicorn**, `psycopg` 3 | API mỏng, viết thẳng SQL cho dễ đọc khi bảo vệ đồ án |
| Lập lịch | **APScheduler** chạy trong tiến trình API | Đọc cron từ `data_source.schedule_cron`, không cần thêm hạ tầng |
| Đối sánh chuỗi | **RapidFuzz** | Nhanh, đủ tốt cho tên kỹ năng và tiêu đề công việc |
| Đối sánh ngữ nghĩa | **sentence-transformers** (bật thêm) | Tầng dự phòng khi chuỗi không giống nhau về mặt ký tự |
| Crawler | **requests + BeautifulSoup**, cache HTML, 2 giây mỗi request | Đơn giản, tôn trọng nguồn |
| Đóng gói | **Docker Compose** 4 dịch vụ | Một lệnh là chạy được toàn hệ |

### 1.4 Lược đồ CSDL

Ba nhóm bảng trong `db/init.sql`:

- **Staging** — `raw_job` giữ nguyên bản ghi gốc dạng `JSONB` kèm `content_hash`. Không bao giờ ghi đè,
  nên luôn dựng lại được dữ liệu tích hợp từ đầu và luôn truy vết được về bản gốc.
- **Lược đồ thống nhất** — `job`, `company`, `location`, `job_source_link` (một tin ứng với nhiều bản ghi nguồn),
  `skill`, `skill_alias`, `skill_relation`, `job_skill`.
- **Siêu dữ liệu & quản trị** — `data_source`, `ingestion_run`, `schema_mapping` (có phiên bản),
  `source_field_profile`, `change_log`, `match_review`, `value_conflict`.

Hai view quan trọng: `v_global_job` là **view GAV** gộp mọi nguồn; `v_skill_descendant` là bao đóng
đệ quy của cây kỹ năng, dùng cho tìm kiếm theo kỹ năng cha.

---

## 2. Crawl khi nào, lấy bao nhiêu, bị chặn thì sao

### 2.1 Ba đường kích hoạt
Đều gọi cùng một hàm `ingest.run_source(name, limit, since, use_known)`:
1. **Bấm "Nạp dữ liệu mới"** ở tab Nguồn dữ liệu → `POST /api/admin/ingest/{nguồn}`. Với nguồn crawler, đây chính là lúc đi lấy trang web về.
2. **Theo lịch** — APScheduler đọc `data_source.schedule_cron`, mặc định mỗi lần lấy tối đa 100 tin trong 7 ngày gần nhất. Xem lịch sắp tới ở `GET /api/admin/schedule`.
3. **Dòng lệnh** — `python -m app.pipeline.run ingest topcv 100 7` (nguồn, số tin, số ngày).

### 2.2 Không bao giờ "crawl all" — ba lớp phanh
| Lớp phanh | Cách hoạt động | Chỉnh ở đâu |
|---|---|---|
| **Ngân sách** | Mỗi lần chỉ đi tối đa `limit` tin và `MAX_PAGES` trang (mặc định 5), nghỉ ngẫu nhiên 2–4 giây giữa hai request | ô "Số tin tối đa" trên giao diện, hằng số trong `crawler.py` |
| **Mốc ngày** | Gặp tin đăng cũ hơn `since` là dừng, vì danh sách xếp theo thời gian nên phía sau chắc chắn còn cũ hơn | ô "Chỉ tin trong N ngày qua" |
| **Dừng sớm khi gặp tin cũ** | Hệ nạp sẵn tập `external_id` đã có; gặp 10 tin đã biết liên tiếp là dừng | ô "Bỏ qua tin đã có" |

Nghĩa là mỗi lần bấm, hệ chỉ đi tới đúng chỗ giáp ranh giữa tin mới và tin đã có rồi quay ra — lần bấm thứ hai liền sau đó gần như không tốn request nào.

### 2.3 Bị chặn thì sao
Crawler đọc `robots.txt` trước, và bắt các tình huống 403 / 429 / 503 / captcha / lỗi mạng thành ngoại lệ `CrawlBlocked`. Khi đó:
- **Phần đã lấy được vẫn giữ nguyên**, đi tiếp bình thường trong pipeline. Lần nạp được đánh dấu `partial`.
- **Con trỏ trang được lưu** vào `data_source.cursor_state.next_page`, lần sau chạy tiếp từ đúng chỗ dừng thay vì quay lại trang 1.
- **Đặt thời gian nghỉ** `blocked_until` theo header `Retry-After` (mặc định 30–60 phút). Trong khoảng đó, mọi lệnh nạp cho nguồn này trả về trạng thái `hoãn` và không gửi request nào nữa.
- Giao diện hiện nhãn "bị chặn tới …" và "trang kế: N" ngay trên dòng của nguồn.

Ngoài ra HTML được cache trong `data/html/`, nên chạy thử lại lúc phát triển không gọi lại trang web.

### 2.4 Mỗi lần bấm lấy được gì
Mỗi bản ghi được băm thành `content_hash`:

| Tình huống | Hệ thống làm gì | Ô hiển thị |
|---|---|---|
| Tin đã có, nội dung không đổi | Bỏ qua ngay từ tầng wrapper hoặc ở `raw_job` | `skipped` |
| Tin đã có, nội dung đã sửa | Ghi phiên bản mới vào `raw_job`, cập nhật `job` hiện hành | `updated` |
| Tin mới hoàn toàn | Tạo bản ghi `job` mới | `new` |
| Tin trùng với tin từ nguồn khác | Gộp vào cùng một `job`, thêm liên kết nguồn | `merged` |
| Trùng nhưng chưa đủ chắc | Vẫn tạo tin mới, đồng thời đẩy cặp vào hàng chờ duyệt | `review` |

Nút bấm xong hiện đúng các con số này kèm lý do dừng ("đủ số tin yêu cầu", "đã chạm mốc ngày …", "đủ tin cũ liên tiếp, dừng sớm", hoặc thông báo bị chặn).

## 3. Các kỹ thuật tích hợp — đủ chưa và nằm ở đâu

| Nhóm | Kỹ thuật | Cài đặt |
|---|---|---|
| **Wrapper** | Ánh xạ khai báo `FIELD_MAP`, hàm chuẩn hoá cắm rời (`TRANSFORMS`) | `app/wrappers/base.py`, `sources.py` |
| **Schema matching** | Ba tín hiệu: tương đồng tên trường (RapidFuzz + từ điển đồng nghĩa Việt–Anh), mẫu giá trị bằng regex, hình dạng dữ liệu (độ dài, tỉ lệ số) → chấm điểm, so với ánh xạ đang khai báo, người duyệt rồi mới ghi | `app/pipeline/schema_match.py` |
| | Hồ sơ hoá dữ liệu (data profiling) làm đầu vào cho matching | `schema_match.profile/save_profile` |
| | Phiên bản ánh xạ theo thời gian (`valid_from`/`valid_to`) | bảng `schema_mapping` |
| **Data cleaning** | Chuẩn hoá lương ("20 - 30 triệu" → số), địa danh, kinh nghiệm, bỏ dấu tiếng Việt | `app/common.py` |
| **Entity matching (tin)** | Blocking theo công ty + đầu tiêu đề → vector tương đồng từng thuộc tính → điểm có trọng số → ba vùng quyết định (gộp / chờ duyệt / không gộp) | `app/pipeline/matching.py` |
| **Entity matching (kỹ năng)** | Bốn tầng: exact → viết tắt (k8s, ML, JS) → fuzzy → embedding đa ngữ; dừng ở tầng đầu tiên khớp | `app/pipeline/skills.py` |
| **Entity matching (khác)** | Công ty chuẩn hoá qua `name_norm`; địa điểm qua bảng bí danh | `common.LOCATION_ALIASES` |
| **Duplicate detection** | Chính là entity matching ở trên, chạy liên nguồn trong cùng khối blocking | `ingest.candidates_in_block` |
| **Data fusion** | Khi hai nguồn mâu thuẫn: chọn theo độ tin cậy nguồn / bản mới hơn / mô tả đầy đủ hơn, và ghi lại lý do | `app/pipeline/fusion.py`, bảng `value_conflict` |
| **Ontology mapping** | Ánh xạ kỹ năng thô về khái niệm ESCO, giữ quan hệ broader/narrower | `app/pipeline/ontology.py`, `skill_relation` |
| **Skill hierarchy** | Bao đóng đệ quy `v_skill_descendant`, index trường `skills_expanded` | `db/init.sql`, `pipeline/index.py` |
| **Provenance** | Mỗi kỹ năng ghi trường nguồn, vị trí ký tự, phương pháp khớp, độ tin cậy, nguồn | `job_skill`, `GET /api/job/{id}/provenance` |
| **Metadata** | Nguồn, lần nạp, thời điểm lấy, hash nội dung, con trỏ nạp | `data_source`, `ingestion_run`, `raw_job` |
| **Quản lý thay đổi** | `change_log` cho từng thay đổi, `job.valid_from/valid_to/is_current` theo kiểu SCD-2 | `db/init.sql` |
| **Human-in-the-loop** | Hàng chờ duyệt cho cả ba loại khớp, lưu người quyết và thời điểm | `match_review`, tab Duyệt khớp |
| **GAV** | `v_global_job` = hợp của các nguồn qua `job_source_link`, truy vấn chạy trên đây cho nhanh | `db/init.sql` |
| **LAV** | Mỗi wrapper tự mô tả nguồn của mình theo lược đồ toàn cục; thêm nguồn thứ sáu chỉ cần thêm một lớp, mediator và câu truy vấn không đổi | `app/wrappers/sources.py` |

**Về GAV/LAV.** Hệ dùng mô hình lai, đúng như cách các hệ thực tế hay làm: *truy vấn* theo GAV để nhanh,
*khai báo nguồn* theo tinh thần LAV để dễ mở rộng. Điểm yếu cố hữu của GAV là thêm nguồn phải sửa định
nghĩa view; ở đây điểm yếu đó được gỡ bằng cách để `job_source_link` chịu trách nhiệm nối, nên view GAV
không cần biết có bao nhiêu nguồn.

---

## 4. Giao diện quản trị có những gì

Bảy khu, tất cả gọi API thật, không có màn hình tĩnh:

| Khu | Chức năng |
|---|---|
| **Tìm theo kỹ năng** | Tìm một kỹ năng, tổ hợp kỹ năng (và/hoặc), mở rộng xuống kỹ năng con, xem kỹ năng cứng/mềm tách màu, mở trang truy vết nguồn gốc từng tin |
| **Cây kỹ năng** | Duyệt quan hệ cha–con, bấm vào là tìm luôn |
| **Nguồn dữ liệu** | Thêm/sửa nguồn, đặt cron, chấm độ tin cậy, bật tắt, nạp thủ công, xem lịch chạy sắp tới, nạp lại ontology, đánh lại chỉ mục |
| **Cấu trúc & ánh xạ** | Xem ánh xạ đang hiệu lực, chạy gợi ý ánh xạ tự động cho một nguồn kèm điểm và mẫu giá trị, áp dụng ánh xạ mới (bản cũ tự đóng theo thời gian), xem hồ sơ thống kê từng trường |
| **Từ điển kỹ năng** | Thêm khái niệm, gắn kỹ năng cha, thêm cách gọi khác, xoá; cache alias tự làm mới |
| **Duyệt khớp** | Duyệt cặp tin nghi trùng và cặp kỹ năng khớp yếu, kèm căn cứ chấm điểm; xem bảng xung đột giá trị và chiến lược đã dùng |
| **Lịch sử nạp** | Từng lần nạp với số tin đọc vào / mới / đổi / gộp, và nhật ký thay đổi chi tiết |

Dải sáu chỉ số trên đầu trang là bảng đo sức khoẻ tích hợp: tin sau tích hợp, tỉ lệ tin bóc tách được kỹ
năng, số khái niệm kỹ năng, số liên kết job–skill, số khớp gần đúng, số tin xác nhận có từ nhiều nguồn.

---

## 5. Cách chạy chi tiết

### 5.1 Cần có sẵn
Chỉ cần **Docker** và **Docker Compose** (Docker Desktop trên Windows/macOS đã kèm sẵn). Không cần cài Python hay PostgreSQL trên máy.

### 5.2 Cây thư mục phải đúng
```
job-di/
├── docker-compose.yml
├── Dockerfile
├── requirements.txt
├── README.md
├── db/init.sql
├── data/vietjobs.csv
├── data/hf_job_skill.jsonl
└── app/
    ├── __init__.py            (file rỗng)
    ├── common.py
    ├── wrappers/{__init__.py, base.py, sources.py, crawler.py}
    ├── pipeline/{__init__.py, ontology.py, skills.py, matching.py,
    │             schema_match.py, fusion.py, ingest.py, index.py, run.py}
    └── api/{__init__.py, main.py, static/index.html}
```
Bốn file `__init__.py` để rỗng, chỉ cần tồn tại. Trên Linux/macOS tạo nhanh:
```bash
touch app/__init__.py app/wrappers/__init__.py app/pipeline/__init__.py app/api/__init__.py
```

### 5.3 Ba lệnh để chạy
```bash
cd job-di
docker compose up -d --build       # kéo Postgres + OpenSearch, dựng image API
docker compose exec api python -m app.pipeline.run all   # nạp ontology → 4 nguồn → đánh chỉ mục
```
Rồi mở **http://localhost:8000**. Lệnh thứ hai in ra số liệu từng nguồn, ví dụ `topcv {'new': 12, ...}`.

Nếu OpenSearch chậm khởi động, chờ khoảng 30 giây rồi chạy lại lệnh thứ hai.

### 5.4 CSDL lấy ở đâu
- **Phần mềm** PostgreSQL không phải cài: Compose kéo image `postgres:16`, tạo sẵn user `di` / mật khẩu `di` / database `jobdi`, lưu vào volume `pgdata`.
- **Cấu trúc bảng** ở `db/init.sql`, được mount vào `/docker-entrypoint-initdb.d/` và Postgres tự chạy **khi khởi tạo lần đầu**.
- Lưu ý: `init.sql` chỉ chạy khi volume còn trống. Sửa file này rồi muốn áp dụng lại thì phải xoá volume:
```bash
docker compose down -v && docker compose up -d --build
```

### 5.5 Dữ liệu tin tuyển dụng
`data/` đã có hai file mẫu nhỏ nên pipeline chạy ra kết quả ngay. Muốn dữ liệu thật, tải về đúng đường dẫn rồi nạp lại:

| Nguồn | Cách lấy | Đặt vào |
|---|---|---|
| VietJobs (~48k tin tiếng Việt) | tải CSV từ repo GitHub công khai | `data/vietjobs.csv` |
| HuggingFace `job_skill_set` | `load_dataset(...).to_json()` | `data/hf_job_skill.jsonl` |
| LinkedIn Job Postings (Kaggle) | tải CSV rồi thêm một wrapper theo mẫu | `data/linkedin.csv` |
| ESCO | tải `skills_en.csv` và `broaderRelationsSkillPillar.csv` | `data/esco/` |
| TopCV, Vieclam24h | crawler tự đi lấy khi bấm nạp | tự động |

Không có ESCO thì hệ dùng từ điển seed 30 khái niệm trong `app/pipeline/ontology.py`, đủ để demo quan hệ cứng/mềm và cha–con.

### 5.6 Lệnh hay dùng
```bash
docker compose logs -f api                                   # xem log API
docker compose exec api python -m app.pipeline.run ingest topcv 100 7   # nạp 1 nguồn: 100 tin, 7 ngày
docker compose exec api python -m app.pipeline.run index     # đánh lại chỉ mục tìm kiếm
docker compose exec db psql -U di -d jobdi                   # vào SQL xem bảng
docker compose --profile graph up -d graph                   # bật Neo4j (tuỳ chọn)
```
Thư mục dự án được mount vào container và uvicorn chạy `--reload`, nên sửa code Python là API tự nạp lại; chỉ khi đổi `requirements.txt` mới cần thêm `--build`.

### 5.7 Chạy không dùng Docker
Cần PostgreSQL 16 và OpenSearch chạy sẵn, rồi:
```bash
pip install -r requirements.txt
psql -U di -d jobdi -f db/init.sql
export PG_DSN=postgresql://di:di@localhost:5432/jobdi
export OS_URL=http://localhost:9200
python -m app.pipeline.run all
uvicorn app.api.main:app --reload
```

## 6. Điểm cuối API chính

```
GET  /api/search?skill=Python&skill=Docker&mode=all&expand=true
GET  /api/skills                      cây kỹ năng
GET  /api/skill/{label}/related       kỹ năng hay đi cùng nhau
GET  /api/job/{id}/provenance         truy vết nguồn gốc từng kỹ năng
POST /api/admin/ingest/{nguồn}        nạp dữ liệu mới
POST /api/admin/source                thêm/sửa nguồn, đặt lịch, độ tin cậy
GET  /api/admin/schema/suggest/{nguồn} gợi ý ánh xạ trường
POST /api/admin/mapping               ghi ánh xạ mới, đóng bản cũ
POST /api/admin/skill                 thêm khái niệm kỹ năng
GET  /api/admin/reviews               hàng chờ duyệt khớp
GET  /api/admin/conflicts             xung đột giá trị đã hoà giải
GET  /api/admin/quality               chỉ số chất lượng tích hợp
```

## 7. Hướng mở rộng

- Bật `sentence-transformers` để tầng khớp ngữ nghĩa hoạt động với tiếng Việt.
- Đẩy `skill_relation` sang Neo4j để vẽ đồ thị kỹ năng.
- Đo precision/recall của phần trích chọn kỹ năng bằng tập HuggingFace làm chuẩn vàng, tách theo từng
  phương pháp khớp (exact / viết tắt / fuzzy / embedding) — số liệu lấy từ `job_skill.match_method`.
