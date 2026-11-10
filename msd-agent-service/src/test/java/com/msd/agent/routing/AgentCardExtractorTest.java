package com.msd.agent.routing;

import com.msd.agent.dto.AgentDramaCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DRAMA_CARD 提取测试。
 */
class AgentCardExtractorTest {

    /**
     * 提取卡片并移除卡片行，重复 dramaId 只保留一张。
     */
    @Test
    void extractsAndRemovesCards() {
        String reply = """
                可以看看这两部。
                [DRAMA_CARD] dramaId=5005; title=长安第一女仵作; subtitle=验尸破案; snippet=长安命案
                [DRAMA_CARD] dramaId=5001; title=隐婚总裁的甜妻; subtitle=先婚后爱; snippet=甜宠
                [DRAMA_CARD] dramaId=5005; title=重复卡片; subtitle=; snippet=
                希望你喜欢。
                """;

        AgentCardExtractor.Extraction extraction = AgentCardExtractor.extract(reply);

        assertThat(extraction.cards()).extracting(AgentDramaCard::dramaId)
                .containsExactly(5005L, 5001L);
        assertThat(extraction.text())
                .contains("可以看看这两部。")
                .contains("希望你喜欢。")
                .doesNotContain("DRAMA_CARD");
    }

    /**
     * 没有卡片时原样返回。
     */
    @Test
    void keepsPlainText() {
        AgentCardExtractor.Extraction extraction = AgentCardExtractor.extract("暂时没有找到");

        assertThat(extraction.text()).isEqualTo("暂时没有找到");
        assertThat(extraction.cards()).isEmpty();
    }
}
