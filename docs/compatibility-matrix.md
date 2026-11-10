# 兼容性矩阵

记录日期：2026-10-22

> 历史记录。验证用的 `msd-infra-smoke` 模块已于 2026-10-22 删除，下表中的 `/smoke/*` 接口不再存在；可复用的配置见 [chapter-0-baseline.md](chapter-0-baseline.md)。

## 基线

| 组件 | 版本 |
|---|---|
| Java | 21.0.11 |
| Spring Boot | 4.0.8 |
| Spring Cloud | 2025.1.3 |
| Spring Cloud Alibaba | 2025.1.0.0（Nacos client 3.1.1） |
| Spring AI | 2.0.1（DeepSeek/OpenAI/Milvus starters；Embedding `qwen3.7-text-embedding-flash`，Rerank `qwen3.7-text-rerank`） |
| MyBatis-Plus | 3.5.17（boot4 starter） |
| springdoc-openapi | 3.1.1 |
| Redisson | 4.8.0（裸客户端） |
| xxl-job | admin 3.5.0 / core 3.5.0 |
| Milvus | 2.6.4（Spring AI SDK 2.6.21） |
| MySQL | 本机 8.0.45，库 `DL` |
| Kafka | 本机 4.1.2（KRaft），kafka-clients 4.1.2 |
| Nacos Server | 本机 3.0.1 |
| Redis | 7.4（Docker） |

## 结果（0.1-0.4）

| 组合/检查 | 状态 | 证据/备注 |
|---|---|---|
| Boot 4.0.8 + Cloud 2025.1.3 | **PASS** | `mvn -B -U -T 1C clean package -DskipTests` 全模块 SUCCESS |
| Boot 4.0.8 + SCA 2025.1.0.0 | **PASS** | 应用启动、Nacos 配置拉取、服务注册成功 |
| Nacos client 3.1.1 ↔ Server 3.0.1 | **PASS** | `/smoke/nacos` 返回 `from-nacos`；实例 `10.246.47.102:8099` healthy |
| Spring AI 2.0.1 启动 | **PASS** | DeepSeek/OpenAI/Milvus starters 加载，上下文启动 |
| Spring AI 模型调用 | **PASS** | `/smoke/ai`：DeepSeek 对话返回中文；`qwen3.7-text-embedding-flash` 维度 1024 |
| 百炼 Rerank | **PASS** | `/smoke/rerank`：`qwen3.7-text-rerank` 返回 4 条相关性分数 |
| Spring AI Milvus auto-config | **PASS** | Milvus 2.6.4 启动后放开 auto-config，应用启动并注入 `MilvusServiceClient` |
| Milvus SDK 2.6.21 ↔ Server 2.6.4 | **PASS** | `/smoke/milvus` 调用 `listCollections` 成功 |
| MyBatis-Plus 3.5.17 boot4 | **PASS** | `/smoke/db` 返回 `select1=1`、`database=dl` |
| MySQL 8.0.45 | **PASS** | 建库 `DL`、`xxl_job`；`msd` 账号可连接 |
| springdoc 3.1.1 | **PASS** | `/swagger-ui.html` 302 到 `/swagger-ui/index.html`（200）；`/v3/api-docs` 返回 OpenAPI 3.1 |
| Kafka 4.1.2 ↔ Boot 4.0.8 kafka-clients 4.1.2 | **PASS** | 8 个 Topic 创建；`/smoke/kafka/produce` 后 `/smoke/kafka/count` 增加 |
| Redis 7.4 + Spring Data Redis | **PASS** | `/smoke/redis` SET/GET 一致 |
| Redisson 4.8.0 | **PASS** | `/smoke/redisson` 获取锁成功 |
| xxl-job admin 3.5.0 + core 3.5.0 | **PASS** | 执行器注册成功；`xxl_job_registry` 有记录 |
| xxl-job 手动触发 `infraSmokeJob` | **PASS** | `POST :9999/trigger` 返回 `{"code":200,"msg":"Success"}`；应用日志有执行记录 |
| 无中间件时启动容错 | **PASS** | Hikari `initialization-fail-timeout=-1`；Nacos optional；Milvus 未启动时需排除 auto-config |

## 0.3 实测输出

```text
GET /smoke/nacos    -> {"data":"from-nacos"}
GET /smoke/db       -> {"data":{"select1":1,"database":"dl"}}
GET /smoke/redis    -> {"data":{"written":"ok-...","read":"ok-...","match":true}}
GET /smoke/redisson -> {"data":{"status":"OK","locked":true}}
GET /smoke/milvus   -> {"data":[]}
POST /smoke/kafka/produce -> {"data":"sent:full-check"}
GET /smoke/kafka/count    -> {"data":1}
```

xxl-job 注册表：

```text
EXECUTOR  msd-infra-smoke  http://host.docker.internal:9999  2026-10-22 00:40:05
```

## 已知问题与处理

1. **Kafka Windows 脚本依赖 wmic**：Windows 11 已移除 `wmic`，`kafka-server-start.bat` 无法启动。改用 `scripts/start-kafka.cmd`，直接 `java -cp libs\* kafka.Kafka`。
2. **Kafka KRaft format**：需要 `--standalone`，否则报 `controller.quorum.voters is not set`。
3. **Nacos 管理 API 需要 token**：即使 `nacos.core.auth.enabled=false`，`/v3/console/*` 仍需 `accessToken`；登录接口是 `POST /v3/auth/user/login`（8080 根路径）。
4. **xxl-job 3.5.0 控制台路径**：context-path 是 `/`，不是 `/xxl-job-admin`；登录页 `http://localhost:8088/auth/login`。
5. **xxl-job 默认数据中文乱码**：导入时加 `--default-character-set=utf8mb4`。
6. **Milvus auto-config 强连**：Milvus 未启动时 `MilvusVectorStoreAutoConfiguration` 会在启动阶段连接 19530 并失败；先启动 Milvus，或临时排除。
7. **Docker Hub 不可直连**：Redis/Milvus/xxl-job 使用 `docker.1panel.live` 镜像源；etcd/MinIO 使用本机缓存镜像。
