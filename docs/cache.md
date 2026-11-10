# 首页多级缓存与虚拟线程

`msd-drama` 的首页和详情是读多写少的聚合接口。首页每次请求执行 3 条 SQL，详情执行 4 条；热度排序没有索引，数据量上来后是明显的读热点。当前实现用 Caffeine + Redis 多级缓存、布隆过滤器、Redisson 击穿锁和延迟双删把读压力挡在数据库之前，并用 Java 21 虚拟线程验证慢 I/O 场景的吞吐上限。

## 1. 读路径

```text
Controller
  └─ DramaBrowseService（业务委托）
       └─ DramaBrowseCacheService（带 @MultiLevelCache）
            ├─ L1 Caffeine（进程内）
            ├─ L2 Redisson RBucket（Redis）
            ├─ 布隆过滤器（仅详情 / 批量 ID）
            ├─ RLock（cache miss 单飞重建）
            └─ MySQL（MyBatis-Plus）
```

### 1.1 key 与 TTL

| 数据 | 缓存名 | Redis key | L1 TTL | L2 TTL | null TTL |
|---|---|---|---:|---:|---:|
| 首页聚合 | `home` | `msd:drama:cache:home:v1` | 10s | 30s | 30s |
| 短剧详情 | `detail` | `msd:drama:cache:detail:{dramaId}` | 30s | 300s | 30s |

- 统一前缀：`msd:drama:cache:{cacheName}:{key}`。
- 击穿锁：`msd:drama:lock:cache:{cacheName}:{key}`，等待 2s、自旋 50ms、lease 30s。
- L2 TTL 会加 ±10% 抖动，避免同一时刻大量 key 一起过期。
- 首页无参数，key 固定 `v1`；DTO 结构升级时改 key 版本，避免读到旧 JSON。

```java
@MultiLevelCache(cacheName = "home", key = "'v1'", policy = "home", lock = true)
public HomeFeed loadHome() { ... }

@MultiLevelCache(cacheName = "detail", key = "#dramaId", policy = "detail",
        bloomId = "#dramaId", lock = true, cacheNull = true)
public DramaDetail loadDetail(Long dramaId) { ... }
```

- `key` 和 `bloomId` 都是 SpEL，支持 `#dramaId`、`#p0`；参数名依赖 `-parameters`，已由编译插件保留。
- `cacheNull=true` 才会缓存 null；`bloomId` 为空表示不查布隆。

### 1.2 命中与回源

1. L1 命中直接返回，不访问 Redis。
2. L1 miss、L2 命中：反序列化后回填 L1。
3. L1/L2 都 miss：详情先查布隆，布隆明确不存在直接返回 `null`，不查库也不写 null 缓存。
4. 布隆通过或首页场景：`tryLock` 抢占重建权。
5. 抢到锁后 **double-check L2**，避免等锁期间别人已经回填；确认仍 miss 才查 DB。
6. 抢不到锁：每 50ms 重读 L1/L2，最多 2s；超时后直接查 DB 但不写缓存，避免惊群回填。
7. Redis 不可用或锁异常：降级到 DB + 本地 Caffeine，业务不报错；恢复后自动重新使用 Redis。
8. RedissonClient 由 `RedissonClientAccessor` 在守护线程异步建连；Redis 启动慢或不可用时，请求线程直接按 miss/降级处理，不会阻塞在 Redisson 建连上。

## 2. 穿透、击穿与雪崩

### 2.1 穿透：布隆过滤器

- 只用于 **详情** 和 **批量 ID 查询**；首页没有 ID 入参，无法用布隆保护。
- `RBloomFilter<Long>` 使用版本化 key：`msd:drama:bloom:v{epochMillis}`。
- 活跃指针：`msd:drama:bloom:active`，读取端本地缓存 10s。
- 新过滤器写满后 **原子切换指针**，旧过滤器 10min 后过期，重建期间不出现空过滤器窗口。
- 布隆返回 `false` 时详情直接 404、批量直接过滤该 ID，**不写 Redis null key**，避免随机 ID 把 Redis 打满。
- 布隆返回 `true` 但 DB 无数据时，详情仍写入 null 标记并缓存 30s；这类是误判，量很小。
- 指针/过滤器缺失、Redis 异常一律 fail-open，按“可能存在”处理。

### 2.2 击穿：Redisson 锁

热点详情过期时只允许一个实例的一个线程回源，其余线程自旋等待并复用回填结果；等待超时后直接查库，不再写缓存，防止大量线程同时回填。

### 2.3 雪崩：TTL 抖动

`home` 的 L2 只有 30s，`detail` 为 300s，实际写入时加 ±10% 抖动。即使同一批 key 同时创建，也不会在同一秒集中过期。

## 3. 写路径：延迟双删与防抖

### 3.1 注解

```java
@MultiLevelCacheEvict(cacheName = "home", key = "'v1'")
@MultiLevelCacheEvict(cacheName = "detail", key = "#drama.id")
@Transactional
public void updateDrama(Drama drama) {
    // 更新数据库
}
```

- `@MultiLevelCacheEvict` 可重复，支持 `allEntries`。
- `beforeInvocation=true`（默认）：方法执行前删一次 L1/L2；事务提交后立即再删一次，并在 500ms 后第二次延迟删除。
- `beforeInvocation=false`：提交后立即删除一次，再延迟删除一次。
- 延迟双删覆盖“读请求在删缓存后、事务提交前把旧值回填”的竞争窗口。

### 3.2 现有写路径

当前没有短剧发布/编辑/下架的 CRUD 接口，延迟双删先接在已有写路径上：

| 写路径 | 失效范围 | 方式 |
|---|---|---|
| `EpisodeService.addUnlockRef` | `detail:{dramaId}` | 事务提交后立即 + 500ms 再删 |
| `InteractionEventConsumer`（点赞/收藏/评论/历史） | `detail:{dramaId}` | 2s 防抖后删 + 延迟再删 |
| 同上，短剧级计数变化 | `home:v1` | 30s 防抖后删 + 延迟再删 |

- 计数事件可能每秒很多条，不能每条都删首页；防抖把窗口内的多次变化合并成一次失效。
- 解锁是低频写，直接立即双删，避免用户解锁后看到旧计数。
- `EpisodeDramaResolver` 用 5min 本地 Caffeine 把 `episodeId -> dramaId` 缓存起来，避免每条事件都查库。
- L1 在进程内，单实例删除不会清其他实例的 L1。`msd.cache.evict.broadcast-enabled=true` 时，`CacheEvictionBroadcaster` 通过 Redis Topic `msd:drama:cache:evict` 广播失效消息，其他实例只清理本地 L1；Redis 不可用时静默跳过，仍由 10s/30s TTL 兜底。

## 4. 布隆重建与 xxl-job

### 4.1 重建流程

1. 分页读取可见短剧 ID：`deleted=0 AND review_status=2 AND publish_time IS NOT NULL`，每页 1000。
2. 新 key `msd:drama:bloom:v{epochMillis}`，`tryInit(max(1024, max(100000, total*2)), 0.01)`。
3. 全部 ID 写入新过滤器后设置 24h TTL。
4. 原子更新 active 指针；旧过滤器设置 10min TTL。
5. 任一步失败：删除新 key，旧过滤器继续服务，不会出现“布隆为空导致全量 404”。

### 4.2 触发方式

- **启动时**：应用就绪后检查 active 指针；缺失则异步重建，失败只记日志（布隆 fail-open）。
- **xxl-job**：`dramaCacheRebuildHandler`，默认每 5 分钟执行一次。

本地首次配置：

```bash
# 1. 发布 msd-drama.yaml（含 xxl.job.*）
python scripts/init-nacos-config.py

# 2. 注册执行器组和任务；Windows 必须加 utf8mb4
mysql -h127.0.0.1 -umsd -p --default-character-set=utf8mb4 \
  < deploy/mysql/init/03_xxl_job_drama.sql

# 3. 启动 admin（Compose 中端口 8088，context-path 为 /）
docker compose -f deploy/docker-compose.yml up -d xxl-job-admin

# 4. 重启 msd-drama
```

验证：

```sql
SELECT app_name, access_token FROM xxl_job_group WHERE app_name = 'msd-drama-executor';
SELECT id, registry_key, registry_value FROM xxl_job_registry;

SELECT l.job_id, l.trigger_time, l.trigger_code, l.handle_code
FROM xxl_job_log l
JOIN xxl_job_info i ON l.job_id = i.id
WHERE i.executor_handler = 'dramaCacheRebuildHandler'
ORDER BY l.id DESC LIMIT 5;
```

- 执行器注册成功时 `xxl_job_registry` 出现 `msd-drama-executor -> http://host.docker.internal:9999`。
- 任务日志 `trigger_code=200`、`handle_code=200` 表示调度和执行成功。
- 应用日志应出现 `xxl-job remoting server start success ... port = 9999` 和 `xxl-job register jobhandler success`。

实测（2026-10-25 17:45）：

```text
xxl_job_log: trigger_code=200, handle_code=200, handle_msg=bloom rebuild success
executor_address: http://host.docker.internal:9999
DefaultDramaBloomFilterService: total=9, added=9,
  filter=msd:drama:bloom:v1792921500094, old=msd:drama:bloom:v1792920464426, elapsedMs=29
```

### 4.3 3.5 的 token 细节

xxl-job 3.5 注册时校验的是 **执行器组 access_token**，不是只看 admin 全局 token。`03_xxl_job_drama.sql` 默认写入本地 dev token `$XXL_JOB_ACCESS_TOKEN`，与 `msd-drama.yaml` 的 `xxl.job.accessToken` 一致。

- test/prod 执行该 SQL 前，把 `03_xxl_job_drama.sql` 里的 token 改成对应环境的 `XXL_JOB_ACCESS_TOKEN`。
- 也可以执行后在 admin UI「执行器管理」里修改该组 token。
- 修改 token 后，admin 的 `JobGroupCache` 约 30 秒刷新，执行器下一次心跳（约 30 秒）完成注册。

## 5. Java 21 虚拟线程与 RestClient

### 5.1 开启方式

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

- 默认开启，可用 `SPRING_THREADS_VIRTUAL_ENABLED=false` 回滚。
- Tomcat 请求线程变为虚拟线程；Kafka listener、xxl-job、缓存延迟删除 scheduler 仍是平台线程，这是刻意隔离。
- 全局开启不改变业务代码，Controller 中阻塞的 JDBC/Redis/HTTP 调用照常执行。

### 5.2 demo 接口

仅 `dev` profile 且 `msd.cache.demo.enabled=true`（默认）时注册，路径不在网关路由内，只能直连 8102：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal-cache/demo/ai/single?prompt=hi` | 单次调用下游 AI，返回线程名、`virtual`、耗时 |
| GET | `/internal-cache/demo/ai/fanout?calls=50&mode=virtual&pool=8&prompt=hi` | 并发扇出，对比虚拟线程和固定线程池 |

下游是 `msd-agent-service` 的 `GET /internal/ai/chat`（Mock RAG + LLM，约 200ms I/O），由 `RestClient` 调用：

- base URL 默认 `http://127.0.0.1:8105`，可用 `MSD_AGENT_URL` 覆盖。
- `JdkClientHttpRequestFactory` 连接超时 2s、读超时 10s。
- `fanout` 默认上限 200 次调用，防止误压垮下游；`pool` 只在 `platform` 模式生效。

```bash
curl "http://127.0.0.1:8102/internal-cache/demo/ai/single?prompt=hi"
curl "http://127.0.0.1:8102/internal-cache/demo/ai/fanout?calls=100&mode=virtual&pool=8&prompt=hi"
curl "http://127.0.0.1:8102/internal-cache/demo/ai/fanout?calls=100&mode=platform&pool=8&prompt=hi"
```

### 5.3 实测

单实例、下游每次约 200ms：

| 场景 | calls | 并发 | 总耗时 | p50 | p95 | 结果 |
|---|---:|---:|---:|---:|---:|---|
| 虚拟线程 | 100 | 100 | 226ms | 208ms | 223ms | 100/100 成功 |
| 固定池 8 | 100 | 8 | 2510ms | 188ms | 202ms | 100/100 成功 |
| 虚拟线程 | 50 | 50 | 215ms | 209ms | 212ms | 50/50 成功 |

200 次单接口压测：

```bash
python scripts/load-test-ai.py --calls 200 --concurrency 100
```

实测 `success=200`、`wall=437ms`、`p50/p95/max=193/215/223ms`。

结论：下游是典型的“等网络/等模型”场景，平台线程池大小固定，8 个线程只能串行处理 100 次 200ms 调用；虚拟线程在 socket I/O 时卸载 carrier，100 个请求几乎同时挂在网络上，总耗时接近单次延迟。吞吐瓶颈因此转移到下游容量、连接池和限流，而不是本地线程数。

### 5.4 注意点

- **pinning**：JDK 21 中 `synchronized` 块内阻塞会钉住 carrier，压测时加 `-Djdk.tracePinnedThreads=full` 检查。
- **Seata / ThreadLocal**：虚拟线程仍然有 ThreadLocal，但线程数量大，`ThreadLocal` 缓存和事务上下文要及时清理；解锁链路回归时必须验证 Seata XID 透传。
- **Hikari**：P3 把 `msd-drama`、`msd-interaction` 连接池调到 20，其他服务 10；虚拟线程把并发拉高后 DB 仍是硬瓶颈，总连接数不要超过 MySQL `max_connections` 的 60%。
- **下游限流**：`msd-agent-service` 有 Sentinel 熔断，demo 的 200 次并发可能触发降级，失败数会体现在响应里。
- **线程名**：虚拟线程默认名可能为空，不要依赖线程名判断；用 `virtual` 字段。
- **回滚**：`SPRING_THREADS_VIRTUAL_ENABLED=false` 重启即可；现有接口和 Seata 解锁不受影响。

## 6. 配置项

本地默认值在 `msd-drama/src/main/resources/application.yml`，可被 Nacos `msd-drama.yaml` 覆盖：

```yaml
msd:
  cache:
    enabled: true
    max-size: 20000
    policies:
      home: { l1-ttl: 10s, l2-ttl: 30s, null-ttl: 30s }
      detail: { l1-ttl: 30s, l2-ttl: 300s, null-ttl: 30s }
    lock: { wait: 2s, spin: 50ms, lease: 30s }
    evict: { delay: 500ms, home-debounce: 30s, detail-debounce: 2s, broadcast-enabled: false }
    bloom:
      enabled: true
      key-prefix: msd:drama:bloom
      expected-insertions: 100000
      false-probability: 0.01
      filter-ttl: 24h
      old-filter-ttl: 10m
      pointer-cache-ttl: 10s
      page-size: 1000
    redis:
      failure-backoff: 15s
    demo:
      enabled: true
      ai-base-url: http://127.0.0.1:8105
      connect-timeout: 2s
      read-timeout: 10s
      max-calls: 200
xxl:
  job:
    enabled: false   # 本地无 admin 时关闭；Nacos 中为 true
```

## 7. 测试

```bash
mvn -B -pl msd-drama -am test
```

当前 37 个测试全部通过，覆盖：

- `CacheSerializerTest`：首页/详情 JSON round-trip、`LocalDateTime`、null、损坏/版本不匹配。
- `MultiLevelCacheAspectTest`：L1/L2 命中、null 缓存、布隆拒绝、锁自旋。
- `DramaBrowseCacheServiceTest`：首页/详情组装、详情 null。
- `DefaultDramaBloomFilterServiceTest`：重建后切指针、fail-open、读取 active。
- `CacheEvictorTest`、`MultiLevelCacheEvictAspectTest`：双删、防抖、可重复注解。
- `RedisCacheBackendTest`、`RedissonClientAccessorTest`：Redis 未就绪时 fail-fast、后台建连与错误记录。
- `EpisodeServiceTest`、`InteractionEventConsumerTest`：解锁和 Kafka 计数的失效钩子。
- `AiFanoutDemoServiceTest`：虚拟/平台线程、成功失败统计、分位、参数校验。

### 7.1 联调记录（2026-10-25）

| 场景 | 结果 |
|---|---|
| 首页连续两次 | 首次 110ms，第二次 3-22ms |
| 详情 5005 连续两次 | 首次 55ms，第二次 3ms |
| 详情 999999 | 404，约 7ms；Redis `detail:999999` 不存在 |
| Redis home key | 存在，TTL 20-30s |
| Redis detail key | 存在，TTL 约 284s |
| 布隆 active 指针 | `msd:drama:bloom:v1792921500094`，TTL 约 24h |
| xxl-job 17:45 调度 | `trigger_code=200`、`handle_code=200`，重建 9 条 |
| 虚拟线程 100 并发 | 226ms，100/100 成功 |
| 固定池 8 并发 100 次 | 2510ms，100/100 成功 |
| 关闭虚拟线程临时实例 | `single.virtual=false`，接口正常 |
| Redis 错误端口降级 | 健康 200；home 200/113ms，detail 200/20ms，999999 404/15ms |
| 解锁延迟双删 | POST 后 detail key 立即删除；回填后 700ms 再次删除 |
| Kafka 点赞失效 | 4s 后 detail key 删除；33s 后 home key 删除；like_count 4→5→4 |
| 虚拟线程 + Seata 回滚 | `unlock-with-failure` 返回 `50000`；order/drama 分支均回滚，余额 170、解锁/引用/计数均保持 0 |
| 200 次外部压测 | 437ms，p50/p95/max=193/215/223ms |
| P4 互动 2000 次/并发 50 | 1999/2000 成功，p50/p95/p99=21/73/128ms，outbox 0、消费组 LAG 0 |

## 8. 排障

| 现象 | 处理 |
|---|---|
| 首页/详情一直查库 | 确认 `msd.cache.enabled=true`、Redis 可达；看应用日志是否有 Redis 降级警告 |
| 详情随机 ID 仍生成 `detail:xxx` | 确认布隆 active 指针存在、`BloomFilterInitializer` 没报错；布隆 false 不应写 null |
| 布隆误判导致 404 | 等 5 分钟重建，或手工触发 xxl-job；新剧发布应调用 `add(id)`（当前无发布接口） |
| xxl-job registry 500 `accessToken or appname invalid` | 检查 `xxl_job_group.access_token` 是否等于 `msd-drama.yaml` 的 `xxl.job.accessToken`；改后等 30s |
| xxl-job 调度失败 `执行器地址为空` | 执行器未注册或 admin 还没刷新 group cache；查 9999 端口和 `xxl_job_registry` |
| 延迟双删没有生效 | 写方法必须是 Spring Bean 且事务提交；检查 `@Transactional` 和 `CacheEvictor` 日志 |
| L1 删了其他实例仍有旧值 | 预期行为；TTL 10s/30s 兜底，必要时启用 Pub/Sub |
| 虚拟线程没有生效 | `single` 返回 `virtual=false`：检查 `spring.threads.virtual.enabled` 和启动参数覆盖 |
| 虚拟线程压测吞吐反而低 | 看下游是否被 Sentinel 限流/熔断、Hikari 是否耗尽、是否发生 pinning |
| demo 接口 404 | 只有 `dev` profile 生效；确认 `msd.cache.demo.enabled=true`，且直连 8102 |

## 9. 限制

- 没有短剧 CRUD 接口，发布/编辑/下架接入缓存失效后，首页/详情才能实时更新；当前依赖 TTL 和已有写路径。
- 布隆新增 ID 最多滞后一个 xxl-job 周期（5 分钟）；发布流程应显式调用 `DramaBloomFilterService.add(id)`。
- L1 是单实例缓存，多实例部署时其他节点最多晚 10s/30s 看到更新。
- Redis 故障时 fail-open 降级到 DB，缓存不再提供保护，需监控降级日志。
- Redisson 建连已放到后台守护线程，Redis 首次不可用不会阻塞请求；恢复后自动重新使用 Redis。仍需监控 `redisson-init` 日志和 Redis 容量。
- 计数防抖意味着热播排序最多滞后约 30s，这是有意的吞吐取舍。
- demo 只用于本地验证，不属于生产 API；生产只暴露网关端口。
