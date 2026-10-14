package com.yuzhuo.agent.trace;

/**
 * 一次模型调用的可观测记录。
 *
 * <p>把模型调用和工具调用一起记，才拼得出"这一轮到底慢在哪"——
 * 是模型本身慢，还是某个工具拖了后腿，看 trace 一目了然。
 *
 * @param step          第几轮
 * @param durationMs    耗时
 * @param promptChars   送进去的字符数，用来发现"上下文悄悄膨胀"
 * @param responseChars 收回来的字符数
 */
public record ModelCallRecord(
        int step,
        long durationMs,
        int promptChars,
        int responseChars) {
}
