-- 码上短剧 - 业务表结构（16 张核心表 + 一致性/Seata 辅助表）
-- 执行：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/02_business_schema.sql
-- 依赖：00_databases.sql 已创建 DL 库和 msd 用户
--
-- 约定：
--   InnoDB + utf8mb4_0900_ai_ci
--   BIGINT UNSIGNED 自增主键，DATETIME(3) 时间
--   金额单位：分；积分用 BIGINT
--   无外键，关联关系由应用层保证
--   枚举用 TINYINT，取值范围见列注释

USE DL;

-- ============================================================
-- 用户域
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_user` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    `phone`           VARCHAR(20)      NOT NULL                COMMENT '手机号',
    `password_hash`   VARCHAR(100)     NOT NULL                COMMENT 'BCrypt 密码哈希',
    `nickname`        VARCHAR(64)      NOT NULL DEFAULT ''     COMMENT '昵称',
    `avatar_url`      VARCHAR(512)     NOT NULL DEFAULT ''     COMMENT '头像地址',
    `bio`             VARCHAR(255)     NOT NULL DEFAULT ''     COMMENT '个人简介',
    `role`            TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '角色：0 普通用户，1 创作者，2 管理员',
    `status`          TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 禁用，1 正常',
    `last_login_time` DATETIME(3)      NULL                    COMMENT '最近登录时间',
    `last_login_ip`   VARCHAR(45)      NULL                    COMMENT '最近登录 IP',
    `create_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户';

-- ============================================================
-- 短剧域
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_drama_category` (
    `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '分类 ID',
    `name`        VARCHAR(32)      NOT NULL                COMMENT '分类名称',
    `sort`        INT              NOT NULL DEFAULT 0      COMMENT '排序值，越小越靠前',
    `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 隐藏，1 可见',
    `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status_sort` (`status`, `sort`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '短剧分类';

CREATE TABLE IF NOT EXISTS `t_drama_tag` (
    `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '标签 ID',
    `name`        VARCHAR(32)      NOT NULL                COMMENT '标签名称',
    `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 隐藏，1 可见',
    `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status` (`status`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '短剧标签';

CREATE TABLE IF NOT EXISTS `t_drama_tag_rel` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关系 ID',
    `drama_id`    BIGINT UNSIGNED NOT NULL                COMMENT '短剧 ID',
    `tag_id`      BIGINT UNSIGNED NOT NULL                COMMENT '标签 ID',
    `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_drama_tag` (`drama_id`, `tag_id`),
    KEY `idx_tag_drama` (`tag_id`, `drama_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '短剧标签关系';

CREATE TABLE IF NOT EXISTS `t_drama` (
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '短剧 ID',
    `title`            VARCHAR(128)     NOT NULL                COMMENT '标题',
    `subtitle`         VARCHAR(255)     NOT NULL DEFAULT ''     COMMENT '副标题',
    `cover_url`        VARCHAR(512)     NOT NULL DEFAULT ''     COMMENT '封面地址',
    `description`      VARCHAR(1000)    NOT NULL DEFAULT ''     COMMENT '简介',
    `category_id`      BIGINT UNSIGNED  NOT NULL                COMMENT '主分类 ID',
    `author_id`        BIGINT UNSIGNED  NOT NULL                COMMENT '创作者 user_id',
    `actors`           VARCHAR(255)     NOT NULL DEFAULT ''     COMMENT '主演，逗号分隔',
    `episode_count`    INT UNSIGNED     NOT NULL DEFAULT 0      COMMENT '总集数（冗余）',
    `review_status`    TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '审核状态：0 草稿，1 审核中，2 已上架，3 驳回，4 下架',
    `reviewer_id`      BIGINT UNSIGNED  NULL                    COMMENT '审核人 user_id',
    `review_remark`    VARCHAR(500)     NOT NULL DEFAULT ''     COMMENT '审核意见',
    `submit_time`      DATETIME(3)      NULL                    COMMENT '提交审核时间',
    `review_time`      DATETIME(3)      NULL                    COMMENT '人工审核时间',
    `publish_time`     DATETIME(3)      NULL                    COMMENT '上架时间',
    `offline_time`     DATETIME(3)      NULL                    COMMENT '下架时间',
    `ai_review_status` TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT 'AI 审核状态：0 未提交，1 待审核，2 通过，3 驳回，4 失败',
    `ai_review_result` JSON             NULL                    COMMENT 'AI 审核结果',
    `ai_review_time`   DATETIME(3)      NULL                    COMMENT 'AI 审核时间',
    `view_count`       BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '详情页浏览量（冗余）',
    `like_count`       BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '点赞数（冗余）',
    `favorite_count`   BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '收藏数（冗余）',
    `comment_count`    BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '评论数（冗余）',
    `deleted`          TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_public` (`deleted`, `review_status`, `publish_time`, `id`),
    KEY `idx_category` (`deleted`, `category_id`, `review_status`, `publish_time`, `id`),
    KEY `idx_author` (`deleted`, `author_id`, `review_status`, `update_time`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '短剧';

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

CREATE TABLE IF NOT EXISTS `t_drama_episode` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '剧集 ID',
    `drama_id`        BIGINT UNSIGNED  NOT NULL                COMMENT '短剧 ID',
    `episode_no`      INT UNSIGNED     NOT NULL                COMMENT '第几集，从 1 开始',
    `title`           VARCHAR(128)     NOT NULL DEFAULT ''     COMMENT '分集标题',
    `video_url`       VARCHAR(512)     NOT NULL                COMMENT '视频地址',
    `cover_url`       VARCHAR(512)     NOT NULL DEFAULT ''     COMMENT '分集封面',
    `duration_seconds` INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '时长（秒）',
    `price_points`    BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '解锁所需积分，0 表示免费',
    `status`          TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 下线，1 上线',
    `play_count`      BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '播放量（冗余）',
    `unlock_count`    BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '解锁次数（冗余）',
    `like_count`      BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '点赞数（冗余）',
    `comment_count`   BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '评论数（冗余）',
    `summary`         VARCHAR(1000)    NOT NULL DEFAULT ''     COMMENT '分集剧情摘要，只描述本集已发生的内容',
    `summary_status`  TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '摘要状态：0 缺失，1 生成中，2 可用，3 失败',
    `summary_updated_at` DATETIME(3)   NULL                    COMMENT '摘要认领或更新时间',
    `create_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_drama_episode` (`drama_id`, `episode_no`),
    KEY `idx_drama_status` (`drama_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '短剧剧集';

-- 字幕文件元数据：一集一种语言保留一份，ASR 或人工上传覆盖旧版本
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

-- 带时间戳的字幕片段，供剧情问答按播放位置防剧透检索
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

-- ============================================================
-- 订单/积分域
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_recharge_plan` (
    `id`           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '档位 ID',
    `plan_code`    VARCHAR(32)      NOT NULL                COMMENT '档位编码',
    `name`         VARCHAR(64)      NOT NULL                COMMENT '档位名称',
    `price_cents`  BIGINT UNSIGNED  NOT NULL                COMMENT '价格，单位：分',
    `base_points`  BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '基础积分',
    `bonus_points` BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '赠送积分',
    `sort`         INT              NOT NULL DEFAULT 0      COMMENT '排序值，越小越靠前',
    `status`       TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 下架，1 上架',
    `create_time`  DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`  DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_code` (`plan_code`),
    KEY `idx_status_sort` (`status`, `sort`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '积分充值档位';

CREATE TABLE IF NOT EXISTS `t_recharge_order` (
    `id`                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '订单 ID',
    `order_no`          VARCHAR(32)      NOT NULL                COMMENT '业务订单号',
    `user_id`           BIGINT UNSIGNED  NOT NULL                COMMENT '下单用户 ID',
    `plan_id`           BIGINT UNSIGNED  NOT NULL                COMMENT '充值档位 ID',
    `plan_name`         VARCHAR(64)      NOT NULL                COMMENT '档位名称快照',
    `amount_cents`      BIGINT UNSIGNED  NOT NULL                COMMENT '实付金额（分）快照',
    `points`            BIGINT UNSIGNED  NOT NULL                COMMENT '到账总积分快照（基础+赠送）',
    `status`            TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '状态：0 待支付，1 已支付，2 已取消，3 已过期',
    `pay_channel`       TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '支付渠道：1 MOCK，2 微信，3 支付宝',
    `pay_trade_no`      VARCHAR(64)      NULL                    COMMENT '第三方交易号',
    `client_request_id` VARCHAR(64)      NOT NULL                COMMENT '客户端幂等键',
    `expire_time`       DATETIME(3)      NOT NULL                COMMENT '支付超时时间',
    `pay_time`          DATETIME(3)      NULL                    COMMENT '支付时间',
    `client_ip`         VARCHAR(45)      NULL                    COMMENT '下单 IP',
    `remark`            VARCHAR(255)     NOT NULL DEFAULT ''     COMMENT '备注',
    `create_time`       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_user_request` (`user_id`, `client_request_id`),
    UNIQUE KEY `uk_pay_trade_no` (`pay_trade_no`),
    KEY `idx_user_status` (`user_id`, `status`, `create_time`, `id`),
    KEY `idx_status_expire` (`status`, `expire_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '积分充值订单';

CREATE TABLE IF NOT EXISTS `t_points_account` (
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
    `balance`     BIGINT          NOT NULL DEFAULT 0      COMMENT '积分余额',
    `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`user_id`),
    CONSTRAINT `chk_points_balance` CHECK (`balance` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '积分账户';

CREATE TABLE IF NOT EXISTS `t_points_ledger` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '流水 ID',
    `user_id`         BIGINT UNSIGNED  NOT NULL                COMMENT '用户 ID',
    `change_type`     TINYINT UNSIGNED NOT NULL                COMMENT '变动类型：1 充值，2 解锁，3 人工调整',
    `change_points`   BIGINT           NOT NULL                COMMENT '变动积分，正数入账，负数扣减',
    `balance_after`   BIGINT           NOT NULL                COMMENT '变动后余额',
    `biz_type`        TINYINT UNSIGNED NOT NULL                COMMENT '业务类型：1 订单，2 剧集解锁，3 人工',
    `biz_id`          BIGINT UNSIGNED  NULL                    COMMENT '业务 ID：订单 ID 或解锁 ID',
    `idempotency_key` VARCHAR(128)     NOT NULL                COMMENT '幂等键，如 order:7001、unlock:1003:6003',
    `remark`          VARCHAR(255)     NOT NULL DEFAULT ''     COMMENT '备注',
    `create_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency` (`idempotency_key`),
    KEY `idx_user_time` (`user_id`, `create_time`, `id`),
    KEY `idx_biz` (`biz_type`, `biz_id`),
    CONSTRAINT `chk_ledger_balance` CHECK (`balance_after` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '积分流水';

CREATE TABLE IF NOT EXISTS `t_episode_unlock` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '解锁记录 ID',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
    `drama_id`    BIGINT UNSIGNED NOT NULL                COMMENT '短剧 ID',
    `episode_id`  BIGINT UNSIGNED NOT NULL                COMMENT '剧集 ID',
    `points_cost` BIGINT UNSIGNED NOT NULL                COMMENT '解锁时消耗的积分快照',
    `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_episode` (`user_id`, `episode_id`),
    KEY `idx_user_drama` (`user_id`, `drama_id`, `create_time`, `id`),
    KEY `idx_episode` (`episode_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '剧集解锁权益';

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

-- ============================================================
-- 互动域
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_user_like` (
    `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '点赞 ID',
    `user_id`     BIGINT UNSIGNED  NOT NULL                COMMENT '用户 ID',
    `target_type` TINYINT UNSIGNED NOT NULL                COMMENT '目标类型：1 短剧，2 剧集，3 评论',
    `target_id`   BIGINT UNSIGNED  NOT NULL                COMMENT '目标 ID',
    `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_target` (`user_id`, `target_type`, `target_id`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户点赞';

CREATE TABLE IF NOT EXISTS `t_user_favorite` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '收藏 ID',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
    `drama_id`    BIGINT UNSIGNED NOT NULL                COMMENT '短剧 ID',
    `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_drama` (`user_id`, `drama_id`),
    KEY `idx_user_time` (`user_id`, `create_time`, `id`),
    KEY `idx_drama` (`drama_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户收藏';

CREATE TABLE IF NOT EXISTS `t_comment` (
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '评论 ID',
    `drama_id`         BIGINT UNSIGNED  NOT NULL                COMMENT '短剧 ID',
    `episode_id`       BIGINT UNSIGNED  NULL                    COMMENT '剧集 ID，短剧级评论为空',
    `user_id`          BIGINT UNSIGNED  NOT NULL                COMMENT '评论人 user_id',
    `root_id`          BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '根评论 ID，根评论为 0',
    `parent_id`        BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '直接父评论 ID，根评论为 0',
    `reply_to_user_id` BIGINT UNSIGNED  NULL                    COMMENT '回复目标用户 ID',
    `content`          VARCHAR(1000)    NOT NULL                COMMENT '评论内容',
    `client_request_id` VARCHAR(64)     NULL                    COMMENT '客户端请求幂等键',
    `like_count`       BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '点赞数（冗余）',
    `reply_count`      BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '回复数（冗余，只记在根评论）',
    `status`           TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '状态：0 待审，1 可见，2 隐藏，3 已删除',
    `ai_review_status` TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT 'AI 审核状态：0 未审核，1 审核中，2 通过，3 风险，4 失败',
    `ai_review_result` VARCHAR(4000)    NULL                    COMMENT 'AI 审核结果 JSON，仅作建议',
    `ai_review_time`   DATETIME(3)      NULL                    COMMENT 'AI 审核时间',
    `create_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_request` (`user_id`, `client_request_id`),
    KEY `idx_drama_root` (`drama_id`, `status`, `root_id`, `create_time`, `id`),
    KEY `idx_root` (`root_id`, `status`, `create_time`, `id`),
    KEY `idx_episode` (`episode_id`, `status`, `create_time`, `id`),
    KEY `idx_user` (`user_id`, `create_time`, `id`),
    KEY `idx_ai_status` (`ai_review_status`, `create_time`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '评论';

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

CREATE TABLE IF NOT EXISTS `t_watch_history` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '记录 ID',
    `user_id`         BIGINT UNSIGNED  NOT NULL                COMMENT '用户 ID',
    `drama_id`        BIGINT UNSIGNED  NOT NULL                COMMENT '短剧 ID',
    `episode_id`      BIGINT UNSIGNED  NOT NULL                COMMENT '剧集 ID',
    `position_ms`     INT UNSIGNED     NOT NULL DEFAULT 0      COMMENT '观看位置（毫秒）',
    `duration_ms`     INT UNSIGNED     NULL                    COMMENT '视频总时长（毫秒）',
    `finished`        TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '是否看完：0 否，1 是',
    `last_watch_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后观看时间',
    `create_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_episode` (`user_id`, `episode_id`),
    KEY `idx_user_time` (`user_id`, `last_watch_time`, `id`),
    KEY `idx_drama_episode` (`drama_id`, `episode_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '观看历史';

CREATE TABLE IF NOT EXISTS `t_outbox_event` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '事件 ID',
    `event_id`        CHAR(36)         NOT NULL                COMMENT '事件 UUID，消费端幂等键',
    `user_id`         BIGINT UNSIGNED  NULL                    COMMENT '触发事件的用户 ID，用于事件归属校验',
    `topic`           VARCHAR(128)     NOT NULL                COMMENT 'Kafka Topic',
    `event_type`      VARCHAR(64)      NOT NULL                COMMENT '事件类型：LIKE/FAVORITE/COMMENT/HISTORY/DRAMA_CHANGED/AI_REVIEW_REQUEST',
    `aggregate_type`  VARCHAR(32)      NOT NULL                COMMENT '聚合类型：DRAMA/EPISODE/COMMENT/ORDER',
    `aggregate_id`    BIGINT UNSIGNED  NULL                    COMMENT '聚合根 ID',
    `event_key`       VARCHAR(128)     NULL                    COMMENT 'Kafka 分区键',
    `payload`         JSON             NOT NULL                COMMENT '事件体',
    `status`          TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '状态：0 待发送，1 已发送，2 死信',
    `retry_count`     INT UNSIGNED     NOT NULL DEFAULT 0      COMMENT '重试次数',
    `next_retry_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次重试时间',
    `sent_at`         DATETIME(3)      NULL                    COMMENT '发送成功时间',
    `claim_token`     VARCHAR(64)      NULL                    COMMENT '发送认领 token，防止多实例重复发送',
    `locked_until`    DATETIME(3)      NULL                    COMMENT '认领租约到期时间',
    `last_error`      VARCHAR(500)     NOT NULL DEFAULT ''     COMMENT '最后一次错误',
    `create_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_poll` (`status`, `next_retry_time`, `id`),
    KEY `idx_user_event` (`user_id`, `event_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '事务性发件箱';

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

-- Seata AT 回滚日志，必须与业务表在同一个库
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
