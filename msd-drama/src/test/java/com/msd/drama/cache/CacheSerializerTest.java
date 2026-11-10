package com.msd.drama.cache;

import com.msd.drama.dto.CategoryView;
import com.msd.drama.dto.DramaCard;
import com.msd.drama.dto.DramaCounters;
import com.msd.drama.dto.DramaDetail;
import com.msd.drama.dto.EpisodeItem;
import com.msd.drama.dto.HomeBanner;
import com.msd.drama.dto.HomeFeed;
import com.msd.drama.dto.HomeSection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheSerializerTest {

    private final CacheSerializer serializer = new CacheSerializer(JsonMapper.builder().build());

    @Test
    void roundTripsDetailWithLocalDateTime() {
        DramaDetail detail = new DramaDetail(
                5005L,
                "长安第一女仵作",
                "验尸破案",
                "cover",
                "description",
                null,
                List.of(),
                List.of("沈知意"),
                1L,
                12,
                LocalDateTime.of(2026, 10, 25, 12, 30),
                new DramaCounters(10L, 20L, 30L, 40L),
                List.of(new EpisodeItem(1L, 1, "第1集", "cover", 120,
                        0L, true, 10L, 20L, 30L, 40L))
        );

        String raw = serializer.encode(detail);
        DecodedCache decoded = serializer.decode(raw, DramaDetail.class);

        assertThat(decoded.nullValue()).isFalse();
        assertThat(decoded.value()).isEqualTo(detail);
    }

    @Test
    void roundTripsHomeFeedWithNestedLists() {
        HomeFeed feed = new HomeFeed(
                List.of(new HomeBanner(5005L, "长安第一女仵作", "验尸破案", "cover")),
                List.of(new HomeSection("hot", "热播推荐",
                        List.of(new DramaCard(5005L, "长安第一女仵作", "验尸破案", "cover",
                                1L, "古装", "沈知意", 12, 10L, 20L, 30L, 40L,
                                LocalDateTime.of(2026, 10, 25, 12, 30))))),
                List.of(new CategoryView(1L, "古装", 1))
        );

        String raw = serializer.encode(feed);
        DecodedCache decoded = serializer.decode(raw, HomeFeed.class);

        assertThat(decoded.nullValue()).isFalse();
        assertThat(decoded.value()).isEqualTo(feed);
    }

    @Test
    void roundTripsNullAsExplicitNullValue() {
        String raw = serializer.encode(null);

        DecodedCache decoded = serializer.decode(raw, DramaDetail.class);

        assertThat(decoded.nullValue()).isTrue();
        assertThat(decoded.value()).isNull();
    }

    @Test
    void rejectsCorruptPayload() {
        assertThatThrownBy(() -> serializer.decode("{bad-json", DramaDetail.class))
                .isInstanceOf(CacheSerializationException.class);
    }

    @Test
    void rejectsVersionMismatch() {
        String raw = "{\"version\":99,\"nullValue\":false,\"data\":\"{}\"}";

        assertThatThrownBy(() -> serializer.decode(raw, DramaDetail.class))
                .isInstanceOf(CacheSerializationException.class)
                .hasMessageContaining("版本");
    }
}
