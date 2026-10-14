package com.yuzhuo.agent.error;

/**
 * 携带 {@link AgentErrorCode} 的业务异常。
 *
 * <p>它的存在是为了让「可预期的失败」走异常通道、由全局异常处理器统一翻译成结构化响应，
 * 而不是在每个 Controller 方法里写 if-else 判断。
 */
public class AgentException extends RuntimeException {

    private final AgentErrorCode errorCode;

    public AgentException(AgentErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public AgentException(AgentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentException(AgentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public AgentErrorCode getErrorCode() {
        return errorCode;
    }
}
