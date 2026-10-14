package com.yuzhuo.agent.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yuzhuo.agent.error.AgentErrorCode;
import com.yuzhuo.agent.trace.ToolCallRecord;

import java.util.List;

/**
 * 同步接口的响应体。成功和失败共用同一个结构 —— 调用方只需要认识一种 JSON。
 *
 * <p>{@code errorCode} 用字符串而不是 int：它在契约上是不透明的标识，不是用来做算术的，
 * 字符串也避免了未来扩位时的兼容问题。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(
        String sessionId,
        String status,
        String answer,
        int steps,
        String errorCode,
        String errorMessage,
        List<ToolCallRecord> toolCalls,
        long durationMs) {

    public static AgentResponse completed(String sessionId, String answer, int steps,
                                          List<ToolCallRecord> toolCalls, long durationMs) {
        return new AgentResponse(sessionId, AgentStatus.COMPLETED.name(), answer, steps,
                null, null, toolCalls, durationMs);
    }

    public static AgentResponse maxStepsExceeded(String sessionId, String answer, int steps,
                                                 List<ToolCallRecord> toolCalls, long durationMs) {
        return new AgentResponse(sessionId, AgentStatus.MAX_STEPS_EXCEEDED.name(), answer, steps,
                AgentErrorCode.MAX_STEPS_EXCEEDED.code(), AgentErrorCode.MAX_STEPS_EXCEEDED.message(),
                toolCalls, durationMs);
    }

    public static AgentResponse error(String sessionId, AgentErrorCode errorCode, String message, long durationMs) {
        return new AgentResponse(sessionId, AgentStatus.ERROR.name(), null, 0,
                errorCode.code(), message, List.of(), durationMs);
    }
}
