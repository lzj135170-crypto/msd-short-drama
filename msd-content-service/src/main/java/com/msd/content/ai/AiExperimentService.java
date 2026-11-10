package com.msd.content.ai;

import com.msd.content.config.AiGovernanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * AI 功能灰度和 A/B 实验分桶。
 *
 * <p>同一用户、同一实验使用稳定哈希，不受实例和调用次数影响；
 * 未配置的功能默认放行，未配置或关闭的实验不覆盖模型。</p>
 */
public class AiExperimentService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AiExperimentService.class);
    /** 灰度百分比上限。 */
    private static final int FULL_PERCENT = 100;

    // AI 治理配置
    private final AiGovernanceProperties properties;

    /**
     * 注入治理配置。
     */
    public AiExperimentService(AiGovernanceProperties properties) {
        this.properties = properties;
    }

    /**
     * 判断某个功能当前用户是否可用。
     *
     * @param feature 功能名，例如 rag-search
     * @param userId  当前用户，未登录时为 null
     */
    public boolean isEnabled(String feature, Long userId) {
        // 1. 未配置的功能默认放行，避免新增开关前误伤现有链路
        AiGovernanceProperties.Feature config = properties.getFeatures().get(feature);
        if (config == null) {
            return true;
        }
        // 2. 全局关闭时直接拒绝
        if (!config.isEnabled()) {
            return false;
        }
        // 3. 全量开启时无需分桶
        int percent = clampPercent(config.getGrayPercent());
        if (percent >= FULL_PERCENT) {
            return true;
        }
        // 4. 未登录用户是否参与灰度由配置决定
        if (userId == null || userId <= 0) {
            return config.isAnonymousIncluded() && percent > 0;
        }
        // 5. 按用户稳定分桶
        return bucket(feature, userId) < percent;
    }

    /**
     * 为当前用户分配实验变体。
     *
     * @param experiment 实验名，例如 rag-model
     * @param userId     当前用户，未登录时为 null
     * @return 变体分配结果；实验关闭或未命中时 active=false
     */
    public Assignment assign(String experiment, Long userId) {
        // 1. 未配置或关闭的实验不生效
        AiGovernanceProperties.Experiment config = properties.getExperiments().get(experiment);
        if (config == null || !config.isEnabled()) {
            return Assignment.inactive(experiment);
        }
        // 2. 未登录用户按配置决定是否参与，参与时固定落在 0 号桶
        if (userId == null || userId <= 0) {
            if (!config.isAnonymousIncluded()) {
                return Assignment.inactive(experiment);
            }
            return pick(experiment, config, 0);
        }
        // 3. 登录用户按实验盐值稳定分桶
        return pick(experiment, config, bucket(experiment + ":" + config.getSalt(), userId));
    }

    /**
     * 按累计百分比选择变体。
     */
    private Assignment pick(String experiment, AiGovernanceProperties.Experiment config,
                            int bucket) {
        List<AiGovernanceProperties.Variant> variants = config.getVariants();
        if (variants == null || variants.isEmpty()) {
            return Assignment.inactive(experiment);
        }
        int cumulative = 0;
        for (int index = 0; index < variants.size(); index++) {
            AiGovernanceProperties.Variant variant = variants.get(index);
            if (variant == null) {
                continue;
            }
            cumulative += clampPercent(variant.getPercent());
            if (bucket < cumulative) {
                String name = hasText(variant.getName())
                        ? variant.getName().trim() : "variant-" + index;
                String model = hasText(variant.getModel()) ? variant.getModel().trim() : null;
                return new Assignment(experiment, name, bucket, true, model,
                        variant.getTemperature());
            }
        }
        // 4. 百分比总和不足 100 时表示剩余流量不参与实验
        return Assignment.inactive(experiment);
    }

    /**
     * 计算稳定分桶，范围 0-99。
     */
    private int bucket(String key, Long userId) {
        String input = key + ":" + userId;
        try {
            // 1. 取 SHA-256 前 8 个字节转正整数
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int index = 0; index < 8; index++) {
                value = (value << 8) | (bytes[index] & 0xFFL);
            }
            // 2. 取模落入 100 个桶
            return (int) Math.floorMod(value, FULL_PERCENT);
        } catch (NoSuchAlgorithmException e) {
            // 3. 理论上不会发生，兜底使用字符串哈希
            log.warn("sha-256 unavailable, fallback to string hash: {}", e.getMessage());
            return Math.floorMod(input.hashCode(), FULL_PERCENT);
        }
    }

    /**
     * 百分比收敛到 0-100。
     */
    private int clampPercent(int percent) {
        return Math.max(0, Math.min(percent, FULL_PERCENT));
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 一次实验分配结果。
     *
     * @param experiment  实验名
     * @param variant     变体名，未命中时为 null
     * @param bucket      用户所在桶，未命中时为 -1
     * @param active      是否命中实验
     * @param model       需要覆盖的模型，null 表示不覆盖
     * @param temperature 需要覆盖的温度
     */
    public record Assignment(String experiment, String variant, int bucket, boolean active,
                             String model, double temperature) {

        /**
         * 未命中实验。
         */
        public static Assignment inactive(String experiment) {
            return new Assignment(experiment, null, -1, false, null, 0);
        }

        /**
         * 是否需要切换模型。
         */
        public boolean hasModel() {
            return model != null && !model.isBlank();
        }
    }
}
