# AI 伴看

AI 伴看让用户在看当前集时，只基于当前集摘要和当前集之前的前情回答问题，并支持多轮对话记忆压缩。

## 1. 调用链

```text
AgentScope companion-agent
  ├─ get_episode_summary(episodeId)
  │    → GET 8107 /internal/ai/companion/tools/episodes/{id}/summary
  └─ search_drama_context(query, currentEpisodeId)
       → POST 8107 /internal/ai/companion/tools/context

Vue / 客户端
  → 网关 /api/content/internal/ai/companion/chat
  → 网关 /api/content/internal/ai/companion/chat/stream（SSE）
  → 网关 /api/content/internal/ai/companion/recap（前情提要）
  → content-service CompanionChatService
     ├─ 当前集摘要
     ├─ 当前集之前的前情
     ├─ DeepSeek 生成回复
     └─ SummarizingChatMemory（最近 20 条 + Redis 历史摘要）
```

依赖方向：`agent-service → content-service → drama`。drama 不反调 content。

## 2. 内部接口

### 2.1 当前集摘要

```http
GET /internal/ai/companion/tools/episodes/{episodeId}/summary
```

```json
{
  "episodeId": 6005,
  "dramaId": 5001,
  "episodeNo": 5,
  "episodeTitle": "第5集 告白",
  "dramaTitle": "隐婚总裁的甜妻",
  "summary": "顾沉舟在雨夜坦白心意……",
  "source": "seed",
  "degraded": false,
  "degradedFlags": []
}
```

- `source`：`seed` 已有摘要，`generated` 本次生成，`missing` 缺失或失败，`generating` 其他实例生成中，`locked` 付费集未解锁。
- 剧集不可见时 `data=null`。
- 带 `X-User-Id` 时校验付费集；没有用户身份时只返回免费集摘要。
- 摘要缺失时走 DB claim，只有认领成功的实例调用轻量模型生成并回写。

### 2.2 前情检索

```http
POST /internal/ai/companion/tools/context
```

```json
{"query":"项目被截胡","currentEpisodeId":6005,"topK":3}
```

```json
{
  "currentEpisodeId": 6005,
  "dramaId": 5001,
  "currentEpisodeNo": 5,
  "items": [
    {
      "episodeId": 6003,
      "episodeNo": 3,
      "episodeTitle": "第3集 危机",
      "summary": "苏晚负责的项目被对手截胡……",
      "score": 0.0328,
      "retrievalSources": ["vector", "bm25"],
      "pricePoints": 30
    }
  ],
  "degraded": false,
  "degradedFlags": [],
  "elapsedMs": 1187
}
```

- 只返回 `docType=episode`、同一 `dramaId`、`episodeNo < currentEpisodeNo` 的文档。
- 检索后再校验 `episodeId < currentEpisodeId`，防止索引数据异常导致剧透。
- 带 `X-User-Id` 时过滤未解锁的付费集；没有用户身份时只保留免费集。
- 第 1 集直接返回空列表，不调用检索。
- ES 中没有该剧分集文档时标记 `episode_index`，调用方不得编造前情。

### 2.2.1 字幕片段检索（P2-1）

字幕/ASR 导入后，content 建立 `docType=transcript` 索引。伴看请求可带 `positionMs`：

- 前几集字幕：全部参与检索，但付费集仍需已解锁。
- 当前集字幕：只接受 `endMs <= positionMs` 的片段，未播放到的内容不进入上下文。
- 未来集字幕：检索层限定 `episodeNo <= 当前集`，后置校验再丢弃。
- 回答引用 `type=transcript`，带 `startMs/endMs`；前端点击可 seek 到对应时间。

```json
{
  "message": "第 3 集结尾发生了什么？",
  "sessionId": "sec8-demo",
  "episodeId": 6005,
  "positionMs": 180000
}
```

- `positionMs` 是客户端上报的播放位置，只用于剧集内防剧透，不作为权限依据。
- 当前集是否可看仍由 order 解锁校验决定；未解锁时整集伴看返回 `locked=true`。

### 2.3 伴看聊天

```http
POST /api/content/internal/ai/companion/chat
Authorization: Bearer <token>
```

```json
{"message":"这一集他们为什么突然和好了？","sessionId":"sec8-demo","episodeId":6005}
```

```json
{
  "reply": "……",
  "sessionId": "sec8-demo",
  "episodeId": 6005,
  "degraded": false,
  "degradedFlags": [],
  "elapsedMs": 2496,
  "requestId": "4d1f...",
  "locked": false,
  "sources": [
    {
      "type": "current",
      "episodeId": 6005,
      "episodeNo": 5,
      "episodeTitle": "第5集 告白",
      "summary": "顾沉舟在雨夜坦白心意……"
    },
    {
      "type": "previous",
      "episodeId": 6003,
      "episodeNo": 3,
      "episodeTitle": "第3集 危机",
      "summary": "苏晚负责的项目被对手截胡……"
    }
  ]
}
```

- `message` 1-1000 字，`sessionId` 只允许字母、数字、下划线和短横线。
- 网关注入 `X-User-Id`；直连缺少该头时返回 400。
- 会话 ID：`u{userId}:d{dramaId}:s{sessionId}`，换集仍保持连续。
- 整体超时 30 秒，超时返回固定兜底文案。
- 付费集必须在后端校验 order 解锁状态：免费集直接放行；付费集未解锁返回 `locked=true`，不读摘要、不检索前情、不调用模型；order 不可用时付费集 fail-closed。
- 前情检索只保留免费集和已解锁付费集；旧索引需要重建一次以写入 `pricePoints` metadata。
- 回答经过 `AiOutputGuard`：隐藏手机号、身份证、邮箱和社交账号，拦截刷单/私下转账等明显引流话术，超长回答截断。
- 登录用户可按 `requestId` 提交 `COMPANION` 反馈；匿名或锁定状态不展示反馈。
- `sources` 包含当前集摘要、实际检索到的前情和字幕片段，供前端展开核对；`type=current/previous/transcript`，字幕来源带 `startMs/endMs`。

### 2.4 流式伴看

```http
POST /api/content/internal/ai/companion/chat/stream
Accept: text/event-stream
Authorization: Bearer <token>
```

请求体与 `/chat` 一致。事件顺序：

```text
event: meta    data: {"requestId":"...","sessionId":"...","episodeId":6005,"rewrittenQuery":""}
event: delta   data: {"text":"顾沉舟"}
event: delta   data: {"text":"其实早就..."}
event: sources data: {"items":[{"type":"current","episodeId":6005,...}]}
event: done    data: {"degraded":false,"degradedFlags":[],"elapsedMs":2496,"locked":false}
event: error   data: {"code":"timeout","message":"..."}
```

- 流式输出经过 `AiStreamSanitizer`：保留 64 字符尾部再做敏感信息匹配，避免手机号/邮箱被 chunk 边界切断；超过 1200 字截断。
- 付费集、剧集不可见、drama 不可用等固定回复仍返回 `meta → delta → done`，不调用模型。
- 生成失败返回 `error` 帧，前端可回退普通 JSON 接口；客户端断开时取消上游订阅。
- 记忆 Advisor 在流结束后写入会话记忆。
- 新增指标 `ai_first_token_seconds{scene}`，记录首增量等待时间。

### 2.5 前情提要/本集看点

```http
GET /api/content/internal/ai/companion/recap?dramaId=5001&episodeNo=5
Authorization: Bearer <token>
```

```json
{
  "dramaId": 5001,
  "episodeNo": 5,
  "current": {
    "episodeId": 6005,
    "episodeNo": 5,
    "title": "第5集 告白",
    "summary": "顾沉舟在雨夜坦白心意……"
  },
  "previous": [
    {"episodeId": 6003, "episodeNo": 3, "title": "第3集 危机", "summary": "苏晚负责的项目被对手截胡……"}
  ],
  "degraded": false,
  "degradedFlags": []
}
```

- 只返回 `episodeNo <= 当前集` 的分集，未来集由 drama SQL 和服务层双重过滤。
- 当前集付费未解锁时 `current=null`，标记 `locked`；未登录只返回免费集。
- 前情只保留免费或已解锁分集，按集数升序取最近 5 条（`msd.ai.companion.recap.max-previous`）。
- 摘要缺失时标记 `summary_missing`，不触发生成，避免详情页等待模型。
- 前端在 Feed 右侧「看点」、桌面工作区「看点」tab 展示，点击前情可跳到对应剧集。

### 2.6 前端伴看面板

- Feed 右侧操作栏增加“伴看”按钮；当前集免费或已解锁时才打开，未解锁只提示。
- [CompanionPanel](../frontend/src/components/companion/CompanionPanel.vue) 负责消息列表、快捷问题、发送状态和降级提示。
- 等待期间显示阶段文案和秒数；支持停止、超时重试、继续追问、复制回答和降级说明。
- 优先走 SSE，首帧前失败自动回退普通 JSON；收到 delta 后直接追加文本，不再做客户端打字机。
- 回答下方可展开 `sources`（本集摘要/第 X 集前情），并保留 `requestId` 反馈。
- `locked=true` 时展示解锁提示，不展示摘要或回答；后端仍是最终权限边界。
- 本地多会话：`sessionStorage` 的 `msd.companion.sessions.u{userId}.{dramaId}` 保存最多 10 个会话，含标题、更新时间和最多 50 条消息；按用户隔离，旧的共享 key 不再读取。
- 打开面板时暂停播放并上报进度，与评论/选集弹层一致。
- “新会话”保留旧会话并新建一个，服务端旧会话按 TTL 自然过期。

### 2.7 摘要批量预热

```http
POST /internal/ai/companion/summaries/warmup?dramaId=5002&limit=1
```

```json
{"code":0,"message":"成功","data":{"scanned":1,"generated":1,"skipped":0,"failed":0,"degradedFlags":[]}}
```

- 只允许直连 8107，网关不路由。
- drama 先返回该剧缺失摘要且可重试的在线剧集 ID，content-service 逐集复用 claim、生成、回写和单集索引。
- `limit` 上限 20；单集失败不中断整批，`degradedFlags` 汇总降级标记。

## 3. AgentScope 工具

Supervisor 根据 `AGENTS.md` 把伴看问题路由到 `companion-agent`，子 Agent 工具由 [CompanionTools](../msd-agent-service/src/main/java/com/msd/agent/tool/CompanionTools.java) 注册：

| 工具 | 入参 | 返回 |
|---|---|---|
| `get_episode_summary` | `episodeId` | `[EPISODE]`、`[SUMMARY]`、`[SOURCE]`、`[NOTICE]` |
| `search_drama_context` | `query`、`currentEpisodeId` | `[CONTEXT]`、`[1..N]`、`[NOTICE]` |

- 失败统一返回 `[NOT_FOUND]`、`[SUMMARY_MISSING]`、`[CONTEXT_EMPTY]`、`[DEGRADED]`，不抛异常。
- `POST /api/agent/internal/ai/agent/chat` 可选传 `episodeId`；服务端会在用户消息前加 `[当前剧集ID=...]`，供 companion-agent 直接使用。
- `companion-agent.md` 要求先查当前集摘要，再按需检索前情，严禁提及后续剧情。
- 实测 Supervisor 路由到 `companion-agent`，并行调用两个工具；回复只引用第 1-4 集，未出现第 6 集或大结局。

## 4. 防剧透

1. **索引过滤**：Milvus/ES 都限制 `docType=episode`、`dramaId=当前剧`、`episodeNo<当前集`。
2. **后置校验**：再次检查 metadata 的剧集、集数和 `episodeId<currentEpisodeId`。
3. **当前集摘要隔离**：当前集摘要只由摘要接口返回，前情检索永远不返回当前集及以后。
4. **字幕时间轴隔离**：当前集字幕只返回 `endMs <= positionMs` 的片段；未来集在检索过滤和后置校验中双重丢弃。
5. **付费前情隔离**：字幕前情复用 order 解锁结果，未解锁付费集不会进入模型上下文或 sources。
4. **Prompt 约束**：系统提示明确禁止提及、猜测、暗示后续剧情；用户追问统一回复「为了不剧透，我只陪你看到当前这一集。」

## 5. 会话记忆

### 5.1 Redis 结构

```text
key:   msd:companion:memory:u1003:d5001:ssec8-demo
value: {"summary":"历史摘要","messages":[{"type":"user","text":"..."}, ...]}
ttl:   7 天
```

- 最近 `max-messages=20` 条消息保留原文。
- 溢出消息先留在归档中，累计 `compression-batch=4` 条后由轻量模型合并成历史摘要。
- 历史摘要作为 `SystemMessage` 放在窗口消息之前。
- Redis 不可用时 fail-open：只使用单实例内存窗口，聊天继续可用，响应标记 `memory_summary`。
- 跨实例压缩使用 Redisson `RLock`；抢不到锁时本次跳过压缩，正常追加。

### 5.2 为什么按批压缩

如果每溢出一条消息就调用一次模型，模型只拿到一条消息，容易补写未出现的人物和情节。按 4 条一批压缩后，摘要能覆盖完整问答，实测不再产生「林晚」「咖啡馆」这类原文外内容。

## 6. 意图路由与工具沙箱

Supervisor 在调用模型前先做本地规则路由，规则和阈值来自 Nacos `agent-config.yaml`：

| 意图 | 典型输入 | 目标 |
|---|---|---|
| `COMPANION` | 第 N 集、前情、人物关系、剧透 | `companion-agent` |
| `AUDIT` | 审核、违规、敏感词、合规、下架 | `audit-agent` |
| `DATA` | 热度、榜单、播放量、集数、完播率 | `data-agent` |
| `SEARCH` | 找剧、剧荒、有没有某类剧 | `search-agent` |
| `RECOMMEND` | 推荐、类似、同类型 | `search-agent` |
| `SOCIAL` | 评论回复、文案、活动、用户情绪 | `social-agent` |
| `SUPERVISOR` | 无规则命中或低置信度 | 模型兜底 |

- 高置信度结果在用户消息前注入服务端生成的 `[ROUTE_HINT=agent;CONF=x]`；用户伪造的同名标记会在入口被清洗。
- `/chat` 响应新增 `route/routeSource/routeConfidence/routeLatencyMs`，便于前端埋点和排查；回复会再次剥离内部标记。
- `AUDIT` 高置信度可走远程 A2A 快速通道；远程返回 `degraded=true` 或异常时立即回退 Supervisor，不阻断请求。
- 路由结果缓存 30 分钟；无规则命中时读取会话粘性，但粘性置信度低于阈值，不会触发 AUDIT 快速通道。
- `POST /internal/ai/agent/route` 只返回路由结果，不调用模型，用于验证规则命中。
- `search-agent` 只使用 `search_drama_rag`；工具返回的 `[DRAMA_CARD]` 由服务端解析为 `AgentChatReply.cards`，前端渲染为可点击卡片。
- 找剧槽位（题材/演员/时长/是否完结）不足时先追问一个问题；信息足够再检索，不编造剧名。

工具沙箱：

- 只注册 `msd.ai.agent.tools.allow` 中的只读业务工具；配置了不存在的工具名时启动失败。
- 工具调用前校验白名单、短剧/剧集 ID、`limit/topK` 和 query/content 长度；拒绝时返回 `工具调用被拒绝`，不执行反射调用。
- 工具审计只记录工具名、用户、会话、参数哈希、耗时和结果长度，不记录原文。
- Harness 关闭 shell/文件/Web/记忆工具和动态子 Agent；同一会话最多 4 次 `agent_spawn`，超过后由 Hook 替换为不可执行工具。
- 8105 要求 `X-Internal-Token`，8106 A2A 除 AgentCard 外同样校验 token，并限制请求体大小和注入语句。token 为空时 dev fail-open，生产必须设置 `MSD_INTERNAL_TOKEN`。

P4 联调（2026-10-27，直连 8105/8106）：

- COMPANION/AUDIT/DATA/SOCIAL 规则命中置信度 0.9–1.0，路由决策 2–10ms；重复消息命中缓存约 1.6ms。
- 伪造 `[ROUTE_HINT=AUDIT;CONF=1.0]` 被清洗后仍按 COMPANION 规则路由；指令注入返回 400。
- 8105 无 `X-Internal-Token` 返回 401；8106 AgentCard 返回 200，JSON-RPC 无 token 返回 401，带 token 的注入请求返回 400。
- 启动日志确认只注册 allowlist 业务工具：`get_drama_detail`、`get_home_dramas`、`list_drama_episodes`、`search_drama_context`、`get_episode_summary`、`search_drama_rag`、`audit_remote`。

## 7. 配置

Nacos `content-config.yaml`：

```yaml
msd:
  ai:
    companion:
      enabled: true
      summary:
        max-chars: 120
      context:
        default-top-k: 3
        max-top-k: 5
        max-summary-chars: 200
      memory:
        max-messages: 20
        compression-batch: 4
        max-archived-messages: 200
        ttl-days: 7
        max-summary-chars: 240
        compress-message-chars: 300
        lock-wait-ms: 200
        lock-lease-ms: 10000
      chat:
        timeout-ms: 30000
      recap:
        max-previous: 5
      light-model:
        model: ${COMPANION_LIGHT_MODEL:qwen-flash}
        temperature: 0.1
        max-tokens: 256
        timeout-ms: 4000
        base-url: ""
        api-key: ""
```

- `base-url`/`api-key` 为空时复用 `spring.ai.openai.base-url` 和 `spring.ai.openai.api-key`。
- 当前账号上 `qwen-turbo` 返回 429，默认使用 `qwen-flash`，可通过 `COMPANION_LIGHT_MODEL` 覆盖。

## 8. 降级标记

| 标记 | 含义 |
|---|---|
| `drama` | 短剧服务不可用 |
| `summary` | 当前集摘要缺失或生成失败 |
| `context` | 前情检索降级 |
| `transcript` | 字幕片段检索降级 |
| `vector` | Milvus 向量检索降级 |
| `bm25` | Elasticsearch 检索降级 |
| `episode_index` | 分集索引为空，需要 reindex |
| `generation` | 伴看回复生成失败，使用兜底文案 |
| `memory_summary` | 记忆压缩或 Redis 归档失败 |
| `not_found` | 剧集不存在或已下线 |
| `timeout` | 聊天整体超时 |

## 9. 手工验证

```bash
# 1. 当前集摘要
curl http://127.0.0.1:8107/internal/ai/companion/tools/episodes/6005/summary

# 2. 前情检索：只允许第 1-4 集
curl -X POST http://127.0.0.1:8107/internal/ai/companion/tools/context \
  -H 'Content-Type: application/json' \
  -d '{"query":"项目被截胡","currentEpisodeId":6005,"topK":3}'

# 3. 伴看聊天（经网关）；positionMs 用于当前集字幕防剧透
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/companion/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"顾沉舟为什么突然告白？","sessionId":"sec8-demo","episodeId":6005,"positionMs":180000}'

# 4. 流式聊天（-N 关闭 curl 缓冲）
curl -N -X POST http://127.0.0.1:8000/api/content/internal/ai/companion/chat/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"顾沉舟为什么突然告白？","sessionId":"sec8-demo","episodeId":6005}'

# 5. 前情提要：只返回第 1-5 集
curl 'http://127.0.0.1:8000/api/content/internal/ai/companion/recap?dramaId=5001&episodeNo=5' \
  -H "Authorization: Bearer $TOKEN"

# 6. 连续 12 轮后检查 Redis 记忆
redis-cli -h 192.168.33.129 -a "$REDIS_PASSWORD" get \
  'msd:companion:memory:u1003:d5001:ssec8-demo'
```

实测（2026-10-25）：

- 12 轮聊天后 Redis 保留 20 条消息 + 143 字历史摘要，TTL 7 天。
- 摘要包含顾沉舟、苏晚、第 3/4 集事实，没有原文外人物。
- 全部回复未出现「第 6 集」「大结局」等未来剧情。
- 前情检索同时命中 `vector` 和 `bm25` 时仍只返回第 1-4 集。
- 停掉 ES：`degradedFlags=["bm25"]`，仅返回向量结果；恢复后标记消失。
- 停掉 Milvus：`degradedFlags=["vector"]`，仅返回 BM25 结果；恢复后标记消失。
- 同时停掉 ES 和 Milvus：items 为空，`degradedFlags=["vector","bm25"]`；Agent 明确回复「查不到前情」，未编造剧情。
- Redis 指向不可用端口：聊天仍返回正常回复，`degradedFlags=["memory_summary"]`，耗时约 4.5 秒。
- 第 1 集前情为空且无降级标记；不存在的剧集，摘要和上下文均返回 `data=null`。
- 非法 `sessionId`、空 `message`、`episodeId=0` 均返回 400；网关访问 tools 路径返回 404。
- 摘要预热 `dramaId=5002&limit=1` 返回 `generated=1`，再次查询候选为空，6104 摘要已落库。
- 前端伴看面板已通过 `npm run build`，网关静态资源包含“AI 伴看”入口。
