package com.yuzhuo.agent.web;

import com.yuzhuo.agent.error.AgentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「无 API Key 必须能正常启动并优雅降级」这条工程约束的验证。
 *
 * <p>这个测试类刻意不提供任何 ChatModel：把 api-key 显式置空，
 * 上下文必须照常启动，接口必须返回结构化错误码而不是 500。
 * 也就是说，这个测试跑得起来本身，就是结论的一半。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=")
@AutoConfigureMockMvc
class AgentDegradationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("没有 API Key 时上下文正常启动，且容器里确实没有 ChatModel")
    void contextStartsWithoutChatModel() {
        assertThat(applicationContext.getBeanProvider(ChatModel.class).getIfAvailable()).isNull();
    }

    @Test
    @DisplayName("同步接口降级为 HTTP 503 + 错误码 1001，不是 500")
    void syncEndpointDegradesGracefully() throws Exception {
        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"degraded\",\"message\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value(AgentErrorCode.MODEL_UNAVAILABLE.code()))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    @Test
    @DisplayName("流式接口降级为 error 事件，同样不抛异常")
    void sseEndpointDegradesGracefully() throws Exception {
        // SseEmitter 是异步返回的：先拿异步句柄，再 dispatch 出已经推送出去的事件帧
        MvcResult result = mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"degraded-sse\",\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        AgentErrorCode.MODEL_UNAVAILABLE.code())));
    }

    @Test
    @DisplayName("参数不合法：HTTP 400 + 错误码 1005，且被翻译成统一结构")
    void validationFailureIsStructured() throws Exception {
        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"x\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(AgentErrorCode.INVALID_REQUEST.code()))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("message")));
    }

    @Test
    @DisplayName("请求体不是 JSON：400 而不是 500")
    void unreadableBodyIsStructured() throws Exception {
        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("这不是 JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(AgentErrorCode.INVALID_REQUEST.code()));
    }

    @Test
    @DisplayName("回放一个从未运行过的会话：404，而不是空对象")
    void traceOfUnknownSessionIsNotFound() throws Exception {
        mockMvc.perform(get("/agent/trace/never-ran")).andExpect(status().isNotFound());
    }
}
