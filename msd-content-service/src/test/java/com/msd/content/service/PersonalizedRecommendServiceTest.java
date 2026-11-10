package com.msd.content.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.PersonalizedDramaCard;
import com.msd.content.dto.PersonalizedFeed;
import com.msd.content.dto.UserPreference;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 首页个性化推荐排序测试。
 */
class PersonalizedRecommendServiceTest {

    /**
     * 命中分类和标签的短剧排在热播之前，并排除最近已看。
     */
    @Test
    void ranksPreferredDramaAndExcludesRecent() {
        UserPreferenceService preferenceService = mock(UserPreferenceService.class);
        DramaCatalogCacheService catalogCache = mock(DramaCatalogCacheService.class);
        when(preferenceService.get(1006L)).thenReturn(new UserPreference(
                List.of(new UserPreference.CategoryWeight(2002L, "甜宠", 10.0)),
                List.of(new UserPreference.TagWeight("逆袭", 8.0)),
                List.of(5001L),
                List.of(),
                false,
                false));
        when(catalogCache.list()).thenReturn(List.of(
                drama(5001L, "已看甜宠", 2002L, "甜宠", List.of("逆袭"), 1000),
                drama(5002L, "匹配甜宠", 2002L, "甜宠", List.of("逆袭"), 10),
                drama(5003L, "无关古装", 2004L, "古装", List.of("悬疑"), 500)));

        PersonalizedRecommendService service = service(preferenceService, catalogCache);
        PersonalizedFeed feed = service.feed(1006L, 6);

        assertThat(feed.personalized()).isTrue();
        assertThat(feed.items()).extracting(item -> item.dramaId())
                .doesNotContain(5001L);
        assertThat(feed.items().get(0).dramaId()).isEqualTo(5002L);
    }

    /**
     * 个性化卡片必须说明推荐原因，并返回命中的偏好标签。
     */
    @Test
    void explainsWhyPersonalizedCardsAreRecommended() {
        UserPreferenceService preferenceService = mock(UserPreferenceService.class);
        DramaCatalogCacheService catalogCache = mock(DramaCatalogCacheService.class);
        when(preferenceService.get(1006L)).thenReturn(new UserPreference(
                List.of(new UserPreference.CategoryWeight(2002L, "甜宠", 10.0)),
                List.of(new UserPreference.TagWeight("逆袭", 8.0)),
                List.of(),
                List.of(),
                false,
                false));
        when(catalogCache.list()).thenReturn(List.of(
                drama(5002L, "匹配甜宠", 2002L, "甜宠", List.of("逆袭", "都市"), 10),
                drama(5003L, "热播古装", 2004L, "古装", List.of("悬疑"), 500)));

        PersonalizedFeed feed = service(preferenceService, catalogCache).feed(1006L, 6);

        assertThat(feed.items()).allSatisfy(item -> assertThat(item.reason()).isNotBlank());
        assertThat(feed.items().get(0).reason()).contains("甜宠");
        assertThat(feed.items().get(0).matchedTags()).containsExactly("逆袭");
    }

    /**
     * 继续观看和共同观看优先，负反馈短剧必须排除。
     */
    @Test
    void ranksContinueAndCoOccurrenceAndExcludesDisliked() {
        UserPreferenceService preferenceService = mock(UserPreferenceService.class);
        DramaCatalogCacheService catalogCache = mock(DramaCatalogCacheService.class);
        when(preferenceService.get(1006L)).thenReturn(new UserPreference(
                List.of(), List.of(), List.of(5001L), List.of(),
                List.of(5001L),
                List.of(new UserPreference.DramaWeight(5002L, 3.0)),
                List.of(5003L),
                false, false));
        when(catalogCache.list()).thenReturn(List.of(
                drama(5001L, "继续看的剧", 2002L, "甜宠", List.of(), 100),
                drama(5002L, "共同观看的剧", 2002L, "甜宠", List.of(), 80),
                drama(5003L, "不感兴趣的剧", 2002L, "甜宠", List.of(), 999)));

        PersonalizedFeed feed = service(preferenceService, catalogCache).feed(1006L, 6);

        assertThat(feed.items()).extracting(item -> item.dramaId())
                .contains(5001L, 5002L)
                .doesNotContain(5003L);
        assertThat(feed.items().get(0).reason()).contains("继续看");
        assertThat(feed.items().get(1).reason()).contains("看过的人也在看");
    }

    /**
     * 冷启动和降级回退都要给出可读的热播理由。
     */
    @Test
    void explainsHotFallbackReasons() {
        UserPreferenceService preferenceService = mock(UserPreferenceService.class);
        DramaCatalogCacheService catalogCache = mock(DramaCatalogCacheService.class);
        when(preferenceService.get(1003L)).thenReturn(UserPreference.empty(false));
        when(preferenceService.get(1004L)).thenReturn(UserPreference.empty(true));
        when(catalogCache.list()).thenReturn(List.of(
                drama(5002L, "热播", 2002L, "甜宠", List.of(), 1000)));

        PersonalizedRecommendService service = service(preferenceService, catalogCache);

        assertThat(service.feed(1003L, 6).items()).singleElement()
                .satisfies(item -> assertThat(item.reason()).isEqualTo("热门推荐"));
        assertThat(service.feed(1004L, 6).items()).singleElement()
                .satisfies(item -> assertThat(item.reason()).isEqualTo("暂时按热播推荐"));
    }

    /**
     * 推荐结果写入 Redis 后要能还原 reason 和 matchedTags。
     */
    @Test
    void roundTripsRecommendedCardThroughCacheJson() throws Exception {
        PersonalizedFeed feed = new PersonalizedFeed(List.of(
                new PersonalizedDramaCard(5002L, "匹配甜宠", "", "", "甜宠", 6,
                        10L, 5L, "因为你喜欢甜宠", List.of("逆袭"))),
                true, false, false);
        JsonMapper jsonMapper = JsonMapper.shared();

        PersonalizedFeed restored = jsonMapper.readValue(
                jsonMapper.writeValueAsString(feed), PersonalizedFeed.class);

        assertThat(restored.items()).singleElement().satisfies(item -> {
            assertThat(item.reason()).isEqualTo("因为你喜欢甜宠");
            assertThat(item.matchedTags()).containsExactly("逆袭");
        });
    }

    /**
     * 无偏好时回退热播并标记冷启动。
     */
    @Test
    void fallsBackToHotWhenPreferenceEmpty() {
        UserPreferenceService preferenceService = mock(UserPreferenceService.class);
        DramaCatalogCacheService catalogCache = mock(DramaCatalogCacheService.class);
        when(preferenceService.get(1003L)).thenReturn(UserPreference.empty(false));
        when(catalogCache.list()).thenReturn(List.of(
                drama(5002L, "热播", 2002L, "甜宠", List.of(), 1000),
                drama(5003L, "冷门", 2004L, "古装", List.of(), 10)));

        PersonalizedRecommendService service = service(preferenceService, catalogCache);
        PersonalizedFeed feed = service.feed(1003L, 6);

        assertThat(feed.personalized()).isFalse();
        assertThat(feed.coldStart()).isTrue();
        assertThat(feed.items()).extracting(item -> item.dramaId())
                .containsExactly(5002L, 5003L);
    }

    /**
     * 创建推荐服务，缓存使用不可用的 Redisson 客户端走实时计算。
     */
    private PersonalizedRecommendService service(UserPreferenceService preferenceService,
                                                  DramaCatalogCacheService catalogCache) {
        RedissonClient redisson = mock(RedissonClient.class);
        return new PersonalizedRecommendService(preferenceService, catalogCache,
                redisson, mock(JsonMapper.class), new RagProperties());
    }

    /**
     * 构造目录 DTO。
     */
    private DramaRagExportDTO drama(Long id, String title, Long categoryId, String categoryName,
                                    List<String> tags, long views) {
        return new DramaRagExportDTO(
                id, title, "", "", "",
                categoryId, categoryName, tags, List.of(), 6, List.of(),
                "2026-10-20T10:00:00", views, 10L, 5L, 1L);
    }
}
