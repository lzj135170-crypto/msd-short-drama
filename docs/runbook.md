# 本地运行手册

## 1. 启动顺序

1. MySQL（Windows 服务 `MySQL80`，已运行，不容器化）
2. 虚拟机 `192.168.33.129`：Redis、Kafka
3. Docker Desktop
4. `docker compose -f deploy/docker-compose.yml up -d`（Nacos / etcd / MinIO / Milvus / Elasticsearch / xxl-job-admin / Sentinel Dashboard / Zipkin / Seata Server，共 9 个容器）
5. 可选：RexUniNLU sidecar（8108），首次先执行 `scripts\setup-rex-uninlu.cmd`，再执行 `scripts\start-rex-uninlu.cmd`

应用侧连接参数与 AI 配置见 [chapter-0-baseline.md](chapter-0-baseline.md)。

## 2. MySQL

首次初始化（需要 root 密码）：

```bash
mysql -uroot -p < deploy/mysql/init/00_databases.sql
mysql -uroot -p --default-character-set=utf8mb4 < deploy/mysql/init/01_xxl_job.sql
```

说明：
- 业务库：`DL`；xxl-job 库：`xxl_job`；Seata Server 库：`seata`。
- 本地账号：`msd / $MSD_DB_PASSWORD`（仅本地开发，生产必须替换）。
- xxl-job 默认数据含中文，导入时必须加 `--default-character-set=utf8mb4`，否则会报 `Incorrect string value`。
- 如果 `01_xxl_job.sql` 已建表但默认数据未导入，可只补数据：
  ```bash
  mysql -uroot -p -D xxl_job --default-character-set=utf8mb4 < <(sed -n '/START TRANSACTION;/,$p' deploy/mysql/init/01_xxl_job.sql)
  ```

### 2.1 业务表初始化

```bash
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/02_business_schema.sql
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/03_business_seed.sql
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/04_mock_data.sql
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/05_consistency.sql
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 < deploy/mysql/init/06_seata_server.sql
# 或
scripts\init-db.cmd
```

- 17 张核心业务表 + `undo_log`、`t_episode_unlock_ref`、`t_event_consume_log` 等一致性辅助表，表结构、枚举和一致性规则见 [schema.md](schema.md)。
- `06_seata_server.sql` 依赖 `00_databases.sql` 创建 `seata` 库；首次初始化必须先用 root 跑 00。
- 基础种子账号：`13800000001`–`13800000003`；扩展模拟账号：`13800000004`–`13800000013`，统一密码 `Msd@123456`（仅本地开发）。
- 扩展数据包含 13 个用户、13 部短剧（上架/审核中/草稿/驳回）、56 集剧集，以及订单、积分流水、解锁、评论、点赞、收藏、观看历史。
- 脚本可重复执行：表用 `CREATE TABLE IF NOT EXISTS`，种子行用 `ON DUPLICATE KEY UPDATE`，会重置种子行但不删除其他数据。

### 2.2 xxl-job 短剧执行器

`03_xxl_job_drama.sql` 注册 `msd-drama-executor` 和每 5 分钟执行的 `dramaCacheRebuildHandler`：

```bash
mysql -h127.0.0.1 -umsd -p --default-character-set=utf8mb4 \
  < deploy/mysql/init/03_xxl_job_drama.sql
```

- SQL 默认把执行器组 `access_token` 设为本地 dev 的 `$XXL_JOB_ACCESS_TOKEN`，必须与 Nacos `msd-drama.yaml` 的 `xxl.job.accessToken` 一致。test/prod 执行前改成对应 token。
- 任务详情和 token 排障见 [cache.md](cache.md) 第 4 节。

## 3. 虚拟机中间件 192.168.33.129

| 组件 | 端口 | 版本 | 说明 |
|---|---|---|---|
| Redis | 6379 | 7.2.14 | 已设 `requirepass`，密码放 `deploy/.env` 的 `REDIS_PASSWORD` |
| Kafka | 9092 | 已安装 | KRaft，需要手动启动 |

宿主机可达性：

```bash
ping 192.168.33.129
docker exec msd-nacos sh -c 'nc -z 192.168.33.129 6379'   # 可选：从容器侧验证
```

### 3.1 Redis

```bash
redis-cli -h 192.168.33.129 -a "$REDIS_PASSWORD" ping   # PONG
```

> 密码不要写进仓库；本地放 `deploy/.env`（已 gitignore）。

### 3.2 Kafka 启动（虚拟机内执行）

配置文件 `config/kraft/server.properties`：

```properties
process.roles=broker,controller
node.id=1
listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
advertised.listeners=PLAINTEXT://192.168.33.129:9092
controller.quorum.voters=1@192.168.33.129:9093
```

**`advertised.listeners` 必须是 `192.168.33.129:9092`**。写成 `localhost` 时，宿主机能连上 bootstrap，但拿到 broker 元数据后会去连 `127.0.0.1:9092` 然后超时。

首次格式化（只做一次）：

```bash
bin/kafka-storage.sh random-uuid
bin/kafka-storage.sh format -t <UUID> -c config/kraft/server.properties
```

启动：

```bash
bin/kafka-server-start.sh -daemon config/kraft/server.properties
```

放行防火墙：

```bash
firewall-cmd --add-port=9092/tcp --permanent && firewall-cmd --reload
# 或 ufw allow 9092/tcp
```

### 3.3 创建 Topic（宿主机执行）

```bat
set JAVA_HOME=C:\path\to\jdk-21
set KAFKA_HOME=C:\path\to\kafka_2.13-4.1.2
scripts\create-topics.cmd
```

或手动：

```bash
export JAVA_HOME="/path/to/jdk-21"
export KAFKA_HOME="/path/to/kafka_2.13-4.1.2"
"$KAFKA_HOME/bin/windows/kafka-topics.bat" \
  --bootstrap-server 192.168.33.129:9092 --list
```

Topic 列表（6 分区、1 副本；`scripts/create-topics.cmd` 会把已有 3 分区扩到 6）：
- `msd.infra.smoke.v1`
- `msd.interaction.like.v1`
- `msd.interaction.favorite.v1`
- `msd.interaction.comment.v1`
- `msd.interaction.history.v1`
- `msd.drama.changed.v1`
- `msd.transcript.changed.v1`
- `msd.ai.review.request.v1`
- `msd.interaction.dlq.v1`

> `scripts/start-kafka.cmd` 只用于本机 Kafka fallback，运行前需要设置 `JAVA_HOME` 和 `KAFKA_HOME`，默认不用。

## 4. Docker 容器

```bash
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps
```

| 服务 | 端口 | 说明 |
|---|---|---|
| Nacos | 8080/8848/9848/9849 | 3.1.1，standalone；A2A AgentCard 注册需要 3.1+ |
| etcd | 不暴露 | Milvus 元数据 |
| MinIO | 9000/9001 | `<MINIO_ROOT_USER>/<MINIO_ROOT_PASSWORD>`（本地） |
| Milvus | 19530/9091 | 2.6.4 |
| Elasticsearch | 9200 | 8.15.3，BM25 索引，单节点、关闭安全认证，只绑 `127.0.0.1` |
| xxl-job-admin | 8088 | 3.5.0，**context-path 为 `/`**，登录 `http://localhost:8088/auth/login`，`admin/$XXL_JOB_ADMIN_PASSWORD` |
| Sentinel Dashboard | 8858 | 1.8.9，控制台 `http://127.0.0.1:8858`，`<SENTINEL_USER>/<SENTINEL_PASSWORD>` |
| Zipkin | 9411 | 3.6.1，UI `http://127.0.0.1:9411`，内存存储 |
| Seata Server | 9988/8091 | 2.5.0，控制台 `http://127.0.0.1:9988`（容器内 9898），TC 8091 注册到 Nacos |

说明：
- Docker Hub 在本机不可直连，镜像统一走 `docker.1panel.live`。
- xxl-job-admin 通过 `host.docker.internal:3306` 连接宿主 MySQL 的 `xxl_job` 库；MySQL 未启动时容器会反复重启。
- 全部容器使用 `msd-net` 网络，容器间用服务名互访。
- 数据卷：`nacos_data`、`nacos_logs`、`etcd_data`、`minio_data`、`milvus_data`、`es_data`、`xxl_job_logs`。

### Nacos 3.1.1 升级与回滚

A2A AgentCard 注册需要 Nacos 3.1+，当前 Compose 使用 `v3.1.1`。升级前备份 embedded storage：

```bash
mkdir -p deploy/nacos/backup
docker run --rm -v msd-infra_nacos_data:/data:ro \
  -v "$(pwd)/deploy/nacos/backup:/backup" alpine \
  sh -c 'tar czf /backup/nacos-data-3.0.1-before-3.1.1.tgz -C /data .'
docker compose -f deploy/docker-compose.yml up -d nacos
docker inspect -f '{{.State.Health.Status}}' msd-nacos
```

- 验证：控制台 `http://127.0.0.1:8080` 可登录，`mashang-duanju-dev` 下 Nacos 配置和业务服务仍在；`GET /nacos/v3/admin/ai/a2a/list?...&search=blur` 返回成功。
- 3.1.1 健康后，原配置/服务通常自动保留；缺失时重跑 `python scripts/init-nacos-config.py` 并重启业务服务。
- 回滚：把 `deploy/docker-compose.yml` 镜像改回 `v3.0.1` 后 `up -d nacos`；若数据卷已被新版本迁移且不兼容，停止容器后用上面的备份包恢复 `nacos_data` 卷（恢复前先确认没有业务写入）。
- 升级窗口内业务服务会短暂重连 Nacos；配置已缓存的进程不受影响。

### Seata Server

| 用途 | 地址 |
|---|---|
| 控制台/健康检查 | `http://127.0.0.1:9988/health`（容器内 9898） |
| TC 端口 | `192.168.65.1:8091`，通过 Nacos 发现 |
| Nacos 注册 | `mashang-duanju-dev` / `SEATA_GROUP` / `seata-server` / cluster `default` |
| 存储 | MySQL `seata` 库，表 `global_table`、`branch_table`、`lock_table`、`distributed_lock`、`vgroup_table` |

- `SEATA_IP` 必须是宿主机可达的非回环 IP；Seata 拒绝 `127.0.0.1`，设回环会回退成容器 IP，宿主机连不上。Docker Desktop 默认 `192.168.65.1`，其他机器在 `deploy/.env` 覆盖。
- 2.1+ 官方镜像不自带 MySQL 驱动，`deploy/seata/libs/mysql-connector-j.jar` 已挂到 `/seata-server/libs/`。
- 日志：`docker logs -f msd-seata-server`。

### 积分解锁与全局回滚

`msd-order` 的 `UnlockFacade` 用 `@GlobalTransactional` 包住 order 本地事务和 drama 的 `unlock-ref` 分支。正常提交验证：

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/orders/episodes/6004/unlock
```

预期：用户 `1003` 余额 `250 -> 220`，`t_episode_unlock`、`t_points_ledger`（幂等键 `unlock:1003:6004`）、`t_episode_unlock_ref` 各新增一行，`unlock_count` `+1`，`undo_log` 为空。

回滚验证：

1. Nacos 把 `mashang-duanju-dev/msd-order.yaml` 的 `msd.tx.demo.fail-after-remote` 改为 `true`，等待 1-3 秒刷新。
2. 用未解锁的 `6006`（价格 50）调用解锁，预期业务失败。
3. 检查两边都没有变化：

```sql
SELECT balance FROM t_points_account WHERE user_id = 1003;
SELECT COUNT(*) FROM t_episode_unlock WHERE user_id = 1003 AND episode_id = 6006;
SELECT COUNT(*) FROM t_episode_unlock_ref WHERE episode_id = 6006;
SELECT unlock_count FROM t_drama_episode WHERE id = 6006;
SELECT COUNT(*) FROM undo_log;
```

4. 把开关改回 `false`，再调用一次确认恢复正常。

### 互动 Outbox、Kafka 消费与 DLQ

```bash
# 1. outbox 投递状态：0 待发送、1 已发送、2 死信；claim_token 为空表示已释放认领
mysql -h127.0.0.1 -umsd -p -D DL -e \
  "SELECT id,event_id,user_id,topic,event_key,status,retry_count,next_retry_time,claim_token,locked_until,last_error FROM t_outbox_event ORDER BY id DESC LIMIT 10"

# 2. 查看 topic 消息（本地 Kafka 可用 127.0.0.1:9092）
D:/kafka_2.13-4.1.2/bin/windows/kafka-console-consumer.bat \
  --bootstrap-server 127.0.0.1:9092 \
  --topic msd.interaction.like.v1 --from-beginning --max-messages 1

# 3. 查看 drama / interaction 消费组进度和堆积
D:/kafka_2.13-4.1.2/bin/windows/kafka-consumer-groups.bat \
  --bootstrap-server 127.0.0.1:9092 --describe --group msd-drama-counter-v1
D:/kafka_2.13-4.1.2/bin/windows/kafka-consumer-groups.bat \
  --bootstrap-server 127.0.0.1:9092 --describe --group msd-interaction-counter-v1

# 4. 查看死信
D:/kafka_2.13-4.1.2/bin/windows/kafka-console-consumer.bat \
  --bootstrap-server 127.0.0.1:9092 \
  --topic msd.interaction.dlq.v1 --from-beginning --timeout-ms 5000

# 5. P3 迁移：claim_token、client_request_id、user_id 和计数重算
mysql -h127.0.0.1 -umsd -p -D DL < deploy/mysql/repair/20261027_interaction_async.sql

# 6. 从明细重建计数和一致性对账
mysql -h127.0.0.1 -umsd -p -D DL < deploy/mysql/repair/rebuild_interaction_counts.sql
mysql -h127.0.0.1 -umsd -p -D DL < scripts/check-interaction-consistency.sql

# 7. 互动写接口压测（专用用户 + 清理评论/点赞）
python scripts/load-test-interaction.py --calls 2000 --concurrency 50 --target direct \
  --user-id 9998 --cleanup-likes
```

- 正常路径：调用互动接口后 1-2 秒内 outbox `status=1`，两个消费组 LAG 回到 0，`t_drama`/`t_drama_episode`/`t_comment` 计数更新。
- 压测脚本默认通过删除接口清理本轮创建的评论；`--cleanup-likes` 只对专用压测用户开启，避免删除真实用户点赞。2026-10-27 P4 实测：1999/2000 成功，0.05% 失败（死锁重试耗尽），p50/p95/p99=21/73/128ms。
- Outbox 每秒短事务认领 200 条，8 线程并行发送，单条 1s 超时，租约 60s；发送失败按 2^retry 秒退避，`msd.outbox.max-retry` 默认 5，超过后 `status=2`。
- Kafka 不可用时：outbox 保持 `status=0`、`retry_count` 递增、`next_retry_time` 按指数退避；恢复后自动补发。
- 新消费组 `msd-interaction-counter-v1` 必须使用 `auto.offset.reset=latest`，避免回放历史 LIKE/COMMENT。需要从最早开始排查时，先停对应服务，再重置位点：
  ```bash
  D:/kafka_2.13-4.1.2/bin/windows/kafka-consumer-groups.bat \
    --bootstrap-server 127.0.0.1:9092 --group msd-drama-counter-v1 \
    --topic msd.interaction.like.v1 --reset-offsets --to-earliest --execute
  ```
- 批量消费：`msd.interaction.batch-consumer.enabled=true` 后 drama 使用批量监听；默认 false。批量监听走独立 `batchKafkaListenerContainerFactory`（AckMode=BATCH），不能复用默认 RECORD 工厂，否则启动报 `Cannot use AckMode.RECORD with a batch listener`。非法消息通过 `BatchListenerFailedException` 定位单条，仍走 3 次重试 + DLQ。
- 强杀 drama 后立即重启，旧成员要等 Kafka session timeout（默认 45 秒）才释放分区，期间会出现短暂 LAG，属于正常现象。

### Nacos 配置

| 用途 | 地址 |
|---|---|
| 控制台 | `http://localhost:8080`，`nacos/$NACOS_PASSWORD` |
| 客户端 HTTP | `localhost:8848` |
| 客户端 gRPC | `localhost:9848` |
| 服务端 gRPC | `localhost:9849` |
| 容器内地址 | `nacos:8848` |

- 健康检查：`curl http://127.0.0.1:8080/v3/console/health/readiness`。
- **9848/9849 必须映射**，只开 8848 会导致应用侧 gRPC 连接失败。
- 3.x 启动脚本强制要求 `NACOS_AUTH_TOKEN`（Base64 字符串），即使 `NACOS_AUTH_ENABLE=false` 也不能为空；已在 compose 中给本地默认值。
- 命名空间：`mashang-duanju-dev` / `mashang-duanju-test` / `mashang-duanju-prod`，分组 `DEFAULT_GROUP`。
- DataId：`common.yaml`（公共配置，含连接池 10、`management.tracing.*` 和日志 traceId 格式）、`agent-config.yaml`（AI 配置，含 Mock LLM 和 Mock RAG 参数，仅 Agent 服务导入）、`content-config.yaml`（RAG 调优参数，仅 content service 导入）、`msd-drama.yaml`（首页缓存、布隆、xxl-job、连接池 20 与批量消费开关）、`msd-interaction.yaml`（连接池 20、虚拟线程、outbox、评论缓存和异步计数）、`msd-order.yaml`（订单服务配置，含 `msd.tx.demo.fail-after-remote` 回滚验证开关，默认 false）。
- Sentinel 规则 DataId：`sentinel-gateway-flow.json`、`sentinel-agent-degrade.json`，分组 `SENTINEL_GROUP`。
- 初始化/覆盖配置：
  ```bash
  python scripts/init-nacos-config.py
  ```
  脚本读取 `deploy/.env`，创建三个命名空间并发布配置；dev 用真实开发密码，test/prod 用 `change-me`。
- **Windows 端口排除**：如果 `docker compose up nacos` 报 `bind: An attempt was made to access a socket in a way forbidden by its access permissions`，用 `netsh interface ipv4 show excludedportrange protocol=tcp` 确认 8848 是否落在排除段。修复步骤（管理员 PowerShell）：
  ```powershell
  docker desktop stop
  Restart-Service winnat -Force
  docker desktop start
  ```
  重启后再确认排除段消失。临时替代方案是把 Nacos 映射到 `18848:8848`、`19848:9848`、`19849:9849`，并用 `NACOS_ADDR=127.0.0.1:18848` 启动业务服务；项目默认端口仍是 8848/9848/9849。
- 环境切换：`SPRING_PROFILES_ACTIVE=test`，命名空间自动变为 `mashang-duanju-test`。
- **SCA 2025.1 不支持 `bootstrap.yml`**，统一用 `spring.config.import`。
- 管理 API 需要登录 token（本机无 `jq`，用 Python 提取）：
  ```bash
  TOKEN=$(curl -s -X POST http://127.0.0.1:8080/v3/auth/user/login \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data "username=$NACOS_USERNAME&password=$NACOS_PASSWORD" | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
  curl -H "accessToken: $TOKEN" http://127.0.0.1:8080/v3/console/core/namespace/list
  ```
- 配置热更新验证：
  ```bash
  curl http://127.0.0.1:8105/internal/config/ai
  # 修改 Nacos 中 mashang-duanju-dev/agent-config.yaml 的 msd.ai.temperature
  # 等待 1-3 秒后再次请求，值应变化
  ```

### 搜索与分类

`msd-drama` 提供分类列表和已上架短剧搜索，经网关访问；Demo 使用 MySQL LIKE，不依赖 ES。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/drama/internal/categories

# 中文关键词由 Python 生成 UTF-8 百分号编码，避免 Windows 终端 GBK 导致 400
KEYWORD=$(python -c "import urllib.parse;print(urllib.parse.quote('总裁'))")
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/drama/internal/search/dramas?keyword=$KEYWORD&sort=latest&pageNo=1&pageSize=12"
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/drama/internal/search/dramas?categoryId=2002&sort=hot"
```

- 未导入真实测试数据时默认搜索 `total=9`，导入后为 18；`categoryId=2002&sort=hot` 会优先返回新增的甜宠剧。
- `keyword=%` 返回 `total=0`，通配符已转义；`pageSize=999` 按 50 截断。
- 前端入口 `/search`，首页头部搜索框跳转。
- 中文参数直接在 GBK 终端用 `curl` 可能得到 400（URL 解码失败），用 Python 生成百分号编码；`sort` 非 latest/hot、关键词超过 50 字或 `categoryId<=0` 返回 400。

### AI 短剧测试数据

`deploy/mysql/seed/ai_drama_test.sql` 提供 9 部 AI 短剧（5101-5109）和 54 集（7101-7154），媒体来自 B 站公开视频，存放于本地 MinIO。

```bash
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 \
  < deploy/mysql/seed/ai_drama_test.sql

# 导入后等待 xxl-job 每 5 分钟重建布隆，或在控制台手动触发“短剧缓存重建”
grep "bloom rebuild completed" logs/msd-drama.log | tail -1
```

- 导入后搜索接口立即可用；详情需要布隆重建后才会返回，之前可能 404。
- 来源、数据适配、验证和清理见 [test-data.md](test-data.md)。

### 首页多级缓存

`msd-drama` 首页/详情使用 Caffeine L1 + Redis L2，详情和批量查询带布隆过滤，缓存 miss 用 Redisson 锁单飞重建。完整设计见 [cache.md](cache.md)。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)

# 首页：第一次回源，第二次命中
curl -s -o /dev/null -w "home1=%{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8102/internal/home
curl -s -o /dev/null -w "home2=%{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8102/internal/home

# 详情：有效 ID 与随机 ID
curl -s -o /dev/null -w "detail=%{http_code} %{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8102/internal/dramas/5005
curl -s -o /dev/null -w "missing=%{http_code} %{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8102/internal/dramas/999999

# Redis key 和布隆指针
set -a; . deploy/.env; set +a
redis-cli -h 192.168.33.129 -a "$REDIS_PASSWORD" --scan --pattern 'msd:drama:cache:*'
redis-cli -h 192.168.33.129 -a "$REDIS_PASSWORD" GET msd:drama:bloom:active
```

预期：

- 首页首次约 100ms、第二次约 3ms；详情首次约 50ms、第二次约 3ms。
- 随机 ID 返回 404，且 Redis 不生成 `msd:drama:cache:detail:999999`（布隆 false 不缓存 null）。
- active 指针指向 `msd:drama:bloom:v*`。

xxl-job 执行器验证：

```bash
docker compose -f deploy/docker-compose.yml up -d xxl-job-admin
mysql -h127.0.0.1 -umsd -p -D xxl_job -e \
  "SELECT registry_key,registry_value FROM xxl_job_registry;"
mysql -h127.0.0.1 -umsd -p -D xxl_job -e \
  "SELECT l.job_id,l.trigger_time,l.trigger_code,l.handle_code FROM xxl_job_log l JOIN xxl_job_info i ON l.job_id=i.id WHERE i.executor_handler='dramaCacheRebuildHandler' ORDER BY l.id DESC LIMIT 3;"
```

- `xxl_job_registry` 应出现 `msd-drama-executor -> http://host.docker.internal:9999`。
- 任务每 5 分钟触发，`trigger_code=200`、`handle_code=200` 表示成功。
- 修改执行器组 token 后，等约 30 秒让 admin 刷新 group cache，再等执行器心跳。

### 虚拟线程 AI demo

`msd-drama` 全局开启虚拟线程，提供仅 dev 生效的压测接口，直连 8102，不经过网关。下游是 `msd-agent-service` 的 Mock AI，单次约 200ms。

```bash
# 单次：virtual 应为 true
curl -s "http://127.0.0.1:8102/internal-cache/demo/ai/single?prompt=hi" | python -m json.tool

# 100 次并发：虚拟线程 vs 固定 8 线程池
curl -s "http://127.0.0.1:8102/internal-cache/demo/ai/fanout?calls=100&mode=virtual&pool=8&prompt=hi"
curl -s "http://127.0.0.1:8102/internal-cache/demo/ai/fanout?calls=100&mode=platform&pool=8&prompt=hi"

# 外部压测脚本
python scripts/load-test-ai.py --calls 200 --concurrency 100
```

预期：虚拟线程总耗时接近单次下游延迟（约 200-300ms），固定池 8 的总耗时约为 `calls/8 * 单次延迟`（100 次约 2.5s）；`virtual` 模式的 p50/p95 仍接近单次延迟。

回滚和 pinning 检查：

```bash
SPRING_THREADS_VIRTUAL_ENABLED=false java -jar msd-drama/target/msd-drama-1.0.0-SNAPSHOT.jar
java -Djdk.tracePinnedThreads=full -jar msd-drama/target/msd-drama-1.0.0-SNAPSHOT.jar
```

- 回滚后 `single` 返回 `virtual=false`，首页/详情和 Seata 解锁应正常。
- 已用 order 的 dev 强制失败入口验证虚拟线程下 Seata 全局回滚：`unlock-with-failure` 返回 `50000`，余额、解锁记录、drama 引用和计数均未变化。
- demo 只用于本地验证，生产 profile 不加载。

### RAG 内容推荐

`msd-content-service`（8107）依赖 Milvus、Elasticsearch、DeepSeek、DashScope、Redis 和 Kafka；Redis/Kafka 不可用时画像、幂等和增量索引会降级或暂停，但检索主链路仍可用。启动顺序：

```bash
# 1. 基础设施：Milvus + ES（etcd/minio 是 Milvus 依赖）
docker compose -f deploy/docker-compose.yml up -d etcd minio milvus elasticsearch
curl -fsS http://127.0.0.1:9091/healthz
curl -fsS 'http://127.0.0.1:9200/_cluster/health?pretty'

# 2. 发布 Nacos 配置（含 content-config.yaml）
python scripts/init-nacos-config.py

# 3. 启动 drama 和 content service
java -jar msd-drama/target/msd-drama-1.0.0-SNAPSHOT.jar
java -jar msd-content-service/target/msd-content-service-1.0.0-SNAPSHOT.jar
```

首次启动或短剧上下架后重建索引：

```bash
curl -X POST http://127.0.0.1:8107/internal/ai/rag/reindex \
  -H 'Content-Type: application/json' -d '{}'
curl http://127.0.0.1:8107/internal/ai/rag/status
curl 'http://127.0.0.1:9200/msd_drama_bm25_v1/_count'
```

期望 `dramaCount=9`、`episodeCount=45`、`esIndexed=54`、`vectorUpserted=54`、ES `_count=54`（9 部短剧 + 45 集）。连续执行两次 reindex，ES 计数仍为 54，Milvus 不出现重复 ID。

经网关推荐：

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/recommend \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"有没有那种先婚后爱的甜宠剧","topK":3}'
```

响应包含 `answer`、`rewrittenQuery`、`sources`、`degraded`、`degradedFlags`、`elapsedMs`。

流式和相似推荐：

```bash
# SSE 找剧；-N 关闭 curl 缓冲
curl -N -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/recommend/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"有没有那种先婚后爱的甜宠剧","topK":3}'

# 相似短剧
curl 'http://127.0.0.1:8000/api/content/internal/ai/rag/related?dramaId=5005&limit=6' \
  -H "Authorization: Bearer $TOKEN"

# 冷启动偏好和个性化缓存清理
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/preferences \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"categoryIds":[2002]}'
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/personalized/evict \
  -H "Authorization: Bearer $TOKEN"

# 推荐负反馈/完播归因
curl -X POST http://127.0.0.1:8000/api/interaction/internal/recommendations/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"requestId":"req-1","dramaId":5005,"eventType":4,"position":1}'

# 评论课代表
curl 'http://127.0.0.1:8000/api/interaction/internal/ai/comments/summary?dramaId=5001' \
  -H "Authorization: Bearer $TOKEN"

# 创作者 AI 预检（CREATOR/ADMIN）
CREATOR_TOKEN=$(python scripts/gen-jwt.py --user-id 1002 --role CREATOR)
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/audit/precheck \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"长安第一女仵作","subtitle":"验尸破案","description":"长安城接连出现离奇命案。","episodeTitles":["第1集 雨夜尸案"]}'
```

P1-7 增量索引：

- 审核通过/下架后 drama 向 `msd.drama.changed.v1` 发送事件；content 消费后只更新单部短剧。
- 首次启动前确认 topic 存在：`scripts\create-topics.cmd`。
- content 消费组 `msd-content-rag-index-v1` 从 `earliest` 开始，首次启动会回放历史事件。
- 首次部署需要先执行一次全量 reindex，确保 ES index 和 mapping 已存在；增量消费不会自动创建索引。
- 关闭增量索引：Nacos `msd.ai.rag.incremental.enabled=false`；改完需重启 content。
- 索引失败重试 3 次后只记录 `rag_index_failure_total`，执行 `POST /internal/ai/rag/reindex` 全量恢复。
- 字幕导入/ASR 使用 `msd.transcript.changed.v1` 和独立消费组 `msd-content-transcript-index-v1`；全量 reindex 会一并补建字幕索引。

P1-4/P1-5 首次部署需要执行：

```bash
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 \
  < deploy/mysql/repair/20261106_p1_comment_recommendation.sql
```

- 新增 `t_recommendation_exposure`、`t_recommendation_event`，以及 `t_comment.ai_review_*` 三列。
- 评论 AI 预审异步执行，管理端可在评论治理页按 AI 风险/失败筛选并重跑。

P2-1 字幕/ASR 首次部署需要执行：

```bash
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 \
  < deploy/mysql/repair/20261106_p2_transcript.sql
```

- 新增 `t_episode_subtitle`、`t_episode_transcript_segment`。
- topic `msd.transcript.changed.v1` 需要创建；`scripts\create-topics.cmd` 已包含。
- content 全量 reindex 会自动补建字幕索引；首次上线仍建议先全量 reindex 一次。

P2-3/P2-4 首次部署：

- 无数据库迁移；`t_ai_feedback` 复用，新增 `CREATOR_COPY` 场景和 `ADOPT` 原因。
- 发布 `content-config.yaml` 的 `msd.ai.governance.*`、`msd.ai.copilot.*`、`msd.ai.rag.rerank.min-score`；确认网关包含 `/api/content/internal/ai/copilot/generate`。
- 全量 reindex 后跑一次离线评测；要验证时间戳引用，先给 golden set 中的 6001/6002 导入字幕或回传 ASR：

```bash
python scripts/eval-rag.py --output eval/report.json
```

- 评测默认直连 8107；经网关时加 `--base-url http://127.0.0.1:8000/api/content --token "$TOKEN"`。
- 灰度：Nacos 把 `msd.ai.governance.features.rag-search.gray-percent` 从 100 调到 5，再逐步升到 50/100；关闭实验保持 `msd.ai.governance.experiments.rag-model.enabled=false`。
- 成本保护：`msd.ai.governance.budget.enabled=true`，`daily-tokens-per-user` 按实际预算调整；Redis 故障时预算 fail-open。
- 语义缓存默认关闭；开启前确认 embedding 延迟可接受，`semantic-threshold` 建议不低于 0.95。
- 创作者 Copilot 每日限额 `msd.ai.copilot.daily-limit`，默认 20；前端入口在编辑页“AI 创作助手”。

字幕/ASR 手工验证：

```bash
# 1. 创作者导入 SRT（需要 CREATOR Token 和已保存的 episodeId）
CREATOR_TOKEN=$(python scripts/gen-jwt.py --user-id 1002 --role CREATOR)
curl -X POST http://127.0.0.1:8000/api/drama/internal/creator/dramas/5001/episodes/6001/subtitles \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"language":"zh","format":"SRT","content":"1\n00:00:01,000 --> 00:00:03,000\n你好\n"}'

# 2. 离线 ASR（需要 ffmpeg + faster-whisper）
pip install -r scripts/requirements-asr.txt
python scripts/transcribe-episode.py --video D:/media/6001.mp4 --episode-id 6001

# 3. 带播放位置问剧情，sources 应出现 type=transcript
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/companion/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"开头发生了什么？","sessionId":"p2-demo","episodeId":6001,"positionMs":60000}'
```

全局找剧助手：

```bash
curl -X POST http://127.0.0.1:8000/api/agent/internal/ai/agent/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"有没有先婚后爱的甜宠剧","sessionId":"assistant-demo"}'
```

- 响应 `cards` 是从 `[DRAMA_CARD]` 解析出的可点击短剧；没有卡片时前端只展示文本。
- 首次部署需重新发布 `agent-config.yaml`，确认 `msd.ai.agent.tools.allow` 包含 `search_drama_rag`。

降级演练：

| 操作 | 预期 |
|---|---|
| `docker compose stop elasticsearch` | `degradedFlags` 含 `bm25`，向量结果仍返回 |
| `docker compose stop milvus` | `degradedFlags` 含 `vector`，BM25 结果仍返回 |
| `--msd.ai.rag.rerank.api-key=invalid` | `degradedFlags` 含 `rerank`，sources 为 RRF Top 3 |
| `--spring.ai.deepseek.api-key=invalid` | `degradedFlags` 含 `rewrite` / `generation`，检索仍返回 |
| 无匹配数据 | `answer` 为固定 no-match 文案，sources 为空 |
| 总超时 | `degradedFlags` 含 `timeout`，HTTP 仍为 200 |

注意：

- `reindex` / `status` 只允许直连 8107；网关只暴露 recommend、recommend/stream、related、personalized、companion chat/stream/recap、审核、创作者预检和 Copilot 接口。
- 网关对 `msd-content-service` 路由限流 5 QPS/IP。
- Milvus 未启动时服务仍可启动（延迟连接），recommend 自动走 BM25。
- ES 启动报 `max virtual memory areas vm.max_map_count [65530] is too low`，在 Docker Desktop VM 执行：
  ```powershell
  wsl -d docker-desktop sysctl -w vm.max_map_count=262144
  ```
  然后重启 ES。
- ES 容器 exit 137：提高 Docker Desktop 内存，或调整 `ES_JAVA_OPTS`。
- 索引重建返回 409：已有任务在执行；返回 502：`msd-drama` 不可用；`partial=true`：Milvus 或 ES 单路失败，按 `errors` 排查。
- `rerank` 降级：检查 `DASHSCOPE_API_KEY`、`DASHSCOPE_RERANK_URL`、`msd.ai.rag.rerank.model`。
- `rewrite` 降级：检查 `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`；检索仍使用原查询。

### AI 伴看与结构化审核

- 接口、防剧透、会话记忆和手工验证见 [companion.md](companion.md)；审核 JSON 契约和降级规则见 [audit.md](audit.md)。
- 伴看依赖 Redis、Milvus、ES、drama 和 content-service。Redis 不可用时聊天 fail-open，响应标记 `memory_summary`，不返回 500。
- Milvus 停止时前情检索标记 `vector`，仅用 BM25；ES 停止时标记 `bm25`，仅用向量；两路都停止返回空前情并同时标记 `vector`、`bm25`，Agent 不得编造前情。
- 分集索引未重建（ES 中该剧分集数为 0）时标记 `episode_index`，需要执行全量 reindex。
- 伴看工具接口和审核接口只允许直连 8107；网关暴露 `/api/content/internal/ai/companion/chat`、`/chat/stream` 和 `/recap`。
- RexUniNLU 情感/标签建议默认启用；sidecar 未启动时审核仍正常，advisory 标记 `rex_unavailable`：

```bash
scripts\start-rex-uninlu.cmd
curl http://127.0.0.1:8108/readyz
# Nacos content-config.yaml 的 msd.ai.rex.enabled=false 可整体关闭
```

  模型、API、依赖兼容和失败演练见 [rex-uninlu.md](rex-uninlu.md)。
- 审核演示前先重置 5003/5012/5013：

```bash
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 \
  < deploy/mysql/repair/reset_review_demo.sql
curl http://127.0.0.1:8102/internal-source/reviews/pending?limit=10
curl -X POST 'http://127.0.0.1:8107/internal/ai/audit/dramas/pending?limit=5'
```

  管理员通过、驳回、下架接口和完整命令见 [audit.md](audit.md) 第 7 节。
- Milvus 2.6.x 的 `common.storageType` 必须为 `remote`；`minio` 已废弃，会导致 `InitRemoteArrowFileSystemSingleton` 失败并在写入时 panic。`deploy/docker-compose.yml` 已固定为 `remote`；修改后重启 Milvus 并重跑 reindex。

### 创作者与审核台

- 接口和页面见 [creator.md](creator.md)。
- 创作者 1002/1005 登录后从个人中心进入“创作者中心”；管理员 1001 可进入“审核台”和“评论治理”。
- 审核台可对单部短剧触发 AI 审核、通过/驳回/下架、查看审核日志和重建 RAG 索引。

### 充值与积分

- 接口、幂等规则和完整命令见 [recharge.md](recharge.md)。
- `msd-order` 需要在运行；没有积分账户的新用户会在模拟支付时自动创建账户。
- 快速验证：

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1008 --role USER)
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/order/internal/recharge/plans
curl -X POST http://127.0.0.1:8000/api/order/internal/recharge/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"planId":4001,"clientRequestId":"runbook-p3-001"}'
```

### Sentinel Dashboard

```bash
docker compose -f deploy/docker-compose.yml up -d sentinel-dashboard
docker compose -f deploy/docker-compose.yml ps sentinel-dashboard
```

- 控制台：`http://127.0.0.1:8858`，账号 `<SENTINEL_USER>/<SENTINEL_PASSWORD>`；端口只绑定本机。
- 规则：Nacos 分组 `SENTINEL_GROUP`，dataId `sentinel-gateway-flow.json`、`sentinel-agent-degrade.json`。
- 规则发布：`python scripts/init-nacos-config.py`；Dashboard 只做监控，手动改规则不会回写 Nacos。
- Dashboard 需要等客户端注册心跳后才能看到应用；网关和 Agent 接入 Sentinel 后再验证。
- Dashboard 在容器内，通过宿主机 IPv4（Docker Desktop 默认 `192.168.65.1`）回连客户端；`host.docker.internal` 会被 Dashboard 判定为非法 IP。Windows 防火墙放行 `8719-8725`。

### 网关限流

网关对 6 条业务路由按客户端 IP 限流，规则在 Nacos `SENTINEL_GROUP/sentinel-gateway-flow.json`，默认 10 QPS/IP；`msd-content-service` 为 5 QPS/IP。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
seq 1 30 | xargs -P 30 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/interaction/internal/users/1003/profile | sort | uniq -c
```

- 预期同时出现 `200` 和 `429`；429 body 为 `{"code":42900,"message":"请求过于频繁","data":null,"success":false}`。
- 串行 `curl` 在 Windows 上通常达不到 10 QPS，必须用并发波次。
- Dashboard 网关流控页看到 6 条规则；`logs/msd-gateway.log` 有 `register SentinelGatewayFilter with order: -50`。
- 如果 Dashboard 看不到应用，检查 `SENTINEL_CLIENT_IP` 是否是 Dashboard 容器可回连的宿主机 IPv4，以及 `8719` 端口和防火墙。

### AI 熔断降级

`msd-agent-service` 的 `aiChat` 资源用 Sentinel 监控 Mock RAG + LLM 调用，规则在 Nacos `SENTINEL_GROUP/sentinel-agent-degrade.json`。

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1001 --role ADMIN)

# 正常调用；Mock 经网关只允许 ADMIN
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/agent/internal/ai/chat?prompt=hi"

# 在 Nacos 把 agent-config.yaml 的 msd.ai.mock.delay-ms 改成 1500
# 顺序调用 8 次，前 5 次约 1.5s，第 6 次起返回兜底
for i in $(seq 1 8); do
  curl -s -w " time=%{time_total}s\n" \
    -H "Authorization: Bearer $TOKEN" \
    "http://127.0.0.1:8000/api/agent/internal/ai/chat?prompt=hi"
done
```

- 正常：`degraded=false`；熔断/异常：`degraded=true`，reply 为 `当前太火爆，请稍后再试`。
- 慢调用熔断：RT > 1000ms 且慢调用比例 > 50%，至少 5 次请求。
- 异常比例熔断：把 `error-rate` 改成 `1.0`，至少 5 次异常后触发。
- 熔断 10 秒后进入半开；把 `delay-ms` / `error-rate` 改回正常值，下一次成功请求后关闭。
- `agent-config.yaml` 还有 Mock RAG 参数：`rag-delay-ms: 120`、`rag-top-k: 3`；默认 120ms + 50ms 低于慢调用阈值，不会误触发熔断。
- Dashboard 熔断规则页：`http://127.0.0.1:8858`，规则接口 `/degrade/rules.json`。

### AgentScope Supervisor

`msd-agent-service` 的 Supervisor 使用 AgentScope 2.0.4，工作区在仓库根 `.agentscope/workspace`，会话状态写 Redis。启动前确认：

- 进程环境有 `DEEPSEEK_API_KEY`（Nacos `msd.ai.api-key` 会引用它）。
- Redis `192.168.33.129:6379` 可达，密码来自 `deploy/.env` / Nacos `common.yaml`。
- 服务从仓库根启动，否则 `.agentscope/workspace` 解析不到。
- 生产设置 `MSD_INTERNAL_TOKEN`，网关自动剥离客户端伪造值后注入 `X-Internal-Token`；本地为空时 fail-open。

```bash
# 直连 Agent 服务
curl -X POST http://127.0.0.1:8105/internal/ai/agent/chat \
  -H "Content-Type: application/json" -H "X-User-Id: 1003" \
  -d '{"message":"查询短剧5001的详情并总结","sessionId":"runbook-agent-1"}'

# 只验证规则路由，不调用模型
curl -X POST http://127.0.0.1:8105/internal/ai/agent/route \
  -H "Content-Type: application/json" -H "X-User-Id: 1003" \
  -d '{"message":"第3集里他们为什么吵架","sessionId":"runbook-route-1"}'

# 检查 Redis 会话 key（密码从 deploy/.env 读取，不打印）
set -a; . deploy/.env; set +a
redis-cli -h 192.168.33.129 -p 6379 -a "$REDIS_PASSWORD" \
  --scan --pattern 'mashang:agent:session:*' | head
```

- 正常返回 `code=0`，`data.reply` 为 Supervisor 汇总结果；相同 `sessionId` 的第二次调用会带上上一轮上下文。
- `50300`：未配置 API Key，或模型/工作区初始化失败；看 `logs/msd-agent-service.log` 中 `AgentScope initialization failed`。
- 模型报错：检查 `msd.ai.agent.model` 是否为当前端点支持的模型；必要时设置 `DEEPSEEK_BASE_URL`。当前开发端点只接受 `deepseek-flash` / `deepseek-v4-pro`，若出现 `The supported API model names are ...`，用 `--msd.ai.agent.model=deepseek-flash` 启动或设置 `MSD_AI_AGENT_MODEL=deepseek-flash`。
- 工具查询失败：确认 `msd-drama` 已启动、Nacos 服务列表有实例，Feign 超时为 2s/5s。
- 子 Agent 未生效：确认 `.agentscope/workspace/subagents/*.md` 存在，front matter 的 `description` 非空，`tools` 名称与启动日志 `AgentScope tools registered` 一致。
- 旧 Mock 接口仍可用：`GET /internal/ai/chat?prompt=hi`，用于对比排查。

### A2A 审核服务

`audit-agent` 以独立进程暴露 A2A，并注册到 Nacos AI Service；Supervisor 通过 Nacos 发现后远程调用。前置条件：

- Nacos Server 3.1+（当前 3.1.1），Compose 中 `msd-nacos` 健康。
- 8106 未被占用；audit 进程从仓库根启动，能读到 `.agentscope/workspace/subagents/audit-agent.md`。
- audit 进程启动时必须显式传 `NACOS_NAMESPACE=mashang-duanju-dev`，否则 `spring.profiles.active=audit` 会推导出不存在的命名空间。

```bash
# 启动 audit A2A 进程（8106）
NACOS_NAMESPACE=mashang-duanju-dev java -jar msd-agent-service/target/msd-agent-service-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=audit

# AgentCard
curl -s http://127.0.0.1:8106/.well-known/agent-card.json | python -m json.tool

# 远程审核演示接口（8105 侧）
curl -X POST http://127.0.0.1:8105/internal/ai/agent/audit/remote \
  -H "Content-Type: application/json" \
  -d '{"content":"短剧标题：总裁的替身新娘","sessionId":"runbook-a2a-1"}'
```

正常响应包含 `source=remote`、`degraded=false`；Nacos 控制台「AI 管理 → A2A 服务」应出现 `audit-agent`（版本 `1.0.0`，JSON-RPC Endpoint `127.0.0.1:8106`）。

容错验证：

1. 停掉 8106 audit 进程。
2. 再次调用远程审核接口，日志出现两次 `Remote audit retry`（只重试网络/超时/5xx/408/429），响应 `source=local-fallback`、`degraded=true`，通常在 1s 内返回。
3. 通过 Supervisor 对话触发审核，日志顺序应为 `audit_remote` → `[DEGRADED]` → `agent_spawn`（本地 `audit-agent`）。
4. 重启 audit 后再次调用，恢复 `source=remote`。

注意：

- A2A Endpoint 不带 JWT，默认只监听本机，仅用于内网演示；生产需要额外鉴权或 A2A 网关。
- audit profile 使用独立的 Sentinel transport 端口 8721（避免与 Supervisor 的 8720 冲突），关闭普通服务注册，但保留 Nacos discovery 供 Feign 调用 `msd-drama`；不创建 Redis 客户端，Redis 不可用不影响 A2A 启动。
- Nacos 注册是启动时一次性执行，最多重试 3 次；失败会让 audit 进程启动失败，修复 Nacos 后重启。
- 同一版本 AgentCard 重复注册时 Nacos 会提示 `already exists`，可能忽略卡片更新；修改卡片内容时提升 `version`。

### 分布式链路追踪

所有服务使用 `spring-boot-starter-zipkin`（Boot 4 的 Brave 方案），interaction 额外依赖 `feign-micrometer`。Zipkin 随 compose 启动：

```bash
docker compose -f deploy/docker-compose.yml up -d zipkin
docker compose -f deploy/docker-compose.yml ps zipkin
curl -s http://127.0.0.1:9411/health
```

- UI：`http://127.0.0.1:9411`，端口只绑定本机；内存存储，容器重启后 trace 丢失。
- 公共配置在 Nacos `common.yaml`：`management.tracing.sampling.probability`（dev `1.0`、test `0.5`、prod `0.1`）和 `management.tracing.export.zipkin.endpoint`（默认 `http://127.0.0.1:9411/api/v2/spans`）。
- **Boot 4 前缀**是 `management.tracing.export.zipkin.*`，旧的 `management.zipkin.tracing.*` 不再读取。
- 网关和 Agent 开启 `spring.reactor.context-propagation: auto`，否则 WebFlux 网关的下游 client span 或 Agent `boundedElastic` 线程上的 `ai.chat` 会断成新的根 span。
- 日志格式增加了 `[traceId,spanId]`，可直接和 Zipkin 对照。

完整 AI 链路（Gateway → interaction → agent）：

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1001 --role ADMIN)
curl -s -w "\ntime=%{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8000/api/interaction/internal/ai/chat?prompt=hi"
```

预期 Zipkin 中同一条 trace 有 8 个 span：

| 服务 | span | 说明 |
|---|---|---|
| `msd-gateway` | `http get` | 网关 server |
| `msd-gateway` | `http get` | 网关到 interaction 的 client |
| `msd-interaction` | `http get /internal/ai/chat` | interaction server |
| `msd-interaction` | `http get` | Feign client |
| `msd-agent-service` | `http get /internal/ai/chat` | agent server |
| `msd-agent-service` | `ai.chat` | AI 请求父 span |
| `msd-agent-service` | `rag.retrieve` | Mock RAG，约 120ms |
| `msd-agent-service` | `llm.generate` | Mock LLM，约 50ms |

Feign 链路（Gateway → interaction → user）：

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/interaction/internal/users/1003/profile
```

Zipkin 中应包含 `msd-gateway`、`msd-interaction`、`msd-user` 三个服务，interaction 的 Feign client span 是 user server span 的 parent。

Zipkin API：

```bash
curl -s http://127.0.0.1:9411/api/v2/services
curl -s "http://127.0.0.1:9411/api/v2/traces?serviceName=msd-gateway&limit=5"
curl -s "http://127.0.0.1:9411/api/v2/trace/<traceId>"
```

### 网关鉴权

所有 `/api/**` 请求（除白名单）需要 `Authorization: Bearer <token>`。

登录与注册走白名单，不需要 Token：

```bash
# 种子账号：13800000003 / Msd@123456
LOGIN=$(curl -s -X POST http://127.0.0.1:8000/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800000003","password":"Msd@123456"}')
TOKEN=$(printf '%s' "$LOGIN" | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/user/internal/users/me
```

- 注册：`POST /api/user/auth/register`，body 为 `{"phone","password","nickname"}`，昵称可省略。
- 登录失败统一返回 `40100 手机号或密码错误`；禁用账号返回 `40300`；重复手机号返回 `40900`。
- 接口契约见 [auth.md](auth.md)。

测试 Token（不经过登录，直接按网关密钥签发）：

```bash
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/user/internal/ping
```

- 网关透传 `X-User-Id` / `X-User-Role`，并移除客户端伪造的同名头。
- 白名单：`/api/user/auth/**`、`/actuator/**`。
- 密钥：`MSD_JWT_SECRET`，默认值仅本地开发，至少 32 字节。
- CORS：开发环境允许所有来源；预检用 `OPTIONS` 验证。
- 直连 8101-8107 不经过网关鉴权，生产只暴露网关端口。

### Feign 调用与负载均衡

互动服务查询用户信息的链路：网关 → `msd-interaction` → Feign → `msd-user`。

```bash
# 1. 起两个用户实例，注册到同一个服务名 msd-user
java -jar msd-user/target/msd-user-1.0.0-SNAPSHOT.jar
SERVER_PORT=8111 java -jar msd-user/target/msd-user-1.0.0-SNAPSHOT.jar

# 2. 起互动服务（默认轮询）
java -jar msd-interaction/target/msd-interaction-1.0.0-SNAPSHOT.jar
# 需要随机策略时改用：
# MSD_LB_STRATEGY=random java -jar msd-interaction/target/msd-interaction-1.0.0-SNAPSHOT.jar

# 3. 经网关调用
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
for i in 1 2 3 4; do
  curl -s -H "Authorization: Bearer $TOKEN" \
    http://127.0.0.1:8000/api/interaction/internal/users/1003/profile
  echo
done
```

- 轮询：`instancePort` 在 8101 / 8111 之间交替；随机：分布不固定。
- 用户数据来自 `t_user`（`1001`–`1013`），禁用用户（如 `1009`）返回 `40400`。
- Feign 日志：`com.msd.interaction.client` 为 `debug`，日志中可见 `GET http://msd-user/...`。
- 互动服务到用户服务是内网直连，不经过网关鉴权；生产只暴露网关端口。

### 前端开发与静态托管

```bash
cd frontend
npm install
npm run dev      # http://127.0.0.1:5173
npm run build    # 产物 frontend/dist
```

- 开发地址 5173，`/api` 由 Vite 代理到 8000；网关和业务服务必须先启动。
- 网关在 Maven `process-resources` 阶段把 `frontend/dist` 复制到静态资源。
- 修改前端后必须重新构建前端并重新打包网关；只重启网关不会更新页面。
- 深链 `/drama/5001`、`/me/favorites` 由 `SpaWebFilter` 回退到 `index.html`。
- 封面会请求 MinIO 上的海报（`msd/covers/ai/*.jpg`），失败自动回退渐变；播放器通过 `videoUrl` 加载 `msd/videos/ai/*.mp4`，视频缺失时会回退封面并保留控制条，不视为页面故障。
- 点开短剧进入全屏 Feed；上下滑动切集，简介/选集/评论在底部弹层，`/watch/:episodeId` 为旧入口兼容。
- Feed 内会锁定 body 滚动，离开页面自动恢复；带弹层离开若发现页面不能滚动，优先检查是否残留 `body.style.overflow=hidden`。
- Token 存在 `localStorage.msd.auth`；接口 401 会清理会话并跳登录。
- 新注册用户没有积分账户，付费集显示“未开通积分账户”。
- Kafka 未启动时，点赞/收藏/评论/历史写入可能被 outbox relay 行锁拖慢，页面会等待接口返回，属环境问题。

## 5. 停止

```bash
# 停业务服务：Ctrl+C 或按端口杀进程
netstat -ano | findstr :<port>

# 停中间件（保留容器与数据卷）
docker compose -f deploy/docker-compose.yml stop

# 彻底移除容器（数据卷保留）
docker compose -f deploy/docker-compose.yml down

# 虚拟机 Redis / Kafka 按各自方式停止
```

## 6. 排障

| 现象 | 原因/处理 |
|---|---|
| 前端 5173 页面空白或接口 404 | 确认网关 8000 和对应业务服务已启动；`/api` 走 Vite 代理 |
| 网关 8000 打开仍是旧页面 | 重新 `npm run build`，再打包并重启网关 |
| 直接刷新 `/drama/5001` 返回 404 | 确认打包前 `frontend/dist` 存在，网关 `SpaWebFilter` 生效 |
| 封面显示渐变、播放器没有视频 | 封面请求 MinIO 失败会回退渐变（检查 9000 端口和桶策略）；播放器仍是占位实现，不读 `videoUrl` |
| 全屏 Feed 上下滑不动 | 确认手指落在 `.feed-scroller` 内；弹层打开时背景被锁定，先关闭弹层 |
| 从 Feed 返回后页面不能滚动 | 检查 `body.style.overflow` 是否残留 `hidden`；Feed/AppModal 使用引用计数锁，重启页面可恢复 |
| `/watch/:episodeId` 与 Feed 显示不一致 | `/watch` 只解析 episode→dramaId，之后复用同一 Feed；检查 playback 是否返回正确 dramaId |
| Kafka 报 `Connection to node -1 (localhost/127.0.0.1:9092) failed` | `advertised.listeners` 配成了 localhost，改成 `192.168.33.129:9092` |
| 宿主机连不上 192.168.33.129:9092 | 虚拟机 Kafka 未启动，或防火墙未放行 9092 |
| Redis 报 `NOAUTH` | 未传密码，检查 `REDIS_PASSWORD` |
| Nacos 反复重启，日志 `env NACOS_AUTH_TOKEN must be set with Base64 String` | 3.x 强制要求 token，补 `NACOS_AUTH_TOKEN` |
| Nacos 控制台 404 | 3.x 控制台在 8080 的 `/`，不是 `8848/nacos` |
| 应用连不上 Nacos | 确认 9848/9849 已映射；容器内用 `nacos:8848` |
| xxl-job 访问 `/xxl-job-admin` 404 | 3.5.0 context-path 是 `/`，访问 `/auth/login` |
| Rex sidecar `/readyz` 503 | 检查模型目录、`REX_UNINLU_MODEL_DIR`、依赖安装；`/healthz` 的 `last_error` 有加载异常 |
| Rex 审核建议 `rex_protocol_error` | 检查 `X-Internal-Token` 是否与 sidecar 一致；内容服务 Rex 客户端必须使用 HTTP/1.1 |
| Rex 分析慢或 partial | 5000 字 6 片实测约 5.2s；确认 `join-timeout-ms=7500`，必要时减少分片或标签候选 |
| xxl-job 容器连不上 MySQL | 确认 `host.docker.internal` 可解析、`msd` 用户对 `xxl_job` 有权限 |
| `Incorrect string value` 导入 xxl-job SQL | 加 `--default-character-set=utf8mb4` |
| Milvus 未启动时应用启动失败 | `MilvusVectorStoreAutoConfiguration` 会强连 19530；先启动 Milvus，或临时排除 auto-config |
| 端口被占用 | 先停本机 Nacos/Kafka；容器与宿主安装不能同时跑 |
| 网关 401 `未登录` | 请求没带 `Authorization: Bearer`，或路径不在白名单 |
| 网关 401 `Token 无效或已过期` | `MSD_JWT_SECRET` 与 `gen-jwt.py` 不一致，或 Token 已过期 |
| 登录 401 `手机号或密码错误` | 手机号不存在、密码错误，或种子 SQL 未导入；先查 `t_user` 确认 |
| 登录 403 `账号已禁用` | `t_user.status=0`（例如 `13800000009`）；改成 1 或换账号 |
| 注册 409 `手机号已注册` | `t_user.phone` 有唯一索引；换手机号，或清理验收数据 |
| 登录/注册 400 `手机号格式不正确` | 必须是 11 位、`1[3-9]` 开头，不要带空格、`+86` 或分隔符 |
| 登录成功但后续接口 401 | 用户服务和网关的 `MSD_JWT_SECRET` / `MSD_JWT_ISSUER` 不一致，或请求没带 Token |
| 首页为空或短剧数量不对 | 确认 `t_drama.deleted=0 AND review_status=2 AND publish_time IS NOT NULL`；种子库应有 9 部已上架 |
| 短剧详情 404 | 短剧未上架、已删除或 ID 不存在；查 `t_drama.review_status` |
| 详情剧集数比预期少 | 剧集 `status=0` 已下线；公开详情只返回在线剧集 |
| 热播顺序不符合预期 | `view_count/like_count/favorite_count/comment_count` 是 Kafka 异步计数；等 1–2 秒或跑 `rebuild_interaction_counts.sql` |
| 播放 404 `剧集不存在或已下架` | 剧集 `status=0`、短剧未上架/已删除或 ID 不存在；未上架短剧用 6201–6203 测试 |
| 付费集 `unlocked=false` | 正常状态，先调解锁接口；免费集 `pricePoints=0` 不会写入解锁表 |
| 解锁 409 `积分账户不存在` | 新注册用户没有 `t_points_account`；演示用 1003（余额 250） |
| 解锁 409 `积分不足` | 余额小于 `pricePoints`；先查 `/api/order/internal/orders/points` |
| 播放/解锁 503 `依赖服务暂不可用` | drama 或 order 未启动、未注册到 Nacos，或 Feign 调用失败 |
| 解锁 500 或一直失败 | Seata TC 8091 未启动、XID 未通过 Feign 透传，或 `undo_log` 异常；先看 `docker compose ps` |
| `/api/drama/internal-source/**` 经网关 404 | 预期行为；网关只路由 `/api/drama/internal/**`，内部源只允许服务间直连 8102 |
| 播放地址打不开 | MinIO 9000 未启动或种子 URL 不可达；播放接口仍会返回 URL，媒体需自行准备 |
| 互动列表标题/昵称为空 | user 或 drama 的批量接口不可用；interaction 日志有 warning，列表会降级为 null |
| 评论删除 403 | 只能删除自己的评论；检查 `t_comment.user_id` 和当前登录用户 |
| 评论删除 404 | 评论不存在或已删除（`status=3`） |
| 删除根评论后回复消失 | 预期行为；根评论会级联软删除所有可见回复 |
| 批量接口 400「一次最多查询 100 个 ID」 | 分批调用；互动服务内部已按 100 分片 |
| 复位脚本执行卡住 | outbox relay 持有 `t_outbox_event` 行锁；先停 `msd-interaction` 再执行 `reset_consistency_console.sql` |
| 列表分页不生效 | `pageNo` 最小 1，`pageSize` 最大 100；确认请求参数名和返回的 `PageResult` |
| CORS 预检失败 | 检查 `Origin`、`Access-Control-Request-Method` 和网关 `globalcors` 配置 |
| Feign 报 `No servers available for service: msd-user` | 用户服务未启动或未注册到当前命名空间，检查 Nacos 实例列表 |
| Dashboard 看不到应用 | `SENTINEL_CLIENT_IP` 用了 `host.docker.internal` 或容器不可达的 IP；改成宿主机 IPv4，确认 `8719` 正在监听且防火墙放行 |
| 网关 429 不生效 | 确认 Nacos `SENTINEL_GROUP` 规则已加载、压测是并发波次、`SENTINEL_CLIENT_IP` 是合法 IPv4 |
| AI 熔断不触发 | 确认 `delay-ms > 1000`、10 秒内至少 5 次请求、Nacos degrade 规则已加载；启动日志应有 `Resolved method [blockHandler]` |
| Agent 接口返回 50300 | 进程没有 `DEEPSEEK_API_KEY`，或模型/Redis/工作区初始化失败；查 `logs/msd-agent-service.log` 的 `AgentScope initialization failed` |
| Agent 直连 401 `缺少或非法内部令牌` | 生产已开启 `MSD_INTERNAL_TOKEN`；直连时带 `X-Internal-Token`，经网关访问由 JWT 过滤器自动注入 |
| Agent 提示 `工具调用被拒绝` | 工具不在 `msd.ai.agent.tools.allow`，或 ID/分页/文本长度超过沙箱限制；看 `agent.tool.audit` 的 outcome |
| Agent 400 `指令注入` | PromptGuard 命中指令覆盖/套取系统提示；这是预期安全拦截，不是模型故障 |
| 路由总是 SUPERVISOR | 检查 Nacos `agent-config.yaml` 的 `routing.rules`、规则版本和关键词；直接调 `/internal/ai/agent/route` 验证 |
| Redis 不可用时路由报错 | 路由缓存应 fail-open；确认 `RouteCache` 版本已更新，首请求约 0.5s，30s 内后续请求直接跳过 Redis |
| Agent 首次调用超时 | 首次会初始化模型、工作区和 Redisson；确认 `msd.ai.agent.request-timeout`、Redis 可达、模型端点正常 |
| Agent 工具报短剧服务异常 | 确认 `msd-drama` 已注册到 Nacos，`/internal/home`、`/internal/dramas/{id}` 可直连访问 |
| Agent 不调用子 Agent | 检查 `.agentscope/workspace/subagents/*.md` 的 `description` 和 `tools`；工具名必须与 `AgentScope tools registered` 日志一致 |
| Agent 会话不连续 | 客户端需复用同一 `X-User-Id` + `sessionId`；Redis 需出现 `mashang:agent:session:*` key |
| 模型 400 `The supported API model names are ...` | 当前端点不支持配置的模型 id；改为支持的 `deepseek-flash`，或把 `msd.ai.agent.base-url` 指向支持该 id 的兼容网关 |
| Agent 启动报 victools `SchemaGenerator` `NoSuchMethodError` | Spring AI 2.0 的 victools 5（Jackson 3）与 AgentScope 2.0.4 的 4.38（Jackson 2）冲突；确认 `spring-ai-model` 排除了 victools，依赖树只剩 4.38.0 |
| audit 8106 启动失败 `Failed to register A2A AgentCard` | Nacos Server 低于 3.1，或 audit 进程没有设置 `NACOS_NAMESPACE=mashang-duanju-dev`；先确认 Nacos 版本和命名空间 |
| Nacos AI 管理看不到 `audit-agent` | audit 进程未启动、启动注册失败，或命名空间不一致；查 `logs/msd-audit-agent.log` 的 `Registered A2A AgentCard` |
| 远程审核报 `No compatible transport found` | A2A SDK 的 SPI 在 fat jar 的 Reactor 线程上初始化失败；确认 Supervisor 启动日志有 `A2A client transport registry initialized: [JSONRPCTransport]`，缺失时检查 `RemoteAuditService` 的 `@PostConstruct` 是否执行 |
| 远程审核一直返回 `local-fallback` | audit 进程已停、8106 不可达，或 Nacos 卡片 Endpoint 过期；重启 audit 后重新调用，必要时提升 AgentCard 版本触发更新 |
| audit 日志 `Agent card audit-agent already exists` | 同版本重复注册时 Nacos 可能忽略卡片更新；只影响卡片内容，不影响 Endpoint，修改卡片内容时提升 `version` |
| Supervisor 不调用 `audit_remote` | 确认启动日志 `AgentScope tools registered` 包含 `audit_remote`，且 `.agentscope/workspace/AGENTS.md` 的审核链路规则已加载 |
| Agent 未出现在 Dashboard | 检查 `SENTINEL_CLIENT_IP=192.168.65.1`、transport 端口 8720 和防火墙 |
| 多次调用 `instancePort` 始终相同 | 只有一个用户实例在注册，或 `MSD_LB_STRATEGY` 未生效 |
| 互动服务启动报 LoadBalancer Bean 冲突 | `RandomLoadBalancerConfig` 不能加 `@Configuration`，只由 `@LoadBalancerClients` 引入 |
| Zipkin UI 没有 trace | 确认 `management.tracing.sampling.probability=1.0`、`management.tracing.export.zipkin.endpoint` 可达、Zipkin 容器 healthy；内存存储重启会丢数据 |
| Zipkin `/api/v2/services` 为空 | 服务还没产生请求，或启动时 Nacos `common.yaml` 没拉到；先请求各服务 `/internal/ping` |
| Gateway 和 Agent 的 traceId 不一致 | 确认 gateway 有 `spring-boot-starter-zipkin`，且 `spring.reactor.context-propagation=auto`；检查网关日志是否加载 tracing 自动配置 |
| interaction 到 agent 的 trace 断开 | 确认 interaction 有 `feign-micrometer`，且 agent 已注册到 Nacos；Zipkin 中应有 interaction 的 Feign client span |
| Agent 的 `ai.chat` 是根 span 或没有子 span | 确认 agent 开启 `spring.reactor.context-propagation=auto`；`ai.chat` 应是 agent HTTP server span 的子 span，`rag.retrieve`/`llm.generate` 应是 `ai.chat` 的子 span |
| 日志没有 `[traceId,spanId]` | 确认 Nacos `common.yaml` 的 `logging.pattern.correlation` 已发布并重启服务；旧服务不会热加载日志格式 |
| Zipkin 容器 unhealthy | 镜像内没有 `wget` 时去掉 healthcheck，用 `curl http://127.0.0.1:9411/health` 人工验证 |
| Seata 报 `No available service 'default' found` | Seata Server 未注册到当前命名空间的 `SEATA_GROUP`，或 `vgroup-mapping` 与 cluster 不一致；检查 Nacos 服务和 `common.yaml` |
| 启动报 `undo_log` 表不存在 | 业务库没执行 `05_consistency.sql`，或服务连到了别的库；`undo_log` 必须和业务表同库 |
| 解锁远程分支没回滚 | 确认 XID 通过 Feign 透传、drama 使用 Seata 代理数据源、远程失败没有被吞掉 |
| Seata 报全局锁冲突 | 并发修改同一业务行；减小事务范围、重试，或检查是否有遗漏的本地事务提交 |
| outbox 一直 `status=0` | Kafka 不可达、topic 不存在或序列化失败；看 `retry_count`、`last_error` 和 `OutboxRelay` 日志 |
| outbox 进入 `status=2` | 超过 `msd.outbox.max-retry`；修复原因后人工重放，或跑重建 SQL 对账 |
| 互动计数重复增加 | 检查 `t_event_consume_log` 唯一键和消费事务是否生效，确认没有绕过消费者手工更新 |
| `msd.interaction.dlq.v1` 有消息 | 消费重试 3 次仍失败；检查 payload 和 drama 日志，修复后重放或跑重建 SQL |
| drama 重启后计数迟迟不更新 | 旧实例被强杀，Kafka 等待 session timeout；等 45 秒或优雅停机 |
| 首次启动 drama 后历史事件没消费 | `auto-offset-reset=latest` 的预期行为；需要重放时先停消费者再重置 offset |
| content service 启动报 `Unable to load config data from 'nacos:common.yaml'` | Nacos 未启动或 8848 不可达；先起 Nacos 并发布配置 |
| 网关访问 recommend 404 | content service 未注册到当前命名空间，或网关未加载新路由；检查 Nacos 实例和 `/actuator/gateway/routes` |
| 网关访问 reindex/status 404 | 预期行为；这两个接口只允许直连 8107 |
| recommend 返回 no-match 且 `degradedFlags` 含 `vector` | Milvus 未启动或连接超时；查 `/internal/ai/rag/status` 和 Milvus 日志 |
| recommend 返回 no-match 且 `degradedFlags` 含 `bm25` | ES 未启动、索引不存在或地址错误；查 9200 和 `msd_drama_bm25_v1` |
| recommend 返回 `rerank` 降级 | DashScope API Key/URL/模型不可用；查 `content-config.yaml` 的 `msd.ai.rag.rerank.*` |
| recommend 返回 `rewrite` 降级 | DeepSeek Key 或模型不可用；检索仍使用原查询 |
| recommend 返回 `timeout` | 总超时 `msd.ai.rag.total-timeout-ms` 太短，或下游模型/检索过慢；看日志中的各阶段耗时 |
| reindex 409 | 已有重建任务在执行；等待完成或查 `rag-index-*` 线程 |
| reindex 502 | `msd-drama` 不可用或 `/internal-source/rag/dramas` 调用失败；先确认 drama 已注册并直连可访问 |
| reindex `partial=true` | Milvus 或 ES 单路失败；看 `errors`，修复后重跑 |
| ES 启动报 `max virtual memory areas vm.max_map_count [65530] is too low` | Docker Desktop VM 执行 `wsl -d docker-desktop sysctl -w vm.max_map_count=262144`，重启 ES |
| ES 容器 exit 137 | Docker Desktop 内存不足；提高内存或调小 `ES_JAVA_OPTS` |
| ES `_count=0` | 未执行 reindex，或 reindex 的 BM25 分支失败；查 `/status` 的 `lastError` |
| Milvus 未启动但 content service 能启动 | 预期行为；`LazyMilvusStoreProvider` 延迟连接，recommend 走 BM25 |
| Milvus insert 后 panic `MmapManager has not been init` 或 `Failed to get filesystem` | 检查 `deploy/docker-compose.yml` 的 `COMMON_STORAGETYPE` 是否为 `remote`；2.6.x 的 `minio` 已废弃，会触发 Arrow FS 初始化失败。改为 `remote` 后重启 Milvus，重跑 reindex；`milvus_data` 可保留 |
| `InitRemoteArrowFileSystemSingleton failed ... Unsupported storage type: minio` | 同上；2.6.x 的 `common.storageType` 只接受 `remote/local/opendal`，不要再使用 `minio` |
| 连续 reindex 后 ES 数量翻倍 | 正常实现会先删再写；若仍翻倍，检查是否有其他进程直接写入索引 |
| 审核通过/下架后索引没变化 | 确认 `msd.kafka.drama-changed.enabled=true`、Kafka 可达、topic `msd.drama.changed.v1` 存在；查 drama 的 `drama changed published` 和 content 的 `drama changed event accepted` 日志 |
| 增量索引 `rag_index_failure_total` 持续增长 | 查 content 日志中的 Milvus/ES 错误；修复后执行全量 reindex，或重放失败事件 |
| AI 预检返回 429 | 用户当天额度用完（默认 10 次）；改 `content-config.yaml` 的 `msd.ai.precheck.daily-limit`，或等次日 |
| AI 预检 `degraded=true` | 合规模型超时/不可用；页面提示以人工审核为准，不影响提交和人工审核 |
| 字幕导入后 `sources` 没有 transcript | 确认 `t_episode_subtitle/segment` 有数据、`msd.transcript.changed.v1` 有事件、content 日志有 `transcript index upsert`；必要时全量 reindex |
| ASR 脚本报 `No module named faster_whisper` | 执行 `pip install -r scripts/requirements-asr.txt`，并确认系统 ffmpeg 在 PATH |
| 全局助手不调用找剧工具 | 确认 `agent-config.yaml` 的 allow 包含 `search_drama_rag`，并重新发布 Nacos 配置后重启 8105 |
| 全局助手返回空 cards | 工具可能返回 `[NO_RESULT]`/`[DEGRADED]`，或 Supervisor 没保留 `[DRAMA_CARD]` 行；查工具调用审计日志 |
| 首页/详情一直查库 | 确认 `msd.cache.enabled=true`、Redis 可达；应用日志有 `redis unavailable` 时先恢复 Redis；见 [cache.md](cache.md) |
| Redis 故障时请求快速降级 | `RedissonClientAccessor` 在 `redisson-init` 守护线程建连，请求线程不阻塞；看该线程日志和 15s 重试间隔，恢复后自动重连 |
| 详情随机 ID 仍在 Redis 生成 null key | 布隆可能未启用或 active 指针缺失；检查 `msd:drama:bloom:active` 和启动日志中的布隆重建 |
| 详情新剧 404 但库里有数据 | 布隆重建最多滞后 5 分钟；手工触发 xxl-job，或检查 `t_drama` 可见性字段 |
| xxl-job registry 500 `accessToken or appname invalid` | `xxl_job_group.access_token` 必须等于 `msd-drama.yaml` 的 `xxl.job.accessToken`；3.5 校验的是执行器组 token，改后等 30 秒 |
| xxl-job 调度失败 `执行器地址为空` | 执行器未注册到 `xxl_job_registry`；确认 9999 端口、admin 可达 `host.docker.internal:9999`、group cache 已刷新 |
| xxl-job 任务一直不触发 | 确认任务 `trigger_status=1`、admin 与 drama 使用同一 MySQL 库；看 `xxl_job_info.trigger_next_time` |
| demo `virtual=false` | `spring.threads.virtual.enabled` 未生效或被 `SPRING_THREADS_VIRTUAL_ENABLED=false` 覆盖；重启确认 |
| demo 接口 404 | 只有 `dev` profile 且 `msd.cache.demo.enabled=true` 时注册；生产不加载，属于预期 |
| 虚拟线程压测 p95 反而变差 | 检查下游 Sentinel 限流、Hikari 连接池耗尽、`synchronized` pinning（`-Djdk.tracePinnedThreads=full`） |
| 解锁后详情计数旧 | 确认 `EpisodeService.addUnlockRef` 调用了 `CacheEvictor`，且事务提交后延迟删除任务执行；L1 其他实例最多晚 30s |
