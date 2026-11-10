package com.msd.content.ai;

import com.msd.content.config.AiGovernanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 功能灰度和 A/B 分桶测试。
 */
class AiExperimentServiceTest {

    /**
     * Nacos 风格的 kebab-case 配置能绑定到治理属性。
     */
    @Test
    void bindsKebabCaseConfig() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("msd.ai.governance.features.rag-search.gray-percent", "5")
                .withProperty("msd.ai.governance.experiments.rag-model.enabled", "true")
                .withProperty("msd.ai.governance.experiments.rag-model.variants[0].name", "light")
                .withProperty("msd.ai.governance.experiments.rag-model.variants[0].percent", "100")
                .withProperty("msd.ai.governance.experiments.rag-model.variants[0].model",
                        "deepseek-chat");
        AiGovernanceProperties properties = Binder.get(environment)
                .bind("msd.ai.governance", Bindable.of(AiGovernanceProperties.class))
                .get();

        assertThat(properties.getFeatures().get("rag-search").getGrayPercent()).isEqualTo(5);
        assertThat(properties.getExperiments().get("rag-model").isEnabled()).isTrue();
        assertThat(properties.getExperiments().get("rag-model").getVariants().get(0).getModel())
                .isEqualTo("deepseek-chat");
    }

    /**
     * 功能关闭时所有用户都不可用。
     */
    @Test
    void featureDisabledForEveryone() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        AiGovernanceProperties.Feature feature = new AiGovernanceProperties.Feature();
        feature.setEnabled(false);
        properties.getFeatures().put("rag-search", feature);
        AiExperimentService service = new AiExperimentService(properties);

        assertThat(service.isEnabled("rag-search", 1003L)).isFalse();
    }

    /**
     * 灰度 100% 时所有登录用户可用，0% 时都不可用。
     */
    @Test
    void grayPercentBoundsAreHonored() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        AiGovernanceProperties.Feature feature = new AiGovernanceProperties.Feature();
        properties.getFeatures().put("rag-search", feature);
        AiExperimentService service = new AiExperimentService(properties);

        feature.setGrayPercent(100);
        assertThat(service.isEnabled("rag-search", 1003L)).isTrue();

        feature.setGrayPercent(0);
        assertThat(service.isEnabled("rag-search", 1003L)).isFalse();
    }

    /**
     * 同一用户同一功能多次判断结果稳定。
     */
    @Test
    void grayBucketIsDeterministic() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        AiGovernanceProperties.Feature feature = new AiGovernanceProperties.Feature();
        feature.setGrayPercent(50);
        properties.getFeatures().put("rag-search", feature);
        AiExperimentService service = new AiExperimentService(properties);

        boolean first = service.isEnabled("rag-search", 1003L);
        boolean second = service.isEnabled("rag-search", 1003L);

        assertThat(first).isEqualTo(second);
    }

    /**
     * 未登录用户按功能配置决定是否参与灰度。
     */
    @Test
    void anonymousFollowsFeatureConfig() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        AiGovernanceProperties.Feature feature = new AiGovernanceProperties.Feature();
        feature.setGrayPercent(50);
        properties.getFeatures().put("rag-search", feature);
        AiExperimentService service = new AiExperimentService(properties);

        feature.setAnonymousIncluded(false);
        assertThat(service.isEnabled("rag-search", null)).isFalse();

        feature.setAnonymousIncluded(true);
        assertThat(service.isEnabled("rag-search", null)).isTrue();
    }

    /**
     * 未配置的功能默认放行，避免 Nacos 缺省配置误伤现有能力。
     */
    @Test
    void unconfiguredFeatureIsEnabled() {
        AiExperimentService service = new AiExperimentService(new AiGovernanceProperties());

        assertThat(service.isEnabled("unknown", 1003L)).isTrue();
    }

    /**
     * 变体百分比覆盖 100% 时返回指定模型。
     */
    @Test
    void assignsVariantModel() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        AiGovernanceProperties.Experiment experiment = new AiGovernanceProperties.Experiment();
        experiment.setEnabled(true);
        experiment.setAnonymousIncluded(true);

        AiGovernanceProperties.Variant control = new AiGovernanceProperties.Variant();
        control.setName("control");
        control.setPercent(0);
        AiGovernanceProperties.Variant light = new AiGovernanceProperties.Variant();
        light.setName("light");
        light.setPercent(100);
        light.setModel("deepseek-chat");
        light.setTemperature(0.1);
        experiment.setVariants(List.of(control, light));
        properties.getExperiments().put("rag-model", experiment);
        AiExperimentService service = new AiExperimentService(properties);

        AiExperimentService.Assignment assignment = service.assign("rag-model", 1003L);

        assertThat(assignment.active()).isTrue();
        assertThat(assignment.variant()).isEqualTo("light");
        assertThat(assignment.model()).isEqualTo("deepseek-chat");
        assertThat(assignment.temperature()).isEqualTo(0.1);
        assertThat(assignment.hasModel()).isTrue();
    }

    /**
     * 实验关闭时返回 inactive，不覆盖模型。
     */
    @Test
    void disabledExperimentReturnsInactive() {
        AiGovernanceProperties properties = new AiGovernanceProperties();
        AiGovernanceProperties.Experiment experiment = new AiGovernanceProperties.Experiment();
        experiment.setEnabled(false);
        properties.getExperiments().put("rag-model", experiment);
        AiExperimentService service = new AiExperimentService(properties);

        AiExperimentService.Assignment assignment = service.assign("rag-model", 1003L);

        assertThat(assignment.active()).isFalse();
        assertThat(assignment.variant()).isNull();
        assertThat(assignment.hasModel()).isFalse();
    }
}
