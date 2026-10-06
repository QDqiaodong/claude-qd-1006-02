-- H2（MySQL 兼容模式）测试库结构：与 mysql/schema.sql 的表 / 列保持一致，
-- 去掉 MySQL 专有语法（GENERATED 列、存储过程、字符集声明等）。

CREATE TABLE raw_water (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(32) NOT NULL,
    sample_at DATETIME,
    water_sample VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE raw_water_reclassify_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    raw_water_id BIGINT NOT NULL,
    from_status VARCHAR(16) NOT NULL,
    to_status VARCHAR(16) NOT NULL,
    operator_name VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    request_id VARCHAR(64),
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_rwrl_request_id UNIQUE (request_id)
);
CREATE INDEX idx_rwrl_sample ON raw_water_reclassify_log (raw_water_id, id);

CREATE TABLE sedimentation_tank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tank_code VARCHAR(16) NOT NULL UNIQUE,
    capacity_m3 DECIMAL(12,2) NOT NULL,
    retention_min INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME
);

CREATE TABLE water_filter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filter_code VARCHAR(16) NOT NULL UNIQUE,
    filter_type VARCHAR(16) NOT NULL,
    flow_m3h DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME
);

CREATE TABLE dosing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chem_name VARCHAR(32) NOT NULL,
    dose_mg_per_l DECIMAL(8,2) NOT NULL,
    target_tank VARCHAR(32) NOT NULL,
    dosed_at DATETIME,
    note VARCHAR(64),
    created_at DATETIME
);

CREATE TABLE disposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    raw_water_id BIGINT NOT NULL,
    source_name VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    owner VARCHAR(32) NOT NULL,
    conclusion VARCHAR(255),
    resolved_at DATETIME,
    created_at DATETIME,
    updated_at DATETIME
);

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
    updated_at DATETIME
);

CREATE TABLE disposal_dosing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_id BIGINT NOT NULL,
    dosing_id BIGINT NOT NULL,
    chem_name VARCHAR(32) NOT NULL,
    dose_mg_per_l DECIMAL(8,2) NOT NULL,
    target_tank VARCHAR(32) NOT NULL,
    operator_name VARCHAR(32),
    created_at DATETIME,
    CONSTRAINT uk_dd_action UNIQUE (disposal_id, chem_name, target_tank)
);

CREATE TABLE disposal_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator_name VARCHAR(32),
    detail VARCHAR(255),
    created_at DATETIME
);

CREATE TABLE process_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_type VARCHAR(8) NOT NULL,
    item_name VARCHAR(32) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME,
    updated_at DATETIME,
    CONSTRAINT uk_pc_item UNIQUE (item_type, item_name)
);

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
    CONSTRAINT uk_dr_batch UNIQUE (target_section, execute_at)
);

CREATE TABLE dosing_record_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator_name VARCHAR(32),
    detail VARCHAR(255),
    created_at DATETIME
);
