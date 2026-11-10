-- 码上短剧 - 互动计数重建脚本
-- 用途：Kafka 消费进入 DLQ、长期停机或手工修数后，从明细表重算冗余计数。
-- 执行：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/repair/rebuild_interaction_counts.sql
-- 说明：当前所有表都在 DL 库；拆库后需要改为各服务对账接口或离线 ETL。
-- 注意：view_count / play_count 按 t_watch_history 的首次观看记录重算；历史播放若没有落明细，
-- 重建后会小于原值。种子数据中的浏览/播放量是演示用基数，不要在生产数据上直接执行本脚本。

USE DL;

UPDATE `t_drama` d
SET d.`like_count` = (
        SELECT COUNT(*) FROM `t_user_like` l
        WHERE l.`target_type` = 1 AND l.`target_id` = d.`id`
    ),
    d.`favorite_count` = (
        SELECT COUNT(*) FROM `t_user_favorite` f
        WHERE f.`drama_id` = d.`id`
    ),
    d.`comment_count` = (
        SELECT COUNT(*) FROM `t_comment` c
        WHERE c.`drama_id` = d.`id` AND c.`status` = 1
    ),
    d.`view_count` = (
        SELECT COUNT(*) FROM `t_watch_history` h
        WHERE h.`drama_id` = d.`id`
    );

UPDATE `t_drama_episode` e
SET e.`like_count` = (
        SELECT COUNT(*) FROM `t_user_like` l
        WHERE l.`target_type` = 2 AND l.`target_id` = e.`id`
    ),
    e.`comment_count` = (
        SELECT COUNT(*) FROM `t_comment` c
        WHERE c.`episode_id` = e.`id` AND c.`status` = 1
    ),
    e.`play_count` = (
        SELECT COUNT(*) FROM `t_watch_history` h
        WHERE h.`episode_id` = e.`id`
    ),
    e.`unlock_count` = (
        SELECT COUNT(*) FROM `t_episode_unlock_ref` r
        WHERE r.`episode_id` = e.`id`
    );

UPDATE `t_comment` c
SET c.`like_count` = (
    SELECT COUNT(*) FROM `t_user_like` l
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
