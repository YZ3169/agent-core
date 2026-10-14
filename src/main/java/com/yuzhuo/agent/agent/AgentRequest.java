package com.yuzhuo.agent.agent;

import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 一次对话请求。
 *
 * @param sessionId 会话标识，用于隔离记忆与 trace。为空时自动生成一个，
 *                  并在响应里回传 —— 这样"只是想试一下"的调用方不必自己编 ID。
 * @param message   用户这一轮说的话
 */
public record AgentRequest(String sessionId, String message) {

    /** 规范化：补全缺失的 sessionId，并去掉首尾空白。 */
    public AgentRequest normalized() {
        String normalizedSessionId = StringUtils.hasText(sessionId) ? sessionId.trim() : UUID.randomUUID().toString();
        return new AgentRequest(normalizedSessionId, message == null ? "" : message.trim());
    }
}
