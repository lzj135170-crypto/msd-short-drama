package com.msd.agent.routing;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;

/**
 * 路由缓存：精确消息缓存 + 会话粘性缓存。
 *
 * <p>Redis 不可用时 fail-open，路由仍由规则和 Supervisor 模型完成。</p>
 */
@Component
public class RouteCache {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RouteCache.class);
    /** 精确消息缓存 key 前缀。 */
    private static final String KEY_PREFIX = "msd:agent:route:";
    /** 会话粘性 key 前缀。 */
    private static final String STICKY_PREFIX = "msd:agent:route:sticky:";
    /** Redis 失败后的退避时间（毫秒）。 */
    private static final long REDIS_BACKOFF_MS = 30_000L;

    // Redis 客户端提供者；模型未配置或 Redis 关闭时为空
    private final ObjectProvider<RedissonClient> redissonProvider;
    // 缓存 TTL 和规则版本
    private final RouteProperties properties;
    // Redis 失败后的退避截止时间
    private volatile long redisUnavailableUntil;

    /**
     * 注入 Redis 提供者和路由配置。
     */
    public RouteCache(ObjectProvider<RedissonClient> redissonProvider,
                      RouteProperties properties) {
        this.redissonProvider = redissonProvider;
        this.properties = properties;
    }

    /**
     * 读取精确消息缓存。
     */
    public CachedRoute get(String normalizedMessage) {
        return read(key(normalizedMessage));
    }

    /**
     * 写入精确消息缓存。
     */
    public void put(String normalizedMessage, CachedRoute route) {
        write(key(normalizedMessage), route);
    }

    /**
     * 读取会话粘性路由，用于“那第 3 集呢”这类省略消息。
     */
    public CachedRoute getSticky(String userId, String sessionId) {
        return read(stickyKey(userId, sessionId));
    }

    /**
     * 写入会话粘性路由。
     */
    public void putSticky(String userId, String sessionId, CachedRoute route) {
        write(stickyKey(userId, sessionId), route);
    }

    /**
     * 读取一个缓存值；Redis 故障时静默跳过。
     */
    private CachedRoute read(String key) {
        // 1. 最近失败过，跳过连接，避免每个请求都等超时
        if (System.currentTimeMillis() < redisUnavailableUntil) {
            return null;
        }
        RedissonClient client = client();
        if (client == null) {
            return null;
        }
        try {
            RBucket<String> bucket = client.getBucket(key);
            String value = bucket.get();
            return decode(value);
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("read route cache failed: key={}", key, ex);
            return null;
        }
    }

    /**
     * 写入一个缓存值；Redis 故障时静默跳过。
     */
    private void write(String key, CachedRoute route) {
        // 1. 空路由和 Redis 退避期都不写
        if (route == null || System.currentTimeMillis() < redisUnavailableUntil) {
            return;
        }
        RedissonClient client = client();
        if (client == null) {
            return;
        }
        try {
            int minutes = Math.max(properties.getCacheTtlMinutes(), 1);
            client.getBucket(key).set(encode(route), Duration.ofMinutes(minutes));
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("write route cache failed: key={}", key, ex);
        }
    }

    /**
     * 获取 Redis 客户端；没有 Bean 或 Bean 创建失败时返回 null。
     */
    private RedissonClient client() {
        if (redissonProvider == null) {
            return null;
        }
        try {
            return redissonProvider.getIfAvailable();
        } catch (RuntimeException ex) {
            // Redis 完全不可达时 Redisson Bean 本身就创建失败，同样进入退避
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("create redisson client failed, route cache disabled temporarily", ex);
            return null;
        }
    }

    /**
     * 精确消息 key，包含规则版本，避免改规则后命中旧结果。
     */
    private String key(String normalizedMessage) {
        String version = version();
        return KEY_PREFIX + version + ":" + sha256(normalizedMessage).substring(0, 32);
    }

    /**
     * 会话粘性 key，按用户和会话隔离。
     */
    private String stickyKey(String userId, String sessionId) {
        String version = version();
        String user = StringUtils.hasText(userId) ? userId.trim() : "anon";
        String session = StringUtils.hasText(sessionId) ? sessionId.trim() : "default";
        return STICKY_PREFIX + version + ":" + sha256(user + "\n" + session).substring(0, 32);
    }

    /**
     * 规则版本，空值回退 v1。
     */
    private String version() {
        return StringUtils.hasText(properties.getRulesVersion())
                ? properties.getRulesVersion().trim().toLowerCase(Locale.ROOT)
                : "v1";
    }

    /**
     * 编码为 route|agent|confidence|matchedRule，避免额外 JSON 依赖。
     */
    private String encode(CachedRoute route) {
        return route.route().name() + "|" + route.agent() + "|" + route.confidence()
                + "|" + (route.matchedRule() == null ? "" : route.matchedRule());
    }

    /**
     * 解析缓存值；格式不合法时视为未命中。
     */
    private CachedRoute decode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            String[] parts = value.split("\\|", 4);
            if (parts.length < 3) {
                return null;
            }
            IntentRoute route = IntentRoute.valueOf(parts[0]);
            String agent = StringUtils.hasText(parts[1]) ? parts[1] : route.agent();
            double confidence = Double.parseDouble(parts[2]);
            String matchedRule = parts.length > 3 ? parts[3] : "";
            return new CachedRoute(route, agent, confidence, matchedRule);
        } catch (IllegalArgumentException ex) {
            log.debug("invalid route cache value: {}", value);
            return null;
        }
    }

    /**
     * SHA-256 十六进制摘要，避免把用户原文放进 Redis key。
     */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                text.append(String.format("%02x", item));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * 缓存中的路由结果，不包含来源标记。
     */
    public record CachedRoute(
            IntentRoute route,
            String agent,
            double confidence,
            String matchedRule) {
    }
}
