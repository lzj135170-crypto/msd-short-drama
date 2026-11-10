package com.msd.drama.mq;

import com.msd.common.event.InteractionEvent;
import com.msd.common.event.InteractionEventType;
import com.msd.common.event.KafkaGroups;
import com.msd.common.event.KafkaTopics;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.mapper.DramaCounterMapper;
import com.msd.drama.mapper.EventConsumeLogMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * drama 互动计数批量消费者：同批同聚合根先合并 delta，再批量 UPDATE 和失效缓存。
 *
 * <p>通过 msd.interaction.batch-consumer.enabled 切换；默认关闭，单条消费者仍可用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.interaction.batch-consumer", name = "enabled",
        havingValue = "true")
public class InteractionEventBatchConsumer {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(InteractionEventBatchConsumer.class);
    /** 目标类型：短剧。 */
    private static final int TARGET_DRAMA = 1;
    /** 目标类型：剧集。 */
    private static final int TARGET_EPISODE = 2;

    // JSON 序列化器
    private final JsonMapper jsonMapper;
    // 消费幂等日志
    private final EventConsumeLogMapper consumeLogMapper;
    // 计数 Mapper
    private final DramaCounterMapper counterMapper;
    // 缓存失效器
    private final CacheEvictor cacheEvictor;
    // episodeId 到 dramaId 解析
    private final EpisodeDramaResolver episodeDramaResolver;

    /**
     * 注入批量消费依赖。
     */
    public InteractionEventBatchConsumer(JsonMapper jsonMapper,
                                         EventConsumeLogMapper consumeLogMapper,
                                         DramaCounterMapper counterMapper,
                                         CacheEvictor cacheEvictor,
                                         EpisodeDramaResolver episodeDramaResolver) {
        this.jsonMapper = jsonMapper;
        this.consumeLogMapper = consumeLogMapper;
        this.counterMapper = counterMapper;
        this.cacheEvictor = cacheEvictor;
        this.episodeDramaResolver = episodeDramaResolver;
    }

    /**
     * 批量消费互动事件。
     */
    @KafkaListener(
            topics = {
                    KafkaTopics.INTERACTION_LIKE,
                    KafkaTopics.INTERACTION_FAVORITE,
                    KafkaTopics.INTERACTION_COMMENT,
                    KafkaTopics.INTERACTION_HISTORY
            },
            groupId = KafkaGroups.DRAMA_COUNTER,
            concurrency = "${msd.kafka.drama-concurrency:6}",
            containerFactory = "batchKafkaListenerContainerFactory",
            batch = "true")
    @Transactional
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // 1. 解析并去重；非法消息交给批量错误处理器定位单条
        Map<String, InteractionEvent> unique = new LinkedHashMap<>();
        Map<String, String> topics = new HashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            InteractionEvent event;
            try {
                event = jsonMapper.readValue(record.value(), InteractionEvent.class);
                if (event.eventType() == null || event.eventId() == null
                        || event.eventId().isBlank()) {
                    throw new IllegalArgumentException("事件缺少 eventType/eventId");
                }
            } catch (Exception ex) {
                throw new BatchListenerFailedException("非法互动事件", ex, record);
            }
            unique.putIfAbsent(event.eventId(), event);
            topics.putIfAbsent(event.eventId(), record.topic());
        }

        // 2. 过滤已消费事件，批量写消费日志
        List<String> eventIds = new ArrayList<>(unique.keySet());
        Set<String> consumed = new HashSet<>(
                consumeLogMapper.selectExistingEventIds(KafkaGroups.DRAMA_COUNTER, eventIds));
        List<InteractionEvent> accepted = unique.values().stream()
                .filter(event -> !consumed.contains(event.eventId()))
                .toList();
        if (accepted.isEmpty()) {
            return;
        }
        List<Map<String, Object>> logs = accepted.stream()
                .map(event -> Map.<String, Object>of(
                        "consumerGroup", KafkaGroups.DRAMA_COUNTER,
                        "eventId", event.eventId(),
                        "topic", topics.getOrDefault(event.eventId(),
                                KafkaTopics.INTERACTION_LIKE)))
                .toList();
        consumeLogMapper.insertIgnoreBatch(logs);

        // 3. 聚合 delta
        BatchDelta delta = new BatchDelta();
        for (InteractionEvent event : accepted) {
            aggregate(event, delta);
        }

        // 4. 按列批量 UPDATE；空 map 不执行 SQL
        apply(delta);

        // 5. 按短剧去重失效缓存
        delta.detailDramaIds.forEach(cacheEvictor::evictDetailDebounced);
        if (delta.homeAffected) {
            cacheEvictor.evictHomeDebounced();
        }
        log.info("interaction batch consumed: records={}, accepted={}, dramas={}",
                records.size(), accepted.size(), delta.detailDramaIds.size());
    }

    /**
     * 把单条事件合并到批量 delta。
     */
    private void aggregate(InteractionEvent event, BatchDelta delta) {
        Long dramaId = resolveDramaId(event);
        if (dramaId != null) {
            delta.detailDramaIds.add(dramaId);
        }
        if (affectsHome(event)) {
            delta.homeAffected = true;
        }

        switch (event.eventType()) {
            case LIKE, UNLIKE -> aggregateLike(event, delta);
            case FAVORITE, UNFAVORITE -> {
                if (event.dramaId() == null) {
                    throw new IllegalArgumentException("收藏事件缺少 dramaId");
                }
                merge(delta.dramaFavorite, event.dramaId(),
                        event.eventType() == InteractionEventType.FAVORITE ? 1 : -1);
            }
            case COMMENT, COMMENT_DELETED -> {
                if (event.dramaId() == null) {
                    throw new IllegalArgumentException("评论事件缺少 dramaId");
                }
                int sign = event.eventType() == InteractionEventType.COMMENT ? 1 : -1;
                merge(delta.dramaComment, event.dramaId(), sign);
                if (event.episodeId() != null) {
                    merge(delta.episodeComment, event.episodeId(), sign);
                }
            }
            case HISTORY -> {
                if (Boolean.TRUE.equals(event.firstWatch())) {
                    if (event.dramaId() == null || event.episodeId() == null) {
                        throw new IllegalArgumentException("观看事件缺少 dramaId/episodeId");
                    }
                    merge(delta.dramaView, event.dramaId(), 1);
                    merge(delta.episodePlay, event.episodeId(), 1);
                }
            }
            default -> throw new IllegalArgumentException("未知事件类型: " + event.eventType());
        }
    }

    /**
     * 点赞/取消点赞聚合；评论点赞交由 interaction 消费者处理。
     */
    private void aggregateLike(InteractionEvent event, BatchDelta delta) {
        Integer targetType = event.targetType();
        Long targetId = event.targetId();
        if (targetType == null || targetId == null) {
            throw new IllegalArgumentException("点赞事件缺少 targetType 或 targetId");
        }
        int sign = event.eventType() == InteractionEventType.LIKE ? 1 : -1;
        if (targetType == TARGET_DRAMA) {
            merge(delta.dramaLike, targetId, sign);
        } else if (targetType == TARGET_EPISODE) {
            merge(delta.episodeLike, targetId, sign);
        }
    }

    /**
     * 执行非空批量更新。
     */
    private void apply(BatchDelta delta) {
        if (!delta.dramaLike.isEmpty()) {
            counterMapper.batchDramaLike(delta.dramaLike);
        }
        if (!delta.dramaFavorite.isEmpty()) {
            counterMapper.batchDramaFavorite(delta.dramaFavorite);
        }
        if (!delta.dramaComment.isEmpty()) {
            counterMapper.batchDramaComment(delta.dramaComment);
        }
        if (!delta.dramaView.isEmpty()) {
            counterMapper.batchDramaView(delta.dramaView);
        }
        if (!delta.episodeLike.isEmpty()) {
            counterMapper.batchEpisodeLike(delta.episodeLike);
        }
        if (!delta.episodeComment.isEmpty()) {
            counterMapper.batchEpisodeComment(delta.episodeComment);
        }
        if (!delta.episodePlay.isEmpty()) {
            counterMapper.batchEpisodePlay(delta.episodePlay);
        }
    }

    /**
     * 合并同一个聚合根的 delta。
     */
    private void merge(Map<Long, Long> target, Long id, long delta) {
        target.merge(id, delta, Long::sum);
    }

    /**
     * 解析事件影响的短剧 ID。
     */
    private Long resolveDramaId(InteractionEvent event) {
        if (event.dramaId() != null) {
            return event.dramaId();
        }
        if (Integer.valueOf(TARGET_DRAMA).equals(event.targetType())) {
            return event.targetId();
        }
        if (event.episodeId() != null) {
            return episodeDramaResolver.resolve(event.episodeId());
        }
        if (Integer.valueOf(TARGET_EPISODE).equals(event.targetType())) {
            return episodeDramaResolver.resolve(event.targetId());
        }
        return null;
    }

    /**
     * 是否影响首页热播排序。
     */
    private boolean affectsHome(InteractionEvent event) {
        return switch (event.eventType()) {
            case LIKE, UNLIKE -> Integer.valueOf(TARGET_DRAMA).equals(event.targetType());
            case FAVORITE, UNFAVORITE, COMMENT, COMMENT_DELETED -> true;
            case HISTORY -> Boolean.TRUE.equals(event.firstWatch());
            default -> false;
        };
    }

    /**
     * 单批聚合增量。
     */
    private static final class BatchDelta {
        private final Map<Long, Long> dramaLike = new HashMap<>();
        private final Map<Long, Long> dramaFavorite = new HashMap<>();
        private final Map<Long, Long> dramaComment = new HashMap<>();
        private final Map<Long, Long> dramaView = new HashMap<>();
        private final Map<Long, Long> episodeLike = new HashMap<>();
        private final Map<Long, Long> episodeComment = new HashMap<>();
        private final Map<Long, Long> episodePlay = new HashMap<>();
        private final Set<Long> detailDramaIds = new HashSet<>();
        private boolean homeAffected;
    }
}
