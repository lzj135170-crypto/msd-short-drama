# RAG 内容推荐

`msd-content-service`（8107）实现短剧 RAG 推荐：查询改写、Milvus 向量检索与 Elasticsearch BM25 并行召回、RRF 融合、DashScope Rerank 精排、基于检索资料的推荐理由生成。

## 1. 架构

```text
msd-drama (MySQL)
   │ GET /internal-source/rag/dramas?page=1&size=200
   ▼
msd-content-service (8107)
   ├─ DramaCatalogClient (Feign)
   ├─ DramaDocumentBuilder → Milvus VectorStore + Elasticsearch BM25
   ├─ LlmQueryTransformer (DeepSeek)
   ├─ HybridRetriever
   │    ├─ Milvus Top 20
   │    └─ ES BM25 Top 20
   │    └─ RrfMerger(k=60) → Top 20
   ├─ DashScopeRerankPostProcessor → Top 3
   └─ RetrievalAugmentationAdvisor + ChatClient → answer + sources
   ▲
   │ POST /api/content/internal/ai/rag/recommend
msd-gateway (JWT)
```

- 不引入 `spring-ai-starter-vector-store-milvus`，避免 Milvus 未启动时应用启动失败。
- 不引入 `spring-boot-starter-web`，服务运行在 WebFlux/Netty 上。
- 与 `msd-agent-service` 的 AgentScope Supervisor/A2A 链路隔离，避免 Jackson/victools 版本冲突。
- 增量索引：drama 审核通过/下架发送 `msd.drama.changed.v1`，content 按 `dramaId` 更新 Milvus/ES，详见 3.1。

## 2. 五段链路

### 2.1 数据向量化

[DramaDocumentBuilder](../msd-content-service/src/main/java/com/msd/content/index/DramaDocumentBuilder.java) 把短剧导出数据转换为 Document：

```text
标题：隐婚总裁的甜妻
副标题：先婚后爱，甜到上头
分类：甜宠
标签：甜宠、重生
演员：林一、苏晚
简介：……（最多 1200 字）
剧集：第1集 闪婚；第2集 同居；……（最多 30 条）
```

- `Document.id = drama-{dramaId}`，Milvus 和 ES 共用同一去重键。
- 短剧 metadata 包含 `docType=drama`、`dramaId`、`title`、`subtitle`、`coverUrl`、`categoryId`、`categoryName`、`tags`、`actors`、`episodeCount`、`viewCount`、`likeCount`、`publishTime`、`snippet`。
- Embedding 使用 `qwen3.7-text-embedding-flash`，1024 维；单批最多 20 条（`msd.ai.rag.vector.batch-size`）。
- 写入前校验/固定维度，collection 为 `msd_drama_rag_v1`。

分集文档：

- `Document.id = episode-{episodeId}`，供 AI 伴看检索当前集之前的前情。
- metadata 增加 `docType=episode`、`dramaId`、`dramaTitle`、`episodeId`、`episodeNo`、`episodeTitle`、`summary`、`snippet`。
- embedding 文本为「剧名 + 第 N 集标题 + 本集摘要」。
- 推荐检索固定过滤 `docType=drama`；前情检索过滤 `docType=episode`、`dramaId`、`episodeNo < 当前集`。
- ES mapping 增加 `docType`、`episodeId`、`episodeNo`、`episodeTitle`、`dramaTitle`、`summary`、`summaryTokens`、`episodeTitleTokens`，旧索引必须全量 reindex。

### 2.2 查询改写

[LlmQueryTransformer](../msd-content-service/src/main/java/com/msd/content/rag/LlmQueryTransformer.java) 使用独立 `rewriteChatClient`（DeepSeek）把口语化需求改写成检索关键词：

```text
有没有那种先婚后爱的甜宠剧
→ 先婚后爱 甜宠
```

- 3 秒超时，失败回退原查询并标记 `rewrite`。
- 失败后 30 秒内不再调用模型。
- 保留 `Query.history` 和 `Query.context`，`RagTrace` 继续传给检索器和精排器。

### 2.2.1 多查询扩展

`msd.ai.rag.expansion.enabled=true` 时，`LlmMultiQueryExpander` 在改写之后生成 1 个额外查询变体；如果 `RagTrace` 带有画像生成的偏好检索词，也会加入。默认最多 3 条（原查询 + 偏好词 + 变体）。

- 每条检索词独立走向量 + BM25，`per-query-top-k` 默认 10，控制下游放大。
- `RrfDocumentJoiner` 对多查询结果按自身排名做跨查询 RRF，按 `Document.id` 去重；单查询时直接返回原结果，保持旧行为。
- 扩展失败/超时回退原查询并标记 `expansion`；偏好不可用时标记 `personalization`。
- 首页「为你推荐」不走多查询，不调用 LLM。

### 2.3 混合检索与 RRF

[HybridRetriever](../msd-content-service/src/main/java/com/msd/content/rag/HybridRetriever.java) 并行执行两路：

- 向量：`LazyMilvusStoreProvider.search(query, 20)`，Milvus `COSINE`，不设相似度阈值。
- BM25：[ElasticsearchBm25Store](../msd-content-service/src/main/java/com/msd/content/rag/ElasticsearchBm25Store.java)，jieba 预分词 + whitespace analyzer，多字段 boost：
  - `titleTokens` 4.0
  - `tagTokens` 3.0
  - `subtitleTokens` 2.5
  - `actorTokens` 2.0
  - `descriptionTokens` 1.2
  - `episodeTokens` 1.0
  - `searchText` 0.5

两路各自限时（向量 3s、BM25 2s），单路失败/超时只标记对应降级。[RrfMerger](../msd-content-service/src/main/java/com/msd/content/rag/RrfMerger.java) 按 `Document.id` 去重并计算：

```text
score += 1 / (k + rank), k = 60
```

输出 Top 20，metadata 增加 `rrfScore`、`vectorRank`、`bm25Rank`、`vectorScore`、`bm25Score`、`retrievalSources`。

### 2.4 Rerank

[DashScopeRerankPostProcessor](../msd-content-service/src/main/java/com/msd/content/rag/DashScopeRerankPostProcessor.java) 调用：

```text
POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
Authorization: Bearer ${DASHSCOPE_API_KEY}
```

请求 Top 20，按 `output.results[].relevance_score` 降序取 Top 3。默认使用用户原始 query 精排，检索使用改写后的关键词。

失败、超时、未配置 Key、非 2xx、解析异常时回退 RRF 前 3 条，标记 `rerank` 并进入 30 秒退避。

### 2.5 生成

[RagConfig](../msd-content-service/src/main/java/com/msd/content/config/RagConfig.java) 装配：

- `rewriteChatClient`：只做查询改写。
- `ragChatClient`：默认挂 `RetrievalAugmentationAdvisor`。
- `ContextualQueryAugmenter`：有上下文/空上下文两套提示，要求只能基于资料、每部给出 1-2 句有依据的理由、不得编造。
- 空上下文模板不能包含 `{query}`：Spring AI 2.0.1 对空上下文模板做无参渲染。

最终返回 `answer` 和最多 `topK` 条 `sources`。回答中的剧名和理由应能在 sources 的标签、简介或剧集中找到依据。

## 3. 索引

| 存储 | 名称 | 说明 |
|---|---|---|
| Milvus collection | `msd_drama_rag_v1` | 1024 维，IVF_FLAT，COSINE，`autoId=false` |
| ES index | `msd_drama_bm25_v1` | 单分片零副本，`dynamic=false`，`jieba_ws` analyzer |

Milvus 2.6.x 的 `common.storageType` 必须为 `remote`；`minio` 已废弃，会触发 Arrow FileSystem 初始化失败并在写入时 panic。`deploy/docker-compose.yml` 已固定为 `remote`。

`POST /internal/ai/rag/reindex` 全量重建：

1. 通过 Feign 分页导出已上架短剧（`deleted=0 AND review_status=2 AND publish_time IS NOT NULL`）。
2. 目录导出失败时不动已有索引。
3. 目录为空且 `allowEmptyCatalog=false` 且已有索引时拒绝清空。
4. Milvus：先删除上一轮和本轮 ID，再 `add`。Spring AI 的 `add` 底层是 insert，不是 upsert。
5. ES：删除并重建 index，逐条 PUT，最后 `_refresh`。
6. 两路互不影响，任一路失败返回 `partial=true` 和 `errors`。
7. `ReentrantLock` 防止并发重建，抢不到返回 409。

### 3.1 增量索引（DRAMA_CHANGED）

审核通过或下架时，drama 在事务提交后向 `msd.drama.changed.v1` 发送事件，content 消费后只更新单部短剧：

1. `UPSERT`：通过 `/internal-source/rag/dramas/{dramaId}` 读取当前已上架状态；短剧已不可见时自动转为删除。
2. Milvus：按 `dramaId` 删除旧向量，再按 `batch-size` 写入短剧和分集文档。
3. ES：`_delete_by_query` 删除旧文档，再逐条 PUT 并 `_refresh`，保证下架后立即不可检索。
4. `DELETE`：按 `dramaId` 删除两路索引，不查询目录。
5. 消费端按 `eventId` 用 Redis 幂等（默认 7 天），按 `dramaId` 去抖 2 秒；晚到的旧事件直接跳过。
6. 索引失败按 `retry-attempts/retry-delay-ms` 重试；仍失败只记录 `rag_index_failure_total`，由全量 reindex 兜底。
7. 指标：`rag_index_upsert_total{action}`、`rag_index_failure_total{action}`、`rag_index_lag_seconds{action}`。
8. 首次部署必须先执行一次全量 reindex：增量消费会检查 ES 索引是否存在，不会用动态 mapping 自动建索引。

上架/下架通常在事件产生后秒级生效；content 停机期间事件保留在 Kafka，恢复后从上次位点继续消费。关闭增量索引：Nacos `msd.ai.rag.incremental.enabled=false`；Kafka 不可用时索引保持最后状态，全量 reindex 不受影响。

### 3.2 字幕索引（TRANSCRIPT_CHANGED）

字幕导入/ASR 回传后，drama 发布 `msd.transcript.changed.v1`，content 重建单集字幕索引：

- ES mapping 新增 `startMs`、`endMs`、`transcriptTokens`；旧索引必须全量 reindex。
- `Document.id = transcript-{episodeId}-{startMs}`，metadata 增加 `docType=transcript`、`dramaId`、`episodeId`、`episodeNo`、`startMs`、`endMs`、`pricePoints`、`transcript`。
- 正文包含剧名、集数、`mm:ss-mm:ss` 时间轴和字幕文本；ES 使用 `transcriptTokens` 预分词字段。
- 写入前按 `episodeId` 删除旧字幕，再写 Milvus/ES；单集无字幕时删除索引。
- 消费端同步处理，失败抛给 Kafka 重试；成功后才写 Redis `eventId` 幂等键。
- 检索限定 `docType=transcript + dramaId + episodeNo <= 当前集`，当前集再按播放位置后置过滤。
- 全量 reindex 会清空并补建全部字幕索引；ES index 被重建时不会丢字幕。
- 指标：`rag_transcript_index_upsert_total{action}`、`rag_transcript_index_failure_total{action}`、`rag_transcript_index_lag_seconds{action}`。

## 4. API

### 4.1 推荐

经网关：`POST /api/content/internal/ai/rag/recommend`  
直连：`POST http://127.0.0.1:8107/internal/ai/rag/recommend`

请求：

```json
{"query":"有没有那种女主很虐的剧","topK":3}
```

- `query` 必填，最长 200。
- `topK` 可选 1-3，默认取 `msd.ai.rag.rerank.top-n`。

响应：

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "answer": "《长安第一女仵作》：女主沈知意验尸破案……",
    "rewrittenQuery": "长安 女仵作 验尸 破案 古装 悬疑",
    "sources": [
      {
        "dramaId": 5005,
        "title": "长安第一女仵作",
        "subtitle": "验尸破案，顺便谈个恋爱",
        "coverUrl": "http://127.0.0.1:9000/msd/covers/5005.jpg",
        "snippet": "长安城接连出现离奇命案……",
        "score": 0.93,
        "rrfScore": 0.0328,
        "vectorRank": 1,
        "bm25Rank": 3,
        "retrievalSources": ["vector", "bm25"]
      }
    ],
    "degraded": false,
    "degradedFlags": [],
    "elapsedMs": 3200,
    "requestId": "4d1f..."
  },
  "success": true
}
```

无检索结果：

```text
抱歉，暂时没有找到符合你描述的短剧，可以换一个说法再试试。
```

有来源但生成失败：

```text
这几部短剧和你的需求比较接近，但暂时无法生成推荐理由，可以点开详情看看。
```

两种情况 HTTP 仍为 200，`degraded=true`，通过 `degradedFlags` 区分原因。

### 4.1.1 首页「为你推荐」

经网关：`GET /api/content/internal/ai/rag/personalized?limit=6`

直连：`GET http://127.0.0.1:8107/internal/ai/rag/personalized?limit=6`

- 需要 `X-User-Id`，由网关注入；直连测试可手动带。
- 多路召回（P1-4）：
  - 继续观看：interaction 按短剧取最近观看时间，权重最高；
  - 共同观看：同一用户看过的其他短剧共现，按共现得分排序；
  - 向量：用画像检索词查询 Milvus 短剧文档；
  - 偏好规则：分类 3 分、标签 2 分、热度 0.5 分、30 天内上新 1 分；
  - 热播兜底，并排除最近已看、已收藏和“不感兴趣”的短剧。
- 同一分类最多先选 2 部，不足时再放开，避免推荐列表过窄。
- 响应新增 `requestId`、`algorithm`、`experimentId`、`variant`，用于曝光、点击、播放和完播归因。
- 结果缓存升级为 `msd:rag:for-you:v3:*`；命中缓存时只更换 `requestId`，算法和实验分桶保持不变。
- 无行为时读取冷启动分类偏好（Redis `msd:rag:cold-pref:{userId}`，30 天）；没有冷启动选择才回退热播。
- interaction 只返回行为和短剧 ID，目录元数据由 content 通过已有目录接口获取。

冷启动偏好：

```http
POST /api/content/internal/ai/rag/preferences
{"categoryIds":[2002,2004]}
```

- 最多 5 个分类；保存后清理画像和个性化缓存，下一轮推荐立即使用。
- 负反馈后前端调用 `POST /api/content/internal/ai/rag/personalized/evict` 清理缓存。

曝光归因：

- content 返回推荐后调用 interaction `POST /internal-source/recommendations/exposures` 批量写入 `t_recommendation_exposure`，按 `(request_id, drama_id)` 幂等。
- 前端点击/播放/完播/不感兴趣调用 `POST /api/interaction/internal/recommendations/events`，写入 `t_recommendation_event`，按 `(user_id, request_id, drama_id, event_type)` 幂等。
- 事件类型：1 点击、2 播放、3 完播、4 不感兴趣；CTR 和完播率可直接按两张表聚合。

### 4.1.2 流式推荐

经网关：`POST /api/content/internal/ai/rag/recommend/stream`

直连：`POST http://127.0.0.1:8107/internal/ai/rag/recommend/stream`

请求体与 `/recommend` 一致，响应为 `text/event-stream`：

```text
event: meta    data: {"requestId":"...","sessionId":"","episodeId":null,"rewrittenQuery":""}
event: delta   data: {"text":"《长安第一女仵作》"}
event: sources data: {"items":[{"dramaId":5005,"title":"长安第一女仵作",...}]}
event: done    data: {"degraded":false,"degradedFlags":[],"elapsedMs":8420,"locked":false}
event: error   data: {"code":"timeout","message":"..."}
```

- 检索为空时丢弃模型输出，统一下发固定 no-match 文案和空 `sources`。
- 生成失败但有来源时下发固定兜底文案，`degradedFlags` 包含 `generation`。
- 流式回答同样经过 `AiStreamSanitizer`：尾部保留 64 字符做跨 chunk 敏感信息匹配，超过 1500 字截断。
- 普通 JSON 接口保留，前端在网关不支持 SSE 或首帧前失败时自动回退。
- 新增指标 `ai_first_token_seconds{scene}`。

### 4.1.3 相似短剧

经网关：`GET /api/content/internal/ai/rag/related?dramaId=5005&limit=6`

直连：`GET http://127.0.0.1:8107/internal/ai/rag/related?dramaId=5005&limit=6`

```json
{
  "items": [
    {
      "dramaId": 5006,
      "title": "总裁的隐婚甜妻",
      "subtitle": "先婚后爱",
      "coverUrl": "http://...",
      "categoryName": "甜宠",
      "episodeCount": 20,
      "viewCount": 12000,
      "likeCount": 800,
      "score": 0.82,
      "reason": "同为「先婚后爱」题材",
      "matchedTags": ["先婚后爱"]
    }
  ],
  "source": "vector",
  "degraded": false
}
```

- 优先用当前短剧的标题、分类、标签和简介构造查询文本，在 Milvus 中检索 `docType=drama` 向量，排除自身。
- 无向量结果或 Milvus 不可用时回退 `GET /internal-source/rag/dramas/{dramaId}/related`：同分类、共同标签优先，再按热度排序。
- `source=vector/rule` 区分召回来源；规则兜底时 `degraded=true`。
- 结果 Redis 缓存 10 分钟，不调用 LLM。
- 前端在详情信息栏和 Feed 结束卡展示“你可能还喜欢”，卡片复用 `DramaCard` 和推荐理由。
- 曝光/点击归因（`requestId`、位置、实验分桶）和 CTR 看板按路线图放到 P1-4，本次只保证入口和来源可追溯。

### 4.1.4 防护、反馈与指标

- 查询入口经过 `AiInputGuard`：长度、控制字符、伪造内部标记和明显注入直接返回 400。
- 模型回答经过 `AiOutputGuard`：手机号、身份证、邮箱、社交账号替换为 `[联系方式已隐藏]`；刷单、私下转账等明显引流话术替换为 `[内容已安全处理]`；超长回答截断。
- 响应包含 `requestId`；登录用户可通过 `POST /api/interaction/internal/ai/feedback` 提交 `RAG_SEARCH` 反馈。
- `/actuator/prometheus` 暴露 `ai_request_total{scene,status}`、`ai_latency_seconds{scene}`、`ai_degraded_total{scene,flag}`、`ai_rag_no_result_total`、`ai_first_token_seconds{scene}`。

### 4.1.5 评估、灰度与成本（P2-3）

**离线评测**

`eval/golden-rag.jsonl` 共 100 条：找剧 40、无结果 20、剧情问答 30、防剧透 10。脚本直连 content-service，不需要额外服务：

```bash
python scripts/eval-rag.py
python scripts/eval-rag.py --base-url http://127.0.0.1:8000/api/content \
  --token "$TOKEN" --output eval/report.json
```

默认阈值：`recall@3 >= 0.7`、无结果准确率 `>= 0.8`、关键词命中率 `>= 0.5`、引用准确率 `>= 0.9`、防剧透泄漏 `= 0`、请求失败 `= 0`。任一项不达标脚本以非 0 退出。报告同时输出 MRR、NDCG@3、轻量词面 grounding（不是 LLM 裁判）和 P95 延迟。

**灰度与 A/B**

Nacos `content-config.yaml`：

```yaml
msd.ai.governance:
  features:
    rag-search:
      enabled: true
      gray-percent: 100   # 5 -> 50 -> 100
      anonymous-included: true
  experiments:
    rag-model:
      enabled: false
      salt: msd-rag
      variants:
        - { name: control, percent: 100 }
        - { name: light, percent: 0, model: deepseek-chat, temperature: 0.2 }
```

- `gray-percent` 按 `userId + 功能名` 稳定哈希分桶；未配置的功能默认放行，关闭时 RAG 返回固定文案和 `feature_disabled`。
- 实验命中后，查询改写和最终生成都切换到变体模型；`RagRecommendResponse` 返回 `variant`，指标 `ai_experiment_total{experiment,variant}` 可按变体观察。
- 不在灰度内的用户不会进入检索和模型调用，避免只在前端隐藏入口。

**缓存与预算**

```yaml
msd.ai.governance:
  budget:
    enabled: true
    daily-tokens-per-user: 200000
    chars-per-token: 1.5
    expected-output-tokens: 500
  cache:
    exact-enabled: true
    exact-minutes: 5
    exact-max-entries: 500
    semantic-enabled: false
    semantic-threshold: 0.95
```

- 精确缓存 key 为 `topK + 用户偏好检索词 + 实验变体 + 归一化 query`；语义缓存在 exact miss 后用 embedding 相似度查找，按偏好指纹隔离，默认关闭。
- 只缓存有来源、未降级的完整回答；缓存命中返回新的 `requestId` 和 `cached=true`，不再调用模型。
- 预算按用户按天在 Redis 累计，入口预占、结束后补记超出部分；Redis 不可用时 fail-open。超预算返回 `budget_exceeded`，不发起检索。
- Rerank 新增 `min-score`，低于阈值的候选不进入上下文；全部低于阈值时按无匹配返回，减少无结果场景的编造。

在线指标在原有指标上增加 `ai_cache_hit_total{type}`、`ai_tokens_total{scene,variant}`、`ai_experiment_total{experiment,variant}`；来源点击、伴看反馈、推荐曝光复用已有行为和反馈表。content 已加入 Zipkin/Micrometer Tracing starter 和 Reactor 上下文传播，网关 traceId 可串到检索/模型调用；各阶段耗时仍同时记录在 `RagTrace` 和指标里。

### 4.2 重建索引

`POST /internal/ai/rag/reindex`（只允许直连 8107）

```json
{"allowEmptyCatalog": false}
```

响应：

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "dramaCount": 9,
    "episodeCount": 45,
    "vectorUpserted": 54,
    "esIndexed": 54,
    "staleDeleted": 0,
    "vectorAvailable": true,
    "bm25Available": true,
    "partial": false,
    "errors": [],
    "elapsedMs": 2010
  },
  "success": true
}
```

### 4.3 状态

`GET /internal/ai/rag/status`（只允许直连 8107）

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "enabled": true,
    "vectorAvailable": true,
    "bm25Available": true,
    "milvusCollection": "msd_drama_rag_v1",
    "elasticsearchIndex": "msd_drama_bm25_v1",
    "lastIndexedAt": "2026-10-25T06:30:00Z",
    "lastDramaCount": 9,
    "lastEpisodeCount": 45,
    "lastPartial": false,
    "lastError": null
  },
  "success": true
}
```

## 5. 配置

模型配置在 `msd-content-service/src/main/resources/application.yml`：

- `spring.ai.model.chat=deepseek`
- `spring.ai.model.embedding=openai`
- `spring.ai.deepseek.base-url=https://api.deepseek.com`
- `spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1`
- `spring.ai.openai.embedding.options.dimensions=1024`
- `spring.ai.retry.max-attempts=2`

RAG 调优参数在 Nacos `content-config.yaml`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `msd.ai.rag.enabled` | `true` | 关闭后 RAG Bean/Controller 不创建 |
| `msd.ai.rag.reindex-on-startup` | `false` | 启动后异步重建 |
| `msd.ai.rag.total-timeout-ms` | `75000` | recommend 整体超时 |
| `msd.ai.rag.rewrite.timeout-ms` | `3000` | 改写超时 |
| `msd.ai.rag.rewrite.failure-backoff-ms` | `30000` | 改写失败退避 |
| `msd.ai.rag.vector.top-k` | `20` | 向量召回数 |
| `msd.ai.rag.vector.timeout-ms` | `3000` | 向量分支超时 |
| `msd.ai.rag.bm25.top-k` | `20` | BM25 召回数 |
| `msd.ai.rag.bm25.timeout-ms` | `2000` | BM25 分支超时 |
| `msd.ai.rag.hybrid.rrf-k` | `60` | RRF 平滑常数 |
| `msd.ai.rag.hybrid.top-k` | `20` | RRF 输出数 |
| `msd.ai.rag.rerank.top-n` | `3` | 精排输出数 |
| `msd.ai.rag.rerank.min-score` | `0`（Nacos `0.25`） | 低于该分数视为无匹配 |
| `msd.ai.rag.rerank.use-rewritten-query` | `false` | 是否用改写词精排 |
| `msd.ai.rag.rerank.url` | DashScope 地址 | Rerank 接口 |
| `msd.ai.rag.rerank.model` | `qwen3.7-text-rerank` | Rerank 模型 |
| `msd.ai.rag.rerank.api-key` | `${DASHSCOPE_API_KEY:}` | 为空时降级 |
| `msd.ai.rag.rerank.timeout-ms` | `3000` | Rerank 超时 |
| `msd.ai.rag.milvus.*` | `127.0.0.1:19530` | 连接、collection、维度、索引 |
| `msd.ai.rag.elasticsearch.*` | `127.0.0.1:9200` | 地址、index、超时 |
| `msd.ai.rag.catalog.page-size` | `200` | 目录导出分页 |
| `msd.ai.rag.document.max-description-chars` | `1200` | 简介裁剪 |
| `msd.ai.rag.document.max-episode-titles` | `30` | 剧集标题裁剪 |
| `msd.ai.rag.expansion.enabled` | `true`（Nacos） | 多查询扩展开关 |
| `msd.ai.rag.expansion.max-queries` | `3` | 最多查询数（含原查询） |
| `msd.ai.rag.expansion.additional-queries` | `1` | LLM 额外生成数 |
| `msd.ai.rag.expansion.per-query-top-k` | `10` | 每条 query 的召回数 |
| `msd.ai.rag.personalization.enabled` | `true`（Nacos） | 是否把画像偏好注入 RAG |
| `msd.ai.rag.personalization.cache-minutes` | `5` | 画像缓存分钟数 |
| `msd.ai.rag.incremental.enabled` | `true`（Nacos） | 是否消费 DRAMA_CHANGED |
| `msd.ai.rag.incremental.debounce-ms` | `2000` | 同一短剧去抖时间 |
| `msd.ai.rag.incremental.concurrency` | `2` | 消费者并发数 |
| `msd.ai.rag.incremental.dedup-ttl-hours` | `168` | eventId 幂等键保留时长 |
| `msd.ai.rag.incremental.retry-attempts` | `3` | 索引失败重试次数 |
| `msd.ai.rag.incremental.retry-delay-ms` | `5000` | 重试间隔 |
| `msd.ai.governance.features.rag-search.gray-percent` | `100` | AI 找剧灰度比例 |
| `msd.ai.governance.experiments.rag-model.enabled` | `false` | 是否按用户分桶切换模型 |
| `msd.ai.governance.budget.daily-tokens-per-user` | `200000` | 每用户每日估算 token 上限 |
| `msd.ai.governance.cache.exact-enabled` | `true` | 精确缓存开关 |
| `msd.ai.governance.cache.semantic-enabled` | `false` | 语义缓存开关 |
| `msd.ai.governance.cache.semantic-threshold` | `0.95` | 语义命中阈值 |

## 6. 降级行为

| 标记 | 触发条件 | 行为 |
|---|---|---|
| `rewrite` | DeepSeek 超时/异常/空结果 | 用原查询继续检索 |
| `expansion` | 多查询模型超时/异常/空结果 | 保留原查询 + 偏好 query |
| `personalization` | interaction/画像依赖失败 | 不使用偏好，继续推荐 |
| `vector` | Milvus 不可用/超时 | 只返回 BM25 结果 |
| `bm25` | ES 不可用/超时/索引不存在 | 只返回向量结果 |
| `rerank` | Key 为空、非 2xx、超时、解析失败、退避 | 回退 RRF Top 3 |
| `generation` | ChatClient 异常/空回答 | 保留 sources，返回固定兜底文案 |
| `timeout` | 超过 `total-timeout-ms` | 返回固定 no-match 文案 |
| `feature_disabled` | 灰度未命中或功能关闭 | 不检索、不调用模型，返回固定文案 |
| `budget_exceeded` | 用户当日 token 预算耗尽 | 不检索、不调用模型，返回固定文案 |

两路检索都失败时 sources 为空，返回固定 no-match 文案；不会编造剧名。

## 7. 本地验证

单元测试和构建：

```bash
mvn -B -pl msd-content-service -am test
mvn -B -pl msd-content-service -am -DskipTests package
```

覆盖：Document 构建、jieba 分词、ES mapping/检索、RRF、混合检索降级、Rerank 回退、改写回退、推荐响应映射、空上下文模板、多查询 RRF、偏好 query、首页个性化排序与冷启动。

真实基础设施联调：

```bash
docker compose -f deploy/docker-compose.yml up -d etcd minio milvus elasticsearch
python scripts/init-nacos-config.py
# 启动 msd-drama、msd-content-service、msd-gateway
curl -X POST http://127.0.0.1:8107/internal/ai/rag/reindex \
  -H 'Content-Type: application/json' -d '{}'
curl http://127.0.0.1:8107/internal/ai/rag/status
curl 'http://127.0.0.1:9200/msd_drama_bm25_v1/_count'

TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/recommend \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"有没有那种先婚后爱的甜宠剧"}'

# 行为聚合（服务间直连）
curl http://127.0.0.1:8104/internal-source/users/1006/behavior

# 首页个性化推荐（需要用户 Token）
curl 'http://127.0.0.1:8000/api/content/internal/ai/rag/personalized?limit=6' \
  -H "Authorization: Bearer $TOKEN"

# 流式找剧（-N 关闭 curl 缓冲）
curl -N -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/recommend/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"有没有那种先婚后爱的甜宠剧","topK":3}'

# 相似短剧
curl 'http://127.0.0.1:8000/api/content/internal/ai/rag/related?dramaId=5005&limit=6' \
  -H "Authorization: Bearer $TOKEN"

# 增量索引：管理员审核通过/下架后自动发送 DRAMA_CHANGED，无需手工 reindex
# 查看 topic 是否产生事件（本地 Kafka 工具）
kafka-console-consumer --bootstrap-server 192.168.33.129:9092 \
  --topic msd.drama.changed.v1 --from-beginning --max-messages 1
```

验收点：

- `dramaCount=9`、`episodeCount=45`、`esIndexed=54`、`vectorUpserted=54`、ES `_count=54`（9 部短剧 + 45 集）。
- 连续 reindex 两次，ES 计数仍为 54，Milvus 无重复 ID。
- `sources` 最多 3 条；回答中的剧名和理由能在 sources 中找到依据。
- 停 ES / Milvus / 修改 Rerank Key / 修改 DeepSeek Key 时，`degradedFlags` 分别出现对应标记。
- 无 Token 访问网关 recommend 返回 401；网关访问 reindex/status 返回 404。

本次本地实测（2026-10-25）：

- `msd-content-service` 57 个单元测试通过；`msd-drama` 45 个测试通过；全项目 `mvn -T 1C test` BUILD SUCCESS。
- Nacos/Milvus/Elasticsearch/Seata 正常，`content-config.yaml` 已发布。
- 9 部短剧、45 集分集导出成功，ES `_count=54`；`reindex` 返回 `dramaCount=9`、`episodeCount=45`、`vectorUpserted=54`、`esIndexed=54`、`partial=false`、`vectorAvailable=true`、`bm25Available=true`；连续两次 reindex 后仍为 54。
- 向量 + BM25 + Rerank + 生成通过：`先婚后爱` 命中 5001/5004/5010，`女主验尸破案` 命中 5005，`degradedFlags=[]`，sources 同时带 `vectorRank` 和 `bm25Rank`；无匹配查询返回 grounded 的 no-match。
- 使用有效百炼 Key 后 Rerank 成功：`先婚后爱` 来源 `score` 为 0.9896 / 0.9744 / 0.7095。
- 重启 `msd-milvus` 后再次查询，向量结果仍正常，说明 collection 已持久化。
- ES 停止时 `degradedFlags=["vector","bm25"]`；Milvus 停止时 BM25 仍返回 5005，恢复后正常。
- 网关 6 条路由；无 Token 401；reindex/status 404；user/drama/order/interaction 旧路由回归 200。
- **Milvus 配置修复**：此前 `COMMON_STORAGETYPE=minio` 导致 Arrow FS 初始化失败并触发 `MmapManager` / `Failed to get filesystem` panic。改为 `COMMON_STORAGETYPE=remote` 后，2.6.4 使用原 `milvus_data` 数据卷直接恢复，无需删库或换镜像；2.5.7/2.6.7 的尝试已不再需要。

P4 联调补充（2026-10-27，本地未启动 Milvus/ES）：

- `GET /internal/ai/rag/personalized`：1006/1003 返回不同排序；未互动用户 9999 返回 `personalized=false, coldStart=true`，回退热播；网关未登录 401。
- `POST /internal/ai/rag/recommend` 带 `X-User-Id` 时日志出现 3 条检索 query（原查询 + 变体 + 偏好），不带时为 2 条；多查询 RRF 与偏好 query 生效。
- Milvus/ES 均不可用时 `degradedFlags=["vector","bm25"]`，接口仍返回 200 和 no-match 兜底文案，耗时约 2.6–6.3s；这是当前环境缺少中间件，不是代码回归。

## 8. 已知限制

- 增量索引覆盖审核通过/下架；草稿编辑不会触发索引，因为草稿本来就不可检索。
- 字幕索引依赖字幕导入或 ASR 回传；没有字幕的剧集只能使用分集摘要，不能做时间戳引用。
- 字幕用于剧情问答，不自动替换已有分集摘要；摘要仍由标题/简介轻量模型生成。
- ASR 是离线 worker，转写准确率取决于音质和模型；回答中标注“AI 转写可能有误”。
- 增量索引失败重试耗尽后不会自动重建全量索引，需要人工执行 `POST /internal/ai/rag/reindex`。
- 用户画像为实时聚合 + Redis 缓存，不落库、不做离线训练；偏好变化最多 5 分钟生效。
- 首页已接入「为你推荐」；RAG 对话推荐已接入搜索页的「AI 找剧」模式和全局 AI 助手，失败时回退关键词搜索。
- `msd-agent-service` 的全局助手已通过 `search_drama_rag` 调用本 RAG；旧 `/internal/ai/chat` Mock 入口仍保留，仅用于兼容。
- 离线评测脚本依赖已启动的 content-service、Rerank 和模型 Key；没有真实 stack 时不能离线跑分。
- 精确/语义缓存是进程内 Caffeine，多实例之间不共享；语义缓存默认关闭，embedding 失败时自动退化为精确缓存。
- token 预算按字符比例估算，不等于账单 token；只用于成本保护，不参与权限判断。
- A/B 目前只有分桶和指标，没有实验管理后台和自动显著性分析。
- 本地 ES 关闭安全认证，仅绑定 `127.0.0.1`；生产需要额外鉴权和网络隔离。
- Milvus 未启用认证，生产需要补齐。
