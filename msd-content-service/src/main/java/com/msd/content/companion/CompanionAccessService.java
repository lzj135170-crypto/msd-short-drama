package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.client.OrderAccessClient;
import com.msd.content.client.OrderDramaUnlocks;
import com.msd.content.client.OrderUnlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 伴看付费集权限校验。
 *
 * <p>免费集直接放行；付费集必须由订单服务确认已解锁。订单服务不可用时对付费集
 * fail-closed，宁可少回答也不能泄露付费剧情。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionAccessService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionAccessService.class);
    /** 单集权限缓存时间。 */
    private static final long ACCESS_CACHE_MS = 60_000L;
    /** 短剧解锁集合缓存时间。 */
    private static final long UNLOCKED_CACHE_MS = 60_000L;

    // 订单只读客户端
    private final OrderAccessClient orderClient;
    // 单集权限缓存，key = userId:episodeId
    private final Map<String, CacheEntry<Boolean>> accessCache = new ConcurrentHashMap<>();
    // 短剧已解锁集合缓存，key = userId:dramaId
    private final Map<String, CacheEntry<Set<Long>>> unlockedCache = new ConcurrentHashMap<>();

    /**
     * 注入订单只读客户端。
     */
    public CompanionAccessService(OrderAccessClient orderClient) {
        this.orderClient = orderClient;
    }

    /**
     * 判断用户是否可以访问当前剧集。
     *
     * @param userId 当前用户，未登录时只为免费集放行
     * @param meta   剧集元信息
     * @return true 表示免费或已解锁
     */
    public boolean canAccess(Long userId, CompanionEpisodeDTO meta) {
        // 1. 剧集缺失或免费集直接放行
        if (meta == null) {
            return false;
        }
        int pricePoints = meta.pricePoints() == null ? 0 : meta.pricePoints();
        if (pricePoints <= 0) {
            return true;
        }
        // 2. 未登录用户不能访问付费集
        if (userId == null || userId <= 0) {
            return false;
        }
        // 3. 读短时缓存，避免每次聊天都打订单服务
        String key = userId + ":" + meta.episodeId();
        Boolean cached = readBooleanCache(key);
        if (cached != null) {
            return cached;
        }
        // 4. 查询订单服务；异常和空结果都按未解锁处理
        boolean allowed = false;
        try {
            Result<OrderUnlockState> response = orderClient.unlockState(userId, meta.episodeId());
            allowed = response != null && response.isSuccess()
                    && response.getData() != null
                    && response.getData().unlocked();
        } catch (Exception e) {
            log.warn("companion access check failed: userId={}, episodeId={}, error={}",
                    userId, meta.episodeId(), rootMessage(e));
        }
        writeCache(accessCache, key, allowed, ACCESS_CACHE_MS);
        return allowed;
    }

    /**
     * 查询用户在某部短剧下已解锁的付费集，用于过滤前情。
     *
     * <p>订单服务失败时返回空集合；调用方再按 pricePoints 只保留免费集。</p>
     */
    public Set<Long> unlockedEpisodeIds(Long userId, Long dramaId) {
        // 1. 未登录或参数非法时没有可用的解锁记录
        if (userId == null || userId <= 0 || dramaId == null || dramaId <= 0) {
            return Set.of();
        }
        // 2. 读缓存
        String key = userId + ":" + dramaId;
        CacheEntry<Set<Long>> cached = unlockedCache.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.value();
        }
        // 3. 查询订单服务；失败时只返回空集合，后续过滤会保留免费集
        Set<Long> unlocked = Set.of();
        try {
            Result<OrderDramaUnlocks> response = orderClient.dramaUnlocks(userId, dramaId);
            if (response != null && response.isSuccess() && response.getData() != null) {
                unlocked = Set.copyOf(response.getData().unlockedEpisodeIds());
            }
        } catch (Exception e) {
            log.warn("companion unlocked episodes failed: userId={}, dramaId={}, error={}",
                    userId, dramaId, rootMessage(e));
        }
        writeCache(unlockedCache, key, unlocked, UNLOCKED_CACHE_MS);
        return unlocked;
    }

    /**
     * 读取未过期的布尔缓存。
     */
    private Boolean readBooleanCache(String key) {
        CacheEntry<Boolean> entry = accessCache.get(key);
        if (entry == null || entry.expiresAt() <= System.currentTimeMillis()) {
            return null;
        }
        return entry.value();
    }

    /**
     * 写入带过期时间的缓存。
     */
    private <T> void writeCache(Map<String, CacheEntry<T>> cache, String key,
                                T value, long ttlMs) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + ttlMs));
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 缓存项。
     */
    private record CacheEntry<T>(T value, long expiresAt) {
    }
}
