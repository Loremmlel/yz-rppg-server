-- =============================================
-- 建表（如果不存在）
-- =============================================

CREATE TABLE IF NOT EXISTS patient
(
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    gender      VARCHAR(16)  NOT NULL,
    id_card_no  VARCHAR(18),
    phone_no    VARCHAR(16)
);

CREATE TABLE IF NOT EXISTS bed
(
    id         BIGSERIAL PRIMARY KEY,
    ward_code  VARCHAR(64)  NOT NULL,
    room_no    VARCHAR(16)  NOT NULL,
    bed_no     VARCHAR(16)  NOT NULL,
    device_sn  VARCHAR(64),
    status     VARCHAR(16)  NOT NULL DEFAULT 'EMPTY'
);

CREATE TABLE IF NOT EXISTS visit
(
    id              BIGSERIAL PRIMARY KEY,
    patient_id      BIGINT       NOT NULL REFERENCES patient (id),
    bed_id          BIGINT       NOT NULL REFERENCES bed (id),
    admission_time  TIMESTAMPTZ,
    discharge_time  TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL
);

-- =============================================
-- 清空表（注意顺序，先清子表）
-- =============================================

TRUNCATE TABLE visit RESTART IDENTITY CASCADE;
TRUNCATE TABLE bed   RESTART IDENTITY CASCADE;
TRUNCATE TABLE patient RESTART IDENTITY CASCADE;

-- =============================================
-- 插入模拟数据
-- =============================================

-- patient
INSERT INTO patient (name, gender, id_card_no, phone_no)
VALUES ('张伟',   'MALE',    '110101199001011234', '13800000001'),
       ('李娜',   'FEMALE',  '110101199202022345', '13800000002'),
       ('王芳',   'FEMALE',  '110101198503033456', '13800000003'),
       ('赵磊',   'MALE',    '110101197804044567', '13800000004'),
       ('刘洋',   'MALE',    '110101200005055678', '13800000005'),
       ('陈静',   'FEMALE',  '110101199306066789', '13800000006');

-- bed
INSERT INTO bed (ward_code, room_no, bed_no, device_sn, status)
VALUES ('内科一区', '101', '1', 'DEV-001', 'OCCUPIED'),
       ('内科一区', '101', '2', 'DEV-002', 'EMPTY'),
       ('内科一区', '102', '1', 'DEV-003', 'OCCUPIED'),
       ('内科一区', '102', '2', 'DEV-004', 'MAINTAINING'),
       ('内科二区', '201', '1', 'DEV-005', 'OCCUPIED'),
       ('内科二区', '201', '2', 'DEV-006', 'EMPTY'),
       ('内科二区', '202', '1', 'DEV-007', 'RESERVED'),
       ('外科一区', '301', '1', 'DEV-008', 'OCCUPIED');

-- visit
INSERT INTO visit (patient_id, bed_id, admission_time, discharge_time, status)
VALUES (1, 1, '2026-02-01T08:00:00Z', NULL,                  'VISITED'),
       (2, 3, '2026-02-05T09:30:00Z', NULL,                  'VISITED'),
       (3, 5, '2026-02-10T10:00:00Z', NULL,                  'VISITED'),
       (4, 8, '2026-02-15T14:00:00Z', NULL,                  'VISITED'),
       (5, 2, '2026-01-10T08:00:00Z', '2026-01-20T10:00:00Z', 'DISCHARGED'),
       (6, 4, '2026-01-15T09:00:00Z', '2026-01-25T11:00:00Z', 'DISCHARGED');

