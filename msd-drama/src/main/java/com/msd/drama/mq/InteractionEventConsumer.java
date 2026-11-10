package com.msd.drama.mq;

import com.msd.common.event.InteractionEvent;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 互动事件消费者：幂等消费点赞/收藏/评论/观看事件，更新短剧和剧集冗余计数。
 * 消费日志和计数更新在同一个数据库事务内，失败时整体回滚并由容器重试。
 */
@Component
@ConditionalOnProperty(prefix = "msd.interaction.batch-consumer", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class InteractionEventConsumer {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(InteractionEventConsumer.class);

    /** 目标类型：短剧 */
    private static final int TARGET_DRAMA = 1;
    /** 目标类型：剧集 */
    private static final int TARGET_EPISODE = 2;
    /** 目标类型：评论 */
    private static final int TARGET_COMMENT = 3;

    // JSON 序列化器，用于解析事件体
    private final JsonMapper jsonMapper;
    // 消费日志 Mapper，按 eventId 做幂等
    private final EventConsumeLogMapper consumeLogMapper;
    // 短剧和剧集的冗余计数 Mapper
    private final DramaCounterMapper counterMapper;
    // 缓存失效器，事务提交后防抖删除首页和详情缓存
    private final CacheEvictor cacheEvictor;
    // episodeId 到 dramaId 的解析器
    private final EpisodeDramaResolver episodeDramaResolver;

    /**
     * 注入 JSON 序列化器、计数 Mapper、缓存失效器和剧集归属解析器。
     */
    public InteractionEventConsumer(JsonMapper jsonMapper,
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
     * 消费一条互动事件。
     * 解析失败、事件 ID 缺失等非法消息会抛出异常，由 error handler 重试后投递 DLQ。
     */
    @KafkaListener(
            topics = {
                    KafkaTopics.INTERACTION_LIKE,
                    KafkaTopics.INTERACTION_FAVORITE,
                    KafkaTopics.INTERACTION_COMMENT,
                    KafkaTopics.INTERACTION_HISTORY
            },
            groupId = KafkaGroups.DRAMA_COUNTER,
            concurrency = "${msd.kafka.drama-concurrency:6}")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        // 1. 反序列化事件体，非法 JSON 直接抛出进入重试/DLQ
        InteractionEvent event = jsonMapper.readValue(record.value(), InteractionEvent.class);
        if (event.eventType() == null) {
            throw new IllegalArgumentException("事件缺少 eventType");
        }

        // 2. 校验幂等键，缺失说明消息格式错误
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("事件缺少 eventId");
        }

        // 3. 写消费日志；返回 0 表示该事件已处理过，直接跳过
        if (consumeLogMapper.insertIgnore(KafkaGroups.DRAMA_COUNTER, eventId, record.topic()) == 0) {
            log.debug("duplicate interaction event ignored: eventId={}, topic={}", eventId, record.topic());
            return;
        }

        // 4. 按事件类型更新对应计数
        switch (event.eventType()) {
            case LIKE -> applyLike(event, 1);
            case UNLIKE -> applyLike(event, -1);
            case FAVORITE -> applyFavorite(event, 1);
            case UNFAVORITE -> applyFavorite(event, -1);
            case COMMENT -> applyComment(event, 1);
            case COMMENT_DELETED -> applyComment(event, -1);
            case HISTORY -> applyHistory(event);
            default -> throw new IllegalArgumentException("未知事件类型: " + event.eventType());
        }

        // 5. 事务提交后防抖失效缓存；计数事件高频，不能每条都直接删 home
        Long dramaId = resolveDramaId(event);
        if (dramaId != null) {
            cacheEvictor.evictDetailDebounced(dramaId);
        }
        if (affectsHome(event)) {
            cacheEvictor.evictHomeDebounced();
        }

        // 6. 输出消费结果，便于链路排查
        log.info("interaction event consumed: eventId={}, type={}, topic={}, partition={}, offset={}",
                eventId, event.eventType(), record.topic(), record.partition(), record.offset());
    }

    /**
     * 解析事件影响的短剧 ID；剧集点赞可能只带 episodeId。
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
     * 只有短剧级计数会影响首页热播排序和卡片计数。
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
     * 点赞/取消点赞：短剧和剧集更新计数，评论点赞由 interaction 本地维护。
     */
    private void applyLike(InteractionEvent event, int delta) {
        // 1. 校验目标字段
        Integer targetType = event.targetType();
        Long targetId = event.targetId();
        if (targetType == null || targetId == null) {
            throw new IllegalArgumentException("点赞事件缺少 targetType 或 targetId");
        }

        // 2. 按目标类型更新对应计数；评论点赞不跨服务处理
        switch (targetType) {
            case TARGET_DRAMA -> warnIfMissing(
                    delta > 0 ? counterMapper.incrementDramaLike(targetId)
                            : counterMapper.decrementDramaLike(targetId),
                    "drama", targetId, event);
            case TARGET_EPISODE -> warnIfMissing(
                    delta > 0 ? counterMapper.incrementEpisodeLike(targetId)
                            : counterMapper.decrementEpisodeLike(targetId),
                    "episode", targetId, event);
            case TARGET_COMMENT -> log.debug(
                    "comment like is maintained by interaction service: eventId={}", event.eventId());
            default -> throw new IllegalArgumentException("非法 targetType: " + targetType);
        }
    }

    /**
     * 收藏/取消收藏：只更新短剧收藏数。
     */
    private void applyFavorite(InteractionEvent event, int delta) {
        // 1. 收藏事件必须带短剧 ID
        Long dramaId = event.dramaId();
        if (dramaId == null) {
            throw new IllegalArgumentException("收藏事件缺少 dramaId");
        }

        // 2. 更新短剧收藏数
        warnIfMissing(delta > 0 ? counterMapper.incrementDramaFavorite(dramaId)
                        : counterMapper.decrementDramaFavorite(dramaId),
                "drama", dramaId, event);
    }

    /**
     * 评论创建/删除：短剧评论数必减，剧集评论数在带 episodeId 时同步更新。
     */
    private void applyComment(InteractionEvent event, int delta) {
        // 1. 评论事件必须带短剧 ID
        Long dramaId = event.dramaId();
        if (dramaId == null) {
            throw new IllegalArgumentException("评论事件缺少 dramaId");
        }

        // 2. 更新短剧评论数
        warnIfMissing(delta > 0 ? counterMapper.incrementDramaComment(dramaId)
                        : counterMapper.decrementDramaComment(dramaId),
                "drama", dramaId, event);

        // 3. 分集评论同时更新剧集评论数
        Long episodeId = event.episodeId();
        if (episodeId != null) {
            warnIfMissing(delta > 0 ? counterMapper.incrementEpisodeComment(episodeId)
                            : counterMapper.decrementEpisodeComment(episodeId),
                    "episode", episodeId, event);
        }
    }

    /**
     * 首次观看：短剧浏览量加一，剧集播放量加一；重复观看不产生事件。
     */
    private void applyHistory(InteractionEvent event) {
        // 1. 只有首次观看才计数
        if (!Boolean.TRUE.equals(event.firstWatch())) {
            log.debug("history event without firstWatch ignored: eventId={}", event.eventId());
            return;
        }

        // 2. 校验必需字段
        Long dramaId = event.dramaId();
        Long episodeId = event.episodeId();
        if (dramaId == null || episodeId == null) {
            throw new IllegalArgumentException("观看事件缺少 dramaId 或 episodeId");
        }

        // 3. 更新短剧浏览量和剧集播放量
        warnIfMissing(counterMapper.incrementDramaView(dramaId), "drama", dramaId, event);
        warnIfMissing(counterMapper.incrementEpisodePlay(episodeId), "episode", episodeId, event);
    }

    /**
     * 目标行不存在时只记录告警，不重试：消息本身无法通过重试修复。
     */
    private void warnIfMissing(int affectedRows, String targetType, Long targetId, InteractionEvent event) {
        if (affectedRows == 0) {
            log.warn("counter target not found: type={}, id={}, eventId={}",
                    targetType, targetId, event.eventId());
        }
    }
}
