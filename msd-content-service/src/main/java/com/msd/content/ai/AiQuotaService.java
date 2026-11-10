package com.msd.content.ai;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 场景按用户按天的轻量配额。
 *
 * <p>Redis 计数失败时 fail-open，避免缓存故障阻断创作者正常提交前预检；
 * 配额只是成本保护，不参与权限判断。</p>
 */
@Service
public class AiQuotaService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AiQuotaService.class);
    /** 配额 key 前缀。 */
    private static final String KEY_PREFIX = "msd:ai:quota:";
    /** 计数过期时间，留一天余量避免跨时区误判。 */
    private static final Duration TTL = Duration.ofDays(2);

    // Redisson 客户端
    private final RedissonClient redissonClient;

    /**
     * 注入懒加载的 Redisson 客户端。
     */
    public AiQuotaService(@Lazy RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 消耗一次配额。
     *
     * @param scene     场景名，例如 precheck
     * @param userId    用户 ID，空值时不做限制
     * @param dailyLimit 每日上限
     * @return 配额结果；Redis 不可用时 allowed=true、remaining=-1
     */
    public QuotaResult consume(String scene, Long userId, int dailyLimit) {
        // 1. 未登录或关闭限制时直接放行
        if (userId == null || userId <= 0 || dailyLimit <= 0) {
            return new QuotaResult(true, 0, -1);
        }
        String key = KEY_PREFIX + scene + ":" + userId + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            // 2. 原子自增，首次计数时设置过期时间
            RAtomicLong counter = redissonClient.getAtomicLong(key);
            long used = counter.incrementAndGet();
            if (used == 1) {
                counter.expire(TTL);
            }
            // 3. 超过上限时返回剩余 0，由调用方转 429
            long remaining = Math.max(dailyLimit - used, 0);
            return new QuotaResult(used <= dailyLimit, used, remaining);
        } catch (Exception ex) {
            // 4. Redis 故障不阻断预检，仅记录 debug，避免日志刷屏
            log.debug("ai quota unavailable: scene={}, userId={}, error={}",
                    scene, userId, ex.getMessage());
            return new QuotaResult(true, 0, -1);
        }
    }

    /**
     * 一次配额消耗结果。
     *
     * @param allowed   是否允许本次调用
     * @param used      已使用次数
     * @param remaining 剩余次数，-1 表示未统计
     */
    public record QuotaResult(boolean allowed, long used, long remaining) {
    }
}
