SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 自来水厂 · 工艺链监控（取水 → 沉淀 → 过滤 → 消毒 → 清水池）
-- 库名 waterworks_plant；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS waterworks_plant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE waterworks_plant;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS dosing_record_log;
DROP TABLE IF EXISTS dosing_record;
DROP TABLE IF EXISTS process_config;
DROP TABLE IF EXISTS disposal_log;
DROP TABLE IF EXISTS disposal_dosing;
DROP TABLE IF EXISTS disposal_equipment;
DROP TABLE IF EXISTS disposal;
DROP TABLE IF EXISTS dosing;
DROP TABLE IF EXISTS water_filter;
DROP TABLE IF EXISTS sedimentation_tank;
DROP TABLE IF EXISTS raw_water;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 原水监测 ----------------
-- 水质值对象 water_sample 由自定义 Hibernate UserType 持久化为单列 VARCHAR：ph|turbidity|chlorine
CREATE TABLE raw_water (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(32) NOT NULL,
    sample_at DATETIME,
    water_sample VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 沉淀池 ----------------
CREATE TABLE sedimentation_tank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tank_code VARCHAR(16) NOT NULL UNIQUE,
    capacity_m3 DECIMAL(12,2) NOT NULL,
    retention_min INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 滤池 ----------------
CREATE TABLE water_filter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filter_code VARCHAR(16) NOT NULL UNIQUE,
    filter_type VARCHAR(16) NOT NULL,
    flow_m3h DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 加药 ----------------
CREATE TABLE dosing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chem_name VARCHAR(32) NOT NULL,
    dose_mg_per_l DECIMAL(8,2) NOT NULL,
    target_tank VARCHAR(32) NOT NULL,
    dosed_at DATETIME,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 超标处置单 ----------------
-- 状态机：待处置 → 执行中 → 已解除（只允许顺向流转，由后端 Dict 约束）。
-- active_source 是生成列：未解除（待处置/执行中）时等于 source_name，解除后为 NULL；
-- 配合唯一索引 uk_disposal_active，在数据库层保证「每个水源同一时刻最多一条未解除处置」，
-- 两名值班人员并发发起时第二个 INSERT 必然唯一冲突，由后端转成「已被接管」的明确提示。
CREATE TABLE disposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    raw_water_id BIGINT NOT NULL,
    source_name VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    owner VARCHAR(32) NOT NULL,
    conclusion VARCHAR(255),
    resolved_at DATETIME,
    created_at DATETIME,
    updated_at DATETIME,
    active_source VARCHAR(32) GENERATED ALWAYS AS (IF(status IN ('待处置','执行中'), source_name, NULL)) STORED,
    UNIQUE KEY uk_disposal_active (active_source),
    KEY idx_disposal_raw_water (raw_water_id),
    KEY idx_disposal_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 处置单-设备关联 ----------------
-- link_status：正常 / 冲突 / 已排除 / 已改选。
-- 设备在处置期间离开「运行」（清洗/反洗）时，由设备状态流转的同事务钩子把「正常」标成「冲突」，
-- 关联绝不悄悄失效；值班人员必须改选可用设备或填写排除原因后才能继续。
CREATE TABLE disposal_equipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_id BIGINT NOT NULL,
    equip_type VARCHAR(8) NOT NULL,
    equip_id BIGINT NOT NULL,
    equip_code VARCHAR(16) NOT NULL,
    link_status VARCHAR(8) NOT NULL,
    conflict_reason VARCHAR(128),
    exclude_reason VARCHAR(128),
    created_at DATETIME,
    updated_at DATETIME,
    KEY idx_de_disposal (disposal_id),
    KEY idx_de_equip (equip_type, equip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 处置单-加药登记 ----------------
-- 每条登记同时落一行 dosing（工段加药流水），dosing_id 回指。
-- uk_dd_action 保证同一处置单内「同药剂 + 同目标工段」只落一条：
-- 重复点击 / 重复提交命中唯一约束，后端按幂等成功返回已存在记录，不会产生两份。
CREATE TABLE disposal_dosing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_id BIGINT NOT NULL,
    dosing_id BIGINT NOT NULL,
    chem_name VARCHAR(32) NOT NULL,
    dose_mg_per_l DECIMAL(8,2) NOT NULL,
    target_tank VARCHAR(32) NOT NULL,
    operator_name VARCHAR(32),
    created_at DATETIME,
    UNIQUE KEY uk_dd_action (disposal_id, chem_name, target_tank),
    KEY idx_dd_disposal (disposal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 处置流转记录 ----------------
CREATE TABLE disposal_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator_name VARCHAR(32),
    detail VARCHAR(255),
    created_at DATETIME,
    KEY idx_dl_disposal (disposal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 工艺配置（药剂 / 目标工段） ----------------
-- 加药登记的「当前有效口径」：登记时药剂与目标工段必须在启用项内，否则不能登记为有效记录。
-- 配置只增停不删历史：停用某项后，历史记录仍展示登记时的药剂 / 工段，并由后端计算
-- 「配置已变更」标记，绝不回写改写历史。
CREATE TABLE process_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_type VARCHAR(8) NOT NULL,
    item_name VARCHAR(32) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_pc_item (item_type, item_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 加药批次记录 ----------------
-- 状态机：待确认 → 已确认；待确认 → 异常待复核 → 已确认 / 已退回（只允许这些流转，由后端 Dict 约束）。
-- uk_dr_batch 保证「同一目标工段 + 同一执行时间」只落一个批次：
-- 重复点击 / 网络重试 / 两名运行工并发登记，第二个 INSERT 必然唯一冲突，
-- 由后端转成「已存在批次 #N（当前状态…）」的明确提示，失败方能看到已存在记录，
-- 不会出现一条待复核、一条已确认的分叉。
CREATE TABLE dosing_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chem_name VARCHAR(32) NOT NULL,
    dose_mg_per_l DECIMAL(8,2) NOT NULL,
    target_section VARCHAR(32) NOT NULL,
    execute_at DATETIME NOT NULL,
    operator_name VARCHAR(32) NOT NULL,
    note VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    anomaly_reason VARCHAR(255),
    review_reason VARCHAR(255),
    confirmed_by VARCHAR(32),
    confirmed_at DATETIME,
    reviewed_by VARCHAR(32),
    reviewed_at DATETIME,
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_dr_batch (target_section, execute_at),
    KEY idx_dr_status (status),
    KEY idx_dr_execute (execute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 加药批次操作留痕 ----------------
-- 登记 / 修改 / 确认 / 标记异常 / 复核通过 / 复核退回 全部留痕，退回保留原始登记与原因。
CREATE TABLE dosing_record_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator_name VARCHAR(32),
    detail VARCHAR(255),
    created_at DATETIME,
    KEY idx_drl_record (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO raw_water (id, source_name, sample_at, water_sample, status, created_at) VALUES
(1, '长江取水泵房', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '7.30|1.20|0.60', '合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, '长江取水泵房', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, '7.10|2.40|0.45', '预警', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(3, '一号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY, '7.55|0.90|1.20', '合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY),
(4, '二号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY,  '8.10|1.80|3.20', '预警', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY),
(5, '长江取水泵房', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, '8.40|2.90|3.80', '超标', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(6, '二号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY,  '8.30|2.80|0.40', '超标', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT INTO sedimentation_tank (id, tank_code, capacity_m3, retention_min, status, note, created_at) VALUES
(1, 'SED-01', 5000.00, 90, '运行', '一期平流沉淀池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, 'SED-02', 5000.00, 95, '运行', '二期平流沉淀池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(3, 'SED-03', 3200.00, 80, '清洗', '斜管沉淀池，待恢复', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO water_filter (id, filter_code, filter_type, flow_m3h, status, note, created_at) VALUES
(1, 'F-01', '砂滤', 280.00, '运行', 'V 型滤池一组', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, 'F-02', '砂滤', 280.00, '运行', 'V 型滤池二组', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(3, 'F-03', '炭滤', 160.00, '反洗', '活性炭滤池，反洗中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO dosing (id, chem_name, dose_mg_per_l, target_tank, dosed_at, note, created_at) VALUES
(1, '聚合氯化铝', 25.00, '沉淀池-1', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '混凝剂前加', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, '次氯酸钠', 1.20,  '清水池',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '出厂水消毒',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(3, '聚合氯化铝', 28.00, '沉淀池-2', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, '原水浊度偏高，加大投加', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(4, '聚合氯化铝', 32.00, 'SED-01',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 20 MINUTE, '处置单#1 联动加药', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 20 MINUTE);

-- ---------------- 超标处置种子：一条已解除的完整闭环（对应 raw_water #5） ----------------
INSERT INTO disposal (id, raw_water_id, source_name, status, owner, conclusion, resolved_at, created_at, updated_at) VALUES
(1, 5, '长江取水泵房', '已解除', '王值班', '加大聚合氯化铝投加至 32 mg/L，F-03 反洗期间改由 F-01/F-02 承担，2 小时后复检合格',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 3 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 3 HOUR);

INSERT INTO disposal_equipment (id, disposal_id, equip_type, equip_id, equip_code, link_status, conflict_reason, exclude_reason, created_at, updated_at) VALUES
(1, 1, '沉淀池', 1, 'SED-01', '正常', NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(2, 1, '滤池',   1, 'F-01',  '正常', NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(3, 1, '滤池',   3, 'F-03',  '已排除', '设备已转入反洗', '反洗预计 4 小时，处置期间改由 F-01/F-02 承担', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 1 HOUR);

INSERT INTO disposal_dosing (id, disposal_id, dosing_id, chem_name, dose_mg_per_l, target_tank, operator_name, created_at) VALUES
(1, 1, 4, '聚合氯化铝', 32.00, 'SED-01', '王值班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 20 MINUTE);

INSERT INTO disposal_log (id, disposal_id, action, from_status, to_status, operator_name, detail, created_at) VALUES
(1, 1, '发起',   NULL, '待处置', '王值班', '原水记录 #5 判超标，发起联动处置；关联 SED-01、F-01、F-03', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(2, 1, '登记加药', '待处置', '待处置', '王值班', '聚合氯化铝 32.00 mg/L → SED-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 20 MINUTE),
(3, 1, '进入执行', '待处置', '执行中', '王值班', '确认沉淀池 1 台、滤池 2 台，进入执行中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 30 MINUTE),
(4, 1, '设备冲突', '执行中', '执行中', '系统', 'F-03 设备已转入反洗，关联标为冲突', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 40 MINUTE),
(5, 1, '排除设备', '执行中', '执行中', '王值班', '排除 F-03：反洗预计 4 小时，处置期间改由 F-01/F-02 承担', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 1 HOUR),
(6, 1, '解除',   '执行中', '已解除', '王值班', '加大聚合氯化铝投加至 32 mg/L，F-03 反洗期间改由 F-01/F-02 承担，2 小时后复检合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY + INTERVAL 3 HOUR);

-- ---------------- 工艺配置种子：当前有效药剂与目标工段 ----------------
INSERT INTO process_config (id, item_type, item_name, enabled, created_at, updated_at) VALUES
(1, '药剂',     '聚合氯化铝', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, '药剂',     '次氯酸钠',   1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(3, '目标工段', '沉淀池-1',   1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(4, '目标工段', '沉淀池-2',   1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(5, '目标工段', '清水池',     1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

-- ---------------- 加药批次种子：覆盖 已确认 / 待确认 / 异常待复核 / 已退回 四种状态 ----------------
INSERT INTO dosing_record (id, chem_name, dose_mg_per_l, target_section, execute_at, operator_name, note, status,
                           anomaly_reason, review_reason, confirmed_by, confirmed_at, reviewed_by, reviewed_at, created_at, updated_at) VALUES
(1, '聚合氯化铝', 25.00, '沉淀池-1', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '王运行', '混凝剂前加，常规批次', '已确认',
 NULL, NULL, '李管理', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY + INTERVAL 2 HOUR, NULL, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY + INTERVAL 2 HOUR),
(2, '次氯酸钠', 1.20, '清水池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, '王运行', '出厂水消毒', '待确认',
 NULL, NULL, NULL, NULL, NULL, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3, '聚合氯化铝', 45.00, '沉淀池-2', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '赵运行', '原水浊度偏高，加大投加', '异常待复核',
 '投加量 45.00 mg/L 超出常规区间（≤ 35 mg/L），待现场复核', NULL, NULL, NULL, NULL, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 30 MINUTE),
(4, '次氯酸钠', 8.50, '清水池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, '赵运行', NULL, '已退回',
 '投加量 8.50 mg/L 超出余氯控制要求', '现场回执投加量为 0.85 mg/L，登记多写一位；原始登记保留，退回后重新登记',
 NULL, NULL, '李管理', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 2 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 2 HOUR);

INSERT INTO dosing_record_log (id, record_id, action, from_status, to_status, operator_name, detail, created_at) VALUES
(1, 1, '登记',     NULL, '待确认',     '王运行', '聚合氯化铝 25.00 mg/L → 沉淀池-1', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, 1, '确认',     '待确认', '已确认', '李管理', '核对现场回执无误，确认为有效批次', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY + INTERVAL 2 HOUR),
(3, 2, '登记',     NULL, '待确认',     '王运行', '次氯酸钠 1.20 mg/L → 清水池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(4, 3, '登记',     NULL, '待确认',     '赵运行', '聚合氯化铝 45.00 mg/L → 沉淀池-2', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(5, 3, '标记异常', '待确认', '异常待复核', '李管理', '投加量 45.00 mg/L 超出常规区间（≤ 35 mg/L），待现场复核', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 30 MINUTE),
(6, 4, '登记',     NULL, '待确认',     '赵运行', '次氯酸钠 8.50 mg/L → 清水池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(7, 4, '标记异常', '待确认', '异常待复核', '李管理', '投加量 8.50 mg/L 超出余氯控制要求', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 1 HOUR),
(8, 4, '复核退回', '异常待复核', '已退回', '李管理', '退回原因：现场回执投加量为 0.85 mg/L，登记多写一位；原始登记保留，退回后重新登记', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 2 HOUR);
