-- 互动计数一致性对账：只读查询，返回不一致行。
-- 使用：mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 DL < scripts/check-interaction-consistency.sql
-- 注意：本地演示种子数据的 like/favorite/comment 是合成基数，没有对应明细；
-- 在演示库上会报告这些预期差异，生产数据或先执行 rebuild_interaction_counts.sql 后才有对账意义。

USE DL;

SELECT 'drama_like' AS metric, d.id AS entity_id, d.like_count AS stored_value,
       COALESCE(s.actual, 0) AS actual
FROM t_drama d
LEFT JOIN (
    SELECT target_id, COUNT(*) AS actual
    FROM t_user_like
    WHERE target_type = 1
    GROUP BY target_id
) s ON s.target_id = d.id
WHERE d.like_count <> COALESCE(s.actual, 0)

UNION ALL

SELECT 'drama_favorite', d.id, d.favorite_count, COALESCE(s.actual, 0)
FROM t_drama d
LEFT JOIN (
    SELECT drama_id, COUNT(*) AS actual
    FROM t_user_favorite
    GROUP BY drama_id
) s ON s.drama_id = d.id
WHERE d.favorite_count <> COALESCE(s.actual, 0)

UNION ALL

SELECT 'drama_comment', d.id, d.comment_count, COALESCE(s.actual, 0)
FROM t_drama d
LEFT JOIN (
    SELECT drama_id, COUNT(*) AS actual
    FROM t_comment
    WHERE status = 1
    GROUP BY drama_id
) s ON s.drama_id = d.id
WHERE d.comment_count <> COALESCE(s.actual, 0)

UNION ALL

SELECT 'episode_like', e.id, e.like_count, COALESCE(s.actual, 0)
FROM t_drama_episode e
LEFT JOIN (
    SELECT target_id, COUNT(*) AS actual
    FROM t_user_like
    WHERE target_type = 2
    GROUP BY target_id
) s ON s.target_id = e.id
WHERE e.like_count <> COALESCE(s.actual, 0)

UNION ALL

SELECT 'episode_comment', e.id, e.comment_count, COALESCE(s.actual, 0)
FROM t_drama_episode e
LEFT JOIN (
    SELECT episode_id, COUNT(*) AS actual
    FROM t_comment
    WHERE status = 1 AND episode_id IS NOT NULL
    GROUP BY episode_id
) s ON s.episode_id = e.id
WHERE e.comment_count <> COALESCE(s.actual, 0)

UNION ALL

SELECT 'comment_like', c.id, c.like_count, COALESCE(s.actual, 0)
FROM t_comment c
LEFT JOIN (
    SELECT target_id, COUNT(*) AS actual
    FROM t_user_like
    WHERE target_type = 3
    GROUP BY target_id
) s ON s.target_id = c.id
WHERE c.like_count <> COALESCE(s.actual, 0)

UNION ALL

SELECT 'comment_reply', c.id, c.reply_count, COALESCE(s.actual, 0)
FROM t_comment c
LEFT JOIN (
    SELECT root_id, COUNT(*) AS actual
    FROM t_comment
    WHERE root_id <> 0 AND status = 1
    GROUP BY root_id
) s ON s.root_id = c.id
WHERE c.root_id = 0 AND c.reply_count <> COALESCE(s.actual, 0)

ORDER BY metric, entity_id;
