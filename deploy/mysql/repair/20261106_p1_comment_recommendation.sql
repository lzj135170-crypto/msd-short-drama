-- P1-4 推荐归因与 P1-5 评论 AI 预审，可重复执行

-- 1. 评论 AI 预审字段；MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判断
SET @db := DATABASE();

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_comment'
       AND COLUMN_NAME = 'ai_review_status') = 0,
    'ALTER TABLE `t_comment` ADD COLUMN `ai_review_status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''AI 审核状态：0 未审核，1 审核中，2 通过，3 风险，4 失败'' AFTER `status`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_comment'
       AND COLUMN_NAME = 'ai_review_result') = 0,
    'ALTER TABLE `t_comment` ADD COLUMN `ai_review_result` VARCHAR(4000) NULL COMMENT ''AI 审核结果 JSON，仅作建议'' AFTER `ai_review_status`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_comment'
       AND COLUMN_NAME = 'ai_review_time') = 0,
    'ALTER TABLE `t_comment` ADD COLUMN `ai_review_time` DATETIME(3) NULL COMMENT ''AI 审核时间'' AFTER `ai_review_result`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_comment'
       AND INDEX_NAME = 'idx_ai_status') = 0,
    'ALTER TABLE `t_comment` ADD KEY `idx_ai_status` (`ai_review_status`, `create_time`, `id`)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 推荐曝光归因
CREATE TABLE IF NOT EXISTS `t_recommendation_exposure` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '曝光 ID',
    `request_id`    VARCHAR(64)     NOT NULL                COMMENT '推荐请求 ID',
    `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
    `scene`         VARCHAR(32)     NOT NULL DEFAULT 'HOME_FEED' COMMENT '场景',
    `algorithm`     VARCHAR(32)     NOT NULL DEFAULT ''     COMMENT '算法版本',
    `experiment_id` VARCHAR(64)     NOT NULL DEFAULT ''     COMMENT '实验 ID',
    `variant`       VARCHAR(32)     NOT NULL DEFAULT ''     COMMENT '实验分桶',
    `drama_id`      BIGINT UNSIGNED NOT NULL                COMMENT '曝光短剧 ID',
    `position`      INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '展示位置，从 1 开始',
    `reason`        VARCHAR(256)    NOT NULL DEFAULT ''     COMMENT '推荐理由',
    `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '曝光时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_drama` (`request_id`, `drama_id`),
    KEY `idx_user_time` (`user_id`, `create_time`, `id`),
    KEY `idx_drama_time` (`drama_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '推荐曝光归因';

-- 3. 推荐点击/播放/完播/负反馈事件
CREATE TABLE IF NOT EXISTS `t_recommendation_event` (
    `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '事件 ID',
    `request_id`  VARCHAR(64)      NOT NULL                COMMENT '推荐请求 ID',
    `user_id`     BIGINT UNSIGNED  NOT NULL                COMMENT '用户 ID',
    `drama_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '短剧 ID',
    `event_type`  TINYINT UNSIGNED NOT NULL                COMMENT '1 点击，2 播放，3 完播，4 不感兴趣',
    `position`    INT UNSIGNED     NOT NULL DEFAULT 0      COMMENT '曝光位置',
    `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_request_drama_type` (`user_id`, `request_id`, `drama_id`, `event_type`),
    KEY `idx_user_type_time` (`user_id`, `event_type`, `create_time`, `id`),
    KEY `idx_request` (`request_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '推荐点击/播放/完播/负反馈事件';
