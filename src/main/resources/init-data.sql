DROP TABLE IF EXISTS visit CASCADE;
DROP TABLE IF EXISTS patient CASCADE;
DROP TABLE IF EXISTS bed CASCADE;

-- =============================================
-- 建表（如果不存在）— 唯一约束内联，保证幂等
-- =============================================

CREATE TABLE IF NOT EXISTS patient
(
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    gender      VARCHAR(16)  NOT NULL,
    id_card_no  VARCHAR(18)  UNIQUE,
    phone_no    VARCHAR(16)
);

CREATE TABLE IF NOT EXISTS bed
(
    id         BIGSERIAL PRIMARY KEY,
    ward_code  VARCHAR(64)  NOT NULL,
    room_no    VARCHAR(16)  NOT NULL,
    bed_no     VARCHAR(16)  NOT NULL,
    device_sn  VARCHAR(64),
    status     VARCHAR(16)  NOT NULL DEFAULT 'EMPTY',
    UNIQUE (ward_code, room_no, bed_no)
);

CREATE TABLE IF NOT EXISTS visit
(
    id              BIGSERIAL PRIMARY KEY,
    patient_id      BIGINT       NOT NULL REFERENCES patient (id),
    bed_id          BIGINT       NOT NULL REFERENCES bed (id),
    admission_time  TIMESTAMPTZ,
    discharge_time  TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL,
    UNIQUE (patient_id, bed_id, admission_time)
);

-- =============================================
-- 插入模拟数据（幂等）
-- =============================================

INSERT INTO patient (name, gender, id_card_no, phone_no)
VALUES ('张伟',   'MALE',    '110101199001011234', '13800000001'),
       ('李娜',   'FEMALE',  '110101199202022345', '13800000002'),
       ('王芳',   'FEMALE',  '110101198503033456', '13800000003'),
       ('赵磊',   'MALE',    '110101197804044567', '13800000004'),
       ('刘洋',   'MALE',    '110101200005055678', '13800000005'),
       ('陈静',   'FEMALE',  '110101199306066789', '13800000006')
ON CONFLICT (id_card_no) DO NOTHING;

INSERT INTO bed (ward_code, room_no, bed_no, device_sn, status)
VALUES ('内科一区', '101', '1', 'DEV-001', 'OCCUPIED'),
       ('内科一区', '101', '2', 'DEV-002', 'EMPTY'),
       ('内科一区', '102', '1', 'DEV-003', 'OCCUPIED'),
       ('内科一区', '102', '2', 'DEV-004', 'MAINTAINING'),
       ('内科二区', '201', '1', 'DEV-005', 'OCCUPIED'),
       ('内科二区', '201', '2', 'DEV-006', 'EMPTY'),
       ('内科二区', '202', '1', 'DEV-007', 'RESERVED'),
       ('外科一区', '301', '1', 'DEV-008', 'OCCUPIED')
ON CONFLICT (ward_code, room_no, bed_no) DO NOTHING;

INSERT INTO visit (patient_id, bed_id, admission_time, discharge_time, status)
SELECT p.id, b.id, '2026-02-01T08:00:00Z'::TIMESTAMPTZ, NULL, 'ADMITTED'
FROM patient p, bed b WHERE p.id_card_no = '110101199001011234' AND b.device_sn = 'DEV-001'
ON CONFLICT (patient_id, bed_id, admission_time) DO NOTHING;

INSERT INTO visit (patient_id, bed_id, admission_time, discharge_time, status)
SELECT p.id, b.id, '2026-02-05T09:30:00Z'::TIMESTAMPTZ, NULL, 'ADMITTED'
FROM patient p, bed b WHERE p.id_card_no = '110101199202022345' AND b.device_sn = 'DEV-003'
ON CONFLICT (patient_id, bed_id, admission_time) DO NOTHING;

INSERT INTO visit (patient_id, bed_id, admission_time, discharge_time, status)
SELECT p.id, b.id, '2026-02-10T10:00:00Z'::TIMESTAMPTZ, NULL, 'ADMITTED'
FROM patient p, bed b WHERE p.id_card_no = '110101198503033456' AND b.device_sn = 'DEV-005'
ON CONFLICT (patient_id, bed_id, admission_time) DO NOTHING;

INSERT INTO visit (patient_id, bed_id, admission_time, discharge_time, status)
SELECT p.id, b.id, '2026-02-15T14:00:00Z'::TIMESTAMPTZ, NULL, 'ADMITTED'
FROM patient p, bed b WHERE p.id_card_no = '110101197804044567' AND b.device_sn = 'DEV-008'
ON CONFLICT (patient_id, bed_id, admission_time) DO NOTHING;

INSERT INTO visit (patient_id, bed_id, admission_time, discharge_time, status)
SELECT p.id, b.id, '2026-01-10T08:00:00Z'::TIMESTAMPTZ, '2026-01-20T10:00:00Z'::TIMESTAMPTZ, 'DISCHARGED'
FROM patient p, bed b WHERE p.id_card_no = '110101200005055678' AND b.device_sn = 'DEV-002'
ON CONFLICT (patient_id, bed_id, admission_time) DO NOTHING;

INSERT INTO visit (patient_id, bed_id, admission_time, discharge_time, status)
SELECT p.id, b.id, '2026-01-15T09:00:00Z'::TIMESTAMPTZ, '2026-01-25T11:00:00Z'::TIMESTAMPTZ, 'DISCHARGED'
FROM patient p, bed b WHERE p.id_card_no = '110101199306066789' AND b.device_sn = 'DEV-004'
ON CONFLICT (patient_id, bed_id, admission_time) DO NOTHING;

-- =============================================
-- TimescaleDB 患者生命体征时序表
-- =============================================

-- 1. 创建标准 PostgreSQL 表（幂等）
CREATE TABLE IF NOT EXISTS patient_vitals (
    "time"              TIMESTAMPTZ       NOT NULL,
    bed_id              BIGINT            NOT NULL,
    patient_id          BIGINT            NOT NULL,
    hr                  DOUBLE PRECISION,
    sqi                 DOUBLE PRECISION,
    latency             DOUBLE PRECISION,
    hrv_bpm             DOUBLE PRECISION,
    hrv_ibi             DOUBLE PRECISION,
    hrv_sdnn            DOUBLE PRECISION,
    hrv_sdsd            DOUBLE PRECISION,
    hrv_rmssd           DOUBLE PRECISION,
    hrv_pnn20           DOUBLE PRECISION,
    hrv_pnn50           DOUBLE PRECISION,
    hrv_hr_mad          DOUBLE PRECISION,
    hrv_sd1             DOUBLE PRECISION,
    hrv_sd2             DOUBLE PRECISION,
    hrv_s               DOUBLE PRECISION,
    hrv_sd1_sd2         DOUBLE PRECISION,
    hrv_breathingrate   DOUBLE PRECISION,
    hrv_vlf             DOUBLE PRECISION,
    hrv_tp              DOUBLE PRECISION,
    hrv_hf              DOUBLE PRECISION,
    hrv_lf              DOUBLE PRECISION,
    hrv_lf_hf           DOUBLE PRECISION
);

-- 2. 转换为 TimescaleDB 超表（if_not_exists => true 保证幂等，无需 DO $$ 块）
SELECT create_hypertable('patient_vitals', 'time', if_not_exists => TRUE);

-- 3. 创建复合查询索引（幂等）
CREATE INDEX IF NOT EXISTS ix_patient_vitals_bed_time
    ON patient_vitals (bed_id, "time" DESC);

CREATE INDEX IF NOT EXISTS ix_patient_vitals_patient_time
    ON patient_vitals (patient_id, "time" DESC);

