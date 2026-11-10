package com.msd.content.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.content.dto.ColdStartPreferenceRequest;
import com.msd.content.dto.PersonalizedFeed;
import com.msd.content.service.ColdStartPreferenceService;
import com.msd.content.service.PersonalizedRecommendService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 首页「为你推荐」接口，经网关访问，用户 ID 由 JWT 过滤器注入。
 */
@RestController
@RequestMapping("/internal/ai/rag")
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PersonalizedRecommendController {

    /** 推荐接口整体超时，避免 Feign 抖动拖住首页。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    // 个性化推荐服务
    private final PersonalizedRecommendService recommendationService;
    // 冷启动偏好服务
    private final ColdStartPreferenceService coldStartPreferenceService;

    /**
     * 注入推荐服务和冷启动偏好服务。
     */
    public PersonalizedRecommendController(
            PersonalizedRecommendService recommendationService,
            ColdStartPreferenceService coldStartPreferenceService) {
        this.recommendationService = recommendationService;
        this.coldStartPreferenceService = coldStartPreferenceService;
    }

    /**
     * 返回当前用户的个性化短剧卡片。
     *
     * @param limit        返回条数，默认 6
     * @param userIdHeader 网关注入的用户 ID
     */
    @GetMapping("/personalized")
    public Mono<Result<PersonalizedFeed>> personalized(
            @RequestParam(value = "limit", defaultValue = "6") int limit,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户；缺失或非法时按未登录处理
        Long userId = parseUserId(userIdHeader);
        // 2. 推荐内部有 Feign/Redis 阻塞调用，切到弹性线程池，避免阻塞 Netty
        return Mono.fromCallable(() -> recommendationService.feed(userId, limit))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(REQUEST_TIMEOUT)
                .map(Result::ok)
                // 3. 超时/异常返回空结果，前端静默隐藏推荐区块
                .onErrorResume(Exception.class,
                        ex -> Mono.just(Result.ok(PersonalizedFeed.empty(true))));
    }

    /**
     * 清理当前用户的个性化缓存，供负反馈后立即刷新。
     */
    @PostMapping("/personalized/evict")
    public Mono<Result<Boolean>> evict(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        return Mono.fromCallable(() -> {
                    recommendationService.evictUser(userId);
                    return Result.ok(true);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(REQUEST_TIMEOUT);
    }

    /**
     * 保存冷启动分类偏好，并清理旧推荐缓存。
     */
    @PostMapping("/preferences")
    public Mono<Result<List<Long>>> preferences(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody ColdStartPreferenceRequest request) {
        // 1. 冷启动偏好只对登录用户生效
        Long userId = parseUserId(userIdHeader);
        // 2. Redis 写入是阻塞调用，切到弹性线程池
        return Mono.fromCallable(() -> {
                    List<Long> saved = coldStartPreferenceService.save(
                            userId, request.categoryIds());
                    recommendationService.evictUser(userId);
                    return Result.ok(saved);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(Exception.class,
                        ex -> Mono.just(Result.ok(List.of())));
    }

    /**
     * 解析用户 ID，非法时返回 401。
     */
    private Long parseUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        try {
            long userId = Long.parseLong(userIdHeader);
            if (userId <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return userId;
        } catch (NumberFormatException ex) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}
