package com.msd.content.companion;

import com.msd.common.dto.RecapEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.RecapItem;
import com.msd.content.dto.RecapView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 前情提要/本集看点。
 *
 * <p>只返回当前集及之前的分集摘要；当前集付费未解锁时不返回本集摘要，
 * 已解锁付费集才进入前情，防止剧透。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionRecapService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionRecapService.class);

    /** 降级标记：短剧服务不可用。 */
    public static final String FLAG_DRAMA = "drama";
    /** 降级标记：当前集不存在或已下线。 */
    public static final String FLAG_NOT_FOUND = "not_found";
    /** 降级标记：当前集付费未解锁。 */
    public static final String FLAG_LOCKED = "locked";
    /** 降级标记：摘要缺失。 */
    public static final String FLAG_SUMMARY_MISSING = "summary_missing";

    // 短剧目录客户端
    private final DramaCatalogClient catalogClient;
    // 付费集权限校验
    private final CompanionAccessService accessService;
    // 伴看配置
    private final CompanionProperties properties;

    /**
     * 注入短剧客户端、权限校验和伴看配置。
     */
    public CompanionRecapService(DramaCatalogClient catalogClient,
                                 CompanionAccessService accessService,
                                 CompanionProperties properties) {
        this.catalogClient = catalogClient;
        this.accessService = accessService;
        this.properties = properties;
    }

    /**
     * 查询当前集及之前的前情提要。
     *
     * @param dramaId   短剧 ID
     * @param episodeNo 当前集数
     * @param userId    当前用户；为空时只返回免费集
     */
    public RecapView getRecap(Long dramaId, Integer episodeNo, Long userId) {
        // 1. 参数非法时直接返回空结果
        if (dramaId == null || dramaId <= 0 || episodeNo == null || episodeNo <= 0) {
            return new RecapView(dramaId, episodeNo, null, List.of(), false, List.of());
        }

        // 2. 拉取当前集及之前的在线分集；失败时降级为空
        List<RecapEpisodeDTO> episodes;
        try {
            Result<List<RecapEpisodeDTO>> response = catalogClient.recap(dramaId, episodeNo);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return degraded(dramaId, episodeNo, FLAG_DRAMA);
            }
            episodes = response.getData();
        } catch (Exception e) {
            log.warn("companion recap lookup failed: dramaId={}, episodeNo={}, error={}",
                    dramaId, episodeNo, rootMessage(e));
            return degraded(dramaId, episodeNo, FLAG_DRAMA);
        }

        // 3. 只有候选里出现付费集时才查询订单，免费剧不增加额外调用
        boolean hasPaid = episodes.stream().anyMatch(this::isPaid);
        Set<Long> unlocked = hasPaid
                ? accessService.unlockedEpisodeIds(userId, dramaId)
                : Set.of();

        List<String> flags = new ArrayList<>();
        RecapItem current = null;
        List<RecapItem> previous = new ArrayList<>();

        // 4. 当前集：付费未解锁不返回摘要；缺失时标记 not_found
        RecapEpisodeDTO currentRow = episodes.stream()
                .filter(item -> item != null && item.episodeNo() != null
                        && item.episodeNo().equals(episodeNo))
                .findFirst()
                .orElse(null);
        if (currentRow == null) {
            flags.add(FLAG_NOT_FOUND);
        } else if (isLocked(currentRow, unlocked)) {
            flags.add(FLAG_LOCKED);
        } else if (!hasText(currentRow.summary())) {
            flags.add(FLAG_SUMMARY_MISSING);
            current = toItem(currentRow);
        } else {
            current = toItem(currentRow);
        }

        // 5. 前情：严格早于当前集、摘要可用、免费或已解锁
        for (RecapEpisodeDTO episode : episodes) {
            if (episode == null || episode.episodeNo() == null
                    || episode.episodeNo() >= episodeNo) {
                continue;
            }
            // 未解锁付费集不返回，也不因它的摘要缺失打扰用户
            if (isLocked(episode, unlocked)) {
                continue;
            }
            if (!hasText(episode.summary())) {
                flags.add(FLAG_SUMMARY_MISSING);
                continue;
            }
            previous.add(toItem(episode));
        }

        // 6. 按集数升序只保留最近 N 条，界面更容易从近到远阅读
        previous.sort(Comparator.comparing(RecapItem::episodeNo));
        int maxPrevious = Math.max(properties.getRecap().getMaxPrevious(), 1);
        if (previous.size() > maxPrevious) {
            previous = new ArrayList<>(previous.subList(previous.size() - maxPrevious,
                    previous.size()));
        }

        return new RecapView(dramaId, episodeNo, current, List.copyOf(previous),
                !flags.isEmpty(), List.copyOf(flags));
    }

    /**
     * 判断分集是否付费。
     */
    private boolean isPaid(RecapEpisodeDTO episode) {
        return episode != null && episode.pricePoints() != null && episode.pricePoints() > 0;
    }

    /**
     * 判断付费分集是否尚未解锁。
     */
    private boolean isLocked(RecapEpisodeDTO episode, Set<Long> unlocked) {
        return isPaid(episode) && !unlocked.contains(episode.episodeId());
    }

    /**
     * 分集 DTO 转前情条目。
     */
    private RecapItem toItem(RecapEpisodeDTO episode) {
        return new RecapItem(episode.episodeId(), episode.episodeNo(),
                episode.title(), episode.summary());
    }

    /**
     * 构造 drama 不可用时的降级视图。
     */
    private RecapView degraded(Long dramaId, Integer episodeNo, String flag) {
        return new RecapView(dramaId, episodeNo, null, List.of(), true, List.of(flag));
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
