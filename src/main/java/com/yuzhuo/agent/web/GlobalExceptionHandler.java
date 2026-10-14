package com.yuzhuo.agent.web;

import com.yuzhuo.agent.agent.AgentResponse;
import com.yuzhuo.agent.error.AgentErrorCode;
import com.yuzhuo.agent.error.AgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 把异常统一翻译成 {@link AgentResponse} 结构。
 *
 * <p>存在的意义：调用方永远只会收到一种 JSON 形状。没有这一层的话，
 * 参数校验失败会得到 Spring 默认的错误结构、业务失败得到 AgentResponse、
 * 未预期异常得到 Tomcat 的 HTML 错误页 —— 三种形状，前端要写三套解析。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<AgentResponse> handleAgentException(AgentException e) {
        return ResponseEntity.status(e.getErrorCode().httpStatus())
                .body(AgentResponse.error(null, e.getErrorCode(), e.getMessage(), 0));
    }

    /** @Valid 校验失败。把字段级错误拼成一句话，前端直接展示即可。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(AgentErrorCode.INVALID_REQUEST.httpStatus())
                .body(AgentResponse.error(null, AgentErrorCode.INVALID_REQUEST, detail, 0));
    }

    /** 请求体不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(AgentErrorCode.INVALID_REQUEST.httpStatus())
                .body(AgentResponse.error(null, AgentErrorCode.INVALID_REQUEST, "请求体不是合法的 JSON", 0));
    }

    /**
     * 兜底。走到这里说明有没预料到的异常，必须打完整堆栈 ——
     * 对外给一句人话，对内留下能查的证据。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentResponse> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(AgentErrorCode.INTERNAL_ERROR.httpStatus())
                .body(AgentResponse.error(null, AgentErrorCode.INTERNAL_ERROR,
                        AgentErrorCode.INTERNAL_ERROR.message(), 0));
    }
}
