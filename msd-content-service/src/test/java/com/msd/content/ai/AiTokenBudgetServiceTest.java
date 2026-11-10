package com.msd.content.ai;

import com.msd.content.config.AiGovernanceProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户每日 token 预算测试。
 */
class AiTokenBudgetServiceTest {

    /**
     * 预算未启用时始终放行。
     */
    @Test
    void disabledBudgetAlwaysAllows() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        RedissonClient redissonClient = mock(RedissonClient.class);
        AiTokenBudgetService service = new AiTokenBudgetService(redissonClient, properties);

        AiTokenBudgetService.Reservation reservation = service.reserve("rag", 1003L, 10_000);

        assertThat(reservation.allowed()).isTrue();
    }

    /**
     * 超过每日上限后拒绝后续调用，并回滚本次预占。
     */
    @Test
    void rejectsWhenDailyLimitExceeded() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        properties.getBudget().setEnabled(true);
        properties.getBudget().setDailyTokensPerUser(10);
        properties.getBudget().setCharsPerToken(1);
        properties.getBudget().setExpectedOutputTokens(0);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RAtomicLong counter = counter();
        when(redissonClient.<Long>getAtomicLong(anyString())).thenReturn(counter);
        AiTokenBudgetService service = new AiTokenBudgetService(redissonClient, properties);

        AiTokenBudgetService.Reservation first = service.reserve("rag", 1003L, 6);
        AiTokenBudgetService.Reservation second = service.reserve("rag", 1003L, 6);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isFalse();
        assertThat(second.used()).isEqualTo(12);
        assertThat(second.limit()).isEqualTo(10);
    }

    /**
     * Redis 故障时 fail-open，不阻断 AI 请求。
     */
    @Test
    void failsOpenWhenRedisUnavailable() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        properties.getBudget().setEnabled(true);
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.<Long>getAtomicLong(anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        AiTokenBudgetService service = new AiTokenBudgetService(redissonClient, properties);

        AiTokenBudgetService.Reservation reservation = service.reserve("rag", 1003L, 100);

        assertThat(reservation.allowed()).isTrue();
        assertThat(reservation.used()).isEqualTo(-1);
    }

    /**
     * 实际输出超过预留时补记差额，低于预留不重复记账。
     */
    @Test
    void settlesOnlyExtraTokens() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        properties.getBudget().setEnabled(true);
        properties.getBudget().setDailyTokensPerUser(10_000);
        properties.getBudget().setCharsPerToken(1);
        properties.getBudget().setExpectedOutputTokens(5);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RAtomicLong counter = counter();
        when(redissonClient.<Long>getAtomicLong(anyString())).thenReturn(counter);
        AiTokenBudgetService service = new AiTokenBudgetService(redissonClient, properties);

        AiTokenBudgetService.Reservation reservation = service.reserve("rag", 1003L, 10);
        service.settle(reservation, 20);
        service.settle(reservation, 1);

        assertThat(counter.get()).isEqualTo(30);
    }

    /**
     * 构造可累加的原子计数器。
     */
    private RAtomicLong counter() {
        AtomicLong delegate = new AtomicLong();
        RAtomicLong counter = mock(RAtomicLong.class);
        when(counter.incrementAndGet()).thenAnswer(invocation -> delegate.incrementAndGet());
        when(counter.addAndGet(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> delegate.addAndGet(invocation.getArgument(0)));
        when(counter.get()).thenAnswer(invocation -> delegate.get());
        when(counter.decrementAndGet()).thenAnswer(invocation -> delegate.decrementAndGet());
        return counter;
    }
}
