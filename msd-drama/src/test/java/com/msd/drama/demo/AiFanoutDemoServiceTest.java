package com.msd.drama.demo;

import com.msd.common.exception.BizException;
import com.msd.drama.cache.CacheProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiFanoutDemoServiceTest {

    @Test
    void singleReportsVirtualThread() throws Exception {
        AiChatClient client = mock(AiChatClient.class);
        when(client.chat("hi")).thenReturn(new AgentReply("ok", false));
        AiFanoutDemoService service = new AiFanoutDemoService(client, new CacheProperties());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AiSingleResult> future = executor.submit(() -> service.single("hi"));
            AiSingleResult result = future.get();

            assertThat(result.virtual()).isTrue();
            assertThat(result.threadName()).isNotNull();
            assertThat(result.reply()).isEqualTo("ok");
            assertThat(result.degraded()).isFalse();
        }
    }

    @Test
    void fanoutAggregatesSuccessAndFailure() {
        AiChatClient client = mock(AiChatClient.class);
        when(client.chat("p"))
                .thenReturn(new AgentReply("ok", false))
                .thenThrow(new IllegalStateException("boom"));
        AiFanoutDemoService service = new AiFanoutDemoService(client, new CacheProperties());

        AiFanoutResult result = service.fanout(2, "virtual", 8, "p");

        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.mode()).isEqualTo("virtual");
        assertThat(result.p50Ms()).isGreaterThanOrEqualTo(0);
        assertThat(result.p95Ms()).isGreaterThanOrEqualTo(result.p50Ms());
    }

    @Test
    void virtualAndPlatformModesUseExpectedThreads() {
        AiChatClient client = mock(AiChatClient.class);
        List<Boolean> virtualFlags = Collections.synchronizedList(new ArrayList<>());
        when(client.chat(anyString())).thenAnswer(invocation -> {
            virtualFlags.add(Thread.currentThread().isVirtual());
            return new AgentReply("ok", false);
        });
        AiFanoutDemoService service = new AiFanoutDemoService(client, new CacheProperties());

        service.fanout(6, "virtual", 8, "p");
        assertThat(virtualFlags).containsOnly(true);

        virtualFlags.clear();
        service.fanout(6, "platform", 2, "p");
        assertThat(virtualFlags).containsOnly(false);
    }

    @Test
    void clampsCallsToConfiguredMaximum() {
        CacheProperties properties = new CacheProperties();
        properties.getDemo().setMaxCalls(3);
        AiChatClient client = mock(AiChatClient.class);
        when(client.chat(anyString())).thenReturn(new AgentReply("ok", false));
        AiFanoutDemoService service = new AiFanoutDemoService(client, properties);

        AiFanoutResult result = service.fanout(10, "virtual", 8, "p");

        assertThat(result.calls()).isEqualTo(3);
        verify(client, times(3)).chat("p");
    }

    @Test
    void rejectsUnknownMode() {
        AiFanoutDemoService service = new AiFanoutDemoService(mock(AiChatClient.class),
                new CacheProperties());

        assertThatThrownBy(() -> service.fanout(2, "thread", 8, "p"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("virtual");
    }

    @Test
    void percentileUsesNearestRank() {
        long[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        assertThat(AiFanoutDemoService.percentile(sorted, 0.50)).isEqualTo(5);
        assertThat(AiFanoutDemoService.percentile(sorted, 0.95)).isEqualTo(10);
        assertThat(AiFanoutDemoService.percentile(new long[0], 0.95)).isZero();
    }
}
