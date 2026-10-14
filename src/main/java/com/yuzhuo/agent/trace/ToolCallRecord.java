package com.yuzhuo.agent.trace;

import java.util.Map;

/**
 * 一次工具调用的可观测记录。
 *
 * @param step          第几轮调用（从 1 开始）
 * @param tool          工具名
 * @param arguments     入参原文。排障时最想知道的就是"模型到底传了什么"
 * @param durationMs    耗时
 * @param success       是否成功。失败也要记 —— 失败率高往往意味着工具描述写得让模型看不懂
 * @param resultSummary 结果摘要，不是全文。Observation 可能很长，trace 里存全文会让回放接口变成流量炸弹
 */
public record ToolCallRecord(
        int step,
        String tool,
        Map<String, Object> arguments,
        long durationMs,
        boolean success,
        String resultSummary) {
}
