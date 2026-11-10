# 生产化基础与待办

P7 只完成了可以在本地验证的基础项；真实视频、真实支付、生产级安全和更大规模架构仍需要外部服务或独立排期。

## 1. 已完成

### CI

`.github/workflows/ci.yml`：

- 后端：JDK 21，`mvn -B -ntp -T 1C test`。
- 前端：Node 22，`npm ci && npm run build`。
- 测试全部是 JUnit + Mockito 单元测试，不依赖 MySQL/Nacos/Kafka。

### 容器镜像模板

`deploy/docker/Dockerfile.service` 是通用 Spring Boot jar 镜像模板：

```bash
mvn -B -pl msd-drama -am package -DskipTests
docker build -f deploy/docker/Dockerfile.service \
  --build-arg JAR_FILE=msd-drama/target/msd-drama-1.0.0-SNAPSHOT.jar \
  -t msd-drama:local .
```

基础设施仍由 `deploy/docker-compose.yml` 提供；业务服务容器化编排、镜像仓库和部署流水线未实现。

### 审核操作日志

- `t_drama_review_log` 记录 SUBMIT / APPROVE / REJECT / OFFLINE / AI_REVIEW。
- 管理端 `GET /api/drama/internal/admin/dramas/{id}/logs` 查询最近 50 条。
- AI 和人工操作共用一张日志表，审核台可直接查看。

### L1 跨实例失效

- `CacheEvictionBroadcaster` 使用 Redis Topic `msd:drama:cache:evict` 广播失效消息。
- 其他实例只清理本地 Caffeine，不再重复删除 L2 或再次广播。
- `msd-drama.yaml` 的 `msd.cache.evict.broadcast-enabled=true`。
- Redis 不可用时广播静默跳过，不影响主流程；本地仍依赖短 TTL 兜底。

### 通用角色校验

- `msd-common` 提供 `UserRole` 和 `RoleGuard`。
- 创作者/管理员接口在服务内二次校验 `X-User-Role`；网关负责认证和注入。
- 经网关调用 AI 审核/重建索引时要求 ADMIN；直连 8107 的运维调用可不带角色。

## 2. 尚未实现

| 项 | 原因 / 后续 |
|---|---|
| 真实视频、封面、转码、CDN、签名 URL、防盗链 | 需要对象存储、转码服务和播放器；当前使用本地 MinIO 短片 + 原生 `<video>` |
| 真实支付（微信/支付宝）、回调验签、退款、对账 | 需要商户资质、证书和回调域名 |
| Spring Security + RBAC 持久化、操作审计、全服务 mTLS | 当前为网关 JWT；Agent/Audit 已加 `X-Internal-Token`，其余服务仍靠服务内 header 与内网隔离 |
| VIP/订阅、优惠券、积分过期、防刷 | 产品功能，未排期 |
| RAG 增量索引失败自动全量恢复 | 已接入 `DRAMA_CHANGED` 增量索引和调度重试；重试耗尽后仍依赖人工全量 reindex |
| 搜索上量 | 当前 MySQL LIKE；数据量上来后改 FULLTEXT ngram、ES 或独立搜索服务 |
| 字幕/ASR 生产化 | Demo 支持 SRT/VTT 导入和离线 faster-whisper 回传；对象存储、大文件分片、版权和人工校验未做 |
| AI 评估与实验 | 已有 golden set、离线脚本、稳定分桶和指标；没有实验管理后台、自动显著性分析和真实 token 账单 |
| 创作者 Copilot | 已有 2-3 版本文案、单版本预检和采纳反馈；标签写回、自动 A/B 发布和素材库未做 |
| 服务/数据库拆分、Kubernetes、压测、监控告警 | 当前单库、jar 本地运行 |
| 服务端聊天历史、跨设备同步 | 流式和本地多会话已接入；服务端会话历史未做 |

## 3. 上线前最低检查

1. 更换所有默认密码、JWT 密钥和 Nacos token，设置 `MSD_INTERNAL_TOKEN`，`deploy/.env` 不进入镜像；非 `dev`/`test` profile 下 JWT 密钥为空或仍为仓库默认值会启动失败。
2. 网关只暴露 8000；`/internal-source/**`、`/internal/**` 只允许内网。
3. 为 MySQL、Redis、Kafka、Nacos、Milvus、ES 配置认证和备份。
4. 配置 HTTPS、CORS 白名单和网关限流。
5. 用真实数据执行一次全量 reindex，并验证降级路径。
6. 执行 P2 字幕表迁移，创建 `msd.transcript.changed.v1`，先全量 reindex 再导入字幕/跑 ASR。
7. 发布 `content-config.yaml` 的治理/Copilot 配置，跑一次 `python scripts/eval-rag.py`，确认 recall、无结果、引用和防剧透阈值。
8. 为服务增加健康检查、日志采集和指标告警。
