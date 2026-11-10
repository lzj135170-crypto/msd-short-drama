# 互动：点赞、收藏、评论与观看历史

`msd-interaction` 负责点赞、收藏、评论和观看历史的读写。写接口沿用 outbox + Kafka 更新 drama 侧冗余计数；列表读接口直接查 interaction 库，不依赖 Kafka。

所有接口都需要登录，经网关访问：

```text
/api/interaction/internal/interactions/**
```

## 1. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/comments?dramaId=&episodeId=&pageNo=&pageSize=` | 根评论分页 |
| GET | `/comments/{rootId}/replies?pageNo=&pageSize=` | 某根评论的回复分页 |
| GET | `/comments/mine?pageNo=&pageSize=` | 我的评论 |
| POST | `/comments` | 创建评论/回复 |
| DELETE | `/comments/{commentId}` | 删除自己的评论 |
| POST | `/likes` | 点赞短剧/剧集/评论 |
| DELETE | `/likes/{targetType}/{targetId}` | 取消点赞 |
| POST | `/favorites` | 收藏短剧 |
| DELETE | `/favorites/{dramaId}` | 取消收藏 |
| GET | `/favorites?pageNo=&pageSize=` | 我的收藏 |
| GET | `/likes/dramas?pageNo=&pageSize=` | 我点赞的短剧 |
| GET | `/dramas/{dramaId}/state` | `{liked, favorited}` |
| GET | `/history?pageNo=&pageSize=` | 观看历史 |
| POST | `/history` | 上报观看进度 |
| DELETE | `/history/{episodeId}` | 删除单条历史 |
| DELETE | `/history` | 清空全部历史 |
| GET | `/stats` | 个人中心互动计数（单条 SQL 聚合） |
| GET | `/events/{eventId}` | 异步计数事件状态，校验事件归属 |
| POST | `/api/interaction/internal/recommendations/events` | 推荐点击/播放/完播/不感兴趣归因 |
| GET | `/api/interaction/internal/ai/comments/summary` | 评论课代表 |
| GET | `/api/interaction/internal/admin/comments?aiReviewStatus=` | 管理端评论列表，支持 AI 状态筛选 |
| POST | `/api/interaction/internal/admin/comments/{id}/ai-audit` | 管理端重跑评论 AI 预审 |

服务间只读接口（不经过网关）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal-source/users/{userId}/behavior` | 推荐画像用行为权重：收藏 3、点赞 2、看完 1.5、未看完 1；返回最近互动、收藏、继续观看、共同观看和负反馈剧 ID |
| POST | `/internal-source/recommendations/exposures` | content 批量写入推荐曝光，幂等 |

## 2. 评论

### 2.1 列表规则

- `dramaId` 必填；传 `episodeId` 查剧集评论，不传查 `episode_id IS NULL` 的短剧级评论。
- 根评论只返回 `status=1` 且 `root_id=0`，按 `create_time DESC, id DESC`。
- 回复只返回 `status=1`，按 `create_time ASC, id ASC`；根评论不存在/不可见返回 404。
- 根评论带 `replyCount`，回复单独分页。
- 每条评论补齐作者昵称/头像、`replyToNickname` 和当前用户 `liked`。

`CommentView`：

```json
{
  "commentId": 8301,
  "dramaId": 5001,
  "episodeId": 6001,
  "userId": 1003,
  "nickname": "追剧用户",
  "avatarUrl": "",
  "rootId": 0,
  "parentId": 0,
  "replyToUserId": null,
  "replyToNickname": null,
  "content": "……",
  "likeCount": 1,
  "replyCount": 1,
  "liked": false,
  "createTime": "2026-10-01T20:00:00"
}
```

### 2.2 创建校验

- 内容 trim 后不能为空。
- 新根评论：`rootId=0`、`parentId=0`。
- 回复：根评论必须存在、可见、是根评论，且与请求的 `dramaId/episodeId` 一致。
- 回复某个回复时，父评论必须属于同一个根评论。
- 服务端写入 `reply_to_user_id`，用于 `@用户` 展示。
- 不合法返回 400/404，不再允许跨短剧或跨根评论拼接回复。

### 2.3 删除

- 只有作者可以删除；不存在返回 404「评论不存在」，非作者返回 403「无权删除该评论」。
- 删除回复：软删除、写一条 `COMMENT_DELETED`；根评论 `reply_count-1` 由 interaction 计数消费者异步派生。
- 删除根评论：级联软删除所有可见回复，根和每条回复各写一条 `COMMENT_DELETED`，保证 drama/episode 评论数和回复数异步递减。
- 已删除评论再次删除返回 404。

### 2.4 评论点赞

- 复用 `/likes`，`targetType=3`。
- 只允许点赞 `status=1` 的评论；已删除/隐藏评论返回 404。
- 点赞前先校验评论存在且可见，避免异步化后产生孤儿点赞。
- `t_comment.like_count` 由 `msd-interaction-counter-v1` 消费 LIKE/UNLIKE 事件异步更新；评论列表返回当前用户 `liked`，前端先乐观更新。

### 2.5 评论 AI 预审

- 评论创建事务提交后发布 `CommentCreatedEvent`，`CommentAiReviewService` 异步调用 content `POST /internal/ai/audit/structured`，`scene=comment`。
- AI 只写 `ai_review_status`、`ai_review_result`、`ai_review_time`：
  - `0` 未审核、`1` 审核中、`2` 通过、`3` 风险、`4` 失败。
- **绝不修改人工 `status`**；评论默认 `status=1` 可见，AI 风险只在管理端标记和排序。
- 降级写 `4`，并在 JSON 中保留 `degradedFlags`；不自动隐藏、不自动放行。
- 管理端列表可按 `aiReviewStatus` 筛选，并支持 `POST /internal/admin/comments/{id}/ai-audit` 重跑。
- 预审结果 JSON 结构：`{"scene":"comment","verdict":{"is_violation":false,...},"degraded":false,"degradedFlags":[],"elapsedMs":...}`。
- 建表/加列脚本：`deploy/mysql/repair/20261106_p1_comment_recommendation.sql`。

## 3. 收藏、点赞短剧与观看历史

### 3.1 收藏

`GET /favorites` 返回：

```json
{
  "dramaId": 5001,
  "title": "隐婚总裁的甜妻",
  "subtitle": "……",
  "coverUrl": "http://...",
  "episodeCount": 6,
  "favoriteTime": "2026-10-01T20:00:00"
}
```

按收藏时间倒序。短剧未上架时仍保留条目，标题/封面为 null。

### 3.2 点赞短剧

`GET /likes/dramas` 只返回 `target_type=1` 的点赞，按点赞时间倒序；剧集/评论点赞不进个人中心列表。

### 3.3 观看历史

`GET /history` 返回：

```json
{
  "dramaId": 5001,
  "dramaTitle": "隐婚总裁的甜妻",
  "dramaCoverUrl": "http://...",
  "episodeId": 6003,
  "episodeNo": 3,
  "episodeTitle": "第3集 危机",
  "episodeCoverUrl": "http://...",
  "positionMs": 150000,
  "durationMs": 150000,
  "finished": true,
  "lastWatchTime": "2026-10-21T21:00:00"
}
```

- 按 `last_watch_time DESC, id DESC`。
- 删除/清空历史只物理删除历史行，不回退播放量。
- 历史上报仍复用 `POST /history`；只有首次插入写 `HISTORY` 事件，播放量只在首次观看增加。

## 4. 分页与跨服务补齐

### 4.1 分页

- `pageNo` 默认 1、最小 1；`pageSize` 默认 20、最大 100。
- 返回 `PageResult`：`{pageNo, pageSize, total, records}`。
- 使用手写 `COUNT + LIMIT/OFFSET`，没有引入 MyBatis-Plus 分页插件。

### 4.2 批量补齐

- 评论作者：`POST /api/user/internal/users/batch`。
- 收藏/历史/点赞/我的评论：`POST /api/drama/internal/dramas/batch`、`POST /api/drama/internal/episodes/batch`。
- 每次最多 100 个 ID；评论页作者 + 回复目标可能超过 100，服务端按 100 分片。
- 批量接口失败时只记录 warning，列表继续返回，缺失的标题/昵称降级为 null，不把列表打成 503。

## 5. 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | 40000 | 参数校验失败、评论 root/parent 不匹配、请求体格式错误 |
| 401 | 40100 | 未登录 |
| 403 | 40300 | 删除他人评论 |
| 404 | 40400 | 评论/根评论不存在，已删除，批量 ID 为空等 |
| 429 | 42900 | 网关限流 |
| 500 | 50000 | 未预期异常 |

## 6. 验收记录（2026-10-24）

环境：MySQL、Nacos、msd-user 8101、msd-drama 8102、msd-interaction 8104、msd-gateway 8000；Kafka 未启动。

| 用例 | 结果 |
|---|---|
| 用户批量 `[1003,1006,999999]` | 返回 1003、1006 |
| 短剧批量 `[5001,5003,999999]` | 只返回已上架 5001 |
| 剧集批量 `[6001,6201,999999]` | 返回 6001、6201（6201 下线但仍存在） |
| 5001/6001 评论 | 8301，作者 1003，replyCount=1 |
| 8301 回复 | 8302，作者 1002，replyTo=1003 |
| 5004 短剧级评论 | 只有 8314；status=0 的 8318 不出现 |
| 5004/6301 评论 | 8310，作者 1006，likeCount=2 |
| 我的评论 | 8301、8314；status=3 的 8326 不出现 |
| 收藏/点赞短剧 | 5001；state 5001 = liked/favorited |
| 历史 | 4 条，标题/集数补齐，按 last_watch_time 倒序 |
| 统计 | likedDramaCount=1、favoriteCount=1、commentCount=2、historyCount=4 |
| 分页 | pageNo=0 归 1，pageSize=200 归 100；pageNo=2&pageSize=2 返回后两条 |
| 评论创建/回复 | 新根 8327，回复 8328/8329；replyToUserId 正确，reply_count=2 |
| 非法回复 | root 不存在 404；root 与短剧不匹配 400；父评论不属于 root 400 |
| 根评论级联删除 | 8327/8328/8329 全部 status=3；outbox 3 条 COMMENT_DELETED |
| 非作者删除 | 1006 删除 8301 → 403「无权删除该评论」 |
| 评论点赞 | 8301 like_count 1→2，列表 liked=true；取消后恢复 |
| 收藏/点赞 5005 | 列表出现；取消后 state 恢复 false |
| 历史删除/清空 | 测试用户 2 条 → 删除 1 条 → 清空 0 条 |
| 批量 101 个 ID | 400「一次最多查询 100 个 ID」 |
| 非法 JSON | 400「请求体格式不正确」 |
| 资料编辑 | 昵称/头像/简介更新成功；空昵称、超长昵称 400；已恢复 1003 原资料 |

测试评论已按 `[slice-test]%` 清理，测试用户和历史已删除，outbox/consume log 已用复位脚本清理。

## 7. 异步计数与全链路确认

### 7.1 写入链路

```text
点赞/收藏/评论
  → interaction 本地事务：明细 + t_outbox_event(status=0)
  → OutboxRelay 短事务认领（claim_token + locked_until 60s）
  → 事务外 8 线程并行发 Kafka，1s 超时
  → 按 claim_token 条件 markSent/markRetry/markDead
  → drama / interaction 消费组按 event_id 幂等更新计数
  → GET /events/{eventId} 返回 SENT/DEAD 与实际消费组
```

- 评论正文仍同步落库并返回 `commentId`；点赞数、回复数等派生效果异步。
- `CommentRequest.clientRequestId` 是请求幂等键；重复或并发重复请求返回已有 `commentId`，不重复写评论和事件。
- Outbox 批次默认 200，发送线程 8，租约 60 秒；实例崩溃后租约到期可被其他实例重新认领。
- `markSent/markRetry/markDead` 都带 `claim_token` 条件，避免旧实例回写覆盖新认领。
- 新消费组 `msd-interaction-counter-v1` 使用 `auto.offset.reset=latest`，上线前先用迁移脚本从明细重算 `like_count/reply_count`。

### 7.2 事件状态

```http
GET /api/interaction/internal/interactions/events/{eventId}
Authorization: Bearer <token>
```

```json
{
  "eventId": "fb2daaee-...",
  "status": 1,
  "statusName": "SENT",
  "eventType": "LIKE",
  "retryCount": 0,
  "sentAt": "2026-10-27T21:56:34.42",
  "lastError": "",
  "consumedGroups": ["msd-drama-counter-v1", "msd-interaction-counter-v1"]
}
```

- 只允许事件归属用户查询；其他用户和不存在统一返回 404。
- `status=0 PENDING`、`1 SENT`、`2 DEAD`；`consumedGroups` 来自 `t_event_consume_log`。
- 前端 `useInteractionAck` 按 0/300/800/1500ms 轮询，页面隐藏时暂停；DEAD 回滚乐观状态，SENT 且已有消费组后拉取 drama 统计校准计数。

### 7.3 读优化

- 评论首页 Caffeine 15 秒缓存原始评论行，返回前再合并当前用户 `liked`；评论创建/删除/隐藏后按 dramaId 失效。
- 个人中心 `stats` 的四个 COUNT 合并为一条 SQL。
- interaction 开启 Java 21 虚拟线程，Hikari 连接池 20；drama 连接池 20；Kafka topic 6 分区、消费并发 6。
- `msd.interaction.batch-consumer.enabled=true` 时 drama 切换批量消费：同聚合根 delta 合并后批量 UPDATE，缓存按短剧去重失效；默认关闭。

### 7.4 压测与对账

```bash
python scripts/load-test-interaction.py --calls 2000 --concurrency 50 --target direct \
  --user-id 9998 --cleanup-likes
mysql -umsd -p -h127.0.0.1 DL < scripts/check-interaction-consistency.sql
```

脚本默认在压测结束后通过删除接口清理本轮创建的评论；`--cleanup-likes` 仅用于专用压测用户，取消测试短剧的点赞，避免污染演示数据。

2026-10-27 P4 本地实测（直达 8104、专用用户 9998）：

- 2000 calls / 并发 50：1999 成功、1 失败（0.05%）；失败为 `INSERT IGNORE t_user_like` 死锁，4 次重试后仍失败，低于 0.1% 目标。
- p50/p95/p99 = 21/73/128ms，wall=1145ms；outbox pending=0，两个消费组 LAG=0。
- 416 条压测评论删除成功，9 个测试短剧点赞取消；压测用户无残留点赞或可见评论。

对账 SQL 输出短剧/剧集点赞、收藏、评论和评论点赞/回复的不一致行；本地演示 seed 计数是合成基数，生产数据或先执行重建脚本后才有对账意义。Kafka 积压可用 `kafka-consumer-groups --describe` 查看。

## 8. 不在本切片

- 评论 AI 预审已实现为异步建议；敏感词、评论编辑、评论恢复、评论点赞列表仍不在本切片；管理员隐藏/删除见 [moderation.md](moderation.md)。
- 按短剧聚合的「继续观看」已接入 `GET /history/continue`，首页显示最近一条。
- 头像上传/裁剪；只接受 URL 字符串。
- 点赞/收藏写入的短剧/剧集存在性校验（列表补齐时自然隐藏）。
- Redis 缓存、通知、消息中心。

## 9. AI 回答反馈

经网关访问，只允许登录用户：

```http
POST /api/interaction/internal/ai/feedback
```

```json
{
  "scene": "RAG_SEARCH",
  "requestId": "4d1f...",
  "rating": 1,
  "reason": "",
  "comment": "",
  "targetId": 5001,
  "sessionId": ""
}
```

```json
{"id": 18, "duplicate": false}
```

- `scene`：`RAG_SEARCH`、`COMPANION`、`AUDIT`、`RECOMMEND`。
- `rating`：`1` 有用，`-1` 没用；0 或其他值返回 400。
- `reason` 可选：`INACCURATE`、`IRRELEVANT`、`SPOILER`、`UNSAFE`、`OTHER`。
- `comment` 最多 500 字；不存原始 prompt 或完整对话。
- `UNIQUE KEY (user_id, scene, request_id)` 保证幂等；重复提交返回已有记录 `duplicate=true`，不重复计数。
- 新增反馈时累加 `ai_feedback_total{scene,rating}`，`/actuator/prometheus` 可采集。
- 匿名 AI 搜索不展示反馈按钮；伴看、审核详情和已登录找剧展示。
- 建表脚本：`deploy/mysql/repair/20261106_ai_feedback.sql`。

## 10. 推荐归因

### 10.1 曝光

content 返回首页推荐后调用（服务间直连）：

```http
POST /internal-source/recommendations/exposures
```

```json
{
  "userId": 1003,
  "requestId": "req-1",
  "scene": "HOME_FEED",
  "algorithm": "v2",
  "experimentId": "for-you-v2",
  "variant": "A",
  "items": [{"dramaId": 5001, "position": 1, "reason": "因为你喜欢甜宠"}]
}
```

- 写入 `t_recommendation_exposure`，按 `(request_id, drama_id)` 幂等。
- content 端失败只记录 debug 日志，不影响推荐返回。

### 10.2 行为事件

```http
POST /api/interaction/internal/recommendations/events
Authorization: Bearer <token>
```

```json
{"requestId":"req-1","dramaId":5001,"eventType":1,"position":1}
```

- `eventType`：1 点击、2 播放、3 完播、4 不感兴趣。
- 按 `(user_id, request_id, drama_id, event_type)` 幂等；重复上报返回成功。
- 前端首页卡片点击/不感兴趣、Feed 播放/完播自动上报。
- 负反馈写入后，`UserBehaviorService` 会把该短剧加入排除列表；前端同时清理 content 个性化缓存。

## 11. 评论课代表

```http
GET /api/interaction/internal/ai/comments/summary?dramaId=5001&episodeId=6005
```

```json
{
  "summary": "大家普遍觉得节奏紧凑，男女主互动自然；也有人认为中段略拖。",
  "sentiment": "mixed",
  "representatives": [
    {"commentId": 9001, "content": "节奏挺快的", "likeCount": 12}
  ],
  "degraded": false,
  "degradedFlags": [],
  "elapsedMs": 1234
}
```

- 只取可见根评论，按点赞数倒序最多 50 条；包含“结局/凶手/剧透/加微信”等词的评论不进入摘要。
- interaction 调 content `POST /internal/ai/comment-summary`，独立 15 秒读超时。
- 结果 Caffeine 缓存 10 分钟；下游失败返回 `degraded=true`，前端隐藏课代表卡片。
- 摘要只做观点聚合，不作为剧情事实；代表评论必须来自输入 ID，前端仅展示原文和赞数。
