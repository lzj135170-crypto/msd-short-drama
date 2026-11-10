package com.msd.content.ai;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * AI 输出轻量防护：隐藏明显联系方式/PII，并限制回答长度。
 *
 * <p>只做确定性过滤，不替代完整的内容安全审核。</p>
 */
@Component
public class AiOutputGuard {

    /** 手机号。 */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    /** 身份证号。 */
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    /** 邮箱。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 常见社交账号引导。 */
    private static final Pattern CONTACT_ACCOUNT = Pattern.compile(
            "(?:加|联系|添加)?\\s*(?:微信|vx|VX|QQ|qq)[:：号]?\\s*[A-Za-z0-9_-]{5,}");
    /** 明显引流/诈骗话术。 */
    private static final Pattern SENSITIVE_SOLICITATION =
            Pattern.compile("刷单|私下转账|代充|博彩|出售账号|刷流水");
    /** 联系方式统一替换文案。 */
    private static final String MASK = "[联系方式已隐藏]";
    /** 敏感话术统一替换文案。 */
    private static final String SAFE_MASK = "[内容已安全处理]";

    /**
     * 过滤回答并截断到指定长度。
     *
     * @param text      模型回答
     * @param maxLength 最大字符数
     * @return 过滤后的回答
     */
    public String sanitize(String text, int maxLength) {
        // 1. 空回答保持为空，交由调用方走兜底文案
        if (text == null || text.isBlank()) {
            return "";
        }
        // 2. 隐藏手机号、身份证、邮箱和社交账号，并拦截明显引流话术
        String sanitized = mask(text);
        // 3. 超长回答截断，避免异常输出撑大响应
        int limit = Math.max(maxLength, 1);
        if (sanitized.length() > limit) {
            return sanitized.substring(0, limit) + "…";
        }
        return sanitized.trim();
    }

    /**
     * 只做联系方式/PII 和引流话术替换，不 trim、不截断。
     *
     * <p>流式输出需要保留 chunk 之间的空格，由调用方统一控制长度。</p>
     *
     * @param text 待过滤文本
     * @return 过滤后的文本；null 返回空字符串
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String sanitized = PHONE.matcher(text).replaceAll(MASK);
        sanitized = ID_CARD.matcher(sanitized).replaceAll(MASK);
        sanitized = EMAIL.matcher(sanitized).replaceAll(MASK);
        sanitized = CONTACT_ACCOUNT.matcher(sanitized).replaceAll(MASK);
        return SENSITIVE_SOLICITATION.matcher(sanitized).replaceAll(SAFE_MASK);
    }
}
