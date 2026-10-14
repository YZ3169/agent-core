package com.yuzhuo.agent.web;

import com.yuzhuo.agent.support.FakeChatModel;
import com.yuzhuo.agent.support.TestChatModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 把整个 Spring 上下文拉起来，用剧本模型走完 HTTP 层。
 *
 * <p>这条链路覆盖了真实运行时的装配关系：Controller → ReActAgent → ToolRegistry → 真实工具
 * （sql_query 会真的查内存 H2），只有模型是假的。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=")
@AutoConfigureMockMvc
@Import(TestChatModelConfig.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeChatModel chatModel;

    @BeforeEach
    void resetScript() {
        chatModel.reset();
    }

    @Test
    @DisplayName("同步接口：走完 工具调用 → 观察 → 最终答案，并带回 toolCalls")
    void syncChatRunsFullLoop() throws Exception {
        chatModel.reset(
                """
                        Thought: 需要查库
                        Action: sql_query
                        Action Input: {"sql": "SELECT region, amount FROM sales_order LIMIT 3"}
                        """,
                """
                        Thought: 已经拿到数据
                        Final Answer: 华东区的订单金额最高。
                        """);

        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web-sync\",\"message\":\"哪个区域卖得最好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.answer").value("华东区的订单金额最高。"))
                .andExpect(jsonPath("$.steps").value(2))
                .andExpect(jsonPath("$.toolCalls[0].tool").value("sql_query"))
                .andExpect(jsonPath("$.toolCalls[0].success").value(true))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    @DisplayName("同步接口：不给 sessionId 时自动生成并回传")
    void generatesSessionIdWhenMissing() throws Exception {
        chatModel.reset("Final Answer: 好的。");

        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"随便问一句\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }

    @Test
    @DisplayName("同步接口：达到 maxSteps 时 HTTP 仍是 200，靠 status/errorCode 表达护栏生效")
    void maxStepsIsNotAnHttpError() throws Exception {
        // 默认 max-steps=6，给足剧本让它一直调工具
        chatModel.reset(
                "Thought: 查\nAction: sql_query\nAction Input: {\"sql\": \"SELECT id FROM sales_order LIMIT 1\"}",
                "Thought: 查\nAction: sql_query\nAction Input: {\"sql\": \"SELECT id FROM sales_order LIMIT 1\"}",
                "Thought: 查\nAction: sql_query\nAction Input: {\"sql\": \"SELECT id FROM sales_order LIMIT 1\"}",
                "Thought: 查\nAction: sql_query\nAction Input: {\"sql\": \"SELECT id FROM sales_order LIMIT 1\"}",
                "Thought: 查\nAction: sql_query\nAction Input: {\"sql\": \"SELECT id FROM sales_order LIMIT 1\"}",
                "Thought: 查\nAction: sql_query\nAction Input: {\"sql\": \"SELECT id FROM sales_order LIMIT 1\"}");

        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web-maxsteps\",\"message\":\"转圈吧\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAX_STEPS_EXCEEDED"))
                .andExpect(jsonPath("$.errorCode").value("1003"))
                .andExpect(jsonPath("$.steps").value(6));
    }

    @Test
    @DisplayName("工具被安全策略拒绝时，整轮照样跑完（模型看到错误后改用别的说法）")
    void toolRejectionDoesNotBreakTheRun() throws Exception {
        chatModel.reset(
                """
                        Thought: 直接查整张表
                        Action: sql_query
                        Action Input: {"sql": "DROP TABLE sales_order"}
                        """,
                """
                        Thought: 被拒了，那我老实说
                        Final Answer: 这个操作不被允许，我只能做只读查询。
                        """);

        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web-reject\",\"message\":\"删掉订单表\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.toolCalls[0].success").value(false))
                .andExpect(jsonPath("$.toolCalls[0].resultSummary").value(containsString("工具执行失败")));
    }

    @Test
    @DisplayName("SSE 接口：按 thought / tool_call / observation / answer 四类事件推送")
    void sseStreamsTypedEvents() throws Exception {
        chatModel.reset(
                """
                        Thought: 需要算一下
                        Action: calculator
                        Action Input: {"expression": "6*7"}
                        """,
                "Thought: 算完了\nFinal Answer: 答案是 42。");

        MvcResult result = mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web-sse\",\"message\":\"6 乘 7 等于几\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:thought")))
                .andExpect(content().string(containsString("event:tool_call")))
                .andExpect(content().string(containsString("event:observation")))
                .andExpect(content().string(containsString("event:answer")))
                .andExpect(content().string(containsString("calculator")))
                .andExpect(content().string(containsString("42")));
    }

    @Test
    @DisplayName("trace 接口：回放最近一次运行的调用链，含名字/入参/耗时/结果摘要")
    void traceReplaysLastRun() throws Exception {
        chatModel.reset(
                """
                        Thought: 算一下
                        Action: calculator
                        Action Input: {"expression": "2+3"}
                        """,
                "Thought: 好了\nFinal Answer: 5。");

        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web-trace\",\"message\":\"2+3\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/agent/trace/web-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("web-trace"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps").value(2))
                .andExpect(jsonPath("$.toolCalls[0].tool").value("calculator"))
                .andExpect(jsonPath("$.toolCalls[0].arguments.expression").value("2+3"))
                .andExpect(jsonPath("$.toolCalls[0].durationMs").isNumber())
                .andExpect(jsonPath("$.modelCalls[0].promptChars").isNumber());
    }

    @Test
    @DisplayName("会话记忆：同一 session 的第二轮能看到第一轮的内容")
    void memoryIsKeptPerSession() throws Exception {
        chatModel.reset("Final Answer: 记住了，你叫余卓。");
        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web-memory\",\"message\":\"我叫余卓\"}"))
                .andExpect(status().isOk());

        chatModel.reset("Final Answer: 你叫余卓。");
        mockMvc.perform(post("/agent/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web-memory\",\"message\":\"我叫什么\"}"))
                .andExpect(status().isOk());

        // 第二轮送给模型的上下文里必须同时有第一轮的问和答
        String secondPrompt = chatModel.promptTextAt(0);
        org.assertj.core.api.Assertions.assertThat(secondPrompt)
                .contains("我叫余卓")
                .contains("记住了，你叫余卓。");
    }
}
