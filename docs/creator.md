# 创作者工作台与审核操作

创作者可以创建草稿短剧、编辑剧集、提交审核；管理员在审核台查看 AI 结果、通过/驳回、下架和重建索引。仅草稿或驳回状态可编辑，已上架内容需先下架。

## 1. 创作者接口

经网关访问，路径前缀 `/api/drama/internal/creator/dramas`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 分页查询我的短剧 |
| GET | `/{dramaId}` | 短剧详情和剧集，供编辑页回填 |
| POST | `/` | 创建草稿短剧和剧集 |
| PUT | `/{dramaId}` | 编辑草稿或驳回短剧 |
| DELETE | `/{dramaId}` | 软删除草稿或驳回短剧 |
| POST | `/{dramaId}/submit` | 提交审核 |
| POST | `/api/content/internal/ai/audit/precheck` | 提交前 AI 预检，不写审核状态 |
| POST | `/api/content/internal/ai/copilot/generate` | 生成多版本文案，只读，不写审核状态 |
| POST | `/{dramaId}/episodes/{episodeId}/subtitles` | 导入 SRT/VTT 字幕，触发 transcript 索引 |

创建/编辑请求：

```json
{
  "title": "雾城谜案",
  "subtitle": "每一通深夜来电，都藏着一个秘密",
  "coverUrl": "",
  "description": "刑警追查十年前旧案。",
  "categoryId": 2003,
  "actors": "赵远,许晴",
  "episodes": [
    {"episodeNo": 1, "title": "第1集 深夜来电", "durationSeconds": 135, "pricePoints": 0, "status": 1},
    {"episodeNo": 2, "title": "第2集 失踪", "durationSeconds": 150, "pricePoints": 20, "status": 1}
  ]
}
```

- 作者固定为当前用户；`CREATOR` 只能操作自己的短剧，`ADMIN` 不限。
- 剧集按 `episode_no` upsert；本次未提交的旧剧集置为下线，不物理删除。
- `video_url` 固定为空字符串，Demo 不存真实视频。
- 编辑后失效详情/首页缓存。

### 1.1 字幕导入（P2-1）

编辑页“字幕导入”区选择一个已保存剧集和 SRT/VTT 文件：

```http
POST /api/drama/internal/creator/dramas/{dramaId}/episodes/{episodeId}/subtitles
Content-Type: application/json

{
  "language": "zh",
  "format": "SRT",
  "content": "1\n00:00:01,000 --> 00:00:03,000\n你好\n"
}
```

- 只允许 `CREATOR` 本人或 `ADMIN`；剧集必须属于该短剧。
- 服务端解析时间轴，按 `episode_id + language` 覆盖旧字幕，并替换全部片段。
- 入库前做确定性安全过滤：手机号/邮箱/微信做掩码，明显引流话术整条丢弃。
- 写入成功后发布 `msd.transcript.changed.v1`，content 重建该集 transcript 索引。
- 编辑页需先保存草稿拿到 `episodeId`，再导入字幕。

离线 ASR：

```bash
pip install -r scripts/requirements-asr.txt   # 需要系统 ffmpeg
python scripts/transcribe-episode.py --video D:/media/6005.mp4 --episode-id 6005
```

脚本使用 faster-whisper 本地转写，再回传 `POST /internal-source/episodes/{episodeId}/transcript`；不进入在线播放链路，转写结果建议人工抽检。

### 1.2 创作者 Copilot（P2-4）

编辑页“AI 创作助手”可一次生成 2-3 个不同角度的标题/副标题/标签方案：

```http
POST /api/content/internal/ai/copilot/generate
Content-Type: application/json

{
  "title": "隐婚总裁的甜妻",
  "subtitle": "先婚后爱",
  "description": "普通女孩苏晚意外闪婚商圈传奇顾沉舟。",
  "actors": "林一,苏晚",
  "episodeTitles": ["第1集 闪婚", "第2集 同居"],
  "tone": "甜宠",
  "count": 3
}
```

响应每个版本包含 `title/subtitle/tags/angle/rationale`，另有 `tips` 合规提醒和 `requestId`。

- 只允许 `CREATOR` 或 `ADMIN`；`CREATOR` 仍只能为自己编辑中的短剧使用。
- 每日限额默认 20 次；相同素材、风格和版本数缓存 10 分钟。
- 生成结果不写 `t_drama`、不写 `ai_review_status`、不写 `t_drama_tag_rel`；创作者点击“采用整套/用标题/用副标题”才回填表单。
- 每个版本可单独点“合规检查”，复用 P1-6 的 AI 预检；预检失败不自动通过。
- 首次采纳会上报 `scene=CREATOR_COPY, reason=ADOPT` 反馈，用于统计采纳率；反馈失败不影响编辑。
- 模型失败返回空版本和 `generation` 降级标记，不编造文案。

## 2. 管理端

| 方法 | 路径 | 角色 |
|---|---|---|
| GET | `/api/drama/internal/admin/dramas/reviews?reviewStatus=&aiReviewStatus=&pageNo=&pageSize=` | ADMIN |
| POST | `/api/drama/internal/admin/dramas/{id}/review` | ADMIN |
| POST | `/api/drama/internal/admin/dramas/{id}/offline` | ADMIN |
| GET | `/api/drama/internal/admin/dramas/{id}/logs` | ADMIN |
| POST | `/api/content/internal/ai/audit/dramas/{id}` | ADMIN（经网关） |
| POST | `/api/content/internal/ai/rag/reindex` | ADMIN（经网关） |

- 审核通过/驳回/下架使用条件 UPDATE，冲突返回 409。
- 通过后失效缓存、加入布隆过滤器；通过/下架会在事务提交后发送 `DRAMA_CHANGED`，content 自动增量更新 RAG 索引。

## 3. 审核操作日志

`t_drama_review_log` 记录：

| 字段 | 说明 |
|---|---|
| `action` | SUBMIT / APPROVE / REJECT / OFFLINE / AI_REVIEW |
| `from_status` / `to_status` | 人工审核状态或 AI 审核状态 |
| `operator_id` | 人工操作人；AI 为 0 |
| `source` | HUMAN / AI |
| `remark` | 审核意见 |

AI 写回和人工状态迁移在同一套日志里，审核台可查看最近 50 条。

## 4. 前端

| 路由 | 页面 | 角色 |
|---|---|---|
| `/creator/dramas` | 我的短剧 | CREATOR / ADMIN |
| `/creator/dramas/new` | 新建短剧 | CREATOR / ADMIN |
| `/creator/dramas/:id/edit` | 编辑短剧与剧集 | CREATOR / ADMIN |
| `/admin/reviews` | 审核台：AI 审核、通过/驳回/下架、日志、重建索引 | ADMIN |

个人中心根据角色显示“创作者中心”“审核台”“评论治理”入口；后端仍做最终权限校验。

- 创作者列表和编辑页解析 `aiReviewResult`，展示 AI 违规原因、命中标签和 Rex 建议标签；AI 失败只提示等待人工复核，不展示技术 flags 或原始 JSON。
- 编辑页增加“AI 预检”：提交前检查合规风险，展示标题/副标题建议、标签建议和整改建议；点击建议只会填入表单，不会自动提交或写回审核状态。文案建议失败不影响合规结论。
- 编辑页增加“AI 创作助手”：按风格生成 2-3 个标题/副标题/标签方案，展示文案角度和推荐理由；采用后仍可编辑，可对单个版本执行合规检查。
- 管理员驳回后如果 AI 没有给出明确违规原因，页面显示“作品已被驳回，请根据审核意见修改后重新提交”。

## 5. v1 边界

- 不做富文本、素材库、视频上传、标签管理页、审核撤回。
- 已上架内容不能直接编辑，需先下架。
- AI 预检只给建议，不自动修改标题、简介、标签，也不改变审核状态。
- Copilot 只生成 2-3 个候选方案，不做自动 A/B 发布；采纳率通过反馈表统计，不做在线分流实验。
- 标签建议目前只展示，短剧标签关系仍需人工维护。
- 字幕/ASR 只用于剧情问答和引用，不改变视频地址、审核状态或公开播放权限。
- 全量重建索引在运行期间可能短暂影响推荐结果；日常上架/下架走增量索引。
