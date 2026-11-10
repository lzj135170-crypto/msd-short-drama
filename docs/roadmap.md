# 剩余功能路线图

模块 1-8 的后端主链路已完成。本文列出尚未实现的功能，按“Demo 可演示闭环优先、生产化最后”排序。真实视频、真实支付、Spring Security、VIP、拆分服务不在 Demo 范围。

## 0. 当前进度

- P0：`.gitignore`、CI、README/架构图已完成；首次 git commit 待确认。
- P1：前端伴看面板、摘要批量预热已完成，后端测试和前端构建通过；浏览器手工验收待做。
- P2：审核状态机、AI 审核写回、管理员接口和角色校验已完成。
- P3：充值档位、下单、模拟支付、积分流水和前端充值页已完成。
- P4：搜索/分类闭环已完成：分类列表、关键词/分类搜索、最新/最热排序、分页和前端 `/search` 页；Demo 使用 MySQL LIKE，未接 ES。
- P5：创作者工作台、管理端审核台、审核操作日志已完成。
- P6：评论治理、继续观看、已解锁列表已完成。
- P7：CI、镜像模板、审核日志、L1 跨实例广播和角色校验基础项已完成；真实视频/支付/安全/VIP 仍待排期，见 [production.md](production.md)。

## 1. 阶段总览

| 阶段 | 目标 | 依赖 | 预估 |
|---|---|---|---|
| P0 | 工程基线：首次提交、CI、文档与架构图 | 无 | 0.5-1 人日 |
| P1 | 伴看前端闭环：Feed 入口、聊天面板、会话保持、降级提示；可选摘要预热 | P0 | 1.5-2 人日（预热 +0.5-1） |
| P2 | 审核闭环：提交/人工审核状态机、AI 审核写回、管理员接口 | P0 | 3-4 人日 |
| P3 | 充值/积分闭环：档位、下单、模拟支付、流水、前端充值页 | P0 | 3-4 人日 |
| P4 | 搜索/分类闭环：短剧搜索、分类列表与分页、前端搜索页 | P0 | 2-3 人日 |
| P5 | 内容后台：创作者草稿/剧集编辑/提交、管理员审核 UI | P2、P4 | 5-7 人日 |
| P6 | 体验补洞：评论治理、头像、继续观看、已解锁列表 | P2、P3 | 4-6 人日 |
| P7 | 生产硬化：真实媒体/支付、安全、CI/CD、L1 广播 | P0-P6 | 15-25+ 人日 |

推荐顺序：`P0 -> P1 -> P2 -> P3 -> P4 -> P5 -> P6 -> P7`。P1 只改前端，可与 P2 后端并行。

## 2. P0：工程基线

- `.gitignore` 增加 `__pycache__/`、`*.py[cod]`、`frontend/logs/`、`.claude/settings_local.json`。
- 首次 git commit（提交前展示变更摘要，不 push）。
- `.github/workflows/ci.yml`：后端 `mvn -B -ntp -T 1C test`，前端 `npm ci && npm run build`。
- README 模块表、架构图、`deploy/.env.example` 与本文档同步。
- 验收：本地构建通过，工作区干净，提交不含 `target/`、`node_modules/`、`frontend/dist/`、`logs/`、`deploy/.env`。

## 3. P1：伴看前端闭环

- Feed 右侧增加“伴看”按钮，打开 `CompanionPanel`。
- 复用 `POST /api/content/internal/ai/companion/chat`，请求携带当前 `episodeId`；`sessionId` 每部剧生成一次，会话与消息存 `sessionStorage`，最多 50 条。
- 面板显示消息、快捷问题、降级提示、字数与发送状态；打开时暂停播放。
- 未解锁集不打开面板，提示“解锁本集后可使用伴看”。
- 可选：drama 提供缺失摘要候选，content-service 提供批量预热接口；不做定时任务。
- 验收：切集上下文连续；关闭/离开再进入消息保留；停 ES/Milvus 时有降级提示；前端构建通过。

已完成（P1-1/P1-2/P1-3）：SSE 流式输出、引用展开、本地多会话、前情提要/本集看点、相似短剧。

已完成（P1-4/P1-5）：多路个性化召回与曝光/点击/播放/完播归因、冷启动偏好、负反馈、评论课代表和评论 AI 预审。

已完成（P1-6/P1-7）：创作者提交前 AI 预检（合规结论 + 文案/标签建议，只读）、`DRAMA_CHANGED` 驱动的 RAG 增量索引（按 dramaId 去抖、Redis 幂等、失败重试和全量兜底）。

已完成（P2-1/P2-2）：分集字幕/ASR 导入、transcript 混合索引、按播放位置防剧透的剧情问答和时间戳 seek；全局 AI 找剧助手、`search_drama_rag` 工具、SEARCH/RECOMMEND 路由和可点击 DRAMA_CARD。

已完成（P2-3/P2-4）：`eval/golden-rag.jsonl` 离线评测和 `scripts/eval-rag.py`；RAG 功能灰度、稳定分桶 A/B、token 预算、精确/语义缓存、Rerank 最低分和成本指标；创作者 Copilot 多版本文案生成、单版本合规检查、采纳反馈和编辑页采纳。

不做：服务端聊天历史、跨设备同步、语音输入。

## 4. P2：审核闭环

流程：`草稿/驳回 -> 提交审核 -> 待审 -> AI 审核 -> 人工审核 -> 上架/驳回/下架`。

- AI 只写 `t_drama.ai_review_*`，**绝不直接改 `review_status`；降级记为失败，绝不自动通过**。
- drama 新增内部接口 `/internal-source/reviews/**`：待审候选、单条详情、AI 结果条件写回（仅 `review_status=1`）。
- content-service 新增 `DramaReviewClient` 和 `DramaReviewAuditService`，调用现有 `StructuredAuditService`，通过 ops 接口 `POST /internal/ai/audit/dramas/pending` 或 `/{id}` 触发；保持 `content-service -> drama` 依赖方向，不反向调用。
- drama 新增管理员接口 `/internal/admin/dramas/**`：提交、通过、驳回、下架、审核队列；校验 `X-User-Role`，创作者只能操作自己的短剧。
- 状态迁移使用条件 UPDATE，0 行返回 409；通过/驳回/下架后调用 `CacheEvictor`，通过后加入布隆过滤器。
- 验收：5003 待审 -> AI 通过 -> 管理员通过 -> 公开详情可见；新增 `scripts/reset-review-demo.sql` 便于重复演示。

不做：异步审核队列、失败自动重试调度、审核日志表、完整内容 CRUD（P5）。

## 5. P3：充值/积分闭环

- 表已存在，无需 DDL：`t_recharge_plan`、`t_recharge_order`、`t_points_account`、`t_points_ledger`。
- 新增档位列表、创建订单、订单详情、模拟支付、积分流水接口。
- 下单按 `(user_id, client_request_id)` 幂等；支付用条件 `UPDATE status=0 -> 1`；入账与流水同一本地事务；流水幂等键 `order:{orderId}`。
- 前端新增 `/recharge`：余额、档位、模拟支付、流水；个人中心和积分不足弹层增加入口。
- 验收：1003 支付 4001 后余额 `250 -> 310`，重复支付不重复加积分。

不做：微信/支付宝、验签、退款、对账、发票、订单取消、定时过期、真实支付 SDK。

## 6. P4：搜索/分类闭环（已完成）

- Demo 使用 drama 服务 MySQL 搜索与分类分页，不把主链路压到 ES；ES 搜索保留为后续增强。
- 新增 `GET /internal/categories` 和 `GET /internal/search/dramas?keyword=&categoryId=&sort=latest|hot&pageNo=&pageSize=`。
- 只返回 `deleted=0 AND review_status=2 AND publish_time IS NOT NULL`；`hot` 复用现有加权公式。
- 关键词 trim 后最多 50 字，`%`、`_`、`/` 会转义；页码小于 1 按 1 处理，页大小默认 12、最大 50。
- 前端新增 `/search`：搜索框、分类 chips、最新/最热、结果网格与分页；首页增加搜索入口。
- 验收（2026-10-26）：关键词、演员、分类、排序、分页、空结果和野字符均正常；`npm run build` 通过。

不做：个性化推荐、搜索历史、热词、拼音/纠错、语义搜索、ES 高亮、跨剧集搜索、搜索缓存。

## 7. P5：内容后台 / 创作者工作台

- 创作者：我的短剧、创建草稿、编辑草稿/驳回、删除草稿、剧集增删改、提交审核。
- 管理员：全量列表、编辑、下线、审核队列与审核操作。
- 仅 `review_status IN (0,3)` 可编辑；已上架修改需先下线；`CREATOR` 仅本人，`ADMIN` 不限。
- 前端 `/creator/dramas`、`/creator/dramas/new`、`/creator/dramas/:id/edit`、`/admin/reviews`。
- 发布后调用缓存失效与布隆 add；后台提供“重建推荐索引”按钮调用现有 reindex。

## 8. P6：体验补洞

- 评论治理：管理员分页、隐藏、删除；条件 `status=1 -> 2/3`，根评论级联，复用 `COMMENT_DELETED` outbox 计数。
- 头像：Demo 保留 URL/预设头像，真实 MinIO 上传放 P7。
- 继续观看：首页增加“继续观看”卡片，复用历史接口与 `/drama/{dramaId}?episode=&start=`。
- 已解锁列表：order 新增分页接口，前端 `/me/unlocks` 按短剧分组。

## 9. P7：生产硬化

- 真实视频：对象存储、转码、CDN、签名 URL、真实播放器、防盗链。
- 真实支付：微信/支付宝、回调验签、对账、退款、订单过期任务。
- 安全：Spring Security、RBAC、管理员账号体系、操作审计、内部 token/mTLS、密钥管理、限流加固。
- CI/CD：各服务 Dockerfile、镜像仓库、部署流水线、环境隔离、回滚。
- RAG：增量索引和字幕索引已接入；后续补充失败自动全量重建、索引 lag 告警和批量重建任务。
- 字幕/ASR：版权、转写准确率人工校验、对象存储和大文件分片仍需生产化。
- 缓存：L1 跨实例 Redis Pub/Sub 广播。
- VIP/订阅、优惠券、积分过期、防刷、审核日志表、服务/数据库拆分、可观测性与压测。
