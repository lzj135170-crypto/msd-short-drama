-- 码上短剧 - 业务种子数据
-- 执行：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/03_business_seed.sql
-- 依赖：02_business_schema.sql 已建表
--
-- 说明：
--   固定高位 ID，可重复执行；重复执行会重置种子行到下面的值。
--   统一密码 Msd@123456（仅本地开发），BCrypt 哈希在文件末尾说明。
--   Outbox 不预置数据，由应用真实流程产生。

USE DL;

-- ============================================================
-- 用户（1001 管理员 / 1002 创作者 / 1003 普通用户）
-- ============================================================

INSERT INTO `t_user`
(`id`, `phone`, `password_hash`, `nickname`, `avatar_url`, `bio`, `role`, `status`, `last_login_time`, `last_login_ip`, `create_time`, `update_time`)
VALUES
(1001, '13800000001', '$2b$10$sUlPbFufB52gSic/9Q0czOYhY6d.sjeMcZ2Oy.fpLmi7SSYqbWE3C', '平台管理员', '', '码上短剧平台管理员', 2, 1, '2026-10-22 09:00:00', '127.0.0.1', '2026-10-01 09:00:00', '2026-10-22 09:00:00'),
(1002, '13800000002', '$2b$10$sUlPbFufB52gSic/9Q0czOYhY6d.sjeMcZ2Oy.fpLmi7SSYqbWE3C', '创作者小短', '', '专注甜宠与逆袭题材', 1, 1, '2026-10-22 09:10:00', '127.0.0.1', '2026-10-01 09:10:00', '2026-10-22 09:10:00'),
(1003, '13800000003', '$2b$10$sUlPbFufB52gSic/9Q0czOYhY6d.sjeMcZ2Oy.fpLmi7SSYqbWE3C', '追剧用户', '', '每天都要看两集', 0, 1, '2026-10-22 09:20:00', '127.0.0.1', '2026-10-01 09:20:00', '2026-10-22 09:20:00')
ON DUPLICATE KEY UPDATE
`phone` = VALUES(`phone`),
`password_hash` = VALUES(`password_hash`),
`nickname` = VALUES(`nickname`),
`avatar_url` = VALUES(`avatar_url`),
`bio` = VALUES(`bio`),
`role` = VALUES(`role`),
`status` = VALUES(`status`),
`last_login_time` = VALUES(`last_login_time`),
`last_login_ip` = VALUES(`last_login_ip`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 分类
-- ============================================================

INSERT INTO `t_drama_category` (`id`, `name`, `sort`, `status`, `create_time`, `update_time`)
VALUES
(2001, '都市', 1, 1, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(2002, '甜宠', 2, 1, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(2003, '悬疑', 3, 1, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(2004, '古装', 4, 1, '2026-10-01 10:00:00', '2026-10-01 10:00:00')
ON DUPLICATE KEY UPDATE
`name` = VALUES(`name`),
`sort` = VALUES(`sort`),
`status` = VALUES(`status`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 标签
-- ============================================================

INSERT INTO `t_drama_tag` (`id`, `name`, `status`, `create_time`)
VALUES
(3001, '逆袭', 1, '2026-10-01 10:00:00'),
(3002, '甜宠', 1, '2026-10-01 10:00:00'),
(3003, '重生', 1, '2026-10-01 10:00:00'),
(3004, '复仇', 1, '2026-10-01 10:00:00'),
(3005, '悬疑', 1, '2026-10-01 10:00:00'),
(3006, '穿越', 1, '2026-10-01 10:00:00')
ON DUPLICATE KEY UPDATE
`name` = VALUES(`name`),
`status` = VALUES(`status`);

-- ============================================================
-- 充值档位
-- ============================================================

INSERT INTO `t_recharge_plan` (`id`, `plan_code`, `name`, `price_cents`, `base_points`, `bonus_points`, `sort`, `status`, `create_time`, `update_time`)
VALUES
(4001, 'trial', '体验包', 600, 60, 0, 1, 1, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(4002, 'standard', '标准包', 3000, 300, 30, 2, 1, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(4003, 'premium', '超值包', 6800, 680, 100, 3, 1, '2026-10-01 10:00:00', '2026-10-01 10:00:00')
ON DUPLICATE KEY UPDATE
`plan_code` = VALUES(`plan_code`),
`name` = VALUES(`name`),
`price_cents` = VALUES(`price_cents`),
`base_points` = VALUES(`base_points`),
`bonus_points` = VALUES(`bonus_points`),
`sort` = VALUES(`sort`),
`status` = VALUES(`status`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 短剧
-- 5001 已上架：6 集，1-2 免费，3-4 各 30 积分，5-6 各 50 积分
-- 5002 已上架：4 集，1-2 免费，3-4 各 30 积分
-- 5003 审核中：3 集，暂未开放
-- ============================================================

INSERT INTO `t_drama`
(`id`, `title`, `subtitle`, `cover_url`, `description`, `category_id`, `author_id`, `actors`, `episode_count`,
 `review_status`, `reviewer_id`, `review_remark`, `submit_time`, `review_time`, `publish_time`, `offline_time`,
 `ai_review_status`, `ai_review_result`, `ai_review_time`,
 `view_count`, `like_count`, `favorite_count`, `comment_count`, `deleted`, `create_time`, `update_time`)
VALUES
(5001, '隐婚总裁的甜妻', '先婚后爱，甜到上头', 'http://127.0.0.1:9000/msd/covers/5001.jpg',
 '一场意外的闪婚，让普通女孩苏晚嫁给了商圈传奇顾沉。婚后两人从互相试探到并肩作战，甜宠与逆袭并行。',
 2002, 1002, '林一,苏晚', 6,
 2, 1001, '审核通过', '2026-09-28 10:00:00', '2026-09-30 15:00:00', '2026-10-01 10:00:00', NULL,
 2, '{"score": 92, "conclusion": "pass"}', '2026-09-29 11:00:00',
 3, 1, 1, 2, 0, '2026-09-28 09:00:00', '2026-10-21 21:10:00'),
(5002, '重生后我成了首富', '重回 2008，从摆摊开始', 'http://127.0.0.1:9000/msd/covers/5002.jpg',
 '被合伙人陷害的创业者陈默重生回到 2008 年，带着未来记忆从夜市摆摊起步，一步步夺回属于自己的一切。',
 2001, 1002, '陈默,周雨', 4,
 2, 1001, '审核通过', '2026-10-05 10:00:00', '2026-10-08 16:00:00', '2026-10-10 10:00:00', NULL,
 2, '{"score": 88, "conclusion": "pass"}', '2026-10-06 09:30:00',
 0, 0, 0, 0, 0, '2026-10-05 09:00:00', '2026-10-10 10:00:00'),
(5003, '雾城谜案', '每一通深夜来电，都藏着一个秘密', 'http://127.0.0.1:9000/msd/covers/5003.jpg',
 '雾城连续发生离奇失踪案，刑警赵远在调查中发现所有线索都指向十年前的一桩旧案。',
 2003, 1002, '赵远,许晴', 3,
 1, NULL, '', '2026-10-20 09:00:00', NULL, NULL, NULL,
 1, NULL, NULL,
 0, 0, 0, 0, 0, '2026-10-19 09:00:00', '2026-10-20 09:00:00')
ON DUPLICATE KEY UPDATE
`title` = VALUES(`title`),
`subtitle` = VALUES(`subtitle`),
`cover_url` = VALUES(`cover_url`),
`description` = VALUES(`description`),
`category_id` = VALUES(`category_id`),
`author_id` = VALUES(`author_id`),
`actors` = VALUES(`actors`),
`episode_count` = VALUES(`episode_count`),
`review_status` = VALUES(`review_status`),
`reviewer_id` = VALUES(`reviewer_id`),
`review_remark` = VALUES(`review_remark`),
`submit_time` = VALUES(`submit_time`),
`review_time` = VALUES(`review_time`),
`publish_time` = VALUES(`publish_time`),
`offline_time` = VALUES(`offline_time`),
`ai_review_status` = VALUES(`ai_review_status`),
`ai_review_result` = VALUES(`ai_review_result`),
`ai_review_time` = VALUES(`ai_review_time`),
`view_count` = VALUES(`view_count`),
`like_count` = VALUES(`like_count`),
`favorite_count` = VALUES(`favorite_count`),
`comment_count` = VALUES(`comment_count`),
`deleted` = VALUES(`deleted`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 短剧标签关系
-- ============================================================

INSERT INTO `t_drama_tag_rel` (`id`, `drama_id`, `tag_id`, `create_time`)
VALUES
(9001, 5001, 3002, '2026-10-01 10:00:00'),
(9002, 5001, 3003, '2026-10-01 10:00:00'),
(9003, 5002, 3001, '2026-10-10 10:00:00'),
(9004, 5002, 3003, '2026-10-10 10:00:00'),
(9005, 5003, 3005, '2026-10-19 09:00:00'),
(9006, 5003, 3004, '2026-10-19 09:00:00')
ON DUPLICATE KEY UPDATE
`drama_id` = VALUES(`drama_id`),
`tag_id` = VALUES(`tag_id`);

-- ============================================================
-- 剧集
-- ============================================================

INSERT INTO `t_drama_episode`
(`id`, `drama_id`, `episode_no`, `title`, `video_url`, `cover_url`, `duration_seconds`,
 `price_points`, `status`, `play_count`, `like_count`, `comment_count`, `create_time`, `update_time`)
VALUES
-- 5001：6 集
(6001, 5001, 1, '第1集 闪婚', 'http://127.0.0.1:9000/msd/videos/5001/01.mp4', 'http://127.0.0.1:9000/msd/covers/5001-01.jpg', 150, 0, 1, 1, 0, 2, '2026-10-01 10:00:00', '2026-10-21 21:10:00'),
(6002, 5001, 2, '第2集 同居', 'http://127.0.0.1:9000/msd/videos/5001/02.mp4', 'http://127.0.0.1:9000/msd/covers/5001-02.jpg', 140, 0, 1, 1, 0, 0, '2026-10-01 10:00:00', '2026-10-21 21:10:00'),
(6003, 5001, 3, '第3集 危机', 'http://127.0.0.1:9000/msd/videos/5001/03.mp4', 'http://127.0.0.1:9000/msd/covers/5001-03.jpg', 150, 30, 1, 1, 0, 0, '2026-10-01 10:00:00', '2026-10-21 21:10:00'),
(6004, 5001, 4, '第4集 反击', 'http://127.0.0.1:9000/msd/videos/5001/04.mp4', 'http://127.0.0.1:9000/msd/covers/5001-04.jpg', 145, 30, 1, 0, 0, 0, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(6005, 5001, 5, '第5集 告白', 'http://127.0.0.1:9000/msd/videos/5001/05.mp4', 'http://127.0.0.1:9000/msd/covers/5001-05.jpg', 160, 50, 1, 0, 0, 0, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
(6006, 5001, 6, '第6集 大结局', 'http://127.0.0.1:9000/msd/videos/5001/06.mp4', 'http://127.0.0.1:9000/msd/covers/5001-06.jpg', 155, 50, 1, 0, 0, 0, '2026-10-01 10:00:00', '2026-10-01 10:00:00'),
-- 5002：4 集
(6101, 5002, 1, '第1集 重生', 'http://127.0.0.1:9000/msd/videos/5002/01.mp4', 'http://127.0.0.1:9000/msd/covers/5002-01.jpg', 130, 0, 1, 0, 0, 0, '2026-10-10 10:00:00', '2026-10-10 10:00:00'),
(6102, 5002, 2, '第2集 摆摊', 'http://127.0.0.1:9000/msd/videos/5002/02.mp4', 'http://127.0.0.1:9000/msd/covers/5002-02.jpg', 125, 0, 1, 0, 0, 0, '2026-10-10 10:00:00', '2026-10-10 10:00:00'),
(6103, 5002, 3, '第3集 第一桶金', 'http://127.0.0.1:9000/msd/videos/5002/03.mp4', 'http://127.0.0.1:9000/msd/covers/5002-03.jpg', 140, 30, 1, 0, 0, 0, '2026-10-10 10:00:00', '2026-10-10 10:00:00'),
(6104, 5002, 4, '第4集 反击', 'http://127.0.0.1:9000/msd/videos/5002/04.mp4', 'http://127.0.0.1:9000/msd/covers/5002-04.jpg', 145, 30, 1, 0, 0, 0, '2026-10-10 10:00:00', '2026-10-10 10:00:00'),
-- 5003：3 集，审核中，先下线
(6201, 5003, 1, '第1集 深夜来电', 'http://127.0.0.1:9000/msd/videos/5003/01.mp4', 'http://127.0.0.1:9000/msd/covers/5003-01.jpg', 135, 50, 0, 0, 0, 0, '2026-10-19 09:00:00', '2026-10-19 09:00:00'),
(6202, 5003, 2, '第2集 失踪', 'http://127.0.0.1:9000/msd/videos/5003/02.mp4', 'http://127.0.0.1:9000/msd/covers/5003-02.jpg', 150, 50, 0, 0, 0, 0, '2026-10-19 09:00:00', '2026-10-19 09:00:00'),
(6203, 5003, 3, '第3集 旧案', 'http://127.0.0.1:9000/msd/videos/5003/03.mp4', 'http://127.0.0.1:9000/msd/covers/5003-03.jpg', 160, 50, 0, 0, 0, 0, '2026-10-19 09:00:00', '2026-10-19 09:00:00')
ON DUPLICATE KEY UPDATE
`title` = VALUES(`title`),
`video_url` = VALUES(`video_url`),
`cover_url` = VALUES(`cover_url`),
`duration_seconds` = VALUES(`duration_seconds`),
`price_points` = VALUES(`price_points`),
`status` = VALUES(`status`),
`play_count` = VALUES(`play_count`),
`like_count` = VALUES(`like_count`),
`comment_count` = VALUES(`comment_count`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 分集剧情摘要：AI 伴看演示数据
-- 只描述本集已发生的内容，不包含后续剧情；只填充空摘要
-- ============================================================

UPDATE `t_drama_episode` SET `summary` = '苏晚为保住家族企业，与只见过一面的顾沉舟领证闪婚，约定三个月后和平离婚。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6001 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '两人被迫同住，苏晚发现顾沉舟生活习惯古怪，却总在她加班时留一份夜宵。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6002 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '苏晚负责的项目被对手截胡，顾沉舟暗中出手挽回，苏晚开始怀疑他的真实身份。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6003 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '苏晚拿到关键证据当众反击对手，顾沉舟以投资人身份现身，两人关系出现转机。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6004 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '顾沉舟在雨夜坦白心意，苏晚承认早已动心，但两人都还隐瞒着各自的秘密。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6005 AND (`summary` IS NULL OR `summary` = '');
UPDATE `t_drama_episode` SET `summary` = '苏晚身世曝光，顾沉舟选择站在她身边，两人决定不再演戏，正式成为夫妻。', `summary_status` = 2, `summary_updated_at` = NOW(3) WHERE `id` = 6006 AND (`summary` IS NULL OR `summary` = '');

-- ============================================================
-- 积分账户
-- ============================================================

INSERT INTO `t_points_account` (`user_id`, `balance`, `create_time`, `update_time`)
VALUES
(1001, 0, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
(1002, 0, '2026-10-01 09:10:00', '2026-10-01 09:10:00'),
(1003, 250, '2026-10-01 09:20:00', '2026-10-22 10:05:00')
ON DUPLICATE KEY UPDATE
`balance` = VALUES(`balance`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 充值订单
-- 7001 已支付；7002 待支付
-- ============================================================

INSERT INTO `t_recharge_order`
(`id`, `order_no`, `user_id`, `plan_id`, `plan_name`, `amount_cents`, `points`, `status`, `pay_channel`,
 `pay_trade_no`, `client_request_id`, `expire_time`, `pay_time`, `client_ip`, `remark`, `create_time`, `update_time`)
VALUES
(7001, '202610220000000001', 1003, 4002, '标准包', 3000, 330, 1, 1,
 'MOCK20261022000001', 'seed-order-7001', '2026-10-22 10:30:00', '2026-10-22 10:05:00', '127.0.0.1', '种子已支付订单', '2026-10-22 10:00:00', '2026-10-22 10:05:00'),
(7002, '202610220000000002', 1003, 4001, '体验包', 600, 60, 0, 1,
 NULL, 'seed-order-7002', '2026-12-31 23:59:59', NULL, '127.0.0.1', '种子待支付订单', '2026-10-22 10:10:00', '2026-10-22 10:10:00')
ON DUPLICATE KEY UPDATE
`order_no` = VALUES(`order_no`),
`user_id` = VALUES(`user_id`),
`plan_id` = VALUES(`plan_id`),
`plan_name` = VALUES(`plan_name`),
`amount_cents` = VALUES(`amount_cents`),
`points` = VALUES(`points`),
`status` = VALUES(`status`),
`pay_channel` = VALUES(`pay_channel`),
`pay_trade_no` = VALUES(`pay_trade_no`),
`client_request_id` = VALUES(`client_request_id`),
`expire_time` = VALUES(`expire_time`),
`pay_time` = VALUES(`pay_time`),
`client_ip` = VALUES(`client_ip`),
`remark` = VALUES(`remark`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 解锁记录（用户 1003 解锁 5001 的第 3、5 集）
-- ============================================================

INSERT INTO `t_episode_unlock` (`id`, `user_id`, `drama_id`, `episode_id`, `points_cost`, `create_time`)
VALUES
(8201, 1003, 5001, 6003, 30, '2026-10-21 21:00:30'),
(8202, 1003, 5001, 6005, 50, '2026-10-21 21:10:30')
ON DUPLICATE KEY UPDATE
`user_id` = VALUES(`user_id`),
`drama_id` = VALUES(`drama_id`),
`episode_id` = VALUES(`episode_id`),
`points_cost` = VALUES(`points_cost`);

-- ============================================================
-- 积分流水：+330 -30 -50 = 250
-- ============================================================

INSERT INTO `t_points_ledger`
(`id`, `user_id`, `change_type`, `change_points`, `balance_after`, `biz_type`, `biz_id`, `idempotency_key`, `remark`, `create_time`)
VALUES
(7101, 1003, 1, 330, 330, 1, 7001, 'order:7001', '充值标准包', '2026-10-22 10:05:00'),
(7102, 1003, 2, -30, 300, 2, 8201, 'unlock:1003:6003', '解锁剧集 6003', '2026-10-21 21:00:30'),
(7103, 1003, 2, -50, 250, 2, 8202, 'unlock:1003:6005', '解锁剧集 6005', '2026-10-21 21:10:30')
ON DUPLICATE KEY UPDATE
`user_id` = VALUES(`user_id`),
`change_type` = VALUES(`change_type`),
`change_points` = VALUES(`change_points`),
`balance_after` = VALUES(`balance_after`),
`biz_type` = VALUES(`biz_type`),
`biz_id` = VALUES(`biz_id`),
`remark` = VALUES(`remark`);

-- ============================================================
-- 点赞与收藏
-- ============================================================

INSERT INTO `t_user_like` (`id`, `user_id`, `target_type`, `target_id`, `create_time`)
VALUES
(8401, 1003, 1, 5001, '2026-10-21 20:40:00'),
(8402, 1002, 3, 8301, '2026-10-21 21:00:00')
ON DUPLICATE KEY UPDATE
`user_id` = VALUES(`user_id`),
`target_type` = VALUES(`target_type`),
`target_id` = VALUES(`target_id`);

INSERT INTO `t_user_favorite` (`id`, `user_id`, `drama_id`, `create_time`)
VALUES
(8501, 1003, 5001, '2026-10-21 20:42:00')
ON DUPLICATE KEY UPDATE
`user_id` = VALUES(`user_id`),
`drama_id` = VALUES(`drama_id`);

-- ============================================================
-- 评论：8301 根评论，8302 回复
-- ============================================================

INSERT INTO `t_comment`
(`id`, `drama_id`, `episode_id`, `user_id`, `root_id`, `parent_id`, `reply_to_user_id`,
 `content`, `like_count`, `reply_count`, `status`, `create_time`, `update_time`)
VALUES
(8301, 5001, 6001, 1003, 0, 0, NULL,
 '第一集就上头了，求更新！', 1, 1, 1, '2026-10-21 20:50:00', '2026-10-21 21:00:00'),
(8302, 5001, 6001, 1002, 8301, 8301, 1003,
 '谢谢喜欢，今晚更新～', 0, 0, 1, '2026-10-21 20:55:00', '2026-10-21 20:55:00')
ON DUPLICATE KEY UPDATE
`drama_id` = VALUES(`drama_id`),
`episode_id` = VALUES(`episode_id`),
`user_id` = VALUES(`user_id`),
`root_id` = VALUES(`root_id`),
`parent_id` = VALUES(`parent_id`),
`reply_to_user_id` = VALUES(`reply_to_user_id`),
`content` = VALUES(`content`),
`like_count` = VALUES(`like_count`),
`reply_count` = VALUES(`reply_count`),
`status` = VALUES(`status`),
`update_time` = VALUES(`update_time`);

-- ============================================================
-- 观看历史
-- ============================================================

INSERT INTO `t_watch_history`
(`id`, `user_id`, `drama_id`, `episode_id`, `position_ms`, `duration_ms`, `finished`, `last_watch_time`, `create_time`, `update_time`)
VALUES
(8601, 1003, 5001, 6001, 120000, 150000, 0, '2026-10-21 21:00:00', '2026-10-21 20:58:00', '2026-10-21 21:00:00'),
(8602, 1003, 5001, 6002, 90000, 140000, 0, '2026-10-21 21:05:00', '2026-10-21 21:03:00', '2026-10-21 21:05:00'),
(8603, 1003, 5001, 6003, 150000, 150000, 1, '2026-10-21 21:10:00', '2026-10-21 21:07:00', '2026-10-21 21:10:00')
ON DUPLICATE KEY UPDATE
`drama_id` = VALUES(`drama_id`),
`episode_id` = VALUES(`episode_id`),
`position_ms` = VALUES(`position_ms`),
`duration_ms` = VALUES(`duration_ms`),
`finished` = VALUES(`finished`),
`last_watch_time` = VALUES(`last_watch_time`),
`update_time` = VALUES(`update_time`);

-- 统一密码：Msd@123456
-- BCrypt: $2b$10$sUlPbFufB52gSic/9Q0czOYhY6d.sjeMcZ2Oy.fpLmi7SSYqbWE3C
