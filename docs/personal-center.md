# 个人中心

个人中心由三个服务组合而成，没有单独的 BFF：

- `msd-user`：资料读取/编辑、批量用户信息。
- `msd-order`：积分余额。
- `msd-interaction`：互动统计、收藏、历史、我的评论、点赞的短剧。

所有接口都需要登录。

## 1. 页面数据

| 数据 | 接口 |
|---|---|
| 当前用户资料 | `GET /api/user/internal/users/me` |
| 积分余额 | `GET /api/order/internal/orders/points` |
| 互动统计 | `GET /api/interaction/internal/interactions/stats` |
| 收藏 | `GET /api/interaction/internal/interactions/favorites` |
| 观看历史 | `GET /api/interaction/internal/interactions/history` |
| 我的评论 | `GET /api/interaction/internal/interactions/comments/mine` |
| 点赞的短剧 | `GET /api/interaction/internal/interactions/likes/dramas` |

前端进入个人中心时并行请求资料、积分、统计，再按当前 tab 请求对应列表。

## 2. 资料编辑

`PUT /api/user/internal/users/me`

```json
{
  "nickname": "追剧用户",
  "avatarUrl": "http://example.com/avatar.png",
  "bio": "每天都要看两集"
}
```

- 三个字段都可选；`null` 表示不修改。
- `avatarUrl`、`bio` 传空字符串表示清空。
- 昵称 trim 后不能为空，最多 20 字符；头像 URL 最多 512；简介最多 255。
- 不允许修改手机号、角色、状态、密码。
- 返回最新的 `UserProfile`。

当前没有头像上传/裁剪，`avatarUrl` 只是字符串；前端可以继续使用昵称首字渐变头像。

## 3. 统计

`GET /api/interaction/internal/interactions/stats`

```json
{
  "likedDramaCount": 1,
  "favoriteCount": 1,
  "commentCount": 2,
  "historyCount": 4
}
```

- `likedDramaCount` 只统计 `target_type=1` 的短剧点赞。
- `commentCount` 统计 `status<>3` 的评论（包含待审/隐藏）。
- 所有计数实时查 interaction 库，不依赖 drama 冗余计数和 Kafka。

## 4. 列表

### 4.1 收藏

`GET /favorites?pageNo=1&pageSize=20`

```json
{
  "pageNo": 1,
  "pageSize": 20,
  "total": 1,
  "records": [
    {
      "dramaId": 5001,
      "title": "隐婚总裁的甜妻",
      "subtitle": "……",
      "coverUrl": "http://...",
      "episodeCount": 6,
      "favoriteTime": "2026-10-01T20:00:00"
    }
  ]
}
```

### 4.2 观看历史

`GET /history?pageNo=1&pageSize=20`

返回 `HistoryItem`：短剧标题/封面、剧集号/标题/封面、进度、是否看完、最近观看时间。

### 4.3 我的评论

`GET /comments/mine?pageNo=1&pageSize=20`

返回 `MyCommentView`：短剧/剧集标题、内容、点赞数、回复数、状态、时间。排除 `status=3`。

### 4.4 点赞的短剧

`GET /likes/dramas?pageNo=1&pageSize=20`

返回 `LikedDramaItem`：短剧标题/封面/集数、点赞时间。

### 4.5 分页

- 所有列表统一 `pageNo`/`pageSize`，默认 1/20，最大 100。
- 响应统一 `PageResult`。
- 列表数据由 user/drama 批量接口补齐；下游不可用时标题/昵称降级为 null，列表本身仍返回。

## 5. 积分

`GET /api/order/internal/orders/points`

```json
{ "userId": 1003, "balance": 250, "exists": true }
```

- 新注册用户没有 `t_points_account`：返回 `exists=false`、`balance=0`。
- 充值入口和积分流水已接入，见 [recharge.md](recharge.md)；本次不做已解锁列表、退款、积分过期。

## 6. 前端调用顺序

1. `GET /users/me` → 渲染头像、昵称、简介、手机号（前端脱敏）、角色、注册时间。
2. `GET /orders/points` → 积分卡片。
3. `GET /interactions/stats` → 四个统计数字。
4. 当前 tab 请求 `/favorites`、`/history`、`/comments/mine`、`/likes/dramas`。
5. 编辑资料：`PUT /users/me`，成功后刷新 `auth.loadMe()`。
6. 取消收藏/删除历史/删除评论后重新请求当前列表和统计。

## 7. 验收记录（2026-10-24）

| 用例 | 结果 |
|---|---|
| 1003 资料 | 追剧用户 / 每天都要看两集 / 空头像 |
| 修改昵称、头像、简介 | 200，`GET /me` 立即生效 |
| 空昵称、21 字符昵称 | 400，提示正确 |
| 恢复原资料 | 200，已恢复 |
| 积分 | 250，`exists=true` |
| 统计 | 1/1/2/4 |
| 收藏 | 5001，标题/封面补齐 |
| 历史 | 4 条，短剧/剧集标题补齐 |
| 我的评论 | 8301、8314，已删除评论不出现 |
| 点赞短剧 | 5001 |
| 新用户积分 | `exists=false`，付费解锁 409「积分账户不存在」 |

## 8. 不在本切片

- 头像上传、裁剪、对象存储。
- 退款、积分过期。
- 已解锁剧集列表已接入 `/me/unlocks`，见 [moderation.md](moderation.md)。
- 资料修改后的 Token 刷新（JWT 不含昵称，无需刷新）。
- 个人中心聚合 BFF；前端直接组合三个服务。
- 消息通知、关注/粉丝。
