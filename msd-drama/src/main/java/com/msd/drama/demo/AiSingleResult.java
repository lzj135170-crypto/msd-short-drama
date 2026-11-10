package com.msd.drama.demo;

/**
 * 单次 AI 调用结果，带当前线程信息。
 *
 * @param threadName 处理请求的线程名
 * @param virtual    处理请求的线程是否为虚拟线程
 * @param elapsedMs  调用耗时，单位毫秒
 * @param reply      下游回复文本
 * @param degraded   下游是否降级返回
 */
public record AiSingleResult(
        String threadName,
        boolean virtual,
        long elapsedMs,
        String reply,
        boolean degraded
) {
}
