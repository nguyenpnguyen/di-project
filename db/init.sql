-- ============================================================
-- Unified schema cho hệ tích hợp tin tuyển dụng (GAV)
-- 3 tầng: (1) staging thô theo nguồn, (2) global schema,
--         (3) ontology kỹ năng + metadata/provenance/version
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------- 1. Đăng ký nguồn & lô nạp (lifecycle) ----------
CREATE TABLE IF NOT EXISTS data_source (
    source_id     SERIAL PRIMARY KEY,
    name          TEXT UNIQUE NOT NULL,        -- topcv, vieclam24h, itviec, vietjobs, linkedin
    kind          TEXT NOT NULL,               -- crawler | dataset | ontology
    base_url      TEXT,
    schedule_cron TEXT,                        -- lịch chạy lại
    enabled       BOOLEAN DEFAULT TRUE,
    last_run_at   TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ingestion_run (
    run_id        SERIAL PRIMARY KEY,
    source_id     INT REFERENCES data_source(source_id),
    started_at    TIMESTAMPTZ DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT DEFAULT 'running',      -- running | success | failed
    rows_in       INT DEFAULT 0,
    rows_new      INT DEFAULT 0,
    rows_changed  INT DEFAULT 0,
    note          TEXT
);

-- ---------- 2. Staging: giữ nguyên bản ghi gốc ----------
CREATE TABLE IF NOT EXISTS raw_job (
    raw_id        BIGSERIAL PRIMARY KEY,
    source_id     INT REFERENCES data_source(source_id),
    run_id        INT REFERENCES ingestion_run(run_id),
    external_id   TEXT NOT NULL,               -- id ở nguồn
    url           TEXT,
    payload       JSONB NOT NULL,              -- schema gốc, chưa map
    content_hash  TEXT NOT NULL,               -- phát hiện thay đổi
    fetched_at    TIMESTAMPTZ DEFAULT now(),
    UNIQUE (source_id, external_id, content_hash)
);

-- ---------- 3. Global schema ----------
CREATE TABLE IF NOT EXISTS company (
    company_id    SERIAL PRIMARY KEY,
    name_norm     TEXT UNIQUE NOT NULL,
    name_display  TEXT
);

CREATE TABLE IF NOT EXISTS location (
    location_id   SERIAL PRIMARY KEY,
    canonical     TEXT UNIQUE NOT NULL,        -- "Ha Noi", "Ho Chi Minh City"
    aliases       TEXT[]                       -- HN, Hà Nội, TP.HCM, Sai Gon...
);

CREATE TABLE IF NOT EXISTS job (
    job_id        BIGSERIAL PRIMARY KEY,
    cluster_key   TEXT,                        -- khoá gom trùng liên nguồn
    title         TEXT NOT NULL,
    title_norm    TEXT,
    company_id    INT REFERENCES company(company_id),
    location_id   INT REFERENCES location(location_id),
    employment_type TEXT,
    exp_years_min NUMERIC,
    salary_min    NUMERIC,
    salary_max    NUMERIC,
    salary_currency TEXT DEFAULT 'VND',
    description   TEXT,
    requirement   TEXT,
    posted_at     DATE,
    valid_from    TIMESTAMPTZ DEFAULT now(),
    valid_to      TIMESTAMPTZ,                 -- NULL = bản ghi hiện hành (SCD-2)
    is_current    BOOLEAN DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_job_cluster ON job(cluster_key) WHERE is_current;

-- Liên kết job global <-> bản ghi nguồn (nhiều nguồn -> 1 job)
CREATE TABLE IF NOT EXISTS job_source_link (
    job_id        BIGINT REFERENCES job(job_id) ON DELETE CASCADE,
    raw_id        BIGINT REFERENCES raw_job(raw_id),
    source_id     INT REFERENCES data_source(source_id),
    match_score   NUMERIC,                     -- điểm entity matching
    PRIMARY KEY (job_id, raw_id)
);

-- ---------- 4. Ontology kỹ năng (ESCO / O*NET) ----------
CREATE TABLE IF NOT EXISTS skill (
    skill_id      SERIAL PRIMARY KEY,
    concept_uri   TEXT UNIQUE,                 -- URI ESCO, NULL nếu skill nội bộ
    pref_label    TEXT NOT NULL,
    skill_type    TEXT,                        -- hard | soft
    level         INT DEFAULT 0,               -- 0 = nhóm gốc, càng lớn càng cụ thể
    source_ont    TEXT DEFAULT 'esco'
);

CREATE TABLE IF NOT EXISTS skill_alias (
    alias_id      SERIAL PRIMARY KEY,
    skill_id      INT REFERENCES skill(skill_id) ON DELETE CASCADE,
    alias         TEXT NOT NULL,
    alias_norm    TEXT NOT NULL,
    lang          TEXT DEFAULT 'en'
);
CREATE INDEX IF NOT EXISTS idx_alias_trgm ON skill_alias USING gin (alias_norm gin_trgm_ops);

-- Quan hệ phân cấp: broader/narrower/related
CREATE TABLE IF NOT EXISTS skill_relation (
    parent_id     INT REFERENCES skill(skill_id) ON DELETE CASCADE,
    child_id      INT REFERENCES skill(skill_id) ON DELETE CASCADE,
    rel_type      TEXT DEFAULT 'broader',
    PRIMARY KEY (parent_id, child_id, rel_type)
);

-- ---------- 5. Skill của job + provenance ----------
CREATE TABLE IF NOT EXISTS job_skill (
    job_id        BIGINT REFERENCES job(job_id) ON DELETE CASCADE,
    skill_id      INT REFERENCES skill(skill_id),
    surface_form  TEXT,                        -- chuỗi gốc trong tin ("Py, Python3")
    is_required   BOOLEAN DEFAULT TRUE,
    confidence    NUMERIC,
    match_method  TEXT,                        -- exact | alias | fuzzy | embedding | llm
    src_field     TEXT,                        -- description | requirement | skills
    src_offset    INT,                         -- vị trí ký tự -> truy vết
    source_id     INT REFERENCES data_source(source_id),
    PRIMARY KEY (job_id, skill_id, surface_form)
);

-- ---------- 6. Metadata theo thời gian ----------
CREATE TABLE IF NOT EXISTS schema_mapping (          -- schema matching, khai báo được
    mapping_id    SERIAL PRIMARY KEY,
    source_id     INT REFERENCES data_source(source_id),
    source_field  TEXT NOT NULL,               -- job_name, income, dia_diem
    global_field  TEXT NOT NULL,               -- title, salary_min, location
    transform     TEXT,                        -- tên hàm chuẩn hoá
    confidence    NUMERIC DEFAULT 1.0,
    valid_from    TIMESTAMPTZ DEFAULT now(),
    valid_to      TIMESTAMPTZ                  -- versioning mapping
);

CREATE TABLE IF NOT EXISTS change_log (              -- quản lý thay đổi
    change_id     BIGSERIAL PRIMARY KEY,
    entity        TEXT,                        -- job | skill | mapping
    entity_id     TEXT,
    change_type   TEXT,                        -- insert | update | delete
    diff          JSONB,
    run_id        INT REFERENCES ingestion_run(run_id),
    changed_at    TIMESTAMPTZ DEFAULT now()
);

-- ---------- 7. View GAV: global = UNION các nguồn ----------
CREATE OR REPLACE VIEW v_global_job AS
SELECT j.job_id, j.title, c.name_display AS company, l.canonical AS location,
       j.salary_min, j.salary_max, j.exp_years_min,
       array_agg(DISTINCT ds.name) AS sources,
       array_agg(DISTINCT s.pref_label) AS skills
FROM job j
LEFT JOIN company c ON c.company_id = j.company_id
LEFT JOIN location l ON l.location_id = j.location_id
LEFT JOIN job_source_link jsl ON jsl.job_id = j.job_id
LEFT JOIN data_source ds ON ds.source_id = jsl.source_id
LEFT JOIN job_skill js ON js.job_id = j.job_id
LEFT JOIN skill s ON s.skill_id = js.skill_id
WHERE j.is_current
GROUP BY j.job_id, c.name_display, l.canonical;

-- Đóng bao phân cấp kỹ năng: dùng cho "tìm theo skill cha"
CREATE OR REPLACE VIEW v_skill_descendant AS
WITH RECURSIVE d(root_id, skill_id, depth) AS (
    SELECT skill_id, skill_id, 0 FROM skill
    UNION ALL
    SELECT d.root_id, r.child_id, d.depth + 1
    FROM d JOIN skill_relation r ON r.parent_id = d.skill_id AND r.rel_type = 'broader'
    WHERE d.depth < 6
)
SELECT * FROM d;

INSERT INTO data_source(name, kind, base_url, schedule_cron) VALUES
 ('vietjobs','dataset','https://github.com/…/vietjobs','0 3 * * 1'),
 ('hf_job_skill','dataset','https://huggingface.co/datasets','0 3 * * 1'),
 ('linkedin_kaggle','dataset','https://kaggle.com','0 4 1 * *'),
 ('topcv','crawler','https://www.topcv.vn','0 2 * * *'),
 ('vieclam24h','crawler','https://vieclam24h.vn','0 2 * * *'),
 ('esco','ontology','https://esco.ec.europa.eu','0 5 1 1 *')
ON CONFLICT (name) DO NOTHING;

-- ============================================================
-- Bổ sung: quản trị nguồn, hàng đợi duyệt khớp, hồ sơ trường
-- ============================================================
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS wrapper_class TEXT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS trust_score NUMERIC DEFAULT 0.7;   -- dùng cho data fusion
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS cursor_state JSONB DEFAULT '{}';   -- nạp tăng dần
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS note TEXT;

-- Hồ sơ trường của nguồn: phục vụ schema matching dựa trên dữ liệu
CREATE TABLE IF NOT EXISTS source_field_profile (
    source_id   INT REFERENCES data_source(source_id) ON DELETE CASCADE,
    field       TEXT,
    n_values    INT,
    n_distinct  INT,
    avg_len     NUMERIC,
    pct_numeric NUMERIC,
    samples     TEXT[],
    profiled_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (source_id, field)
);

-- Hàng đợi duyệt các cặp khớp không chắc chắn (human-in-the-loop)
CREATE TABLE IF NOT EXISTS match_review (
    review_id   BIGSERIAL PRIMARY KEY,
    kind        TEXT,                 -- job_dedup | skill_map | schema_map
    left_ref    TEXT,
    right_ref   TEXT,
    score       NUMERIC,
    evidence    JSONB,
    status      TEXT DEFAULT 'pending',   -- pending | accepted | rejected
    decided_at  TIMESTAMPTZ,
    decided_by  TEXT
);

-- Xung đột giá trị giữa các nguồn + cách hoà giải
CREATE TABLE IF NOT EXISTS value_conflict (
    conflict_id BIGSERIAL PRIMARY KEY,
    job_id      BIGINT REFERENCES job(job_id) ON DELETE CASCADE,
    field       TEXT,
    values_by_source JSONB,
    resolved    TEXT,
    strategy    TEXT,               -- trust | recency | majority | longest
    resolved_at TIMESTAMPTZ DEFAULT now()
);

UPDATE data_source SET trust_score = 0.9 WHERE kind = 'dataset';
UPDATE data_source SET wrapper_class = name WHERE wrapper_class IS NULL;
