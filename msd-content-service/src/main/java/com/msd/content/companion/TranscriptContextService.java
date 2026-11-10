package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.TranscriptCitation;
import com.msd.content.dto.TranscriptContextView;
import com.msd.content.rag.HybridRetriever;
import com.msd.content.rag.RagMetadata;
import com.msd.content.rag.RagTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 字幕片段检索。
 *
 * <p>防剧透规则：只接受当前集之前的内容；当前集只接受结束时间早于播放位置的片段。
 * 付费前情仍需确认解锁，当前集由聊天链路的付费校验统一放行。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TranscriptContextService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(TranscriptContextService.class);
    /** 降级标记：检索整体失败。 */
    public static final String FLAG_CONTEXT = "transcript";
    /** 引用文本最大字符数。 */
    private static final int MAX_TEXT_CHARS = 200;

    // 混合检索器
    private final HybridRetriever hybridRetriever;
    // 付费集权限校验
    private final CompanionAccessService accessService;
    // 伴看配置
    private final CompanionProperties properties;

    /**
     * 注入检索器、权限校验和伴看配置。
     */
    public TranscriptContextService(HybridRetriever hybridRetriever,
                                    CompanionAccessService accessService,
                                    CompanionProperties properties) {
        this.hybridRetriever = hybridRetriever;
        this.accessService = accessService;
        this.properties = properties;
    }

    /**
     * 检索当前播放位置之前的字幕片段。
     *
     * @param positionMs 播放器当前位置；为空或 0 时只返回之前剧集
     */
    public TranscriptContextView retrieve(String query, CompanionEpisodeDTO meta,
                                          Long positionMs, Integer topK, Long userId) {
        long startedAt = System.currentTimeMillis();
        List<String> flags = new ArrayList<>();
        if (meta == null || meta.dramaId() == null || meta.episodeNo() == null) {
            return TranscriptContextView.empty(System.currentTimeMillis() - startedAt);
        }
        long playhead = positionMs == null ? 0 : Math.max(positionMs, 0);
        // 第 1 集且尚未播放时没有可引用的字幕
        if (meta.episodeNo() <= 1 && playhead <= 0) {
            return TranscriptContextView.empty(System.currentTimeMillis() - startedAt);
        }

        List<Document> documents;
        RagTrace trace = new RagTrace(query);
        try {
            Query ragQuery = Query.builder()
                    .text(query)
                    .context(Map.of(RagTrace.CONTEXT_KEY, trace))
                    .build();
            documents = hybridRetriever.retrieveTranscripts(
                    ragQuery, meta.dramaId(), meta.episodeNo());
        } catch (Exception e) {
            log.warn("transcript context retrieval failed: episodeId={}, error={}",
                    meta.episodeId(), rootMessage(e));
            flags.add(FLAG_CONTEXT);
            return new TranscriptContextView(List.of(), true, List.copyOf(flags),
                    System.currentTimeMillis() - startedAt);
        }
        flags.addAll(trace.getDegradedFlags());
        List<TranscriptCitation> items = filterAndMap(documents, meta, playhead, topK, userId);
        return new TranscriptContextView(items, !flags.isEmpty(), List.copyOf(flags),
                System.currentTimeMillis() - startedAt);
    }

    /**
     * 后置校验剧集、时间轴和付费权限。
     */
    private List<TranscriptCitation> filterAndMap(List<Document> documents,
                                                  CompanionEpisodeDTO meta, long playhead,
                                                  Integer topK, Long userId) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Set<Long> unlockedEpisodeIds = documents.stream().anyMatch(this::hasPaidPrice)
                ? accessService.unlockedEpisodeIds(userId, meta.dramaId())
                : Set.of();
        Set<String> seen = new HashSet<>();
        List<TranscriptCitation> items = new ArrayList<>();
        int limit = resolveTopK(topK);

        for (Document document : documents) {
            if (document == null) {
                continue;
            }
            Map<String, Object> metadata = document.getMetadata();
            // 1. 只接受同类文档、同剧字幕
            if (!RagMetadata.DOC_TYPE_TRANSCRIPT.equals(
                    stringValue(metadata.get(RagMetadata.DOC_TYPE)))) {
                continue;
            }
            if (!Objects.equals(longValue(metadata.get(RagMetadata.DRAMA_ID)), meta.dramaId())) {
                continue;
            }

            // 2. 当前集只接受播放位置之前的片段，未来集一律丢弃
            Long episodeId = longValue(metadata.get(RagMetadata.EPISODE_ID));
            Integer episodeNo = integerValue(metadata.get(RagMetadata.EPISODE_NO));
            Integer endMs = integerValue(metadata.get(RagMetadata.END_MS));
            if (episodeId == null || episodeNo == null || endMs == null) {
                continue;
            }
            if (episodeNo > meta.episodeNo()) {
                continue;
            }
            if (episodeNo.equals(meta.episodeNo()) && endMs > playhead) {
                continue;
            }

            // 3. 付费前情必须已解锁；当前集已在聊天入口校验
            int pricePoints = integerValue(metadata.get(RagMetadata.PRICE_POINTS)) == null
                    ? 0 : integerValue(metadata.get(RagMetadata.PRICE_POINTS));
            if (episodeNo < meta.episodeNo() && pricePoints > 0
                    && !unlockedEpisodeIds.contains(episodeId)) {
                continue;
            }

            // 4. 同一片段只返回一次
            if (!seen.add(document.getId())) {
                continue;
            }
            items.add(new TranscriptCitation(
                    episodeId,
                    episodeNo,
                    stringValue(metadata.get(RagMetadata.EPISODE_TITLE)),
                    integerValue(metadata.get(RagMetadata.START_MS)),
                    endMs,
                    truncate(stringValue(metadata.get(RagMetadata.TRANSCRIPT)), MAX_TEXT_CHARS),
                    firstDouble(metadata.get(RagMetadata.RRF_SCORE), document.getScore()),
                    retrievalSources(metadata),
                    pricePoints));
            if (items.size() >= limit) {
                break;
            }
        }
        return List.copyOf(items);
    }

    /**
     * 判断文档是否付费。
     */
    private boolean hasPaidPrice(Document document) {
        if (document == null) {
            return false;
        }
        Integer pricePoints = integerValue(document.getMetadata().get(RagMetadata.PRICE_POINTS));
        return pricePoints != null && pricePoints > 0;
    }

    /**
     * 收敛返回条数。
     */
    private int resolveTopK(Integer requested) {
        int defaultTopK = Math.max(properties.getContext().getDefaultTopK(), 1);
        int maxTopK = Math.max(properties.getContext().getMaxTopK(), defaultTopK);
        return Math.max(1, Math.min(requested == null ? defaultTopK : requested, maxTopK));
    }

    /**
     * 读取检索来源。
     */
    private List<String> retrievalSources(Map<String, Object> metadata) {
        Object value = metadata.get(RagMetadata.RETRIEVAL_SOURCES);
        if (value instanceof List<?> values) {
            List<String> sources = new ArrayList<>();
            for (Object item : values) {
                String source = stringValue(item);
                if (!source.isBlank()) {
                    sources.add(source);
                }
            }
            if (!sources.isEmpty()) {
                return List.copyOf(sources);
            }
        }
        String source = stringValue(metadata.get(RagMetadata.RETRIEVAL_SOURCE));
        return source.isBlank() ? List.of() : List.of(source);
    }

    /**
     * 任意对象转 Long。
     */
    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 任意对象转 Integer。
     */
    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 取第一个数字分数。
     */
    private double firstDouble(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        }
        return 0.0;
    }

    /**
     * 任意对象转字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 截断文本。
     */
    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
