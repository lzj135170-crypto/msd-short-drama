package com.msd.content.ai;

/**
 * 流式输出过滤器。
 *
 * <p>保留一小段尾部，等后续 chunk 到齐后再做正则匹配，避免手机号、邮箱等
 * 被 chunk 边界切断而漏过；同时统一控制回答总长度。</p>
 */
public class AiStreamSanitizer {

    /** 尾部保留字符数，覆盖常见手机号、身份证和邮箱。 */
    private static final int HOLD_BACK_CHARS = 64;

    // 输出防护
    private final AiOutputGuard guard;
    // 最大输出字符数
    private final int maxLength;
    // 尚未确认安全的尾部文本
    private final StringBuilder pending = new StringBuilder();

    // 已输出字符数
    private int emittedChars;
    // 是否已经触发长度截断
    private boolean truncated;

    /**
     * 创建流式过滤器。
     *
     * @param guard     输出防护
     * @param maxLength 最大输出字符数
     */
    public AiStreamSanitizer(AiOutputGuard guard, int maxLength) {
        this.guard = guard == null ? new AiOutputGuard() : guard;
        this.maxLength = Math.max(maxLength, 1);
    }

    /**
     * 接收一个模型 chunk，返回当前可以安全输出的文本。
     *
     * @param chunk 模型增量文本
     * @return 可输出文本；需要继续等待时为空字符串
     */
    public String accept(String chunk) {
        // 1. 已截断或空 chunk 不再输出
        if (truncated || chunk == null || chunk.isEmpty()) {
            return "";
        }
        pending.append(chunk);
        // 2. 尾部不足保留长度时继续等待，避免敏感信息跨 chunk 漏过
        if (pending.length() <= HOLD_BACK_CHARS) {
            return "";
        }
        // 3. 只放出确定安全的头部，尾部留待下次匹配
        int safeLength = pending.length() - HOLD_BACK_CHARS;
        String safe = pending.substring(0, safeLength);
        pending.delete(0, safeLength);
        return emit(safe);
    }

    /**
     * 结束流式输出，处理尾部剩余文本。
     *
     * @return 可输出文本
     */
    public String finish() {
        if (truncated || pending.isEmpty()) {
            return "";
        }
        String rest = pending.toString();
        pending.setLength(0);
        return emit(rest);
    }

    /**
     * 过滤并控制长度；超出上限时补省略号。
     */
    private String emit(String text) {
        // 1. 先做敏感内容替换，再计算剩余可输出长度
        String masked = guard.mask(text);
        int remaining = maxLength - emittedChars;
        if (remaining <= 0) {
            truncated = true;
            return "";
        }
        // 2. 超出剩余长度时截断并补省略号，只触发一次
        if (masked.length() > remaining) {
            masked = masked.substring(0, remaining) + "…";
            truncated = true;
        }
        emittedChars += masked.length();
        return masked;
    }
}
