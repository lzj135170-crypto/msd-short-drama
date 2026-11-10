package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.dto.SummaryClaimDTO;
import com.msd.common.dto.SummaryWritebackRequest;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.EpisodeSummaryView;
import com.msd.content.index.EpisodeIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分集摘要读取与补全。
 *
 * <p>已有摘要直接返回；缺失时通过 drama 的 DB claim 选出唯一生成者，
 * 用轻量模型生成后回写，并 best-effort 更新单集索引。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class EpisodeSummaryService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(EpisodeSummaryService.class);

    /** 摘要来源：数据库已有。 */
    public static final String SOURCE_SEED = "seed";
    /** 摘要来源：本次生成。 */
    public static final String SOURCE_GENERATED = "generated";
    /** 摘要来源：缺失或生成失败。 */
    public static final String SOURCE_MISSING = "missing";
    /** 摘要来源：其他实例正在生成。 */
    public static final String SOURCE_GENERATING = "generating";
    /** 摘要来源：付费集未解锁。 */
    public static final String SOURCE_LOCKED = "locked";

    /** 降级标记：drama 下游不可用。 */
    public static final String FLAG_DRAMA = "drama";
    /** 降级标记：轻量模型未配置。 */
    public static final String FLAG_MODEL = "summary_model";
    /** 降级标记：摘要生成失败。 */
    public static final String FLAG_GENERATION = "summary_generation";
    /** 降级标记：其他实例正在生成。 */
    public static final String FLAG_GENERATING = "summary_generating";
    /** 降级标记：摘要回写失败。 */
    public static final String FLAG_WRITEBACK = "summary_writeback";
    /** 降级标记：单集索引更新失败。 */
    public static final String FLAG_INDEX = "episode_index";
    /** 降级标记：付费集未解锁。 */
    public static final String FLAG_LOCKED = "locked";

    /** 摘要生成提示，只允许使用给定事实。 */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是“码上短剧”的分集摘要生成器。
            只根据给出的剧名、短剧简介、集数和分集标题，写一段 60-120 字的中文摘要。
            不得编造未给出的情节，不得提及后续剧集，不得剧透。
            只输出摘要正文，不要标题、序号或解释。
            """;

    // 短剧内部接口客户端
    private final DramaCompanionClient dramaClient;
    // 轻量模型
    private final CompanionLightModel lightModel;
    // 单集索引更新服务
    private final EpisodeIndexService indexService;
    // 伴看配置
    private final CompanionProperties properties;
    // 付费集权限校验
    private final CompanionAccessService accessService;

    /**
     * 注入短剧客户端、轻量模型、索引服务、伴看配置和权限校验。
     */
    public EpisodeSummaryService(DramaCompanionClient dramaClient,
                                 CompanionLightModel lightModel,
                                 EpisodeIndexService indexService,
                                 CompanionProperties properties,
                                 CompanionAccessService accessService) {
        this.dramaClient = dramaClient;
        this.lightModel = lightModel;
        this.indexService = indexService;
        this.properties = properties;
        this.accessService = accessService;
    }

    /**
     * 查询分集摘要；剧集不可见时返回 null。
     */
    public EpisodeSummaryView getSummary(Long episodeId) {
        long startedAt = System.currentTimeMillis();
        List<String> flags = new ArrayList<>();

        // 1. 读取剧集元信息，区分不可见和下游不可用
        CompanionEpisodeDTO meta;
        try {
            Result<CompanionEpisodeDTO> response = dramaClient.companion(episodeId);
            if (response == null || !response.isSuccess()) {
                flags.add(FLAG_DRAMA);
                return degradedView(episodeId, flags, startedAt);
            }
            meta = response.getData();
        } catch (Exception e) {
            log.warn("companion episode lookup failed: episodeId={}, error={}",
                    episodeId, rootMessage(e));
            flags.add(FLAG_DRAMA);
            return degradedView(episodeId, flags, startedAt);
        }
        if (meta == null) {
            return null;
        }
        return getSummary(meta, startedAt, flags);
    }

    /**
     * 查询摘要并按用户权限校验付费集。
     *
     * <p>只用于 C 端工具接口；后台摘要预热仍使用不带用户的 getSummary。</p>
     */
    public EpisodeSummaryView getSummaryForUser(Long episodeId, Long userId) {
        long startedAt = System.currentTimeMillis();
        List<String> flags = new ArrayList<>();

        // 1. 读取剧集元信息，区分不可见和下游不可用
        CompanionEpisodeDTO meta;
        try {
            Result<CompanionEpisodeDTO> response = dramaClient.companion(episodeId);
            if (response == null || !response.isSuccess()) {
                flags.add(FLAG_DRAMA);
                return degradedView(episodeId, flags, startedAt);
            }
            meta = response.getData();
        } catch (Exception e) {
            log.warn("companion summary lookup failed: episodeId={}, error={}",
                    episodeId, rootMessage(e));
            flags.add(FLAG_DRAMA);
            return degradedView(episodeId, flags, startedAt);
        }
        if (meta == null) {
            return null;
        }

        // 2. 付费集未解锁时不生成、不返回摘要
        if (!accessService.canAccess(userId, meta)) {
            flags.add(FLAG_LOCKED);
            return view(meta, "", SOURCE_LOCKED, flags, startedAt);
        }
        return getSummary(meta, startedAt, flags);
    }

    /**
     * 使用已读取的剧集元信息查询摘要，避免聊天链路重复调用 drama。
     */
    public EpisodeSummaryView getSummary(CompanionEpisodeDTO meta) {
        if (meta == null) {
            return null;
        }
        return getSummary(meta, System.currentTimeMillis(), new ArrayList<>());
    }

    /**
     * 摘要补全主流程，元信息已经完成可见性校验。
     */
    private EpisodeSummaryView getSummary(CompanionEpisodeDTO meta, long startedAt,
                                          List<String> flags) {
        Long episodeId = meta.episodeId();

        // 1. 已有摘要直接返回，不触发生成
        if (hasText(meta.summary())) {
            return view(meta, meta.summary(), SOURCE_SEED, flags, startedAt);
        }

        // 2. 认领生成权，只有认领成功才调用模型
        SummaryClaimDTO claim;
        try {
            Result<SummaryClaimDTO> response = dramaClient.claimSummary(episodeId);
            if (response == null || !response.isSuccess()) {
                flags.add(FLAG_DRAMA);
                return view(meta, "", SOURCE_MISSING, flags, startedAt);
            }
            claim = response.getData();
        } catch (Exception e) {
            log.warn("companion summary claim failed: episodeId={}, error={}",
                    episodeId, rootMessage(e));
            flags.add(FLAG_DRAMA);
            return view(meta, "", SOURCE_MISSING, flags, startedAt);
        }
        if (claim == null) {
            return null;
        }

        // 3. 其他实例已生成或正在生成
        if (!claim.claimed()) {
            if (hasText(claim.summary())) {
                return view(meta, claim.summary(), SOURCE_SEED, flags, startedAt);
            }
            flags.add(FLAG_GENERATING);
            return view(meta, "", SOURCE_GENERATING, flags, startedAt);
        }

        // 4. 模型不可用时释放认领，允许配置 Key 后重试
        if (!lightModel.isAvailable()) {
            flags.add(FLAG_MODEL);
            releaseQuietly(episodeId);
            return view(meta, "", SOURCE_MISSING, flags, startedAt);
        }

        // 5. 生成摘要，失败时释放认领
        String summary = generate(meta);
        if (summary == null) {
            flags.add(FLAG_GENERATION);
            releaseQuietly(episodeId);
            return view(meta, "", SOURCE_MISSING, flags, startedAt);
        }

        // 6. 回写摘要；并发写入时优先返回已存在的版本
        if (!writeback(episodeId, summary, flags)) {
            CompanionEpisodeDTO latest = loadMetaQuietly(episodeId);
            if (latest != null && hasText(latest.summary())) {
                return view(latest, latest.summary(), SOURCE_SEED, flags, startedAt);
            }
            releaseQuietly(episodeId);
            return view(meta, summary, SOURCE_GENERATED, flags, startedAt);
        }

        // 7. best-effort 更新单集索引，失败只标记降级
        boolean indexed = indexService.upsert(
                meta.episodeId(), meta.dramaId(), meta.dramaTitle(),
                meta.episodeNo(), meta.episodeTitle(), summary, meta.pricePoints());
        if (!indexed) {
            flags.add(FLAG_INDEX);
        }
        return view(meta, summary, SOURCE_GENERATED, flags, startedAt);
    }

    /**
     * 调用轻量模型生成摘要，空结果视为失败。
     */
    private String generate(CompanionEpisodeDTO meta) {
        // 1. 只把可公开的剧集元信息交给模型，不传用户会话
        String userPrompt = "剧名：" + nullToEmpty(meta.dramaTitle())
                + "\n短剧简介：" + nullToEmpty(meta.dramaDescription())
                + "\n集数：第" + meta.episodeNo() + "集"
                + "\n分集标题：" + nullToEmpty(meta.episodeTitle());
        // 2. 生成后按配置截断，避免超出摘要列宽
        String text = lightModel.complete(SUMMARY_SYSTEM_PROMPT, userPrompt);
        if (!hasText(text)) {
            return null;
        }
        return truncate(text.trim(), Math.max(properties.getSummary().getMaxChars(), 1));
    }

    /**
     * 回写摘要，失败只记录标记。
     */
    private boolean writeback(Long episodeId, String summary, List<String> flags) {
        try {
            Result<Boolean> response = dramaClient.writebackSummary(episodeId,
                    new SummaryWritebackRequest(summary, lightModel.getModelName()));
            if (response != null && response.isSuccess() && Boolean.TRUE.equals(response.getData())) {
                return true;
            }
            flags.add(FLAG_WRITEBACK);
        } catch (Exception e) {
            log.warn("companion summary writeback failed: episodeId={}, error={}",
                    episodeId, rootMessage(e));
            flags.add(FLAG_WRITEBACK);
        }
        return false;
    }

    /**
     * 释放认领并标记失败，异常只记录日志。
     */
    private void releaseQuietly(Long episodeId) {
        try {
            dramaClient.releaseSummaryClaim(episodeId);
        } catch (Exception e) {
            log.warn("companion summary release failed: episodeId={}, error={}",
                    episodeId, rootMessage(e));
        }
    }

    /**
     * 重新读取剧集元信息，异常时返回 null。
     */
    private CompanionEpisodeDTO loadMetaQuietly(Long episodeId) {
        try {
            Result<CompanionEpisodeDTO> response = dramaClient.companion(episodeId);
            return response == null || !response.isSuccess() ? null : response.getData();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构造正常摘要视图并记录耗时。
     */
    private EpisodeSummaryView view(CompanionEpisodeDTO meta, String summary, String source,
                                    List<String> flags, long startedAt) {
        log.info("companion summary: episodeId={}, source={}, flags={}, summaryChars={}, elapsedMs={}",
                meta.episodeId(), source, flags, summary == null ? 0 : summary.length(),
                System.currentTimeMillis() - startedAt);
        return new EpisodeSummaryView(
                meta.episodeId(),
                meta.dramaId(),
                meta.episodeNo(),
                meta.episodeTitle(),
                meta.dramaTitle(),
                summary,
                source,
                !flags.isEmpty(),
                List.copyOf(flags));
    }

    /**
     * drama 不可用且没有剧集元信息时的降级视图。
     */
    private EpisodeSummaryView degradedView(Long episodeId, List<String> flags, long startedAt) {
        log.info("companion summary degraded: episodeId={}, flags={}, elapsedMs={}",
                episodeId, flags, System.currentTimeMillis() - startedAt);
        return new EpisodeSummaryView(
                episodeId, null, null, "", "", "", SOURCE_MISSING,
                true, List.copyOf(flags));
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * null 转为空字符串。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 按字符数截断摘要，尽量停在完整句子末尾。
     */
    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        // 1. 先截到上限，再找最后一个句末标点，避免从句子中间断开
        String cut = value.substring(0, maxChars);
        int lastStop = Math.max(
                Math.max(cut.lastIndexOf('。'), cut.lastIndexOf('！')),
                Math.max(cut.lastIndexOf('？'), cut.lastIndexOf('；')));
        // 2. 标点太靠前时保留原截断，避免摘要过短
        return lastStop >= maxChars / 2 ? cut.substring(0, lastStop + 1) : cut;
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
