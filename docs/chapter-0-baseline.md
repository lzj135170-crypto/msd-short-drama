# 第 0 章 已验证配置基线（归档）

冒烟模块 `msd-infra-smoke` 与对照模块 `msd-demo` 已于 2026-10-22 删除。本文件保留后续业务服务直接复用的部分，验证结论见 [smoke-report.md](smoke-report.md)。

## 1. 版本组合

已实测可编译、可启动、可连通全部中间件：

| 组件 | 版本 |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.8 |
| Spring Cloud | 2025.1.3 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Spring AI | 2.0.1 |
| MyBatis-Plus | 3.5.17（`mybatis-plus-spring-boot4-starter`） |
| springdoc-openapi | 3.1.1 |
| Redisson | 4.8.0（裸客户端） |
| xxl-job | 3.5.0 |
| Milvus | 2.6.4（SDK 2.6.21） |
| Kafka | 4.1.2（kafka-clients 4.1.2） |
| Nacos | Server 3.0.1 / client 3.1.1 |
| Redis | 7.4 |

依赖管理顺序（根 `pom.xml`）：Spring Cloud → SCA → Spring AI BOM → MyBatis-Plus BOM → 显式版本。

## 2. 应用配置基线

```yaml
spring:
  application:
    name: <service>
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  config:
    import:
      - nacos:common.yaml?group=DEFAULT_GROUP&refreshEnabled=true
      - optional:nacos:<service>.yaml?group=DEFAULT_GROUP&refreshEnabled=true
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:127.0.0.1:8848}
      config:
        namespace: ${NACOS_NAMESPACE:mashang-duanju-${spring.profiles.active}}
        group: DEFAULT_GROUP
        file-extension: yaml
        refresh-enabled: true
      discovery:
        namespace: ${NACOS_NAMESPACE:mashang-duanju-${spring.profiles.active}}
        group: DEFAULT_GROUP
        ip: ${NACOS_DISCOVERY_IP:127.0.0.1}

  datasource:
    url: ${MSD_DB_URL:jdbc:mysql://127.0.0.1:3306/DL?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}
    username: ${MSD_DB_USERNAME:msd}
    password: ${MSD_DB_PASSWORD:}
    hikari:
      maximum-pool-size: 5
      connection-timeout: 3000
      initialization-fail-timeout: -1   # 中间件未就绪时不阻塞启动

  data:
    redis:
      host: ${REDIS_HOST:192.168.33.129}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2s

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:192.168.33.129:9092}
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

  ai:
    model:
      chat: deepseek            # 必须显式指定，否则两个 ChatModel 会让 ChatClient.Builder 注入失败
      embedding: openai
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      chat:
        options:
          model: ${DEEPSEEK_MODEL:deepseek-flash}
          temperature: 0.3
    openai:
      api-key: ${DASHSCOPE_API_KEY:}
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}  # 必须带 /v1
      embedding:
        options:
          model: ${DASHSCOPE_EMBEDDING_MODEL:qwen3.7-text-embedding-flash}
          dimensions: 1024
    vectorstore:
      milvus:
        client:
          host: ${MILVUS_HOST:127.0.0.1}
          port: ${MILVUS_PORT:19530}
        database-name: default
        collection-name: <service_collection>
        embedding-dimension: 1024
        initialize-schema: false
```

> SCA 2025.1 已移除 bootstrap 支持，`bootstrap.yml` 不会生效；Nacos 配置统一通过 `spring.config.import` 加载。Agent 服务额外导入 `agent-config.yaml`。命名空间按环境隔离：`mashang-duanju-dev` / `-test` / `-prod`。

## 3. 百炼 Rerank 契约

Spring AI 2.0 没有一等的 `RerankModel`，走百炼原生接口。

```text
POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
Authorization: Bearer ${DASHSCOPE_API_KEY}
Content-Type: application/json
```

请求：

```json
{
  "model": "qwen3.7-text-rerank",
  "input": { "query": "适合女生的甜宠短剧", "documents": ["...", "..."] },
  "parameters": { "return_documents": false }
}
```

响应：

```json
{ "output": { "results": [ { "index": 0, "relevance_score": 0.6679 } ] } }
```

实现要点（原 `DashScopeRerankClient`，RAG 阶段在 `msd-ai` 重建）：

```java
RestClient client = RestClient.builder()
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .build();

Map<String, Object> body = new LinkedHashMap<>();
body.put("model", model);
body.put("input", Map.of("query", query, "documents", documents));
body.put("parameters", Map.of("return_documents", false));

// 取 output.results[].index / relevance_score
```

## 4. 中间件连接参数

| 组件 | 地址 | 凭据（仅本地） |
|---|---|---|
| MySQL | `127.0.0.1:3306`，库 `DL` | `msd / $MSD_DB_PASSWORD` |
| Nacos | `127.0.0.1:8848`，控制台 `8080` | `nacos / $NACOS_PASSWORD` |
| Redis | `192.168.33.129:6379`（虚拟机 7.2.14） | `REDIS_PASSWORD` |
| Kafka | `192.168.33.129:9092`（虚拟机） | 无 |
| Milvus | `127.0.0.1:19530` | 无 |
| xxl-job admin | `http://127.0.0.1:8088`，context-path `/` | `admin / $XXL_JOB_ADMIN_PASSWORD` |
| xxl-job executor | `http://host.docker.internal:9999` | header `XXL-JOB-ACCESS-TOKEN` + `XXL-JOB-APPNAME` |

> 分布：MySQL 是本机服务；Redis、Kafka 在虚拟机 `192.168.33.129`；Nacos、MinIO、Milvus、xxl-job 由 `deploy/docker-compose.yml` 启动。容器内互访用服务名：`nacos:8848`、`milvus:19530`。

## 5. 已知坑

1. `spring.ai.model.chat` / `spring.ai.model.embedding` 必须显式设置。
2. 百炼 Embedding base-url 少 `/v1` 会 404（Spring AI 会自行追加 `/embeddings`）。
3. Milvus auto-config 在启动阶段强连 19530；Milvus 未启动时需先起 Milvus 或临时排除 `MilvusVectorStoreAutoConfiguration`。
4. xxl-job 3.5.0 admin 的 context-path 是 `/`，不是 `/xxl-job-admin`。
5. Kafka 在虚拟机上时，`advertised.listeners` 必须是 `192.168.33.129:9092`；配成 localhost 会出现「能连 bootstrap、随后超时」。
6. Nacos 管理 API 即使鉴权关闭也要 `accessToken`，登录接口 `POST /v3/auth/user/login`。

RAG 实现在 `msd-content-service`（8107）：Milvus 向量 + Elasticsearch BM25 + DashScope Rerank，详见 [rag.md](rag.md)。
