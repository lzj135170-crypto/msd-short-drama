package com.msd.content.companion;

import com.msd.common.result.Result;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.dto.EpisodeSummaryView;
import com.msd.content.dto.SummaryWarmupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分集摘要批量预热。
 *
 * <p>只处理缺失或失败可重试的在线剧集；逐集复用 {@link EpisodeSummaryService}
 * 的 claim、生成和回写流程，单集失败不中断整批。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class EpisodeSummaryWarmupService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(EpisodeSummaryWarmupService.class);

    /** 单批最大剧集数。 */
    private static final int MAX_LIMIT = 20;
    /** 降级标记：drama 下游不可用。 */
    private static final String FLAG_DRAMA = "drama";
    /** 降级标记：摘要生成失败。 */
    private static final String FLAG_GENERATION = "summary_generation";

    // 短剧内部接口客户端
    private final DramaCompanionClient dramaClient;
    // 单集摘要服务
    private final EpisodeSummaryService summaryService;

    /**
     * 注入短剧客户端和单集摘要服务。
     */
    public EpisodeSummaryWarmupService(DramaCompanionClient dramaClient,
                                       EpisodeSummaryService summaryService) {
        this.dramaClient = dramaClient;
        this.summaryService = summaryService;
    }

    /**
     * 为指定短剧的缺失摘要剧集批量生成摘要。
     */
    public SummaryWarmupResult warmup(Long dramaId, Integer limit) {
        // 1. 参数非法时直接返回空结果
        if (dramaId == null || dramaId <= 0) {
            return new SummaryWarmupResult(0, 0, 0, 0, List.of());
        }
        int size = limit == null ? 10 : Math.max(1, Math.min(limit, MAX_LIMIT));

        // 2. 从 drama 读取候选剧集；下游不可用时整体降级
        List<Long> candidates;
        try {
            Result<List<Long>> response = dramaClient.summaryCandidates(dramaId, size);
            if (response == null || !response.isSuccess()) {
                return new SummaryWarmupResult(0, 0, 0, 0, List.of(FLAG_DRAMA));
            }
            candidates = response.getData() == null ? List.of() : response.getData();
        } catch (Exception e) {
            log.warn("summary warmup candidates failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return new SummaryWarmupResult(0, 0, 0, 0, List.of(FLAG_DRAMA));
        }

        // 3. 逐集生成，统计成功、跳过和失败
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> flags = new LinkedHashSet<>();
        for (Long episodeId : candidates) {
            EpisodeSummaryView view;
            try {
                view = summaryService.getSummary(episodeId);
            } catch (Exception e) {
                log.warn("summary warmup failed: dramaId={}, episodeId={}, error={}",
                        dramaId, episodeId, rootMessage(e));
                failed++;
                flags.add(FLAG_GENERATION);
                continue;
            }
            if (view == null) {
                failed++;
                continue;
            }
            if (EpisodeSummaryService.SOURCE_GENERATED.equals(view.source())) {
                generated++;
            } else if (EpisodeSummaryService.SOURCE_SEED.equals(view.source())
                    || EpisodeSummaryService.SOURCE_GENERATING.equals(view.source())) {
                skipped++;
            } else {
                failed++;
                flags.addAll(view.degradedFlags());
            }
        }

        // 4. 汇总日志，便于运维核对
        SummaryWarmupResult result = new SummaryWarmupResult(
                candidates.size(), generated, skipped, failed, List.copyOf(flags));
        log.info("summary warmup: dramaId={}, scanned={}, generated={}, skipped={}, "
                        + "failed={}, flags={}",
                dramaId, result.scanned(), result.generated(), result.skipped(),
                result.failed(), result.degradedFlags());
        return result;
    }

    /**
     * 取最内层异常信息。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
