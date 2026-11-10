# 评论治理、继续观看与已解锁列表

P6 补齐三块体验：管理员评论治理、首页继续观看、个人中心已解锁剧集。

## 1. 评论治理

经网关访问，路径前缀 `/api/interaction/internal/admin/comments`，只允许 ADMIN：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 分页查询评论，`status`、`dramaId` 可选 |
| POST | `/{commentId}/hide` | 隐藏评论，根评论级联隐藏回复 |
| POST | `/{commentId}/delete` | 删除评论，根评论级联删除回复 |

- 状态：0 待审、1 可见、2 隐藏、3 已删除。
- 只有原本可见（status=1）的评论被隐藏/删除时才写 `COMMENT_DELETED` 事件，避免重复扣减 drama/episode 评论数。
- 回复被隐藏/删除时同步减少根评论 `reply_count`。
- 前端 `/admin/comments`：按状态筛选、隐藏、删除；个人中心“评论治理”入口仅 ADMIN 可见。

## 2. 继续观看

`GET /api/interaction/internal/interactions/history/continue?limit=3`

- 每部短剧取最近一条观看记录，按最后观看时间倒序。
- 首页显示“继续观看”卡片，点击跳转 `/drama/{dramaId}?episode={episodeId}&start={positionMs}`。
- 复用历史接口的短剧/剧集批量补齐；补齐失败只隐藏标题，不影响跳转。

## 3. 已解锁剧集

`GET /api/order/internal/orders/unlocks?pageNo=1&pageSize=20`

- 按解锁时间倒序返回；短剧标题和剧集标题通过 drama 批量接口补齐。
- 前端 `/me/unlocks` 展示短剧、集数、消耗积分和解锁时间，点击进入对应剧集。
- drama 批量接口不可用时返回空标题，不影响解锁记录本身。
