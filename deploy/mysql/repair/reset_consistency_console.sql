-- 码上短剧 - 数据一致性测试台复位脚本（仅本地开发）
-- 用途：清理测试台针对用户 1003、短剧 5010、剧集 6004/6006/6902 产生的数据。
-- 执行：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/repair/reset_consistency_console.sql
-- 注意：
--   1. 本脚本会清空 t_outbox_event / t_event_consume_log，仅限本地开发库使用。
--   2. 执行后建议重跑种子脚本恢复短剧/剧集冗余计数：
--      mysql -umsd -p --default-character-set=utf8mb4 < deploy/mysql/init/03_business_seed.sql
--      mysql -umsd -p --default-character-set=utf8mb4 < deploy/mysql/init/04_mock_data.sql
--      mysql -umsd -p --default-character-set=utf8mb4 < deploy/mysql/init/05_consistency.sql
--   3. 生产环境不要执行；生产计数对账使用 rebuild_interaction_counts.sql。

USE DL;

START TRANSACTION;

-- 1. 清理测试产生的异步事件和消费日志
DELETE FROM t_outbox_event;
DELETE FROM t_event_consume_log;

-- 2. 清理 Seata 解锁测试数据，恢复用户 1003 余额
DELETE FROM t_episode_unlock_ref
 WHERE user_id = 1003 AND episode_id IN (6004, 6006);
DELETE FROM t_episode_unlock
 WHERE user_id = 1003 AND episode_id IN (6004, 6006);
DELETE FROM t_points_ledger
 WHERE user_id = 1003
   AND idempotency_key IN ('unlock:1003:6004', 'unlock:1003:6006');
UPDATE t_points_account SET balance = 250 WHERE user_id = 1003;
UPDATE t_drama_episode SET unlock_count = 0 WHERE id IN (6004, 6006);

-- 3. 清理互动测试明细
DELETE FROM t_watch_history
 WHERE user_id = 1003 AND episode_id = 6902;
DELETE FROM t_user_like
 WHERE user_id = 1003
   AND ((target_type = 1 AND target_id = 5010)
     OR (target_type = 2 AND target_id = 6902));
DELETE FROM t_user_favorite
 WHERE user_id = 1003 AND drama_id = 5010;
DELETE FROM t_comment
 WHERE user_id = 1003 AND drama_id = 5010
   AND content LIKE '[test-console]%';

COMMIT;
