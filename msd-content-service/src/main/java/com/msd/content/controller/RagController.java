package com.msd.content.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.AiStreamDone;
import com.msd.content.dto.AiStreamError;
import com.msd.content.dto.RagRecommendRequest;
import com.msd.content.dto.RagRecommendResponse;
import com.msd.content.dto.RagStatus;
import com.msd.content.dto.ReindexRequest;
import com.msd.content.dto.ReindexResult;
import com.msd.content.index.DramaIndexService;
import com.msd.content.rag.ElasticsearchBm25Store;
import com.msd.content.service.RagRecommendationService;
import com.msd.content.support.LazyMilvusStoreProvider;
import com.msd.content.support.RagIndexState;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * RAG 推荐、索引重建和状态接口。
 *
 * <p>recommend 经网关暴露；reindex/status 只允许直连 8107。</p>
 */
@RestController
@RequestMapping("/internal/ai/rag")
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RagController {

    // 推荐编排服务，负责检索、生成和降级兜底
    private final RagRecommendationService recommendationService;
    // 索引重建服务，负责拉取短剧目录并写入 Milvus/ES
    private final DramaIndexService indexService;
    // Milvus 客户端懒加载提供者，status 时用于探测可用性
    private final LazyMilvusStoreProvider milvusProvider;
    // Elasticsearch BM25 索引存储，status 时用于探测可用性
    private final ElasticsearchBm25Store bm25Store;
    // 最近一次索引的状态快照
    private final RagIndexState indexState;
    // RAG 超时、collection/index 名称等配置
    private final RagProperties properties;

    /**
     * 注入推荐、索引和状态依赖。
     */
    public RagController(RagRecommendationService recommendationService,
                         DramaIndexService indexService,
                         LazyMilvusStoreProvider milvusProvider,
                         ElasticsearchBm25Store bm25Store,
                         RagIndexState indexState,
                         RagProperties properties) {
        this.recommendationService = recommendationService;
        this.indexService = indexService;
        this.milvusProvider = milvusProvider;
        this.bm25Store = bm25Store;
        this.indexState = indexState;
        this.properties = properties;
    }

    /**
     * 执行一次 RAG 推荐，整体超时返回固定兜底文案。
     *
     * @param request 推荐请求，query 必填
     * @return 推荐结果；超过 totalTimeoutMs 时返回 timeout 降级响应
     */
    @PostMapping("/recommend")
    public Mono<Result<RagRecommendResponse>> recommend(
            @Valid @RequestBody RagRecommendRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析可选用户 ID；未登录或非法时不加载画像
        Long userId = parseUserId(userIdHeader);
        // 2. 推荐链路包含远程检索和模型调用，切到弹性线程池执行，避免阻塞事件循环
        return Mono.fromCallable(() -> recommendationService.recommend(request, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(properties.getTotalTimeoutMs()))
                .map(Result::ok)
                // 整体超时不抛错，返回固定兜底文案，保证调用方始终拿到可用结果
                .onErrorResume(TimeoutException.class,
                        e -> Mono.just(Result.ok(recommendationService.timeoutResponse())));
    }

    /**
     * 流式推荐，事件顺序为 meta → delta* → sources → done。
     */
    @PostMapping(value = "/recommend/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> recommendStream(
            @Valid @RequestBody RagRecommendRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析可选用户 ID；未登录或非法时不加载画像
        Long userId = parseUserId(userIdHeader);
        long timeoutMs = Math.max(properties.getTotalTimeoutMs(), 1);
        // 2. 画像读取会阻塞，放到弹性线程池后再订阅模型流
        return Mono.fromCallable(() -> recommendationService.stream(request, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(flux -> flux)
                // 3. 整体超时追加 error/done 帧，前端可回退到普通 JSON 接口
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(TimeoutException.class, e -> Flux.just(
                        event("error", new AiStreamError("timeout",
                                "这次找剧花的时间有点久，可以重试")),
                        event("done", new AiStreamDone(true, List.of("timeout"),
                                timeoutMs, false))));
    }

    /**
     * 全量重建向量和 BM25 索引。
     *
     * @param request 可选请求体，allowEmptyCatalog 控制目录为空时是否继续
     * @return 本次重建的统计结果
     */
    @PostMapping("/reindex")
    public Mono<Result<ReindexResult>> reindex(
            @RequestBody(required = false) ReindexRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 经网关调用时必须是管理员；直连 8107 的运维调用可以不带角色
        RoleGuard.requireAdminIfPresent(role);
        // 只有显式传 allowEmptyCatalog=true 才允许在空目录上重建
        boolean allowEmptyCatalog = request != null
                && Boolean.TRUE.equals(request.allowEmptyCatalog());
        // 重建包含全量拉取和批量写入，切到弹性线程池执行
        return Mono.fromCallable(() -> indexService.reindex(allowEmptyCatalog))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    /**
     * 查询 Milvus、ES 可用性和最近一次索引结果。
     *
     * @return RAG 链路状态
     */
    @GetMapping("/status")
    public Mono<Result<RagStatus>> status() {
        // 状态探测会 ping Milvus 和 ES，切到弹性线程池执行
        return Mono.fromCallable(this::buildStatus)
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    /**
     * 解析可选用户 ID；缺失或非法时返回 null，推荐继续但不用画像。
     */
    private Long parseUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(userIdHeader);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 构造 SSE 事件。
     */
    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.<Object>builder(data).event(name).build();
    }

    /**
     * 组装状态响应；Milvus/ES ping 可能涉及网络，调用方已切到 boundedElastic。
     */
    private RagStatus buildStatus() {
        return new RagStatus(
                properties.isEnabled(),
                milvusProvider.ping(),
                bm25Store.ping(),
                properties.getMilvus().getCollectionName(),
                properties.getElasticsearch().getIndexName(),
                indexState.getLastIndexedAt(),
                indexState.getLastDramaCount(),
                indexState.getLastEpisodeCount(),
                indexState.isLastPartial(),
                indexState.getLastError());
    }
}
