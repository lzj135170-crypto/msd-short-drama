-- P2-1 字幕/ASR 剧情问答：字幕元数据和带时间戳的片段表
-- 可重复执行；不修改已有业务列。

CREATE TABLE IF NOT EXISTS `t_episode_subtitle` (
    `id`            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '字幕 ID',
    `episode_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '剧集 ID',
    `language`      VARCHAR(16)      NOT NULL DEFAULT 'zh'   COMMENT '语言',
    `format`        VARCHAR(16)      NOT NULL DEFAULT 'SRT'  COMMENT '格式：SRT/VTT/ASR',
    `object_key`    VARCHAR(512)     NOT NULL DEFAULT ''     COMMENT '原始字幕对象键；Demo 存内容哈希',
    `source`        VARCHAR(16)      NOT NULL DEFAULT 'UPLOAD' COMMENT '来源：UPLOAD/ASR',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 隐藏，1 可用',
    `segment_count` INT UNSIGNED     NOT NULL DEFAULT 0      COMMENT '片段数',
    `create_time`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_episode_language` (`episode_id`, `language`),
    KEY `idx_episode` (`episode_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '分集字幕元数据';

CREATE TABLE IF NOT EXISTS `t_episode_transcript_segment` (
    `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '片段 ID',
    `subtitle_id` BIGINT UNSIGNED  NOT NULL                COMMENT '字幕 ID',
    `episode_id`  BIGINT UNSIGNED  NOT NULL                COMMENT '剧集 ID',
    `drama_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '短剧 ID',
    `episode_no`  INT UNSIGNED     NOT NULL                COMMENT '集数序号',
    `start_ms`    INT UNSIGNED     NOT NULL                COMMENT '开始时间（毫秒）',
    `end_ms`      INT UNSIGNED     NOT NULL                COMMENT '结束时间（毫秒）',
    `text`        VARCHAR(1000)    NOT NULL                COMMENT '字幕文本',
    `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 隐藏，1 可用',
    `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_episode_time` (`episode_id`, `start_ms`),
    KEY `idx_drama_episode` (`drama_id`, `episode_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '分集字幕片段';
