package com.msd.agent.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图路由配置，规则由 Nacos 的 agent-config.yaml 热更新。
 */
@Component
@ConfigurationProperties(prefix = "msd.ai.agent.routing")
public class RouteProperties {

    /** 是否启用规则前置路由。 */
    private boolean enabled = true;
    /** 高置信度阈值，达到后把路由提示交给 Supervisor。 */
    private double highConfidence = 0.7;
    /** 低置信度阈值，低于该值不再使用规则候选。 */
    private double lowConfidence = 0.4;
    /** 路由缓存 TTL（分钟）。 */
    private int cacheTtlMinutes = 30;
    /** 规则版本；改规则时必须同步升级，避免旧缓存污染。 */
    private String rulesVersion = "v1";
    /** 是否允许高置信度 AUDIT 走远程无状态快速通道。 */
    private boolean fastPathAudit = true;
    /** 单会话最多允许多少次子 Agent spawn。 */
    private int maxSpawn = 4;
    /** 路由规则表。 */
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getHighConfidence() {
        return highConfidence;
    }

    public void setHighConfidence(double highConfidence) {
        this.highConfidence = highConfidence;
    }

    public double getLowConfidence() {
        return lowConfidence;
    }

    public void setLowConfidence(double lowConfidence) {
        this.lowConfidence = lowConfidence;
    }

    public int getCacheTtlMinutes() {
        return cacheTtlMinutes;
    }

    public void setCacheTtlMinutes(int cacheTtlMinutes) {
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    public String getRulesVersion() {
        return rulesVersion;
    }

    public void setRulesVersion(String rulesVersion) {
        this.rulesVersion = rulesVersion;
    }

    public boolean isFastPathAudit() {
        return fastPathAudit;
    }

    public void setFastPathAudit(boolean fastPathAudit) {
        this.fastPathAudit = fastPathAudit;
    }

    public int getMaxSpawn() {
        return maxSpawn;
    }

    public void setMaxSpawn(int maxSpawn) {
        this.maxSpawn = maxSpawn;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    /**
     * 单条关键词/正则路由规则。
     */
    public static class Rule {

        /** 规则名称，写入日志和缓存。 */
        private String name;
        /** 目标意图。 */
        private IntentRoute route;
        /** 目标子 Agent；为空时使用 IntentRoute 默认值。 */
        private String agent;
        /** 单次命中达到的基础置信度。 */
        private double weight = 0.8;
        /** 关键词列表。 */
        private List<String> keywords = new ArrayList<>();
        /** 正则列表，适合第 N 集这类句式。 */
        private List<String> patterns = new ArrayList<>();
        /** 同分时的优先级，数值越大越优先。 */
        private int priority;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public IntentRoute getRoute() {
            return route;
        }

        public void setRoute(IntentRoute route) {
            this.route = route;
        }

        public String getAgent() {
            return agent;
        }

        public void setAgent(String agent) {
            this.agent = agent;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords == null ? new ArrayList<>() : keywords;
        }

        public List<String> getPatterns() {
            return patterns;
        }

        public void setPatterns(List<String> patterns) {
            this.patterns = patterns == null ? new ArrayList<>() : patterns;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }
}
