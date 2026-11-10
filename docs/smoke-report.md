# 0.4 冒烟测试报告（历史）

日期：2026-10-22

> `msd-infra-smoke` 与 `msd-demo` 已在验证通过后删除，本文的启动与复现命令不再可执行，仅作为验证证据保留。可直接复用的配置、Rerank 契约和已知坑已整理到 [chapter-0-baseline.md](chapter-0-baseline.md)。

## 环境

| 组件 | 版本/地址 |
|---|---|
| 应用 | `msd-infra-smoke` 1.0.0-SNAPSHOT，端口 8099 |
| Spring Boot | 4.0.8 |
| Spring Cloud | 2025.1.3 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Spring AI | 2.0.1 |
| MySQL | 本机 8.0.45，库 `DL` |
| Nacos | 本机 3.0.1，namespace `msd-dev`，group `MSD` |
| Kafka | 本机 4.1.2，KRaft |
| Redis | Docker 7.4，6379 |
| Milvus | Docker 2.6.4，19530 |
| xxl-job | admin 3.5.0，8088；executor 3.5.0，9999 |

## 结果

| # | 检查 | 接口/命令 | 结果 |
|---|---|---|---|
| 1 | 应用存活 | `GET /smoke/ping` | PASS |
| 2 | Nacos 配置 | `GET /smoke/nacos` | PASS，返回 `from-nacos` |
| 3 | MySQL | `GET /smoke/db` | PASS，`select1=1`，`database=dl` |
| 4 | Redis | `GET /smoke/redis` | PASS，写入/读取一致 |
| 5 | Redisson 锁 | `GET /smoke/redisson` | PASS，`locked=true` |
| 6 | Milvus SDK | `GET /smoke/milvus` | PASS，`listCollections` 返回 `[]` |
| 7 | 依赖 classpath | `GET /smoke/deps` | PASS，10 项全部 `true` |
| 8 | Kafka 生产/消费 | `POST /smoke/kafka/produce` + `GET /smoke/kafka/count` | PASS，count 增加 |
| 9 | DeepSeek 对话 | `GET /smoke/ai` | PASS，返回中文回答 |
| 10 | 百炼 Embedding | `GET /smoke/ai` | PASS，`qwen3.7-text-embedding-flash`，维度 1024 |
| 11 | 百炼 Rerank | `GET /smoke/rerank` | PASS，`qwen3.7-text-rerank`，返回相关性分数 |
| 12 | xxl-job 执行器注册 | `xxl_job_registry` | PASS，`msd-infra-smoke -> http://host.docker.internal:9999` |
| 13 | xxl-job 任务触发 | `POST :9999/trigger` | PASS，`{"code":200,"msg":"Success"}` |
| 14 | Swagger UI | `/swagger-ui/index.html` | PASS，HTTP 200 |
| 15 | OpenAPI | `/v3/api-docs` | PASS，HTTP 200 |

## 关键输出

```json
GET /smoke/ping
{"code":0,"message":"成功","data":{"status":"UP","application":"msd-infra-smoke"}}

GET /smoke/nacos
{"code":0,"message":"成功","data":"from-nacos"}

GET /smoke/db
{"code":0,"message":"成功","data":{"select1":1,"database":"dl"}}

GET /smoke/redis
{"code":0,"message":"成功","data":{"written":"ok-...","read":"ok-...","match":true}}

GET /smoke/redisson
{"code":0,"message":"成功","data":{"status":"OK","locked":true}}

GET /smoke/milvus
{"code":0,"message":"成功","data":[]}

GET /smoke/deps
{"code":0,"message":"成功","data":{"mybatis-plus":true,"mysql-driver":true,"spring-data-redis":true,"redisson":true,"spring-kafka":true,"spring-ai-chat":true,"spring-ai-embedding":true,"milvus-sdk":true,"spring-ai-milvus":true,"xxl-job":true}}

GET /smoke/ai
{"code":0,"message":"成功","data":{"chat":"码上短剧是扫码即看的精品短剧平台。","embeddingDimension":1024}}

GET /smoke/rerank
{"code":0,"message":"成功","data":{"query":"适合女生的甜宠短剧","results":[{"index":0,"score":0.6679185811428614},{"index":2,"score":0.4799644440506127},{"index":3,"score":0.3479452202110712},{"index":1,"score":0.31450243010863266}]}}

POST /smoke/kafka/produce?message=smoke-0.4-final
{"code":0,"message":"成功","data":"sent:smoke-0.4-final"}

GET /smoke/kafka/count
{"code":0,"message":"成功","data":1}
```

xxl-job 触发：

```text
POST http://127.0.0.1:9999/trigger
Headers: XXL-JOB-ACCESS-TOKEN, XXL-JOB-APPNAME
Body: TriggerParam(jobId=1, executorHandler=infraSmokeJob, glueType=BEAN, ...)
Response: {"code":200,"msg":"Success"}
应用日志: infraSmokeJob executed at ...
```

## 重要修正

1. **Spring AI 2.0 模型选择**：使用 `spring.ai.model.chat=deepseek`、`spring.ai.model.embedding=openai`，而不是 `spring.ai.openai.chat.enabled=false`；否则会同时存在 `deepSeekChatModel` 与 `openAiChatModel`，`ChatClient.Builder` 注入失败。
2. **百炼 OpenAI 兼容 Embedding 地址**：必须配置 `https://dashscope.aliyuncs.com/compatible-mode/v1`；Spring AI 的 OpenAI 客户端会在 base-url 后追加 `/embeddings`，少 `/v1` 会 404。
3. **免费模型**：Embedding 使用 `qwen3.7-text-embedding-flash`（1024 维），Rerank 使用 `qwen3.7-text-rerank`；Rerank 走百炼原生接口，Spring AI 2.0 无内置 RerankModel。
4. **AI Key 注入**：`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY` 通过环境变量注入，未写入仓库；仓库密钥扫描无命中。
5. **xxl-job 执行器触发接口**：3.5.0 executor 的 HTTP 路径是 `/trigger`（不是 `/run`），且必须同时带 `XXL-JOB-ACCESS-TOKEN` 与 `XXL-JOB-APPNAME` 两个 Header。

## 复现命令

```bash
# 启动应用（Key 从安全位置注入，不写文件）
DEEPSEEK_API_KEY=... \
DASHSCOPE_API_KEY=... \
NACOS_DISCOVERY_ENABLED=true \
REDIS_PASSWORD=$REDIS_PASSWORD \
MSD_SMOKE_REDIS_ENABLED=true \
MSD_DB_PASSWORD=$MSD_DB_PASSWORD \
MSD_SMOKE_XXL_JOB_ENABLED=true \
XXL_JOB_ACCESS_TOKEN=$XXL_JOB_ACCESS_TOKEN \
java -jar msd-infra-smoke/target/msd-infra-smoke-1.0.0-SNAPSHOT.jar

# 基础检查
for ep in ping nacos db redis redisson milvus deps ai rerank; do
  curl -s "http://127.0.0.1:8099/smoke/$ep"; echo
done

# Kafka
curl -s -X POST "http://127.0.0.1:8099/smoke/kafka/produce?message=smoke"
sleep 2
curl -s "http://127.0.0.1:8099/smoke/kafka/count"

# xxl-job
curl -s -X POST "http://127.0.0.1:9999/trigger" \
  -H "Content-Type: application/json" \
  -H "XXL-JOB-ACCESS-TOKEN: $XXL_JOB_ACCESS_TOKEN" \
  -H "XXL-JOB-APPNAME: msd-infra-smoke" \
  -d '{"jobId":1,"executorHandler":"infraSmokeJob","executorParams":"","executorBlockStrategy":"SERIAL_EXECUTION","executorTimeout":0,"logId":1,"logDateTime":0,"glueType":"BEAN","glueSource":"","glueUpdatetime":0,"broadcastIndex":0,"broadcastTotal":1}'
```

## 结论

0.4 冒烟测试全部通过。基础设施与中间件（Nacos、MySQL、Redis、Redisson、Kafka、Milvus、xxl-job）以及 Spring AI 2.0 的 DeepSeek 对话、百炼 Embedding 均已打通，可以进入业务域开发。
