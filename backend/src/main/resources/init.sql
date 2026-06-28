-- ============================================================
-- TransformPDF 数据库初始化脚本
-- 使用方法: mysql -u root -p < init.sql
-- ============================================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS transformpdf
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE transformpdf;

-- 2. 创建转换任务表
--    注意: JPA 配置 ddl-auto=update 时会自动建表，
--    此脚本用于手动初始化或参考表结构。
CREATE TABLE IF NOT EXISTS conversion_tasks (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY  COMMENT '主键ID',
    original_filename   VARCHAR(500)    NOT NULL                    COMMENT '原始文件名',
    stored_filename     VARCHAR(500)    NOT NULL                    COMMENT '存储文件名(UUID)',
    file_path           VARCHAR(1000)   NOT NULL                    COMMENT '文件相对路径',
    file_size           BIGINT          DEFAULT NULL                COMMENT '文件大小(字节)',
    file_type           VARCHAR(100)    DEFAULT NULL                COMMENT 'MIME类型',
    conversion_type     VARCHAR(50)     DEFAULT NULL                COMMENT '转换类型: IMAGE_TO_PDF/PDF_TO_WORD/IMAGE_SCAN_TO_IMAGE等',
    output_filename     VARCHAR(500)    DEFAULT NULL                COMMENT '输出文件名',
    output_path         VARCHAR(1000)   DEFAULT NULL                COMMENT '输出文件路径',
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING'  COMMENT '状态: PENDING/PROCESSING/COMPLETED/FAILED',
    error_message       TEXT            DEFAULT NULL                COMMENT '错误信息',
    is_scanned          TINYINT(1)      DEFAULT 0                   COMMENT '是否扫描任务',
    scan_area_json      TEXT            DEFAULT NULL                COMMENT '扫描区域角点坐标JSON',
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',

    INDEX idx_status        (status),
    INDEX idx_conversion_type (conversion_type),
    INDEX idx_created_at    (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='转换任务表';
