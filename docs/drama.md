# 首页推荐与短剧详情

`msd-drama` 提供用户端首页聚合和短剧详情接口。两者都经网关访问，需要 `Authorization: Bearer <token>`；本切片不返回点赞/收藏/解锁等用户态。

## 1. 接口

| 方法 | 网关路径 | 下游路径 | 说明 |
|---|---|---|---|
| GET | `/api/drama/internal/home` | `/internal/home` | 首页轮播、热播、最新、分类 |
| GET | `/api/drama/internal/categories` | `/internal/categories` | 启用中的分类列表 |
| GET | `/api/drama/internal/search/dramas` | `/internal/search/dramas` | 短剧搜索/分类浏览，分页返回 |
| GET | `/api/drama/internal/dramas/{dramaId}` | `/internal/dramas/{dramaId}` | 短剧详情和在线剧集 |

### 1.1 首页

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800000003","password":"Msd@123456"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/drama/internal/home
```

响应结构：

```json
{
  "banners": [
    { "dramaId": 5005, "title": "长安第一女仵作", "subtitle": "验尸破案，顺便谈个恋爱", "coverUrl": "http://..." }
  ],
  "sections": [
    {
      "key": "hot",
      "title": "热播推荐",
      "items": [
        {
          "dramaId": 5005,
          "title": "长安第一女仵作",
          "subtitle": "验尸破案，顺便谈个恋爱",
          "coverUrl": "http://...",
          "categoryId": 2004,
          "categoryName": "古装",
          "actors": "沈知意,裴行舟",
          "episodeCount": 6,
          "viewCount": 204,
          "likeCount": 4,
          "favoriteCount": 2,
          "commentCount": 1,
          "publishTime": "2026-10-14T10:00:00"
        }
      ]
    },
    { "key": "new", "title": "最新上架", "items": [] }
  ],
  "categories": [
    { "id": 2001, "name": "都市", "sort": 1 }
  ]
}
```

规则：

- `banners`：热播榜前 3。
- `sections`：固定 `hot`（热播推荐）和 `new`（最新上架），各 6 条。
- `categories`：`status=1` 的分类，按 `sort, id` 升序，用于分类导航。
- 只返回 `deleted=0 AND review_status=2 AND publish_time IS NOT NULL` 的短剧。

### 1.2 详情

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/drama/internal/dramas/5005
```

响应示例（节选）：

```json
{
  "id": 5005,
  "title": "长安第一女仵作",
  "subtitle": "验尸破案，顺便谈个恋爱",
  "coverUrl": "http://127.0.0.1:9000/msd/covers/5005.jpg",
  "description": "长安城接连出现离奇命案……",
  "category": { "id": 2004, "name": "古装", "sort": 4 },
  "tags": [
    { "id": 3004, "name": "悬疑" },
    { "id": 3005, "name": "复仇" }
  ],
  "actors": ["沈知意", "裴行舟"],
  "authorId": 1004,
  "episodeCount": 6,
  "publishTime": "2026-10-14T10:00:00",
  "counters": { "viewCount": 204, "likeCount": 4, "favoriteCount": 2, "commentCount": 1 },
  "episodes": [
    {
      "episodeId": 6401,
      "episodeNo": 1,
      "title": "第1集 雨夜尸案",
      "coverUrl": "http://127.0.0.1:9000/msd/covers/5005-01.jpg",
      "durationSeconds": 160,
      "pricePoints": 0,
      "free": true,
      "playCount": 140,
      "likeCount": 1,
      "commentCount": 0,
      "unlockCount": 0
    }
  ]
}
```

规则：

- 短剧不存在、已删除、未上架 → HTTP 404 + `40400 短剧不存在或未上架`。
- 剧集只返回 `status=1`，按 `episode_no` 升序。
- **不返回 `video_url`**：付费视频地址留到播放接口做解锁校验。
- 标签只返回 `t_drama_tag.status=1`；分类被隐藏时详情仍返回名称。
- `actors` 按 `,`、`，`、`、` 拆分并去空。

### 1.3 分类与搜索

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/drama/internal/categories

# 关键词搜索；中文参数需要 UTF-8 百分号编码
curl -s -G -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "keyword=总裁" \
  http://127.0.0.1:8000/api/drama/internal/search/dramas

# 分类 + 最热 + 分页
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/drama/internal/search/dramas?categoryId=2002&sort=hot&pageNo=1&pageSize=12"
```

| 参数 | 规则 |
|---|---|
| `keyword` | 可选，trim 后最多 50 字；匹配标题、副标题、简介、演员；`%`、`_`、`/` 会转义 |
| `categoryId` | 可选，正整数 |
| `sort` | `latest`（默认）或 `hot`；`hot` 复用首页加权公式 |
| `pageNo` | 默认 1，小于 1 按 1 处理 |
| `pageSize` | 默认 12，超过 50 截断 |

响应为 `PageResult<DramaCard>`：

```json
{
  "pageNo": 1,
  "pageSize": 12,
  "total": 1,
  "records": [
    {
      "dramaId": 5001,
      "title": "隐婚总裁的甜妻",
      "subtitle": "先婚后爱，甜到上头",
      "coverUrl": "http://127.0.0.1:9000/msd/covers/5001.jpg",
      "categoryId": 2002,
      "categoryName": "甜宠",
      "actors": "林一,苏晚",
      "episodeCount": 6,
      "viewCount": 5,
      "likeCount": 1,
      "favoriteCount": 1,
      "commentCount": 0,
      "publishTime": "2026-10-01T10:00:00"
    }
  ]
}
```

- 只返回 `deleted=0 AND review_status=2 AND publish_time IS NOT NULL`。
- 不传关键词和分类时等价于“全部已上架”，按最新排序，可用于发现页。
- 目标分类不存在或没有命中时返回 `total=0` 和空 `records`，不报 404。
- 该接口不加缓存，避免搜索结果与审核状态不一致。

## 2. 排序与可见性

- 热播热度：`view_count * 1 + like_count * 3 + favorite_count * 5 + comment_count * 2`。
- 分数相同按 `publish_time DESC, id DESC`。
- 最新区块按 `publish_time DESC, id DESC`。
- 计数直接读 `t_drama` 冗余列，由 Kafka 消费端异步更新，允许 1–2 秒延迟。
- 详情页浏览不增加 `view_count`；`view_count` 由首次观看事件累加。
- 只跑过 `02`–`04` 初始化脚本时，`t_drama_episode.unlock_count` 可能为 0，需要 `05_consistency.sql` 回填。

## 3. 实现位置

- `msd-drama/src/main/java/com/msd/drama/controller/DramaBrowseController.java`
- `msd-drama/src/main/java/com/msd/drama/controller/DramaSearchController.java`
- `msd-drama/src/main/java/com/msd/drama/service/DramaBrowseService.java`
- `msd-drama/src/main/java/com/msd/drama/service/DramaSearchService.java`
- `msd-drama/src/main/java/com/msd/drama/mapper/DramaMapper.java`
- `msd-drama/src/main/java/com/msd/drama/mapper/EpisodeMapper.java`
- `msd-drama/src/main/java/com/msd/drama/mapper/DramaCategoryMapper.java`
- `msd-drama/src/main/java/com/msd/drama/mapper/DramaTagMapper.java`
- `msd-drama/src/main/java/com/msd/drama/entity/Drama.java`、`DramaEpisode.java`、`DramaCategory.java`、`DramaTag.java`
- `msd-drama/src/main/java/com/msd/drama/dto/HomeFeed.java` 等 9 个 record

## 4. 验收记录（2026-10-24）

环境：MySQL 3306、Nacos 8848、msd-user 8101、msd-drama 8102、msd-gateway 8000。

| 用例 | 结果 |
|---|---|
| `GET /api/drama/internal/home` | 200，banners 3 条，hot/new 各 6 条，categories 7 条 |
| 热播顺序 | 5005、5004、5008、5006、5010、5007 |
| 最新顺序 | 5010、5009、5008、5007、5006、5005 |
| 首页可见性 | 未出现 5003、5011、5012、5013 |
| `GET /api/drama/internal/dramas/5005` | 200，分类「古装」，标签「悬疑/复仇」，演员 2 人，剧集 6 条 |
| 详情计数 | 与 `t_drama` 行一致（204/4/2/1） |
| 详情视频字段 | 响应中无 `videoUrl` / `video_url` |
| `/dramas/5003`（审核中） | 404 `40400` |
| `/dramas/5011`、`5012`、`5013` | 404 `40400` |
| `/dramas/999999` | 404 `40400` |
| 不带 Token / 非法 Token | 401「未登录」/「Token 无效或已过期」 |
| 下线剧集过滤 | 临时把 6402 置为 `status=0`，详情返回 5 条；已恢复 `status=1` |
| 回归 | `/stats`、`/episodes`、`/internal/episodes/6401` 均正常 |

说明：种子数据中 5005 的计数是 203/3/2/1，验收时库里已是 204/4/2/1（此前互动测试产生），接口与数据库一致。

### 4.1 搜索/分类（2026-10-26）

> 以下结果未导入可选的 `deploy/mysql/seed/ai_drama_test.sql`。导入 9 部 AI 短剧后，默认总数变为 18，搜索结果会包含 5101-5109。

| 用例 | 结果 |
|---|---|
| `GET /api/drama/internal/categories` | 200，7 个启用分类 |
| 默认搜索 | 200，`total=9`，`pageSize=12` |
| `keyword=总裁` | 200，命中 5001「隐婚总裁的甜妻」 |
| `keyword=长安` | 200，命中 5005「长安第一女仵作」 |
| `keyword=沈知意`（演员） | 200，命中 5005 |
| `categoryId=2002&sort=hot` | 200，5004、5001，按加权热度降序 |
| `pageNo=2&pageSize=3` | 200，返回 5007、5006、5005 |
| `keyword=%` | 200，`total=0`，通配符已转义 |
| `keyword=不存在的剧名xyz` | 200，`total=0` |
| `sort=random` | 400 `排序方式只能为 latest 或 hot` |
| 51 字关键词 | 400 `搜索关键词不能超过 50 字` |
| `categoryId=0` | 400 `分类 ID 不合法` |
| `pageSize=999` | 200，按 50 截断 |
| 前端 `/search` 与懒加载资源 | 200 |

## 5. 不在本切片

- 个性化推荐、继续观看；搜索已由 P4 提供，但不做语义搜索、搜索历史、热词、拼音纠错和 ES 高亮。
- 用户点赞/收藏状态、作者主页、评论列表。
- 播放授权已由 order 的 playback 接口提供，见 [playback.md](playback.md)；详情仍不返回视频地址。
- 用户解锁状态用 order 的 `/orders/dramas/{dramaId}/unlocks` 查询，不放进详情。
- 热度物化表；首页/详情已接入 Caffeine + Redis 多级缓存、布隆过滤和击穿锁，见 [cache.md](cache.md)。
