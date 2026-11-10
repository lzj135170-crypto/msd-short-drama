package com.msd.agent.tool;

import com.msd.agent.client.AgentDramaClient;
import com.msd.agent.client.dto.AgentDramaViews.DramaCardView;
import com.msd.agent.client.dto.AgentDramaViews.DramaCountersView;
import com.msd.agent.client.dto.AgentDramaViews.DramaDetailView;
import com.msd.agent.client.dto.AgentDramaViews.EpisodeItemView;
import com.msd.agent.client.dto.AgentDramaViews.EpisodeView;
import com.msd.agent.client.dto.AgentDramaViews.HomeBannerView;
import com.msd.agent.client.dto.AgentDramaViews.HomeFeedView;
import com.msd.agent.client.dto.AgentDramaViews.HomeSectionView;
import com.msd.agent.client.dto.AgentDramaViews.TagView;
import com.msd.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 短剧查询工具，使用 Spring AI @Tool 注解声明，由 SpringAiToolBridge 注册到 AgentScope。
 *
 * <p>所有方法只读，返回适合模型阅读的紧凑文本，不返回视频地址。</p>
 */
@Component
public class DramaTools {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(DramaTools.class);

    /** 单次工具结果最多展示的剧集数。 */
    private static final int MAX_EPISODES = 30;

    // 短剧服务只读 Feign 客户端
    private final AgentDramaClient dramaClient;

    /**
     * 注入短剧服务 Feign 客户端。
     */
    public DramaTools(AgentDramaClient dramaClient) {
        this.dramaClient = dramaClient;
    }

    /**
     * 查询首页轮播和推荐区块。
     */
    @Tool(name = "get_home_dramas",
            description = "查询码上短剧首页：轮播位、推荐区块和分类。用于了解当前热门短剧和推荐结构。")
    public String getHomeDramas() {
        try {
            // 1. 调用首页接口，失败或数据为空时返回统一失败文案
            Result<HomeFeedView> result = dramaClient.home();
            if (!result.isSuccess() || result.getData() == null) {
                return failed("首页", result.getMessage());
            }

            // 2. 依次拼接轮播、推荐区块和分类
            HomeFeedView feed = result.getData();
            StringBuilder text = new StringBuilder("首页推荐数据：\n");
            appendBanners(text, feed.banners());
            appendSections(text, feed.sections());
            // 3. 分类只展示前 8 个，避免结果过长
            if (feed.categories() != null && !feed.categories().isEmpty()) {
                String categories = feed.categories().stream()
                        .limit(8)
                        .map(category -> category.name() + "(ID " + category.id() + ")")
                        .collect(Collectors.joining("、"));
                text.append("分类：").append(categories).append('\n');
            }
            return text.toString();
        } catch (Exception ex) {
            // 4. 远程异常转成可读文案，避免中断 Agent 循环
            log.warn("get_home_dramas failed", ex);
            return "查询首页失败：" + errorMessage(ex);
        }
    }

    /**
     * 查询指定短剧的详情。
     */
    @Tool(name = "get_drama_detail",
            description = "按短剧ID查询标题、简介、分类、标签、演员、播放/点赞/收藏/评论计数和剧集概览。")
    public String getDramaDetail(
            @ToolParam(description = "短剧ID，必须为正整数", required = true) long dramaId) {
        // 1. 本地校验 ID，避免无效远程调用
        if (dramaId <= 0) {
            return "短剧ID必须为正整数";
        }
        try {
            // 2. 调用详情接口，失败或数据为空时返回统一失败文案
            Result<DramaDetailView> result = dramaClient.detail(dramaId);
            if (!result.isSuccess() || result.getData() == null) {
                return failed("短剧详情", result.getMessage());
            }

            // 3. 按标题、副标题、分类、标签、演员、计数、简介、剧集的固定顺序拼接
            DramaDetailView detail = result.getData();
            StringBuilder text = new StringBuilder();
            text.append("短剧：").append(safe(detail.title()))
                    .append("（ID ").append(detail.id()).append("）\n");
            if (StringUtils.hasText(detail.subtitle())) {
                text.append("副标题：").append(detail.subtitle()).append('\n');
            }
            if (detail.category() != null) {
                text.append("分类：").append(safe(detail.category().name())).append('\n');
            }
            appendTags(text, detail.tags());
            if (detail.actors() != null && !detail.actors().isEmpty()) {
                text.append("演员：").append(String.join("、", detail.actors())).append('\n');
            }
            appendCounters(text, detail.counters());
            text.append("集数：").append(number(detail.episodeCount())).append('\n');
            if (StringUtils.hasText(detail.description())) {
                text.append("简介：").append(truncate(detail.description(), 200)).append('\n');
            }
            appendEpisodeItems(text, detail.episodes());
            return text.toString();
        } catch (Exception ex) {
            // 4. 远程异常转成可读文案，避免中断 Agent 循环
            log.warn("get_drama_detail failed: dramaId={}", dramaId, ex);
            return "查询短剧详情失败：" + errorMessage(ex);
        }
    }

    /**
     * 查询指定短剧的剧集列表。
     */
    @Tool(name = "list_drama_episodes",
            description = "按短剧ID查询剧集列表，返回集号、标题、免费/积分解锁价、播放/点赞/评论计数。")
    public String listDramaEpisodes(
            @ToolParam(description = "短剧ID，必须为正整数", required = true) long dramaId) {
        // 1. 本地校验 ID，避免无效远程调用
        if (dramaId <= 0) {
            return "短剧ID必须为正整数";
        }
        try {
            // 2. 调用剧集接口，失败或数据为空时返回统一失败文案
            Result<List<EpisodeView>> result = dramaClient.episodes(dramaId);
            if (!result.isSuccess() || result.getData() == null) {
                return failed("剧集列表", result.getMessage());
            }

            // 3. 按上限拼接剧集，超出部分只提示数量
            StringBuilder text = new StringBuilder("短剧ID ")
                    .append(dramaId).append(" 的剧集：\n");
            List<EpisodeView> episodes = result.getData();
            int limit = Math.min(episodes.size(), MAX_EPISODES);
            for (int index = 0; index < limit; index += 1) {
                EpisodeView episode = episodes.get(index);
                text.append("第").append(number(episode.episodeNo())).append("集 ")
                        .append(safe(episode.title()))
                        .append("（").append(priceLabel(episode.pricePoints()))
                        .append("，播放").append(number(episode.playCount()))
                        .append("，点赞").append(number(episode.likeCount()))
                        .append("，评论").append(number(episode.commentCount()))
                        .append("）\n");
            }
            if (episodes.size() > limit) {
                text.append("其余 ").append(episodes.size() - limit).append(" 集已省略。\n");
            }
            return text.toString();
        } catch (Exception ex) {
            // 4. 远程异常转成可读文案，避免中断 Agent 循环
            log.warn("list_drama_episodes failed: dramaId={}", dramaId, ex);
            return "查询剧集列表失败：" + errorMessage(ex);
        }
    }

    /**
     * 追加首页轮播信息。
     */
    private void appendBanners(StringBuilder text, List<HomeBannerView> banners) {
        if (banners == null || banners.isEmpty()) {
            return;
        }
        text.append("轮播：");
        for (int index = 0; index < Math.min(banners.size(), 3); index += 1) {
            HomeBannerView banner = banners.get(index);
            if (index > 0) {
                text.append("；");
            }
            text.append(safe(banner.title())).append("(ID ").append(banner.dramaId()).append(")");
        }
        text.append('\n');
    }

    /**
     * 追加首页推荐区块，每块最多取 5 部。
     */
    private void appendSections(StringBuilder text, List<HomeSectionView> sections) {
        if (sections == null || sections.isEmpty()) {
            return;
        }
        for (int index = 0; index < Math.min(sections.size(), 3); index += 1) {
            HomeSectionView section = sections.get(index);
            text.append(safe(section.title())).append("：");
            List<DramaCardView> items = section.items();
            if (items == null || items.isEmpty()) {
                text.append("暂无\n");
                continue;
            }
            for (int itemIndex = 0; itemIndex < Math.min(items.size(), 5); itemIndex += 1) {
                DramaCardView item = items.get(itemIndex);
                if (itemIndex > 0) {
                    text.append("；");
                }
                text.append(safe(item.title()))
                        .append("(ID ").append(item.dramaId())
                        .append("，共").append(number(item.episodeCount())).append("集")
                        .append("，播放").append(number(item.viewCount()))
                        .append("，点赞").append(number(item.likeCount()))
                        .append(")");
            }
            text.append('\n');
        }
    }

    /**
     * 追加标签。
     */
    private void appendTags(StringBuilder text, List<TagView> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        String names = tags.stream()
                .map(TagView::name)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("、"));
        if (StringUtils.hasText(names)) {
            text.append("标签：").append(names).append('\n');
        }
    }

    /**
     * 追加短剧计数。
     */
    private void appendCounters(StringBuilder text, DramaCountersView counters) {
        if (counters == null) {
            return;
        }
        text.append("播放：").append(number(counters.viewCount()))
                .append("，点赞：").append(number(counters.likeCount()))
                .append("，收藏：").append(number(counters.favoriteCount()))
                .append("，评论：").append(number(counters.commentCount()))
                .append('\n');
    }

    /**
     * 追加详情内嵌的剧集概览。
     */
    private void appendEpisodeItems(StringBuilder text, List<EpisodeItemView> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            return;
        }
        text.append("剧集概览：");
        int limit = Math.min(episodes.size(), MAX_EPISODES);
        for (int index = 0; index < limit; index += 1) {
            EpisodeItemView episode = episodes.get(index);
            if (index > 0) {
                text.append("；");
            }
            text.append("第").append(number(episode.episodeNo())).append("集 ")
                    .append(safe(episode.title()))
                    .append("（").append(episode.free() ? "免费" : episode.pricePoints() + "积分")
                    .append("）");
        }
        if (episodes.size() > limit) {
            text.append("；其余 ").append(episodes.size() - limit).append(" 集已省略");
        }
        text.append('\n');
    }

    /**
     * 统一失败文案。
     */
    private String failed(String scene, String message) {
        return "查询" + scene + "失败：" + (StringUtils.hasText(message) ? message : "短剧服务返回异常");
    }

    /**
     * 剧集价格文案。
     */
    private String priceLabel(Long pricePoints) {
        long price = number(pricePoints);
        return price <= 0 ? "免费" : price + "积分";
    }

    /**
     * 空值转空串。
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 空计数转 0。
     */
    private static long number(Number value) {
        return value == null ? 0 : value.longValue();
    }

    /**
     * 截断长文本。
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return safe(value);
        }
        return value.substring(0, maxLength) + "…";
    }

    /**
     * 提取可读异常信息。
     */
    private static String errorMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage())
                ? ex.getMessage()
                : ex.getClass().getSimpleName();
    }
}
