-- 码上短剧 - P3 互动异步与全链路闭环迁移
-- 适用已有 DL 库；幂等执行。
-- 执行：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/repair/20261027_interaction_async.sql

USE DL;

DROP PROCEDURE IF EXISTS add_column_if_absent;
DELIMITER //
CREATE PROCEDURE add_column_if_absent(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl VARCHAR(500)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

DROP PROCEDURE IF EXISTS add_index_if_absent;
DELIMITER //
CREATE PROCEDURE add_index_if_absent(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_ddl VARCHAR(500)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- outbox 认领租约与事件归属
CALL add_column_if_absent(
    't_outbox_event',
    'user_id',
    '`user_id` BIGINT UNSIGNED NULL COMMENT ''触发事件的用户 ID，用于事件归属校验'' AFTER `event_id`'
);
CALL add_column_if_absent(
    't_outbox_event',
    'claim_token',
    '`claim_token` VARCHAR(64) NULL COMMENT ''发送认领 token，防止多实例重复发送'' AFTER `sent_at`'
);
CALL add_column_if_absent(
    't_outbox_event',
    'locked_until',
    '`locked_until` DATETIME(3) NULL COMMENT ''认领租约到期时间'' AFTER `claim_token`'
);
CALL add_index_if_absent(
    't_outbox_event',
    'idx_user_event',
    'KEY `idx_user_event` (`user_id`, `event_id`)'
);

-- 评论请求幂等键
CALL add_column_if_absent(
    't_comment',
    'client_request_id',
    '`client_request_id` VARCHAR(64) NULL COMMENT ''客户端请求幂等键'' AFTER `content`'
);
CALL add_index_if_absent(
    't_comment',
    'uk_user_request',
    'UNIQUE KEY `uk_user_request` (`user_id`, `client_request_id`)'
);

-- 事件状态查询按 event_id 反查所有消费组
CALL add_index_if_absent(
    't_event_consume_log',
    'idx_event_id',
    'KEY `idx_event_id` (`event_id`)'
);

DROP PROCEDURE IF EXISTS add_column_if_absent;
DROP PROCEDURE IF EXISTS add_index_if_absent;

-- 历史事件回填用户归属；旧 payload 中没有 userId 的事件保持 NULL
UPDATE `t_outbox_event`
SET `user_id` = CAST(JSON_UNQUOTE(JSON_EXTRACT(`payload`, '$.userId')) AS UNSIGNED)
WHERE `user_id` IS NULL
  AND JSON_EXTRACT(`payload`, '$.userId') IS NOT NULL;

-- 切换异步评论计数前，从明细重算一次，避免历史同步计数与新消费者重复累加
UPDATE `t_comment` c
SET c.`like_count` = (
    SELECT COUNT(*)
    FROM `t_user_like` l
    WHERE l.`target_type` = 3 AND l.`target_id` = c.`id`
);

UPDATE `t_comment` SET `reply_count` = 0 WHERE `root_id` = 0;

UPDATE `t_comment` root
JOIN (
    SELECT `root_id`, COUNT(*) AS reply_total
    FROM `t_comment`
    WHERE `root_id` <> 0 AND `status` = 1
    GROUP BY `root_id`
) reply ON reply.`root_id` = root.`id`
SET root.`reply_count` = reply.reply_total
WHERE root.`root_id` = 0;
