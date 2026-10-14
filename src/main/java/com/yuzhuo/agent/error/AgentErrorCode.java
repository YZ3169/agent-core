package com.yuzhuo.agent.error;

import org.springframework.http.HttpStatus;

/**
 * 服务对外暴露的结构化错误码。
 *
 * <p>为什么要有它：调用方（前端 / 上游服务）需要能区分「我没配 Key（1001，服务是好的，配了就能用）」
 * 和「服务内部炸了（9999）」。前者的正确反应是提示运维配 Key，后者才需要告警。
 * 把这两类都塞进 HTTP 500 会让调用方无从判断，所以这里每个码都带一个语义明确的 HTTP 状态。
 */
public enum AgentErrorCode {

    /** 没有可用模型。注意 HTTP 是 503 而不是 500 —— 这是"服务暂时不可用"，不是"服务写挂了"。 */
    MODEL_UNAVAILABLE("1001", "未配置模型 API Key（DEEPSEEK_API_KEY），Agent 能力已降级", HttpStatus.SERVICE_UNAVAILABLE),

    /** 单步超时。模型调用或工具执行单步超过 agent.step-timeout-ms。 */
    STEP_TIMEOUT("1002", "单步执行超时", HttpStatus.GATEWAY_TIMEOUT),

    /** 达到 max-steps 仍未得出最终答案。这是循环的硬护栏，不是崩溃。 */
    MAX_STEPS_EXCEEDED("1003", "达到最大步数仍未得出最终答案", HttpStatus.OK),

    /** 模型调用本身失败（网络、鉴权、限流）。 */
    MODEL_CALL_FAILED("1004", "模型调用失败", HttpStatus.BAD_GATEWAY),

    /** 请求参数不合法，比如 sessionId 为空。 */
    INVALID_REQUEST("1005", "请求参数不合法", HttpStatus.BAD_REQUEST),

    /** 兜底。真走到这里说明有未预期的异常，需要看日志。 */
    INTERNAL_ERROR("9999", "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    AgentErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** 按码反查。找不到就退化成 INTERNAL_ERROR —— 宁可报"内部错误"，也不要抛异常把响应毁掉。 */
    public static AgentErrorCode fromCode(String code) {
        for (AgentErrorCode candidate : values()) {
            if (candidate.code.equals(code)) {
                return candidate;
            }
        }
        return INTERNAL_ERROR;
    }
}
