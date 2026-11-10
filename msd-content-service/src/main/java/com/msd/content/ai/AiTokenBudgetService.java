package com.msd.content.ai;

import com.msd.content.config.AiGovernanceProperties;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 按用户按天的 token 预算。
 *
 * <p>入口先按输入字符和预期输出预占额度，调用结束后只补记超出部分；
 * Redis 不可用时 fail-open，预算只是成本保护，不参与权限判断。</p>
 */
public class AiTokenBudgetService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AiTokenBudgetService.class);
    /** 预算 key 前缀。 */
    private static final String KEY_PREFIX = "msd:ai:budget:";
    /** 计数过期时间，留一天余量避免跨时区误判。 */
    private static final Duration TTL = Duration.ofDays(2);

    // Redisson 客户端
    private final RedissonClient redissonClient;
    // AI 治理配置
    private final AiGovernanceProperties properties;

    /**
     * 注入 Redis 和治理配置。
     */
    public AiTokenBudgetService(RedissonClient redissonClient,
                                AiGovernanceProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    /**
     * 预占一次调用的 token 额度。
     *
     * @param scene      场景名，例如 rag、companion
     * @param userId     当前用户，空值不限制
     * @param inputChars 输入字符数
     * @return 预占结果；拒绝时调用方不应再调用模型
     */
    public Reservation reserve(String scene, Long userId, int inputChars) {
        AiGovernanceProperties.Budget budget = properties.getBudget();
        // 1. 未启用、未登录或上限非法时直接放行
        if (!budget.isEnabled() || userId == null || userId <= 0
                || budget.getDailyTokensPerUser() <= 0) {
            return new Reservation(true, scene, userId, inputChars, 0, -1,
                    budget.getDailyTokensPerUser());
        }
        int estimated = estimate(inputChars, budget.getExpectedOutputTokens());
        String key = key(scene, userId);
        try {
            // 2. 原子预占；首次计数时设置过期时间
            RAtomicLong counter = redissonClient.getAtomicLong(key);
            long used = counter.addAndGet(estimated);
            if (used == estimated) {
                markExpire(counter);
            }
            // 3. 超过上限时回滚本次预占，返回拒绝
            if (used > budget.getDailyTokensPerUser()) {
                counter.addAndGet(-estimated);
                return new Reservation(false, scene, userId, inputChars, estimated,
                        used, budget.getDailyTokensPerUser());
            }
            return new Reservation(true, scene, userId, inputChars, estimated,
                    used, budget.getDailyTokensPerUser());
        } catch (Exception ex) {
            // 4. Redis 故障不阻断 AI 链路
            log.debug("ai token budget unavailable: scene={}, userId={}, error={}",
                    scene, userId, ex.getMessage());
            return new Reservation(true, scene, userId, inputChars, estimated, -1,
                    budget.getDailyTokensPerUser());
        }
    }

    /**
     * 调用结束后补记实际输出超出预留的部分。
     *
     * <p>同一预占只应结算一次；实际用量低于预留时不退款，避免高估成本被忽略。</p>
     */
    public void settle(Reservation reservation, int outputChars) {
        AiGovernanceProperties.Budget budget = properties.getBudget();
        if (reservation == null || !reservation.allowed() || !budget.isEnabled()
                || reservation.userId() == null || reservation.userId() <= 0) {
            return;
        }
        int actual = estimate(reservation.inputChars(), outputChars);
        int extra = actual - reservation.estimatedTokens();
        if (extra <= 0) {
            return;
        }
        try {
            redissonClient.getAtomicLong(key(reservation.scene(), reservation.userId()))
                    .addAndGet(extra);
        } catch (Exception ex) {
            log.debug("ai token budget settle failed: scene={}, userId={}, error={}",
                    reservation.scene(), reservation.userId(), ex.getMessage());
        }
    }

    /**
     * 字符数换算为估算 token。
     */
    public int estimate(int chars, int outputTokens) {
        double ratio = properties.getBudget().getCharsPerToken();
        double safeRatio = ratio <= 0 ? 1.5 : ratio;
        int inputTokens = (int) Math.ceil(Math.max(chars, 0) / safeRatio);
        return Math.max(inputTokens + Math.max(outputTokens, 0), 0);
    }

    /**
     * 首次计数时设置过期时间，失败不阻断后续计数。
     */
    private long markExpire(RAtomicLong counter) {
        try {
            counter.expire(TTL);
        } catch (Exception ex) {
            log.debug("ai token budget expire failed: {}", ex.getMessage());
        }
        return counter.get();
    }

    /**
     * 构造按用户按天的预算 key。
     */
    private String key(String scene, Long userId) {
        return KEY_PREFIX + userId + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /**
     * 一次 token 预占结果。
     *
     * @param allowed         是否允许调用
     * @param scene           场景
     * @param userId          用户
     * @param inputChars      输入字符数
     * @param estimatedTokens 预占 token
     * @param used            当前已用 token，-1 表示未统计
     * @param limit           每日上限
     */
    public record Reservation(boolean allowed, String scene, Long userId, int inputChars,
                              int estimatedTokens, long used, long limit) {
    }
}
