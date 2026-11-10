-- 码上短剧 - 数据一致性升级脚本
-- 适用已有 DL 库；02_business_schema.sql 已包含新装定义，本脚本负责幂等补列/补表/回填。
-- 执行：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/05_consistency.sql

USE DL;

-- 短剧审核操作日志；AI 审核和人工审核共用
CREATE TABLE IF NOT EXISTS `t_drama_review_log` (
    `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
    `drama_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '短剧 ID',
    `action`      VARCHAR(32)      NOT NULL                COMMENT 'SUBMIT/APPROVE/REJECT/OFFLINE/AI_REVIEW',
    `from_status` TINYINT UNSIGNED NULL                    COMMENT '变更前状态',
    `to_status`   TINYINT UNSIGNED NULL                    COMMENT '变更后状态',
    `operator_id` BIGINT UNSIGNED  NULL                    COMMENT '操作人，AI 为 0',
    `source`      VARCHAR(16)      NOT NULL DEFAULT 'HUMAN' COMMENT 'HUMAN/AI',
    `remark`      VARCHAR(500)     NOT NULL DEFAULT ''     COMMENT '审核意见',
    `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_drama_time` (`drama_id`, `create_time`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '短剧审核操作日志';

-- Seata AT 回滚日志，必须和业务表同库
CREATE TABLE IF NOT EXISTS `undo_log` (
    `branch_id`     BIGINT       NOT NULL COMMENT '分支事务 ID',
    `xid`           VARCHAR(128) NOT NULL COMMENT '全局事务 ID',
    `context`       VARCHAR(128) NOT NULL COMMENT '上下文',
    `rollback_info` LONGBLOB     NOT NULL COMMENT '回滚信息',
    `log_status`    INT          NOT NULL COMMENT '状态',
    `log_created`   DATETIME(6)  NOT NULL COMMENT '创建时间',
    `log_modified`  DATETIME(6)  NOT NULL COMMENT '修改时间',
    `ext`           VARCHAR(100) DEFAULT NULL COMMENT '扩展信息',
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Seata AT 回滚日志';

-- 已有库补 unlock_count；新库由 02_business_schema.sql 直接创建
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

CALL add_column_if_absent(
    't_drama_episode',
    'unlock_count',
    '`unlock_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''解锁次数（冗余）'' AFTER `play_count`'
);

-- AI 伴看分集摘要；新库由 02_business_schema.sql 直接创建
CALL add_column_if_absent(
    't_drama_episode',
    'summary',
    '`summary` VARCHAR(1000) NOT NULL DEFAULT '''' COMMENT ''分集剧情摘要，只描述本集已发生的内容'' AFTER `comment_count`'
);

CALL add_column_if_absent(
    't_drama_episode',
    'summary_status',
    '`summary_status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''摘要状态：0 缺失，1 生成中，2 可用，3 失败'' AFTER `summary`'
);

CALL add_column_if_absent(
    't_drama_episode',
    'summary_updated_at',
    '`summary_updated_at` DATETIME(3) NULL COMMENT ''摘要认领或更新时间'' AFTER `summary_status`'
);

DROP PROCEDURE IF EXISTS add_column_if_absent;

-- drama 侧远程分支的幂等引用；unlock_id 来自 order.t_episode_unlock.id
CREATE TABLE IF NOT EXISTS `t_episode_unlock_ref` (
    `unlock_id`   BIGINT UNSIGNED NOT NULL                COMMENT '订单侧 t_episode_unlock.id',
    `episode_id`  BIGINT UNSIGNED NOT NULL                COMMENT '剧集 ID',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
    `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`unlock_id`),
    KEY `idx_episode` (`episode_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '剧集解锁计数幂等引用';

-- 已有解锁数据回填引用和计数
INSERT IGNORE INTO `t_episode_unlock_ref` (`unlock_id`, `episode_id`, `user_id`, `create_time`)
SELECT `id`, `episode_id`, `user_id`, `create_time`
FROM `t_episode_unlock`;

UPDATE `t_drama_episode` e
SET e.`unlock_count` = (
    SELECT COUNT(*)
    FROM `t_episode_unlock` u
    WHERE u.`episode_id` = e.`id`
);

-- Kafka 消费幂等日志；同一消费者组处理过的 event_id 只允许一次
CREATE TABLE IF NOT EXISTS `t_event_consume_log` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消费日志 ID',
    `consumer_group` VARCHAR(64)     NOT NULL                COMMENT '消费者组',
    `event_id`       CHAR(36)        NOT NULL                COMMENT '事件 UUID',
    `topic`          VARCHAR(128)    NOT NULL                COMMENT 'Kafka Topic',
    `consumed_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '消费时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_event` (`consumer_group`, `event_id`),
    KEY `idx_event_id` (`event_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Kafka 消费幂等日志';

-- ============================================================
-- AI 伴看演示摘要：只描述本集已发生的内容，不包含后续剧情
-- 只填充空摘要，不覆盖服务生成的摘要
-- ============================================================

-- 5001 闪婚总裁：6 集
UPDATE `t_drama_episode` SET `summary` = '苏晚为保住家族企业，与只见过一面的顾沉舟领证闪婚，约定三个月后和平离婚。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6001 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '两人被迫同住，苏晚发现顾沉舟生活习惯古怪，却总在她加班时留一份夜宵。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6002 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '苏晚负责的项目被对手截胡，顾沉舟暗中出手挽回，苏晚开始怀疑他的真实身份。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6003 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '苏晚拿到关键证据当众反击对手，顾沉舟以投资人身份现身，两人关系出现转机。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6004 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '顾沉舟在雨夜坦白心意，苏晚承认早已动心，但两人都还隐瞒着各自的秘密。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6005 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '苏晚身世曝光，顾沉舟选择站在她身边，两人决定不再演戏，正式成为夫妻。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6006 AND (`summary` IS NULL OR `summary` = '');

-- 5004 先婚后爱：5 集
UPDATE `t_drama_episode` SET `summary` = '林知夏为摆脱家族联姻，与陌生男人陆时衍签下一年婚约，约定互不干涉。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6301 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '两人列出同居守则，却在早餐口味和作息上屡屡破例，关系逐渐缓和。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6302 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '陆时衍的前任回国搅局，林知夏第一次在众人面前维护这段婚姻。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6303 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '陆时衍因林知夏与同事走得近频频吃醋，两人第一次认真谈起彼此的心意。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6304 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '陆时衍放弃家族安排，向林知夏坦白结婚的真实原因并正式告白。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6305 AND (`summary` IS NULL OR `summary` = '');

-- 5005 古装悬疑：6 集
UPDATE `t_drama_episode` SET `summary` = '长安雨夜发现一具无名女尸，女仵作沈昭被召入大理寺协助验尸，发现死者指甲里藏着香料。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6401 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '沈昭追查香料来源，死者随身的玉佩却被人提前取走，线索指向城西当铺。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6402 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '沈昭以乐师身份混入青楼，查到死者生前最后见的人是一位神秘客商。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6403 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '案件牵出朝中权贵，沈昭的验尸结果被人施压篡改，她决定暗中留一份底稿。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6404 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '沈昭发现此案与十年前的旧案手法一致，旧案卷宗却被封存，她与搭档决定暗中重查。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6405 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '沈昭在旧案卷宗里找到关键证据，当众揭开真凶身份，为两桩命案讨回公道。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6406 AND (`summary` IS NULL OR `summary` = '');

-- 5008 外卖修仙：6 集
UPDATE `t_drama_episode` SET `summary` = '外卖员陈小满误入修仙界，接到第一单配送，用电动车把灵药送到山门。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6701 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '陈小满因迟到被仙门弟子打差评，他研究出御剑配送路线，评分逐渐回升。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6702 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '魔尊点了一份人间烧烤，陈小满在魔界支起摊位，意外和魔尊聊起心事。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6703 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '仙门与魔界为配送费吵翻，陈小满提出按里程计费，两边都成了常客。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6704 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '仙魔开战，陈小满用外卖路线图帮双方避开主战场，救下被困的凡人。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6705 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '大战结束，陈小满的店铺拿到五星好评，他决定留在两界之间继续送餐。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6706 AND (`summary` IS NULL OR `summary` = '');
