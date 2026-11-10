# AI 短剧测试数据

`deploy/mysql/seed/ai_drama_test.sql` 提供 9 部 AI 短剧的可选测试数据，用于搜索、分类、详情、Feed、解锁、收藏和真实视频播放链路。默认初始化脚本不包含这些数据，按需导入。

原先的 `real_drama_test.sql`（真实短剧元数据）已被本文件取代，保留作历史参考。

## 1. 数据范围

| ID | 剧名 | 题材 | 分类 | 来源 |
|---|---|---|---|---|
| 5101 | 殷商传奇 | 历史 / 古装 | 古装 | [BV1cBaU6WECQ](https://www.bilibili.com/video/BV1cBaU6WECQ) |
| 5102 | 局中人 | 民俗悬疑 | 悬疑 | [BV1H3bP6pEMm](https://www.bilibili.com/video/BV1H3bP6pEMm) |
| 5103 | 十日终焉 | 悬疑 / 轮回 | 悬疑 | [BV1wjEY6jE54](https://www.bilibili.com/video/BV1wjEY6jE54) |
| 5104 | 认错相亲对象怎么办 | 甜宠 / 喜剧 | 甜宠 | [BV18YHV6CEmG](https://www.bilibili.com/video/BV18YHV6CEmG) |
| 5105 | 考研倒计时 | 校园 | 校园 | [BV1b9dJBVEbg](https://www.bilibili.com/video/BV1b9dJBVEbg) |
| 5106 | 余波 | 丧尸 / 亲情 | 悬疑 | [BV1JGJy6rEU5](https://www.bilibili.com/video/BV1JGJy6rEU5) |
| 5107 | 末日长途 | 末日公路 | 悬疑 | [BV1CnnS6eEvD](https://www.bilibili.com/video/BV1CnnS6eEvD) |
| 5108 | 一觉醒来，我来到了一百年后 | 科幻 | 玄幻 | [BV1WPn86yEB2](https://www.bilibili.com/video/BV1WPn86yEB2) |
| 5109 | 全家魔丸 | 家庭 / 喜剧 | 都市 | [BV1GynP6HE3i](https://www.bilibili.com/video/BV1GynP6HE3i) |

抓取时间：2026-10-26。标题、简介为测试改写，演员字段留空。

## 2. 数据适配

- 每部 6 集，剧集 ID 为 `7101`-`7154`。
- 前 2 集免费，后 4 集价格为 20/30/30/50 积分。
- 视频从源片前几分钟切分，每集 14-28 秒，`duration_seconds` 与文件时长一致。
- 封面为源视频封面裁成 3:4，存放在 MinIO。
- 短剧计数为演示用模拟值，首次导入写入；重复导入只更新元数据，不覆盖已有计数。
- 视频只用于本地演示，不进入仓库，也不随项目分发。

## 3. 导入

```bash
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 \
  < deploy/mysql/seed/ai_drama_test.sql
```

验证：

```sql
SELECT COUNT(*) FROM t_drama WHERE id BETWEEN 5101 AND 5109;              -- 9
SELECT COUNT(*) FROM t_drama_episode WHERE drama_id BETWEEN 5101 AND 5109; -- 54
SELECT COUNT(*) FROM t_drama_tag_rel WHERE drama_id BETWEEN 5101 AND 5109; -- 19
```

## 4. 媒体文件

媒体只存在本地 MinIO 的 `msd` 桶，仓库里没有视频和封面：

```text
msd/covers/ai/5101.jpg ... 5109.jpg
msd/videos/ai/5101-01.mp4 ... 5109-06.mp4
```

桶需要允许匿名读，验证：

```bash
curl -I http://127.0.0.1:9000/msd/covers/ai/5101.jpg    # 200 image/jpeg
curl -I http://127.0.0.1:9000/msd/videos/ai/5101-01.mp4 # 200 video/mp4
```

MinIO 数据卷被清空后需要重新上传；未上传时前端自动回退渐变封面，播放器显示封面占位，不影响其他功能。

接口验证：

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/drama/internal/dramas/5101

# 播放信息会返回真实视频地址（免费或已解锁）
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/playback/episodes/7101
```

## 5. 布隆过滤器

短剧详情查询前置布隆过滤器。如果是在已有 5101-5109 的基础上更新数据，ID 已存在，无需处理；如果是新库首次导入，等待 xxl-job `dramaCacheRebuildHandler` 下一次执行（每 5 分钟），或在控制台手动触发“短剧缓存重建”。

重建完成后日志出现：

```text
bloom rebuild completed: total=18, added=18, ...
```

搜索接口不经过布隆过滤器，导入后可立即使用。

## 6. 清理

测试数据使用预留 ID 段：

- 短剧：`5101`-`5109`
- 剧集：`7101`-`7154`
- 标签关系：自动 ID，按 `drama_id` 定位

如果未产生互动数据，可按依赖顺序删除：

```sql
DELETE FROM t_drama_tag_rel WHERE drama_id BETWEEN 5101 AND 5109;
DELETE FROM t_drama_episode WHERE drama_id BETWEEN 5101 AND 5109;
DELETE FROM t_drama WHERE id BETWEEN 5101 AND 5109;
```

如果已经产生评论、点赞、收藏、观看历史或解锁记录，需要先清理对应明细并校准计数和积分；Demo 环境更简单的方式是重新执行 `deploy/mysql/init/02`-`05` 初始化脚本。清理后同样执行一次布隆重建。

## 7. 版权说明

视频和封面来自 B 站公开可访问的 AI 短剧投稿，仅用于本地功能演示，不下载到仓库、不再分发。剧名和简介为测试改写；如需在演示以外场景使用，请自行确认原作者授权。
