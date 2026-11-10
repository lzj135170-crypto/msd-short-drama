-- P0-6 AI 回答反馈表，可重复执行
CREATE TABLE IF NOT EXISTS `t_ai_feedback` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '反馈 ID',
    `user_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '反馈用户 ID',
    `scene`      VARCHAR(32)      NOT NULL                COMMENT '场景：RAG_SEARCH/COMPANION/AUDIT/RECOMMEND',
    `request_id` VARCHAR(64)      NOT NULL                COMMENT 'AI 回答请求 ID',
    `rating`     TINYINT          NOT NULL                COMMENT '评分：1 有用，-1 没用',
    `reason`     VARCHAR(64)      NOT NULL DEFAULT ''     COMMENT '结构化原因',
    `comment`    VARCHAR(500)     NOT NULL DEFAULT ''     COMMENT '用户补充说明',
    `target_id`  BIGINT UNSIGNED  NULL                    COMMENT '关联短剧/剧集 ID',
    `session_id` VARCHAR(64)      NULL                    COMMENT '会话 ID',
    `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_scene_request` (`user_id`, `scene`, `request_id`),
    KEY `idx_scene_rating_time` (`scene`, `rating`, `create_time`),
    KEY `idx_request_id` (`request_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI 回答反馈';
