package com.yuzhuo.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * /agent/chat 与 /agent/chat/sync 的请求体。
 *
 * @param sessionId 会话标识，可空。空则服务端生成并在响应里回传。
 *                  限制长度是为了防止有人拿超长字符串当 key 塞进内存 Map。
 * @param message   用户输入，必填
 */
public record ChatRequest(
        @Size(max = 64, message = "sessionId 长度不能超过 64") String sessionId,
        @NotBlank(message = "message 不能为空") @Size(max = 4000, message = "message 长度不能超过 4000") String message) {
}
