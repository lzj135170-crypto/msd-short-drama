# 码上短剧

微服务 + AI 短剧平台。0.1-0.4、2.1-2.6、**分布式事务与异步解耦（数据一致性）**、**RAG 内容推荐**、**首页多级缓存与 Java 21 虚拟线程**、**AI 伴看与结构化审核**、**RexUniNLU 情感/标签建议**、**SSE 流式回答/引用/多会话**、**前情提要**、**相似短剧**、**个性化 v2/推荐归因**、**评论课代表/评论预审**、**创作者 AI 预检**、**RAG 增量索引**、**字幕/ASR 剧情问答**、**全局 AI 找剧助手**、**AI 评估/灰度/成本**、**创作者 Copilot**模块已完成并验证；Vue 3 + Vite 前端已接入登录、首页（含继续观看、个性化推荐）、详情、播放/解锁、互动、个人中心、充值中心、创作者工作台、审核台、AI 伴看面板和全局找剧助手。剩余功能与分阶段计划见 [docs/roadmap.md](docs/roadmap.md)。

## 技术基线

- Java 21
- Spring Boot 4.0.8
- Spring Cloud 2025.1.3
- Spring Cloud Alibaba 2025.1.0.0
- Spring AI 2.0.1（DeepSeek 对话 / `qwen3.7-text-embedding-flash` / `qwen3.7-text-rerank` / Milvus + Elasticsearch 8）
- Python 3.11 + FastAPI + RexUniNLU（达摩院 zero-shot 情感/标签建议，本地 CPU sidecar）
- Micrometer Tracing 1.6.7 + Brave（全链路追踪，上报 Zipkin）
- MySQL 8.0.45（本机，业务库 `DL`，不容器化）
- Redis 7.2.14 / Kafka 4.1.2（KRaft）在虚拟机 `192.168.33.129`
- Nacos 3.1.1 / Milvus 2.6.4 / Elasticsearch 8.15.3 / xxl-job 3.5.0 / Sentinel Dashboard 1.8.9 / Zipkin 3.6.1 / Seata Server 2.5.0（Docker Compose，9 个容器）

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| `msd-gateway` | 8000 | 统一网关，路由与前端静态托管 |
| `msd-common` | - | 统一返回体、错误码、异常、常量 |
| `msd-user` | 8101 | 用户与认证 |
| `msd-drama` | 8102 | 短剧、剧集、分类、标签、搜索、创作者工作台、字幕/ASR 导入、审核状态机与 AI 审核写回；Caffeine + Redis 多级缓存、布隆、xxl-job、虚拟线程；审核通过/下架发送 `DRAMA_CHANGED` |
| `msd-order` | 8103 | 充值下单、模拟支付、积分账户/流水、剧集解锁、已解锁列表 |
| `msd-interaction` | 8104 | 点赞、收藏、评论、评论治理、观看历史、继续观看、outbox |
| `msd-agent-service` | 8105 / 8106 | AI Agent / AgentScope Supervisor（8105，含 companion-agent/search-agent）；audit A2A 服务（8106，`audit` profile） |
| `msd-content-service` | 8107 | RAG 推荐、个性化推荐、多查询扩展、AI 伴看、字幕剧情问答、分集摘要、结构化审核、Rex 情感/标签建议；Milvus + ES 混合检索、Rerank |
| `rex-uninlu-service` | 8108 | RexUniNLU 本地 sidecar：短剧情感分析、标签建议，仅 advisory、fail-open |

> 冒烟模块 `msd-infra-smoke` 与原始 Nacos 示例 `msd-demo` 在 0.4 验证通过后已删除，仓库只保留生产代码。验证结论与可复用配置见 [docs/chapter-0-baseline.md](docs/chapter-0-baseline.md)。

前端位于 `frontend/`（Vue 3 + Vite + JavaScript），开发端口 5173；生产构建由网关在 8000 托管。点开短剧进入红果式全屏 Feed，上下滑切换本剧剧集，右侧抖音式动态点赞/收藏、评论/分享，简介/选集/评论使用底部弹层。封面优先使用 MinIO 中的公开海报，加载失败回退 CSS/SVG 渐变占位图；播放器使用真实 `<video>` 播放 MinIO 中的测试短片并上报观看历史。

## 前端

```bash
cd frontend
npm install
npm run dev      # http://127.0.0.1:5173，/api 代理到 8000
npm run build    # 产物 frontend/dist
```

修改前端后需要重新 `npm run build` 并重新打包网关，静态文件才会更新：

```bash
mvn -B -pl msd-gateway -am package -DskipTests
java -jar msd-gateway/target/msd-gateway-1.0.0-SNAPSHOT.jar
```

演示账号：`13800000003 / Msd@123456`（用户 1003，余额 250）。

## 微服务启动

```bash
mvn -B -T 1C clean package -DskipTests

# 业务服务（各开一个终端）
java -jar msd-user/target/msd-user-1.0.0-SNAPSHOT.jar
java -jar msd-drama/target/msd-drama-1.0.0-SNAPSHOT.jar
java -jar msd-order/target/msd-order-1.0.0-SNAPSHOT.jar
java -jar msd-interaction/target/msd-interaction-1.0.0-SNAPSHOT.jar
java -jar msd-agent-service/target/msd-agent-service-1.0.0-SNAPSHOT.jar

# RexUniNLU sidecar（可选，8108；首次先执行 setup）
scripts\setup-rex-uninlu.cmd
scripts\start-rex-uninlu.cmd

java -jar msd-content-service/target/msd-content-service-1.0.0-SNAPSHOT.jar

# audit A2A 服务（可选，另开终端；8106）
# NACOS_NAMESPACE 必须显式设置，否则 audit profile 会推导出不存在的 mashang-duanju-audit
NACOS_NAMESPACE=mashang-duanju-dev java -jar msd-agent-service/target/msd-agent-service-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=audit

# 最后启动网关
java -jar msd-gateway/target/msd-gateway-1.0.0-SNAPSHOT.jar
```

健康检查：`curl http://127.0.0.1:<port>/actuator/health`；网关路由验证：`curl http://127.0.0.1:8000/api/user/internal/ping`。

## Nacos 配置与环境隔离

- 命名空间：`mashang-duanju-dev` / `-test` / `-prod`，分组 `DEFAULT_GROUP`。
- 初始化配置：`python scripts/init-nacos-config.py`（读取 `deploy/.env`）。
- RAG 调优参数：`content-config.yaml`（`msd.ai.rag.*`），包含混合检索、Rerank、Milvus/ES 地址和超时。
- 默认 `dev`；切换环境用 `SPRING_PROFILES_ACTIVE=test`，命名空间随之切换。
- 热更新示例：`curl http://127.0.0.1:8105/internal/config/ai`，修改 Nacos 的 `agent-config.yaml` 后无需重启。
- SCA 2025.1 不支持 `bootstrap.yml`，Nacos 配置统一走 `spring.config.import`。

## 网关鉴权与跨域

网关（8000）统一校验 JWT，并把用户信息透传给下游：

- 请求头：`Authorization: Bearer <token>`
- 透传头：`X-User-Id`、`X-User-Role`
- 白名单：`/api/user/auth/**`、`/actuator/**`
- 无 Token / 非法 Token / 过期 Token 返回 401

### 登录注册

登录注册由 `msd-user` 提供，经网关访问，不需要 Token：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/auth/register` | 手机号 + 密码 + 可选昵称，成功直接返回 Token |
| POST | `/api/user/auth/login` | 手机号 + 密码登录 |
| GET | `/api/user/internal/users/me` | 当前用户资料，需要 `Authorization: Bearer` |

```bash
# 种子账号：13800000003 / Msd@123456（追剧用户）
LOGIN=$(curl -s -X POST http://127.0.0.1:8000/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800000003","password":"Msd@123456"}')
TOKEN=$(printf '%s' "$LOGIN" | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/user/internal/users/me
```

- 种子账号：`13800000001`–`13800000013`，统一密码 `Msd@123456`；`13800000009` 是禁用账号，用于验证 403。
- 密码以 BCrypt 校验，注册时只存哈希；接口响应不返回 `password_hash`。
- 完整契约和错误码见 [docs/auth.md](docs/auth.md)。

生成测试 Token（HS256，默认密钥与网关一致）：

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/user/internal/ping
```

生产环境通过 `MSD_JWT_SECRET` 覆盖默认密钥，至少 32 字节；网关和用户服务在非 `dev`/`test` profile 下会拒绝空密钥或仓库中的公开默认值，直接启动失败。CORS 开发环境允许所有来源，生产需改成具体域名。

网关测试台 `/internal/dev/token` 默认关闭；本地需要时用 `MSD_DEV_TOKEN_ENABLED=true` 启动网关。

> 直连 8101-8108 会绕过网关鉴权，生产只暴露网关端口；8108 只绑定 127.0.0.1。

## 短剧浏览接口

首页和详情由 `msd-drama` 提供，经网关访问，需要登录：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/drama/internal/home` | 首页轮播、热播、最新、分类 |
| GET | `/api/drama/internal/categories` | 启用中的分类列表 |
| GET | `/api/drama/internal/search/dramas` | 搜索/分类浏览已上架短剧，分页返回 |
| GET | `/api/drama/internal/dramas/{dramaId}` | 短剧详情和在线剧集 |

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800000003","password":"Msd@123456"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/drama/internal/home
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/drama/internal/categories
curl -G -H "Authorization: Bearer $TOKEN" --data-urlencode "keyword=总裁" \
  http://127.0.0.1:8000/api/drama/internal/search/dramas
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/drama/internal/search/dramas?categoryId=2002&sort=hot"
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/drama/internal/dramas/5005
```

- 只返回已上架短剧（`deleted=0 AND review_status=2`）和在线剧集（`status=1`）；未上架或不存在统一 404。
- 详情不返回视频地址，播放授权见 [docs/playback.md](docs/playback.md)。
- 热度公式、字段说明和完整契约见 [docs/drama.md](docs/drama.md)。

## 首页多级缓存与虚拟线程

`msd-drama` 首页/详情使用 Caffeine L1 + Redis L2 多级缓存，详情和批量 ID 查询前置 Redisson 布隆过滤器，缓存 miss 用 `RLock` 单飞重建；写路径通过 `@MultiLevelCacheEvict` 和 `CacheEvictor` 做事务提交后延迟双删。

- 首页 key `msd:drama:cache:home:v1`，L1 10s / L2 30s；详情 `msd:drama:cache:detail:{id}`，L1 30s / L2 300s。
- 布隆使用版本化过滤器 + active 指针，启动缺失时自动重建；xxl-job `dramaCacheRebuildHandler` 每 5 分钟重建。
- 新增 `msd-drama.yaml` 和 `03_xxl_job_drama.sql` 注册 `msd-drama-executor`；3.5 的执行器组 token 必须与 Nacos 配置一致。本地已验证 17:45 调度成功（`trigger_code=200`、`handle_code=200`）。
- `spring.threads.virtual.enabled=true` 全局开启虚拟线程；dev 提供 `/internal-cache/demo/ai/single` 和 `/fanout`，对比虚拟线程与固定线程池。
- 实测 100 次约 200ms 的下游调用：虚拟线程总耗时 226ms，固定池 8 为 2510ms。
- Redisson 客户端由后台线程异步建连，Redis 不可用时请求快速降级，恢复后自动重连。
- 已在虚拟线程下回归 Seata 解锁强制回滚：余额、解锁记录、drama 引用和计数均未变化。
- 37 个 `msd-drama` 测试通过；设计、配置、xxl-job 注册和排障见 [docs/cache.md](docs/cache.md)。

```bash
# 缓存命中
curl -s -o /dev/null -w "%{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8102/internal/home

# 虚拟线程 demo（仅 dev，直连 8102）
curl "http://127.0.0.1:8102/internal-cache/demo/ai/single?prompt=hi"
curl "http://127.0.0.1:8102/internal-cache/demo/ai/fanout?calls=100&mode=virtual&pool=8&prompt=hi"
python scripts/load-test-ai.py --calls 200 --concurrency 100
```

## 剧集播放与积分解锁

播放授权由 `msd-order` 提供，需要登录：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/order/internal/playback/episodes/{episodeId}` | 播放信息；未解锁时 `videoUrl=null` |
| POST | `/api/order/internal/orders/episodes/{episodeId}/unlock` | 积分解锁 |
| GET | `/api/order/internal/orders/points` | 积分余额 |
| GET | `/api/order/internal/orders/dramas/{dramaId}/unlocks` | 某部短剧已解锁的付费集 |

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/playback/episodes/6004
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/orders/episodes/6004/unlock
```

- 免费集直接返回 `videoUrl`；付费集未解锁返回 200 + `unlocked=false` + `videoUrl=null`。
- 解锁使用 Seata AT：扣积分、写流水、写 drama 解锁引用；并发重复解锁幂等。
- drama 的网关路由已收窄为 `/api/drama/internal/**`，`/internal-source/**` 仅服务间直连。
- 演示账号 `1003` 余额 250；新注册用户没有积分账户，只能看免费集。
- 完整流程、错误码和验收记录见 [docs/playback.md](docs/playback.md)。

## 互动与个人中心

互动读接口和个人中心数据由 `msd-interaction` 提供，需要登录：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/interaction/internal/interactions/comments?dramaId=&episodeId=` | 根评论分页 |
| GET | `/api/interaction/internal/interactions/comments/{rootId}/replies` | 回复分页 |
| GET | `/api/interaction/internal/interactions/comments/mine` | 我的评论 |
| GET | `/api/interaction/internal/interactions/favorites` | 我的收藏 |
| GET | `/api/interaction/internal/interactions/history` | 观看历史 |
| GET | `/api/interaction/internal/interactions/likes/dramas` | 点赞的短剧 |
| GET | `/api/interaction/internal/interactions/stats` | 个人中心统计 |
| GET | `/api/interaction/internal/interactions/dramas/{dramaId}/state` | 点赞/收藏状态 |
| DELETE | `/api/interaction/internal/interactions/history/{episodeId}` | 删除单条历史 |
| DELETE | `/api/interaction/internal/interactions/history` | 清空历史 |
| PUT | `/api/user/internal/users/me` | 修改昵称/头像 URL/简介 |

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/interaction/internal/interactions/comments?dramaId=5001&episodeId=6001"
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/interaction/internal/interactions/stats
```

- 评论为两级结构：根评论分页 + 回复分页；删除根评论会级联删除回复。
- 列表统一 `pageNo/pageSize`，默认 1/20、最大 100；标题/作者由 user/drama 批量接口补齐。
- 个人中心由 user/order/interaction 三个服务组合，没有额外 BFF。
- 完整契约见 [docs/interaction.md](docs/interaction.md) 和 [docs/personal-center.md](docs/personal-center.md)。

## 服务间调用与负载均衡

`msd-interaction` 通过 OpenFeign 调用 `msd-user` 的 `/internal/users/{id}`，用户数据来自 `t_user` 表。

- 默认轮询：Spring Cloud LoadBalancer 的 `RoundRobinLoadBalancer`。
- 切随机：启动互动服务时设置 `MSD_LB_STRATEGY=random`。
- 响应中的 `instancePort` 表示实际命中的用户服务实例，便于验证。

```bash
# 两个用户实例注册到同一个 Nacos 服务名
java -jar msd-user/target/msd-user-1.0.0-SNAPSHOT.jar
SERVER_PORT=8111 java -jar msd-user/target/msd-user-1.0.0-SNAPSHOT.jar

# 互动服务：默认轮询
java -jar msd-interaction/target/msd-interaction-1.0.0-SNAPSHOT.jar
# 随机策略
MSD_LB_STRATEGY=random java -jar msd-interaction/target/msd-interaction-1.0.0-SNAPSHOT.jar

TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/interaction/internal/users/1003/profile
```

服务间调用走内网，不经过网关鉴权；生产只暴露网关端口。

## 构建

```bash
mvn -B -U -T 1C clean package -DskipTests
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/roadmap.md](docs/roadmap.md) | 剩余功能路线图：伴看前端、审核、充值、搜索、内容后台与生产化 |
| [docs/architecture.svg](docs/architecture.svg) | 当前项目架构图（含 P1 个性化/多查询、P2 路由/沙箱、P3 异步互动、RexUniNLU advisory 细节） |
| [docs/runbook.md](docs/runbook.md) | 本地启动顺序、命令、排障 |
| [docs/test-data.md](docs/test-data.md) | AI 短剧测试数据：来源、媒体上传、布隆刷新与清理 |
| [docs/rag.md](docs/rag.md) | RAG 推荐架构、五段链路、API、配置与降级 |
| [docs/rex-uninlu.md](docs/rex-uninlu.md) | RexUniNLU 情感/标签建议：模型、API、分片聚合、依赖兼容与降级 |
| [docs/recharge.md](docs/recharge.md) | 积分充值、模拟支付、订单幂等与积分流水 |
| [docs/creator.md](docs/creator.md) | 创作者工作台、管理端审核、审核操作日志 |
| [docs/moderation.md](docs/moderation.md) | 评论治理、首页继续观看、已解锁剧集 |
| [docs/production.md](docs/production.md) | 生产化基础：CI、镜像模板、L1 广播与上线前检查 |
| [docs/companion.md](docs/companion.md) | AI 伴看：摘要、前情检索、防剧透、会话记忆压缩 |
| [docs/audit.md](docs/audit.md) | 结构化内容审核：JSON 契约、标签枚举、降级规则 |
| [docs/auth.md](docs/auth.md) | 登录注册接口、错误码、JWT 约定 |
| [docs/drama.md](docs/drama.md) | 首页推荐与短剧详情接口、热度规则 |
| [docs/cache.md](docs/cache.md) | 多级缓存、布隆、击穿锁、延迟双删、xxl-job、虚拟线程 demo |
| [docs/playback.md](docs/playback.md) | 剧集播放、积分解锁、观看历史上报 |
| [docs/interaction.md](docs/interaction.md) | 点赞、收藏、评论、观看历史列表 |
| [docs/personal-center.md](docs/personal-center.md) | 个人中心资料、统计与四个列表 |
| [docs/frontend.md](docs/frontend.md) | Vue 前端路由、页面接口、视频播放器、构建与静态托管 |
| [docs/schema.md](docs/schema.md) | 17 张核心业务表 + 一致性辅助表、枚举字典、事务与最终一致规则 |
| [docs/chapter-0-baseline.md](docs/chapter-0-baseline.md) | 已验证的版本组合、应用配置、Rerank 契约 |
| [docs/compatibility-matrix.md](docs/compatibility-matrix.md) | 兼容性矩阵与实测证据 |
| [docs/smoke-report.md](docs/smoke-report.md) | 0.4 冒烟测试报告（历史记录） |

## 0.1-0.4 验证结果

| 能力 | 状态 |
|---|---|
| Nacos 配置拉取 / 服务注册 | PASS |
| MySQL / MyBatis-Plus | PASS |
| Redis / Redisson 锁 | PASS |
| Milvus SDK | PASS |
| Kafka 生产/消费 | PASS |
| Spring AI DeepSeek 对话 | PASS |
| 百炼 Embedding（1024 维） | PASS |
| 百炼 Rerank | PASS |
| xxl-job 执行器注册 / 触发 | PASS |
| Swagger UI / OpenAPI 3.1 | PASS |

## 中间件启动

```bash
# 1. 先确保虚拟机上的 Redis、Kafka 已启动
# 2. 再起 9 个容器
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps   # 全部 healthy
```

## Sentinel Dashboard

```bash
docker compose -f deploy/docker-compose.yml up -d sentinel-dashboard
```

- 控制台：`http://127.0.0.1:8858`，账号 `<SENTINEL_USER>/<SENTINEL_PASSWORD>`；只绑定本机。
- 规则：Nacos 分组 `SENTINEL_GROUP`，dataId `sentinel-gateway-flow.json`、`sentinel-agent-degrade.json`。
- 规则由 `python scripts/init-nacos-config.py` 发布；Dashboard 用于监控，手动改规则不会回写 Nacos。
- 客户端的 `SENTINEL_CLIENT_IP` 用宿主机 IPv4（Docker Desktop 默认 `192.168.65.1`），不能用 `host.docker.internal`，Dashboard 会做 IP 校验。

### 网关限流

`msd-gateway` 对 6 条业务路由按客户端 IP 限流，默认 10 QPS/IP；`msd-content-service` 为 5 QPS/IP。规则在 Nacos `SENTINEL_GROUP/sentinel-gateway-flow.json`。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
seq 1 30 | xargs -P 30 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/interaction/internal/users/1003/profile | sort | uniq -c
```

- 超限返回 HTTP 429，body `{"code":42900,"message":"请求过于频繁","data":null,"success":false}`。
- Dashboard 的网关流控页能看到 6 条规则和实时 QPS / 拒绝数。
- 串行 `curl` 在 Windows 上通常达不到 10 QPS，要用并发波次触发。

### AI 熔断降级

`msd-agent-service` 的 `aiChat` 资源用 Sentinel 监控 Mock RAG + LLM 调用，规则在 Nacos `SENTINEL_GROUP/sentinel-agent-degrade.json`：

- 慢调用比例：RT 超过 1000ms 且慢调用比例超过 50%，至少 5 次请求。
- 异常比例：异常比例超过 50%，至少 5 次请求。
- 熔断 10 秒后进入半开。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1001 --role ADMIN)
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/agent/internal/ai/chat?prompt=hi"
```

在 Nacos `agent-config.yaml` 调整 Mock LLM：

```yaml
msd:
  ai:
    mock:
      delay-ms: 50      # 改 1500 触发慢调用熔断
      error-rate: 0.0   # 改 1.0 触发异常熔断
      rag-delay-ms: 120 # 模拟 RAG 检索耗时
      rag-top-k: 3      # 模拟返回片段数
```

- 正常：`degraded=false`。
- 熔断/异常：`degraded=true`，reply 为 `当前太火爆，请稍后再试`。
- Dashboard 的熔断规则页可看到 `aiChat` 的两条规则。

### AgentScope Supervisor

`msd-agent-service` 同时提供基于 AgentScope Java 2.0 的 Supervisor，与上面的 Mock 熔断链路并存：

- 新接口：`POST /api/agent/internal/ai/agent/chat`（直连 8105 为 `/internal/ai/agent/chat`），请求体 `{"message":"...","sessionId":"...","episodeId":6005}`；`episodeId` 可选，伴看问题需要传，`X-User-Id` 由网关 JWT 过滤器注入。
- 主 Agent `supervisor` 读取 `.agentscope/workspace/AGENTS.md`，按路由规则调度 `social-agent`、`audit-agent`、`data-agent`、`companion-agent`、`search-agent`。
- 全局找剧：`SEARCH`/`RECOMMEND` 路由到 `search-agent`，调用真实 RAG 工具 `search_drama_rag`；工具返回 `[DRAMA_CARD]`，服务端解析为 `cards` 供前端渲染可点击短剧卡片。
- 秒级意图路由：`POST http://127.0.0.1:8105/internal/ai/agent/route` 只做规则/缓存决策，不调用模型；网关不暴露该路由。高置信度结果注入 `[ROUTE_HINT=...]`，AUDIT 可走远程快速通道，详见 [docs/companion.md](docs/companion.md#6-意图路由与工具沙箱)。
- 网关只暴露 `/api/agent/internal/ai/agent/chat` 和 ADMIN-only Mock `/api/agent/internal/ai/chat`；配置接口和远程审核仅允许直连。
- 工具：`get_home_dramas`、`get_drama_detail`、`list_drama_episodes`，通过 Feign 查询 `msd-drama` 的只读内部接口；审核任务另有 `audit_remote` 远程 A2A 工具；伴看使用 `get_episode_summary`、`search_drama_context` 查询 content-service 的当前集摘要和前情；找剧使用 `search_drama_rag` 调用 content RAG，详见 [docs/companion.md](docs/companion.md)。
- 工具权限沙箱：只注册 allowlist 业务工具，校验 ID/分页/文本长度并记录参数哈希；shell/文件/Web/记忆工具关闭，单会话最多 4 次 `agent_spawn`。
- 8105/8106 支持 `X-Internal-Token`；生产设置 `MSD_INTERNAL_TOKEN`，网关会剥离客户端伪造值后为 Agent 路由注入。
- 会话状态存 Redis，key 前缀 `mashang:agent:session:`；Redis 连接复用 Nacos `common.yaml` 的 `spring.data.redis.*`。
- 模型默认 `deepseek-flash`，可用 `msd.ai.agent.model` 或 `DEEPSEEK_AGENT_MODEL` 覆盖；必要时用 `DEEPSEEK_BASE_URL` 指向兼容网关。
- 当前开发环境的兼容端点只接受 `deepseek-flash` / `deepseek-v4-pro`；若日志出现 `The supported API model names are ...`，用 `MSD_AI_AGENT_MODEL=deepseek-flash` 或 `--msd.ai.agent.model=deepseek-flash` 启动。
- 缺少 `DEEPSEEK_API_KEY` 时 Supervisor Bean 不创建，新接口返回 `50300`，旧 `/internal/ai/chat` 不受影响。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST "http://127.0.0.1:8000/api/agent/internal/ai/agent/chat" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"message":"查询短剧5001的详情并总结","sessionId":"demo-1"}'

# 只验证路由决策，不消耗模型调用；该接口只允许直连 8105
curl -X POST "http://127.0.0.1:8105/internal/ai/agent/route" \
  -H "Content-Type: application/json" \
  -d '{"message":"第3集里他们为什么吵架","sessionId":"route-1"}'
```

- 首次调用会初始化模型、工作区和 Redis 状态存储，耗时会明显高于后续调用。
- 复用同一个 `sessionId` 可继续同一段对话；响应会回传实际使用的 `sessionId`。
- Supervisor 只启用只读业务工具，shell/web/文件/记忆工具已关闭。

### A2A 审核服务（audit profile）

`audit-agent` 还可以作为独立进程暴露为标准 A2A Endpoint，并注册到 Nacos AI Service。A2A 注册要求 Nacos 3.1+，本项目 Compose 使用 3.1.1。

```bash
# 8106：只加载 audit profile，不创建 Supervisor
NACOS_NAMESPACE=mashang-duanju-dev java -jar msd-agent-service/target/msd-agent-service-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=audit

# AgentCard（公开发现信息）
curl http://127.0.0.1:8106/.well-known/agent-card.json

# 直连 Supervisor 的远程审核演示接口
curl -X POST http://127.0.0.1:8105/internal/ai/agent/audit/remote \
  -H "Content-Type: application/json" \
  -d '{"content":"短剧标题：总裁的替身新娘","sessionId":"a2a-demo-1"}'
```

- A2A JSON-RPC Endpoint：`POST http://127.0.0.1:8106/`；AgentCard 名称 `audit-agent`，版本 `1.0.0`。
- Nacos：命名空间 `mashang-duanju-dev`，控制台「AI 管理 → A2A 服务」可看到卡片和 JSON-RPC Endpoint。
- Supervisor 侧通过 `NacosAgentCardResolver` 动态解析地址，每次调用新建 `A2aAgent`；单次超时 60s，仅对网络/超时/5xx/408/429 重试，最多 2 次，其他 4xx 直接降级。
- 远程不可用时先做 3s 预检，通常 1s 内返回 `source=local-fallback, degraded=true`；工具级阻塞上限 90s，确保 Supervisor 在整体 120s 超时前拿到降级结果并改派本地 `audit-agent`。
- A2A Endpoint 默认只监听本机、不带 JWT，适合内网演示；生产环境需要额外鉴权或专用 A2A 网关。

## RAG 内容推荐

`msd-content-service`（8107）实现短剧 RAG 推荐：查询改写 → Milvus 向量 + Elasticsearch BM25 并行检索 → RRF 融合 → DashScope Rerank → 基于检索资料的推荐理由。content（8107）和 interaction（8104）的 `/actuator/prometheus` 暴露 AI 请求、降级、耗时和反馈指标。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/content/internal/ai/rag/recommend` | 经网关，需要登录；返回回答和最多 3 条来源 |
| POST | `/api/content/internal/ai/rag/recommend/stream` | 经网关，需要登录；SSE 流式找剧，事件 `meta → delta* → sources → done` |
| GET | `/api/content/internal/ai/rag/related` | 经网关，需要登录；相似短剧，Milvus 向量召回，规则兜底 |
| GET | `/api/content/internal/ai/rag/personalized` | 经网关，需要登录；多路召回首页推荐，返回归因 `requestId` |
| POST | `/api/content/internal/ai/rag/preferences` | 经网关，需要登录；保存冷启动分类偏好 |
| POST | `/api/interaction/internal/recommendations/events` | 经网关，需要登录；推荐点击/播放/完播/不感兴趣归因 |
| GET | `/api/interaction/internal/ai/comments/summary` | 经网关，需要登录；评论课代表（仅供参考） |
| GET | `/api/content/internal/ai/companion/recap` | 经网关，需要登录；当前集及之前的前情提要 |
| POST | `/api/content/internal/ai/audit/precheck` | 经网关，需要登录；创作者提交前 AI 预检，不写审核状态 |
| POST | `/api/content/internal/ai/copilot/generate` | 经网关，CREATOR/ADMIN；生成 2-3 版标题/副标题/标签方案 |
| POST | `/api/drama/internal/creator/dramas/{dramaId}/episodes/{episodeId}/subtitles` | 经网关，CREATOR/ADMIN；导入 SRT/VTT 字幕 |
| POST | `/internal-source/episodes/{episodeId}/transcript` | 直连 8102；ASR worker 回传带时间戳片段 |
| GET | `/internal-source/rag/episodes/{episodeId}/transcript` | 直连 8102；字幕导出供 content 索引 |
| POST | `/api/agent/internal/ai/agent/chat` | 经网关，需要登录；全局 AI 找剧助手，返回 `cards` |
| POST | `/api/interaction/internal/ai/feedback` | 经网关，需要登录；按 `requestId` 提交 AI 回答赞/踩 |
| POST | `/internal/ai/rag/reindex` | 直连 8107；全量重建 Milvus/ES 索引 |
| GET | `/internal/ai/rag/status` | 直连 8107；Milvus/ES 可用性和最近索引结果 |

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/recommend \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"有没有那种先婚后爱的甜宠剧","topK":3}'

# 离线评测：recall@3、无结果、引用、防剧透和 P95
python scripts/eval-rag.py --output eval/report.json

# 直连重建和状态
curl -X POST http://127.0.0.1:8107/internal/ai/rag/reindex \
  -H 'Content-Type: application/json' -d '{}'
curl http://127.0.0.1:8107/internal/ai/rag/status
```

- 索引：Milvus collection `msd_drama_rag_v1`，ES index `msd_drama_bm25_v1`。
- 检索：两路各 Top 20，RRF k=60；Rerank 精排 Top 3。
- 多查询扩展：原查询 + 1 个 LLM 变体 + 可选偏好 query，跨查询 RRF 后再精排；关闭时保持单查询行为。
- 个性化：interaction 聚合收藏/点赞/观看权重，content 结合目录分类/标签生成画像；首页卡片规则排序，不调用 LLM。
- 流式推荐：SSE 首帧带 `requestId`，末尾带 sources；网关或首帧失败时前端自动回退普通 JSON。
- 相似短剧：用当前短剧文本做 Milvus 向量召回，排除自身；无向量时回退同分类/共同标签/热度。
- 个性化 v2：继续观看 + 共同观看 + 向量 + 偏好 + 热播多路召回，曝光/点击/播放/完播写 `t_recommendation_*`，支持不感兴趣和冷启动分类偏好。
- 评论 AI：评论课代表只聚合观点；评论异步预审只写 `ai_review_*`，不自动修改人工 `status`。
- 创作者预检：提交前复用结构化审核给出合规结论、标题/副标题/标签建议；只读，不写审核状态，默认 10 次/人/天。
- 增量索引：审核通过/下架在事务提交后发送 `msd.drama.changed.v1`，content 按 `dramaId` 增量更新 Milvus/ES；失败重试，全量 `reindex` 兜底。
- 字幕/ASR：SRT/VTT 导入或离线 faster-whisper 转写后，content 建立 `docType=transcript` 索引；伴看按 `positionMs` 只取已播放片段，回答带 `[第X集 mm:ss]` 引用，前端可点击 seek。
- 降级标记：`rewrite`、`expansion`、`personalization`、`vector`、`bm25`、`rerank`、`generation`、`timeout`；推荐接口始终返回 200 和可读兜底文案。
- `reindex`/`status` 不经网关，避免误操作。
- Milvus 2.6.x 的 `common.storageType` 使用 `remote`；`minio` 已废弃，会导致写入 panic。
- 完整架构、配置、验收和排障见 [docs/rag.md](docs/rag.md)。

## RexUniNLU 情感审核与标签建议

`msd-content-service` 在短剧 AI 审核时并行调用本地 RexUniNLU sidecar（8108）：

- 情感：`positive/negative/mixed/unknown`，负向支持数达到 2/3 片时给出“建议人工复核”；
- 标签：从 drama 启用标签字典提取，最多 5 个，仅作为审核建议；
- 边界：只写 `t_drama.ai_review_result.advisory`，不写 `t_drama_tag_rel`，不改变 `ai_review_status`；
- 降级：sidecar 未启动、超时或解析失败时审核继续，advisory 标记 degraded。

```bash
scripts\setup-rex-uninlu.cmd   # 首次：venv + CPU torch + 模型下载
scripts\start-rex-uninlu.cmd   # 启动 8108
curl http://127.0.0.1:8108/readyz
curl -X POST http://127.0.0.1:8107/internal/ai/audit/dramas/5003
```

模型 `iic/nlp_deberta_rex-uninlu-chinese-base@v1.0.2`（Apache 2.0），权重不进入 git。
完整 API、分片/聚合、依赖兼容、性能和失败演练见 [docs/rex-uninlu.md](docs/rex-uninlu.md)。

## 分布式链路追踪

6 个服务统一使用 Micrometer Tracing + Brave，上报到 Zipkin。网关到下游用 W3C `traceparent` 透传，Feign 调用由 `feign-micrometer` 自动创建 client span。

```bash
# Zipkin 随 compose 启动，UI 只绑定本机
docker compose -f deploy/docker-compose.yml up -d zipkin
curl http://127.0.0.1:9411/health
```

- Zipkin UI：`http://127.0.0.1:9411`；内存存储，容器重启后 trace 丢失。
- 公共配置在 Nacos `common.yaml`：采样率 dev `1.0` / test `0.5` / prod `0.1`，上报地址默认 `http://127.0.0.1:9411/api/v2/spans`。
- Boot 4 的配置前缀是 `management.tracing.export.zipkin.*`，旧的 `management.zipkin.tracing.*` 不生效。
- 网关和 Agent 开启 `spring.reactor.context-propagation=auto`，保证 WebFlux 和 `boundedElastic` 线程切换时 trace 不断。
- Agent 的 AI 请求包含三层 span：`ai.chat` → `rag.retrieve` / `llm.generate`。

验证完整链路：

```bash
ADMIN_TOKEN=$(python scripts/gen-jwt.py --user-id 1001 --role ADMIN)

# Gateway -> interaction -> agent（Mock 代理仅 ADMIN）
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://127.0.0.1:8000/api/interaction/internal/ai/chat?prompt=hi"

# Gateway -> interaction -> user（Feign）
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://127.0.0.1:8000/api/interaction/internal/users/1003/profile"
```

第一条请求在 Zipkin 中应看到 8 个 span，且 traceId 相同：

```text
msd-gateway        http get                          (server)
msd-gateway        http get                          (client)
msd-interaction    http get /internal/ai/chat       (server)
msd-interaction    http get                          (Feign client)
msd-agent-service  http get /internal/ai/chat       (server)
msd-agent-service  ai.chat
msd-agent-service  rag.retrieve
msd-agent-service  llm.generate
```

`rag.retrieve` 约 120ms、`llm.generate` 约 50ms，`ai.chat` 是两者的父 span。日志默认带 `[traceId,spanId]`，例如：

```text
[6ada1a7b5ea72824a2e652c4fe803e4e,0dfc3008205bf8d2] ...
```

Zipkin API：

```bash
curl http://127.0.0.1:9411/api/v2/services
curl "http://127.0.0.1:9411/api/v2/traces?serviceName=msd-gateway&limit=5"
```

## 分布式事务与异步解耦

### 积分解锁（Seata AT）

`msd-order` 的 `UnlockFacade` 使用 `@GlobalTransactional`，把 order 本地事务（扣积分、写流水、写解锁记录）和 drama 分支事务（写解锁引用、`unlock_count + 1`）纳入同一个全局事务，任一步失败由 TC 回滚。

- Seata Server 2.5.0，DB 存储，注册到 Nacos `mashang-duanju-dev` / `SEATA_GROUP`；客户端配置在 `common.yaml`。
- 回滚验证开关：Nacos `msd-order.yaml` 的 `msd.tx.demo.fail-after-remote`，正常必须为 `false`。
- order/drama 当前共用 `DL` 库，验证的是服务级全局事务，不是物理分库。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/orders/episodes/6004/unlock
```

### 互动计数（Outbox + Kafka）

`msd-interaction` 在同一个本地事务里写互动明细和 `t_outbox_event`；`OutboxRelay` 短事务认领 200 条，事务外 8 线程并行投递 Kafka，`msd-drama` 和 `msd-interaction` 两个消费组按 `event_id` 幂等更新计数。

- 投递状态：`0` 待发送、`1` 已发送、`2` 死信；`claim_token + locked_until` 防止多实例重复发送，租约 60 秒。
- 投递失败按 `min(2^retry, 60)` 秒退避，超过 `msd.outbox.max-retry` 转死信；发送更新均带 claim token 条件。
- 消费幂等：`t_event_consume_log(consumer_group, event_id)` 唯一键拦截重复消息；消费日志和计数更新在同一个数据库事务内。
- 评论正文同步落库并返回 `commentId`，点赞数/回复数由 `msd-interaction-counter-v1` 异步派生；`clientRequestId` 保证重复提交只创建一条评论。
- 最终一致：接口只等待明细和 outbox 落库，计数在 1-2 秒内异步更新；`GET /events/{eventId}` 查询 SENT/DEAD 和各消费组处理情况。
- 失败处理：监听器重试 3 次（间隔 1 秒）后投递 `msd.interaction.dlq.v1`；压测用 `scripts/load-test-interaction.py`，对账用 `scripts/check-interaction-consistency.sql`。

```bash
# 查看 outbox 认领和投递状态
mysql -h127.0.0.1 -umsd -p -D DL -e \
  "SELECT id,user_id,topic,event_key,status,retry_count,claim_token,locked_until,last_error FROM t_outbox_event ORDER BY id DESC LIMIT 5"

# 查看两个消费组进度
D:/kafka_2.13-4.1.2/bin/windows/kafka-consumer-groups.bat \
  --bootstrap-server 127.0.0.1:9092 --describe --group msd-drama-counter-v1
D:/kafka_2.13-4.1.2/bin/windows/kafka-consumer-groups.bat \
  --bootstrap-server 127.0.0.1:9092 --describe --group msd-interaction-counter-v1
```

## P4 联调与压测结果（2026-10-27）

本地单机：Windows 11，MySQL/Redis/Kafka 本机，Nacos 容器；该轮 Milvus/ES 未启动。

- 回归：`mvn -B -pl msd-content-service,msd-interaction,msd-drama,msd-agent-service -am test` 共 **101 tests、0 failures**；`npm --prefix frontend run build` 通过。全量 `package` 时 gateway 因运行中 jar 被占用失败，停止 gateway 后单独 repackage 成功。
- 个性化：用户 1006 与 1003 返回不同推荐；未互动用户 9999 返回 `personalized=false, coldStart=true` 并回退热播；网关未登录 401，`/internal-source/**` 经网关 404。
- 多查询：带 `X-User-Id` 时产生 3 条检索 query（原查询 + 变体 + 偏好），不带时 2 条；Milvus/ES 未启动时 `degradedFlags=["vector","bm25"]`，仍返回兜底文案而不报错。
- 路由：COMPANION/AUDIT/DATA/SOCIAL 规则命中置信度 0.9–1.0，决策 2–10ms；重复消息走缓存约 1.6ms；伪造 `[ROUTE_HINT]` 被清洗，注入语句 400，无 `X-Internal-Token` 401；8106 AgentCard 200、无 token JSON-RPC 401、带 token 的注入请求 400。
- 互动压测：2000 calls / 并发 50，**1999 成功、1 失败（0.05%）**，p50/p95/p99 = **21/73/128ms**，wall 1145ms；失败为 MySQL 死锁在 4 次重试后仍失败，低于 0.1% 目标。outbox pending=0，两个消费组 LAG=0。
- 压测清理：416 条 `[load-test]` 评论通过删除接口清理，9 个测试短剧点赞取消；专用压测用户 9998 无残留点赞/评论。
- 对账说明：本地演示 seed 的 like/favorite/comment 是合成基数，`check-interaction-consistency.sql` 会报告预期差异；生产数据或先重建计数后才有对账意义。

## RexUniNLU 联调结果（2026-10-27）

- 回归：Python `pytest` **23 passed**；`mvn -B -pl msd-content-service,msd-drama -am test` **196 tests、0 failures**；前端构建通过。
- 真实模型：5003 审核返回 `aiReviewStatus=2`，`ai_review_result.advisory` 含情感 `negative`、`highNegative=true` 和标签建议，JSON 长度 697。
- 失败演练：停止 8108 后再次审核 5003，`aiReviewStatus` 仍为 2，advisory 为 `degraded=true / rex_unavailable`；内容服务不重启。
- 数据边界：只写 `t_drama.ai_review_result.advisory`，不写 `t_drama_tag_rel`，不触发详情缓存失效或 RAG reindex。
- 性能：典型单片段约 0.2s；5000 字、6 片、12 个标签候选实测约 5.2s，低于 7s 分析预算。
- 兼容性：官方 wrapper 的本地方向、special token 顺序、JDK HTTP/2 升级、`pyarrow`/NumPy 冲突均已处理，详见 [docs/rex-uninlu.md](docs/rex-uninlu.md#6-兼容性陷阱)。

## 数据库初始化

```bash
# 首次：建 DL / xxl_job 库和 msd 用户（需要 root）
mysql -uroot -p < deploy/mysql/init/00_databases.sql

# 业务表 + 种子数据（可重复执行）
scripts\init-db.cmd
```

17 张核心业务表 + 一致性辅助表的设计与枚举见 [docs/schema.md](docs/schema.md)。种子账号 `13800000001`–`13800000013`（含管理员、创作者、普通用户和禁用账号），统一密码 `Msd@123456`（仅本地开发）。`04_mock_data.sql` 额外提供 13 部短剧、56 集剧集及订单、积分、评论、点赞、收藏、观看历史等模拟数据。

## 端口

| 组件 | 端口 | 位置 |
|---|---|---|
| Nacos 控制台 | 8080 | 容器 |
| Nacos API/gRPC | 8848/9848/9849 | 容器 |
| MySQL | 3306 | 本机服务 |
| Redis | 6379 | 虚拟机 `192.168.33.129` |
| Kafka | 9092 | 虚拟机 `192.168.33.129` |
| Milvus | 19530/9091 | 容器 |
| Elasticsearch | 9200 | 容器（只绑 `127.0.0.1`） |
| MinIO | 9000/9001 | 容器 |
| `msd-content-service` | 8107 | 本机服务 |
| `rex-uninlu-service` | 8108 | 本机 Python sidecar（可选） |
| xxl-job-admin | 8088 | 容器 |
| Sentinel Dashboard | 8858 | 容器（只绑 `127.0.0.1`） |
| Zipkin | 9411 | 容器（只绑 `127.0.0.1`） |
| Seata 控制台 | 9988 | 容器（容器内 9898，只绑 `127.0.0.1`） |
| Seata TC | 8091 | 容器（业务服务通过 Nacos 发现后连接） |

## 注意

- 本地开发凭据从 `deploy/.env` 读取；生产必须替换。
- AI 免费模型：`qwen3.7-text-embedding-flash`（1024 维）、`qwen3.7-text-rerank`；对话使用 DeepSeek。
- 真实 API Key 只放环境变量或 `deploy/.env`，不提交仓库。
- 本机 Docker Hub 不可直连，镜像使用 `docker.1panel.live`。
- Kafka 的 Windows 批处理依赖 `wmic`，请使用 `scripts/start-kafka.cmd`。
- 网关测试台 `/internal/dev/token` 默认关闭；本地需要时设置 `MSD_DEV_TOKEN_ENABLED=true`。

## License

[MIT](LICENSE) © 2026 lzj135170-crypto
