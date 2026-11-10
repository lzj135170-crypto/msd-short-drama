package com.msd.interaction.service;

import com.msd.common.dto.UserBehaviorDTO;
import com.msd.interaction.dto.UserBehaviorRow;
import com.msd.interaction.mapper.UserBehaviorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户行为聚合服务：只读取 interaction 自己的点赞、收藏和观看历史。
 *
 * <p>不在这里 join drama 表；分类/标签由 content-service 通过目录接口补齐。</p>
 */
@Service
public class UserBehaviorService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(UserBehaviorService.class);
    /** 最近互动短剧返回条数。 */
    private static final int RECENT_LIMIT = 20;
    /** 最近收藏短剧返回条数。 */
    private static final int FAVORITE_LIMIT = 20;
    /** 继续观看返回条数。 */
    private static final int CONTINUE_LIMIT = 10;
    /** 共同观看召回条数。 */
    private static final int CO_OCCURRENCE_LIMIT = 20;
    /** 负反馈返回条数。 */
    private static final int DISLIKE_LIMIT = 50;

    // 行为聚合 Mapper
    private final UserBehaviorMapper behaviorMapper;
    // 行为时间窗口，默认 90 天
    private final int windowDays;

    /**
     * 注入 Mapper 和时间窗口。
     */
    public UserBehaviorService(UserBehaviorMapper behaviorMapper,
                               @Value("${msd.preference.window-days:90}") int windowDays) {
        this.behaviorMapper = behaviorMapper;
        this.windowDays = Math.max(windowDays, 1);
    }

    /**
     * 查询用户行为；任何异常都返回空行为，避免影响推荐主链路。
     */
    public UserBehaviorDTO getBehavior(Long userId) {
        // 1. 非法用户直接返回空行为
        if (userId == null || userId <= 0) {
            return UserBehaviorDTO.empty(userId);
        }

        // 2. 计算时间窗口并查询聚合行
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);
        try {
            List<UserBehaviorRow> rows = behaviorMapper.selectWeights(userId, since);
            if (rows == null) {
                rows = List.of();
            }
            // 3. 按短剧去重并组装权重列表
            Map<Long, Double> weights = new LinkedHashMap<>();
            for (UserBehaviorRow row : rows) {
                if (row == null || row.getDramaId() == null || row.getDramaId() <= 0) {
                    continue;
                }
                double weight = row.getWeight() == null ? 0.0 : row.getWeight();
                if (weight > 0) {
                    weights.put(row.getDramaId(), weight);
                }
            }

            // 4. 最近互动按行为时间倒序，保留有限条数
            List<Long> recent = rows.stream()
                    .filter(row -> row != null && row.getDramaId() != null && row.getDramaId() > 0)
                    .sorted(Comparator.comparing(UserBehaviorRow::getLastTime,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(UserBehaviorRow::getDramaId)
                    .distinct()
                    .limit(RECENT_LIMIT)
                    .toList();

            // 5. 收藏、继续观看、共同观看和负反馈分别查询
            List<Long> favorites = behaviorMapper.selectFavoriteDramaIds(userId, FAVORITE_LIMIT);
            List<Long> continueIds = behaviorMapper.selectContinueDramaIds(userId, CONTINUE_LIMIT);
            List<UserBehaviorRow> coOccurrenceRows = behaviorMapper.selectCoOccurrenceDramas(
                    userId, since, CO_OCCURRENCE_LIMIT);
            // 迁移脚本未执行时推荐事件表可能不存在，负反馈缺失不能拖垮整个画像
            List<Long> disliked;
            try {
                disliked = behaviorMapper.selectDislikedDramaIds(userId, DISLIKE_LIMIT);
            } catch (Exception ex) {
                log.debug("load disliked dramas failed: userId={}", userId, ex);
                disliked = List.of();
            }

            // 6. 转换行为权重和共现得分
            List<UserBehaviorDTO.DramaWeight> weightList = new ArrayList<>();
            weights.forEach((dramaId, weight) ->
                    weightList.add(new UserBehaviorDTO.DramaWeight(dramaId, weight)));
            List<UserBehaviorDTO.DramaWeight> coOccurrence = new ArrayList<>();
            if (coOccurrenceRows != null) {
                for (UserBehaviorRow row : coOccurrenceRows) {
                    if (row != null && row.getDramaId() != null && row.getDramaId() > 0) {
                        coOccurrence.add(new UserBehaviorDTO.DramaWeight(
                                row.getDramaId(), row.getWeight() == null ? 0 : row.getWeight()));
                    }
                }
            }

            return new UserBehaviorDTO(userId, weightList, recent,
                    favorites == null ? List.of() : List.copyOf(favorites),
                    continueIds == null ? List.of() : List.copyOf(continueIds),
                    List.copyOf(coOccurrence),
                    disliked == null ? List.of() : List.copyOf(disliked));
        } catch (Exception ex) {
            log.warn("load user behavior failed: userId={}", userId, ex);
            return UserBehaviorDTO.empty(userId);
        }
    }
}
