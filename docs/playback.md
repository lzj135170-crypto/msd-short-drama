# 剧集播放与积分解锁

播放授权由 `msd-order` 提供：order 从 `msd-drama` 的内部接口读取剧集源，再结合 `t_episode_unlock` 决定是否下发视频地址。付费集未解锁时返回 200，但 `videoUrl=null`，前端据此弹出解锁框。

## 1. 接口

| 方法 | 网关路径 | 说明 |
|---|---|---|
| GET | `/api/order/internal/playback/episodes/{episodeId}` | 播放信息与视频地址 |
| POST | `/api/order/internal/orders/episodes/{episodeId}/unlock` | 积分解锁（已有） |
| GET | `/api/order/internal/orders/points` | 积分余额 |
| GET | `/api/order/internal/orders/dramas/{dramaId}/unlocks` | 某部短剧已解锁的付费集 |
| POST | `/api/interaction/internal/interactions/history` | 上报观看进度（已有） |

全部需要 `Authorization: Bearer <token>`。

### 1.1 播放信息

已解锁或免费集：

```json
{
  "dramaId": 5005,
  "dramaTitle": "长安第一女仵作",
  "episodeId": 6401,
  "episodeNo": 1,
  "episodeTitle": "第1集 雨夜尸案",
  "coverUrl": "http://127.0.0.1:9000/msd/covers/5005-01.jpg",
  "durationSeconds": 160,
  "pricePoints": 0,
  "free": true,
  "unlocked": true,
  "videoUrl": "http://127.0.0.1:9000/msd/videos/5005/01.mp4"
}
```

付费未解锁：

```json
{
  "dramaId": 5001,
  "dramaTitle": "隐婚总裁的甜妻",
  "episodeId": 6004,
  "episodeNo": 4,
  "episodeTitle": "第4集 反击",
  "coverUrl": "http://127.0.0.1:9000/msd/covers/5001-04.jpg",
  "durationSeconds": 145,
  "pricePoints": 30,
  "free": false,
  "unlocked": false,
  "videoUrl": null
}
```

规则：

- 剧集不存在、`status=0`、父短剧未上架/已删除 → 404 `40400 剧集不存在或已下架`。
- 免费集不查积分账户，新注册用户也能播放。
- 付费集按 `t_episode_unlock(user_id, episode_id)` 判断；未解锁不下发 `videoUrl`。
- 播放接口不增加播放量；播放量由观看历史首次上报异步累加。

### 1.2 积分解锁

请求：

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/orders/episodes/6004/unlock
```

响应：

```json
{
  "unlockId": 8720,
  "episodeId": 6004,
  "pointsCost": 30,
  "balanceAfter": 220,
  "alreadyUnlocked": false
}
```

- 免费集返回 `unlockId=0`、`pointsCost=0`，不写解锁记录和积分流水。
- 重复解锁返回 `alreadyUnlocked=true`，不重复扣积分。
- 并发重复解锁已做幂等：唯一键冲突时用加锁读返回已有记录。
- 父短剧未上架返回 404 `40400 短剧未上架`；剧集下线返回 409 `40900 剧集已下线`。
- 新注册用户没有积分账户：`GET /points` 返回 `exists=false`，付费解锁返回 409 `40900 积分账户不存在`。

### 1.3 批量解锁状态

```json
{ "dramaId": 5001, "unlockedEpisodeIds": [6003, 6005, 6004] }
```

- 只包含 `t_episode_unlock` 里的付费解锁记录。
- 免费集不写入，前端用详情接口里的 `free=true` 判断。
- 顺序按 `create_time, id` 升序。

### 1.4 观看历史

```bash
curl -s -X POST http://127.0.0.1:8000/api/interaction/internal/interactions/history \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"dramaId":5001,"episodeId":6003,"positionMs":150000,"durationMs":150000,"finished":true}'
```

- 首次上报 `firstWatch=true`，写 outbox；Kafka 消费后增加 `t_drama.view_count` 和 `t_drama_episode.play_count`。
- 重复上报只更新位置，不重复计数。
- 后端不校验该集是否已解锁；客户端只应对成功播放的剧集上报。

## 2. 播放页调用顺序

1. `GET /api/drama/internal/dramas/{dramaId}` 拿剧集列表和 `pricePoints/free`。
2. `GET /api/order/internal/orders/dramas/{dramaId}/unlocks` 拿到已解锁付费集，渲染锁图标。
3. `GET /api/order/internal/playback/episodes/{episodeId}` 获取播放信息。
4. `unlocked=false` 时调用解锁接口，成功后重新获取播放信息。
5. 播放过程中定时调用 history 接口上报进度。

## 3. 内部播放源与网关路由

drama 新增内部接口：

```text
GET /internal-source/episodes/{episodeId}
```

- 返回 `videoUrl`，SQL 已过滤 `episode.status=1`、`drama.deleted=0`、`drama.review_status=2`。
- 网关的 drama 路由已从 `/api/drama/**` 收窄为 `/api/drama/internal/**`，因此外部请求 `/api/drama/internal-source/**` 返回 404。
- order 通过 Nacos 直连 drama 调用，不经过网关。
- 直连 8102 仍可访问该接口，生产只暴露 8000。

## 4. 实现位置

- `msd-drama/src/main/java/com/msd/drama/controller/EpisodeSourceController.java`
- `msd-drama/src/main/java/com/msd/drama/service/EpisodeService.java`
- `msd-drama/src/main/java/com/msd/drama/mapper/EpisodeMapper.java`
- `msd-drama/src/main/java/com/msd/drama/dto/EpisodeSource.java`
- `msd-order/src/main/java/com/msd/order/controller/PlaybackController.java`
- `msd-order/src/main/java/com/msd/order/service/PlaybackService.java`
- `msd-order/src/main/java/com/msd/order/client/DramaSourceClient.java`
- `msd-order/src/main/java/com/msd/order/service/UnlockFacade.java`
- `msd-order/src/main/java/com/msd/order/service/UnlockLocalService.java`
- `msd-order/src/main/java/com/msd/order/controller/OrderQueryController.java`
- `msd-order/src/main/java/com/msd/order/mapper/EpisodeUnlockMapper.java`
- `msd-gateway/src/main/resources/application.yml`

## 5. 验收记录（2026-10-24）

环境：MySQL 3306、Nacos 8848、Seata 8091、msd-user 8101、msd-drama 8102、msd-order 8103、msd-interaction 8104、msd-gateway 8000。MinIO 未启动。

| 用例 | 结果 |
|---|---|
| 1003 积分余额 | 250 |
| 播放 6401（免费） | 200，`free=true`、`unlocked=true`、`videoUrl` 非空 |
| 播放 6004（付费未解锁） | 200，`unlocked=false`、`videoUrl=null`、`pricePoints=30` |
| 解锁 6004 | 200，`unlockId=8720`、`balanceAfter=220` |
| 解锁后播放 6004 | 200，`unlocked=true`、`videoUrl` 非空 |
| 重复解锁 6004 | 200，`alreadyUnlocked=true`，余额仍 220 |
| 批量解锁 5001 | `[6003, 6005, 6004]` |
| 免费集解锁 6401 | `unlockId=0`，无解锁记录、无流水 |
| 并发解锁 6006 | 两次均 200；一次 `alreadyUnlocked=false`、一次 `true`；余额只扣一次（170） |
| 播放 6201–6203（5003 审核中） | 404 `40400 剧集不存在或已下架` |
| 解锁 6201 `status=0` | 409 `剧集已下线` |
| 解锁 6201 临时 `status=1`（短剧未上架） | 404 `短剧未上架`（已恢复 `status=0`） |
| 播放 999999 | 404 |
| 无 Token 播放 | 401「未登录」 |
| 内部源经网关 | 404（网关路由已收窄） |
| 内部源直连 8102 | 200，返回 `videoUrl` |
| 数据库核对 | 余额 220、流水 `-30/220`、unlock_ref 1 条、`undo_log` 0 条 |
| 历史 6003（已有记录） | 200，`firstWatch=false`、`eventId=null` |
| 新用户 1016 | 免费播放正常；付费解锁 409「积分账户不存在」；测试用户已清理 |
| drama 停止时播放 | 503 `50000 依赖服务暂不可用`；重启后恢复 |
| 复位脚本 | 1003 余额恢复 250，6004/6006 记录清理完成 |

## 6. 不在本切片与已知限制

- 播放链路不做充值，充值入口见 [recharge.md](recharge.md)；充值支付时会自动创建积分账户。
- 不做退款、积分过期、批量解锁、多集打包购买。
- 不做签名 URL、防盗链、视频转码；MinIO 未启动时种子 URL 不可播放。
- 观看历史列表已由 interaction 提供，见 [interaction.md](interaction.md)；不做按短剧聚合的「继续观看」。
- history 不校验剧集存在性和解锁状态。
- 播放接口不直接增加播放量，计数依赖 Kafka，存在 1–2 秒延迟。
- Seata 未启动时付费解锁不可用；免费播放不受影响。
- 新注册用户没有积分账户，只能看免费集；演示解锁请用 1003（余额 250）。
