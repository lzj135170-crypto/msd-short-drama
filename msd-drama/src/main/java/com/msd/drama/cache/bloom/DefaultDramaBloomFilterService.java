package com.msd.drama.cache.bloom;

import com.msd.drama.cache.CacheProperties;
import com.msd.drama.cache.RedissonClientAccessor;
import com.msd.drama.mapper.DramaMapper;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redisson RBloomFilter 实现。
 *
 * <p>使用版本化 filter + active 指针原子切换，重建期间旧 filter 仍然可用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.cache.bloom", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DefaultDramaBloomFilterService implements DramaBloomFilterService {

    // 布隆过滤器日志
    private static final Logger log = LoggerFactory.getLogger(DefaultDramaBloomFilterService.class);

    // 非阻塞 Redisson 客户端访问器
    private final RedissonClientAccessor clientAccessor;
    // 短剧数据访问，用于全量重建
    private final DramaMapper dramaMapper;
    // 缓存配置
    private final CacheProperties properties;

    // 本地缓存的 active 过滤器 key
    private volatile String cachedFilterKey;
    // active 指针本地缓存的写入时间戳
    private volatile long cachedAt;
    // Redis 故障退避截止时间戳
    private volatile long unavailableUntil;

    /**
     * 注入 Redisson 客户端访问器、短剧 Mapper 和缓存配置。
     */
    public DefaultDramaBloomFilterService(RedissonClientAccessor clientAccessor,
                                          DramaMapper dramaMapper,
                                          CacheProperties properties) {
        this.clientAccessor = clientAccessor;
        this.dramaMapper = dramaMapper;
        this.properties = properties;
    }

    /**
     * 判断 ID 是否可能存在；Redis 不可用或过滤器未初始化时一律放行。
     */
    @Override
    public boolean mightContain(Long id) {
        // 1. 空 ID 直接判定不存在
        if (id == null) {
            return false;
        }
        // 2. 客户端或 active 指针不可用时 fail-open
        RedissonClient client = clientOrNull();
        if (client == null) {
            return true;
        }
        String filterKey = currentFilterKey(client);
        if (filterKey == null) {
            return true;
        }
        try {
            // 3. 查询过滤器；异常时同样放行，避免误杀正常请求
            return client.getBloomFilter(filterKey).contains(id);
        } catch (Exception e) {
            log.warn("bloom contains failed, fail-open: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 向当前 active 过滤器添加 ID；写入失败只记录日志。
     */
    @Override
    public void add(Long id) {
        // 1. 空 ID、客户端不可用或 active 指针缺失时跳过
        if (id == null) {
            return;
        }
        RedissonClient client = clientOrNull();
        if (client == null) {
            return;
        }
        String filterKey = currentFilterKey(client);
        if (filterKey == null) {
            return;
        }
        try {
            // 2. 写入失败不抛出，等待下次全量重建补齐
            client.getBloomFilter(filterKey).add(id);
        } catch (Exception e) {
            log.warn("bloom add failed: {}", e.getMessage());
        }
    }

    /**
     * 全量重建过滤器，成功后原子切换 active 指针，旧过滤器延迟过期。
     */
    @Override
    public BloomRebuildResult rebuild() {
        // 1. 获取可用客户端、开始时间和重建锁
        RedissonClient client = requiredClient();
        long startedAt = System.currentTimeMillis();
        RLock lock = client.getLock(properties.getBloom().getKeyPrefix() + ":rebuild-lock");
        boolean locked = false;
        String newKey = null;
        try {
            locked = lock.tryLock(0, 5, TimeUnit.MINUTES);
            if (!locked) {
                // 已有实例在重建，直接返回空结果
                log.warn("bloom rebuild is already running");
                return new BloomRebuildResult(0, 0, null, null,
                        System.currentTimeMillis() - startedAt);
            }

            // 2. 读取公共剧总数，按 2 倍冗余预估位数组容量
            long total = dramaMapper.countPublic();
            int pageSize = Math.max(1, properties.getBloom().getPageSize());
            long expected = Math.max(1_024L,
                    Math.max(properties.getBloom().getExpectedInsertions(), total * 2));
            newKey = properties.getBloom().getKeyPrefix() + ":v" + System.currentTimeMillis();

            // 3. 初始化新版本过滤器；预期容量或误判率不合法时走失败清理
            RBloomFilter<Long> filter = client.getBloomFilter(newKey);
            if (!filter.tryInit(expected, properties.getBloom().getFalseProbability())) {
                throw new IllegalStateException("初始化布隆过滤器失败: " + newKey);
            }

            // 4. 分页读取公共剧 ID 并写入新过滤器
            long added = 0;
            for (int offset = 0; offset < total; offset += pageSize) {
                List<Long> ids = dramaMapper.selectPublicIds(offset, pageSize);
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                for (Long id : ids) {
                    filter.add(id);
                    added++;
                }
            }

            // 5. 设置新过滤器 TTL，并原子切换 active 指针
            client.getKeys().expire(properties.getBloom().getFilterTtl(), newKey);
            RBucket<String> active = client.getBucket(activeKey(), StringCodec.INSTANCE);
            String oldKey = active.getAndSet(newKey);
            // 6. 旧过滤器只缩短 TTL，等重建窗口过后自然过期
            if (oldKey != null && !oldKey.equals(newKey)) {
                client.getKeys().expire(properties.getBloom().getOldFilterTtl(), oldKey);
            }
            // 7. 更新本地 active 指针缓存
            cachedFilterKey = newKey;
            cachedAt = System.currentTimeMillis();

            // 8. 汇总并记录重建结果
            BloomRebuildResult result = new BloomRebuildResult(total, added, newKey, oldKey,
                    System.currentTimeMillis() - startedAt);
            log.info("bloom rebuild completed: total={}, added={}, filter={}, old={}, elapsedMs={}",
                    total, added, newKey, oldKey, result.elapsedMs());
            return result;
        } catch (InterruptedException e) {
            // 中断时恢复中断标记并终止重建
            Thread.currentThread().interrupt();
            throw new IllegalStateException("布隆过滤器重建被中断", e);
        } catch (Exception e) {
            // 重建失败时清理未生效的新过滤器，旧过滤器继续服务
            if (newKey != null) {
                try {
                    client.getKeys().delete(newKey);
                } catch (Exception ignored) {
                    // 清理失败不影响旧 filter 继续服务
                }
            }
            throw new IllegalStateException("布隆过滤器重建失败: " + e.getMessage(), e);
        } finally {
            // 释放重建锁，避免阻塞后续重建任务
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * active 指针缺失时触发重建；Redis 不可用时直接跳过。
     */
    @Override
    public void rebuildIfMissing() {
        // 1. 客户端不可用时无法判断过滤器是否存在
        RedissonClient client = clientOrNull();
        if (client == null) {
            return;
        }
        // 2. active 指针缺失时执行全量重建
        if (currentFilterKey(client) == null) {
            rebuild();
        }
    }

    /**
     * 取当前 active 过滤器 key，带本地短缓存；Redis 异常时回退到上次值。
     */
    private String currentFilterKey(RedissonClient client) {
        long now = System.currentTimeMillis();
        // 1. 本地缓存未过期时直接返回，减少 Redis 往返
        if (cachedFilterKey != null && now - cachedAt < properties.getBloom().getPointerCacheTtl().toMillis()) {
            return cachedFilterKey;
        }
        try {
            // 2. 从 Redis 读取 active 指针并刷新本地缓存
            RBucket<String> bucket = client.getBucket(activeKey(), StringCodec.INSTANCE);
            String key = bucket.get();
            cachedFilterKey = key;
            cachedAt = now;
            unavailableUntil = 0;
            return key;
        } catch (Exception e) {
            // 3. 读取失败时进入退避，并沿用上次已知的过滤器 key
            markUnavailable(e);
            return cachedFilterKey;
        }
    }

    /**
     * 取可用客户端；退避期内或尚未就绪时返回 null。
     */
    private RedissonClient clientOrNull() {
        // 处于退避期时不访问 Redis
        if (System.currentTimeMillis() < unavailableUntil) {
            return null;
        }
        RedissonClient client = clientAccessor.get();
        if (client == null) {
            return null;
        }
        unavailableUntil = 0;
        return client;
    }

    /**
     * 取可用客户端，不可用时抛出异常，供重建流程快速失败。
     */
    private RedissonClient requiredClient() {
        RedissonClient client = clientOrNull();
        if (client == null) {
            throw new IllegalStateException("Redis 不可用，无法重建布隆过滤器");
        }
        return client;
    }

    /**
     * active 指针在 Redis 中的 key。
     */
    private String activeKey() {
        return properties.getBloom().getKeyPrefix() + ":active";
    }

    /**
     * 记录错误并开启退避窗口。
     */
    private void markUnavailable(Exception e) {
        unavailableUntil = System.currentTimeMillis()
                + properties.getRedis().getFailureBackoff().toMillis();
        log.warn("bloom redis unavailable: {}", e.getMessage());
    }
}
