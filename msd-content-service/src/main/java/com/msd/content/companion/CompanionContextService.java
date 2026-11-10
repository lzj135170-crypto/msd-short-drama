package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.CompanionContextView;
import com.msd.content.dto.ContextItem;
import com.msd.content.rag.ElasticsearchBm25Store;
import com.msd.content.rag.HybridRetriever;
import com.msd.content.rag.RagMetadata;
import com.msd.content.rag.RagTrace;
import com.msd.content.rag.RetrievalScope;
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
 * AI 伴看前情检索。
 *
 * <p>只返回当前剧集之前、同一部短剧的分集文档；检索层过滤后再做一次后置校验，
 * 防止索引数据或 metadata 异常导致剧透。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionContextService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionContextService.class);

    /** 降级标记：drama 下游不可用。 */
    public static final String FLAG_DRAMA = "drama";
    /** 降级标记：分集索引为空。 */
    public static final String FLAG_EPISODE_INDEX = "episode_index";
    /** 降级标记：检索整体失败。 */
    public static final String FLAG_CONTEXT = "context";

    // 短剧内部接口客户端
    private final DramaCompanionClient dramaClient;
    // 混合检索器
    private final HybridRetriever hybridRetriever;
    // BM25 存储，用于判断分集索引是否已建立
    private final ElasticsearchBm25Store bm25Store;
    // 伴看配置
    private final CompanionProperties properties;
    // 付费集权限校验
    private final CompanionAccessService accessService;

    /**
     * 注入短剧客户端、混合检索器、BM25 存储、伴看配置和权限校验。
     */
    public CompanionContextService(DramaCompanionClient dramaClient,
                                   HybridRetriever hybridRetriever,
                                   ElasticsearchBm25Store bm25Store,
                                   CompanionProperties properties,
                                   CompanionAccessService accessService) {
        this.dramaClient = dramaClient;
        this.hybridRetriever = hybridRetriever;
        this.bm25Store = bm25Store;
        this.properties = properties;
        this.accessService = accessService;
    }

    /**
     * 查询当前集之前的前情；剧集不可见时返回 null。
     */
    public CompanionContextView retrieve(String query, Long currentEpisodeId, Integer topK) {
        return retrieve(query, currentEpisodeId, topK, null);
    }

    /**
     * 查询当前集之前的前情，并按用户权限过滤付费集。
     *
     * @param userId 当前用户；为空时只保留免费集
     */
    public CompanionContextView retrieve(String query, Long currentEpisodeId, Integer topK,
                                         Long userId) {
        long startedAt = System.currentTimeMillis();
        List<String> flags = new ArrayList<>();

        // 1. 读取剧集元信息，区分不可见和下游不可用
        CompanionEpisodeDTO meta;
        try {
            Result<CompanionEpisodeDTO> response = dramaClient.companion(currentEpisodeId);
            if (response == null || !response.isSuccess()) {
                flags.add(FLAG_DRAMA);
                return degradedView(currentEpisodeId, flags, startedAt);
            }
            meta = response.getData();
        } catch (Exception e) {
            log.warn("companion context lookup failed: episodeId={}, error={}",
                    currentEpisodeId, rootMessage(e));
            flags.add(FLAG_DRAMA);
            return degradedView(currentEpisodeId, flags, startedAt);
        }
        if (meta == null) {
            return null;
        }
        return retrieve(query, meta, topK, startedAt, flags, userId);
    }

    /**
     * 使用已读取的剧集元信息检索前情，避免聊天链路重复调用 drama。
     */
    public CompanionContextView retrieve(String query, CompanionEpisodeDTO meta, Integer topK) {
        return retrieve(query, meta, topK, null);
    }

    /**
     * 使用已读取的剧集元信息检索前情，并按用户权限过滤付费集。
     */
    public CompanionContextView retrieve(String query, CompanionEpisodeDTO meta, Integer topK,
                                         Long userId) {
        if (meta == null) {
            return null;
        }
        return retrieve(query, meta, topK, System.currentTimeMillis(), new ArrayList<>(), userId);
    }

    /**
     * 前情检索主流程，元信息已经完成可见性校验。
     */
    private CompanionContextView retrieve(String query, CompanionEpisodeDTO meta, Integer topK,
                                          long startedAt, List<String> flags, Long userId) {
        Long dramaId = meta.dramaId();
        Integer episodeNo = meta.episodeNo();

        // 1. 第 1 集没有前情，直接返回空结果
        if (dramaId == null || episodeNo == null || episodeNo <= 1) {
            return view(meta, List.of(), flags, startedAt);
        }

        // 2. 两路混合检索，范围锁定为当前剧且集数小于当前集
        List<Document> documents;
        RagTrace trace = new RagTrace(query);
        try {
            Query ragQuery = Query.builder()
                    .text(query)
                    .context(Map.of(RagTrace.CONTEXT_KEY, trace))
                    .build();
            documents = hybridRetriever.retrieve(
                    ragQuery, RetrievalScope.episodes(dramaId, episodeNo));
        } catch (Exception e) {
            log.warn("companion context retrieval failed: episodeId={}, error={}",
                    meta.episodeId(), rootMessage(e));
            flags.add(FLAG_CONTEXT);
            return view(meta, List.of(), flags, startedAt);
        }

        // 3. 保留两路检索的降级标记
        flags.addAll(trace.getDegradedFlags());

        // 4. 后置校验、去重、付费集过滤并截断
        List<ContextItem> items = filterAndMap(documents, meta, topK, userId);
        if (items.isEmpty() && flags.isEmpty() && bm25Store.countEpisodes(dramaId) == 0) {
            flags.add(FLAG_EPISODE_INDEX);
        }
        return view(meta, items, flags, startedAt);
    }

    /**
     * 过滤未来集、跨剧文档和重复集，再转换为返回模型。
     */
    private List<ContextItem> filterAndMap(List<Document> documents,
                                           CompanionEpisodeDTO meta, Integer topK,
                                           Long userId) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int limit = resolveTopK(topK);
        int maxSummaryChars = Math.max(properties.getContext().getMaxSummaryChars(), 1);
        Set<Long> seen = new HashSet<>();
        // 只有候选里出现付费集时才查订单，免费剧不增加额外调用
        Set<Long> unlockedEpisodeIds = documents.stream().anyMatch(this::hasPaidPrice)
                ? accessService.unlockedEpisodeIds(userId, meta.dramaId())
                : Set.of();
        List<ContextItem> items = new ArrayList<>();

        for (Document document : documents) {
            if (document == null) {
                continue;
            }
            Map<String, Object> metadata = document.getMetadata();
            // 1. 只接受同一部短剧的分集文档
            if (!RagMetadata.DOC_TYPE_EPISODE.equals(
                    stringValue(metadata.get(RagMetadata.DOC_TYPE)))) {
                continue;
            }
            if (!Objects.equals(longValue(metadata.get(RagMetadata.DRAMA_ID)), meta.dramaId())) {
                continue;
            }

            // 2. 后置校验剧集和集数都严格早于当前集
            Long episodeId = longValue(metadata.get(RagMetadata.EPISODE_ID));
            Integer episodeNo = integerValue(metadata.get(RagMetadata.EPISODE_NO));
            if (episodeId == null || episodeId >= meta.episodeId()) {
                continue;
            }
            if (episodeNo == null || episodeNo >= meta.episodeNo()) {
                continue;
            }
            if (!seen.add(episodeId)) {
                continue;
            }

            // 3. 付费且未解锁的前情不能进入上下文
            int pricePoints = integerValue(metadata.get(RagMetadata.PRICE_POINTS)) == null
                    ? 0 : integerValue(metadata.get(RagMetadata.PRICE_POINTS));
            if (pricePoints > 0 && !unlockedEpisodeIds.contains(episodeId)) {
                continue;
            }

            // 4. 摘要截断后返回，分数优先取 RRF
            String summary = truncate(
                    stringValue(metadata.get(RagMetadata.SUMMARY)), maxSummaryChars);
            double score = firstDouble(
                    metadata.get(RagMetadata.RRF_SCORE), document.getScore());
            items.add(new ContextItem(
                    episodeId,
                    episodeNo,
                    stringValue(metadata.get(RagMetadata.EPISODE_TITLE)),
                    summary,
                    score,
                    retrievalSources(metadata),
                    pricePoints));
            if (items.size() >= limit) {
                break;
            }
        }
        return List.copyOf(items);
    }

    /**
     * 判断文档是否是付费集。
     */
    private boolean hasPaidPrice(Document document) {
        if (document == null) {
            return false;
        }
        Integer pricePoints = integerValue(document.getMetadata().get(RagMetadata.PRICE_POINTS));
        return pricePoints != null && pricePoints > 0;
    }

    /**
     * 收敛返回条数，避免一次塞入过多前情。
     */
    private int resolveTopK(Integer requested) {
        int defaultTopK = Math.max(properties.getContext().getDefaultTopK(), 1);
        int maxTopK = Math.max(properties.getContext().getMaxTopK(), defaultTopK);
        int topK = requested == null ? defaultTopK : requested;
        return Math.max(1, Math.min(topK, maxTopK));
    }

    /**
     * 读取两路检索来源。
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
     * 正常检索视图，记录耗时。
     */
    private CompanionContextView view(CompanionEpisodeDTO meta, List<ContextItem> items,
                                      List<String> flags, long startedAt) {
        log.info("companion context: episodeId={}, returned={}, flags={}, elapsedMs={}",
                meta.episodeId(), items.size(), flags,
                System.currentTimeMillis() - startedAt);
        return new CompanionContextView(
                meta.episodeId(), meta.dramaId(), meta.episodeNo(), items,
                !flags.isEmpty(), List.copyOf(flags),
                System.currentTimeMillis() - startedAt);
    }

    /**
     * drama 不可用且没有剧集元信息时的降级视图。
     */
    private CompanionContextView degradedView(Long episodeId, List<String> flags, long startedAt) {
        log.info("companion context degraded: episodeId={}, flags={}, elapsedMs={}",
                episodeId, flags, System.currentTimeMillis() - startedAt);
        return new CompanionContextView(
                episodeId, null, null, List.of(), true, List.copyOf(flags),
                System.currentTimeMillis() - startedAt);
    }

    /**
     * 任意对象转 Long，无法转换时返回 null。
     */
    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 任意对象转 Integer，无法转换时返回 null。
     */
    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 取第一个非空 Double，缺省返回 0。
     */
    private double firstDouble(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                    // 继续尝试下一个值
                }
            }
        }
        return 0.0;
    }

    /**
     * 任意对象转字符串，null 返回空字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 按字符数截断文本。
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
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
