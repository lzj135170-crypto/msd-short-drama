package com.msd.content.index;

import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.DramaChangedEvent;
import com.msd.content.config.RagProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 增量索引调度器的去抖和失败重试测试。
 */
class DramaIndexUpdateSchedulerTest {

    private final IncrementalIndexService indexService = mock(IncrementalIndexService.class);
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    private final RagProperties properties = new RagProperties();

    private final DramaIndexUpdateScheduler scheduler =
            new DramaIndexUpdateScheduler(indexService, properties, executor);

    /**
     * 关闭测试线程池。
     */
    @AfterEach
    void shutdown() {
        scheduler.shutdown();
    }

    /**
     * 同一短剧在去抖窗口内只处理最后一条事件。
     */
    @Test
    void debouncesByDramaId() throws Exception {
        properties.getIncremental().setDebounceMs(80);
        List<DramaChangedEvent> applied = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            applied.add(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(indexService).apply(any());

        scheduler.schedule(new DramaChangedEvent(
                "event-1", 5005L, DramaChangedAction.UPSERT, 1000L));
        scheduler.schedule(new DramaChangedEvent(
                "event-2", 5005L, DramaChangedAction.DELETE, 2000L));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        assertThat(applied).singleElement()
                .satisfies(event -> assertThat(event.eventId()).isEqualTo("event-2"));
    }

    /**
     * 索引失败后按配置重试。
     */
    @Test
    void retriesFailedIndex() throws Exception {
        properties.getIncremental().setDebounceMs(10);
        properties.getIncremental().setRetryAttempts(2);
        properties.getIncremental().setRetryDelayMs(10);
        CountDownLatch latch = new CountDownLatch(1);
        doThrow(new IllegalStateException("milvus down"))
                .doAnswer(invocation -> {
                    latch.countDown();
                    return null;
                })
                .when(indexService).apply(any());

        scheduler.schedule(new DramaChangedEvent(
                "event-1", 5005L, DramaChangedAction.UPSERT, 1000L));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(indexService, times(2)).apply(any());
    }

    /**
     * 旧事件晚到时直接跳过，避免覆盖新状态。
     */
    @Test
    void ignoresStaleEvent() throws Exception {
        properties.getIncremental().setDebounceMs(80);
        List<DramaChangedEvent> applied = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            applied.add(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(indexService).apply(any());

        // 先让较新事件占用调度窗口，再提交较旧事件；旧事件不取消新事件
        scheduler.schedule(new DramaChangedEvent(
                "event-new", 5005L, DramaChangedAction.UPSERT, 2000L));
        scheduler.schedule(new DramaChangedEvent(
                "event-old", 5005L, DramaChangedAction.UPSERT, 1000L));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(applied).singleElement()
                .satisfies(event -> assertThat(event.eventId()).isEqualTo("event-new"));
    }
}
