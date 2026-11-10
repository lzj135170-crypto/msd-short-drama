-- 码上短剧 - 审核演示数据重置
-- 用途：重复演示 P2 审核闭环前，把 5003/5012/5013 恢复到种子状态。
-- 执行：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/repair/reset_review_demo.sql
-- 注意：只重置演示数据；不会删除短剧、剧集或审核日志。

USE DL;

-- 5003：审核中，AI 待审核
UPDATE `t_drama`
SET `review_status` = 1,
    `reviewer_id` = NULL,
    `review_remark` = '',
    `submit_time` = '2026-10-20 09:00:00',
    `review_time` = NULL,
    `publish_time` = NULL,
    `offline_time` = NULL,
    `ai_review_status` = 1,
    `ai_review_result` = NULL,
    `ai_review_time` = NULL
WHERE `id` = 5003;

-- 5012：草稿
UPDATE `t_drama`
SET `review_status` = 0,
    `reviewer_id` = NULL,
    `review_remark` = '',
    `submit_time` = NULL,
    `review_time` = NULL,
    `publish_time` = NULL,
    `offline_time` = NULL,
    `ai_review_status` = 0,
    `ai_review_result` = NULL,
    `ai_review_time` = NULL
WHERE `id` = 5012;

-- 5013：已驳回
UPDATE `t_drama`
SET `review_status` = 3,
    `reviewer_id` = 1001,
    `review_remark` = '剧情节奏偏慢，建议精简后再提交',
    `submit_time` = '2026-10-18 09:00:00',
    `review_time` = '2026-10-19 15:00:00',
    `publish_time` = NULL,
    `offline_time` = NULL,
    `ai_review_status` = 3,
    `ai_review_result` = '{"score": 68, "conclusion": "reject", "reason": "节奏偏慢"}',
    `ai_review_time` = '2026-10-18 15:00:00'
WHERE `id` = 5013;
