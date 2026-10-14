package com.yuzhuo.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuo.agent.agent.parse.ActionParser;
import com.yuzhuo.agent.agent.prompt.SystemPromptBuilder;
import com.yuzhuo.agent.config.AgentProperties;
import com.yuzhuo.agent.error.AgentErrorCode;
import com.yuzhuo.agent.error.AgentException;
import com.yuzhuo.agent.memory.ConversationMemory;
import com.yuzhuo.agent.support.FakeChatModel;
import com.yuzhuo.agent.support.ThrowingTool;
import com.yuzhuo.agent.tool.AgentTool;
import com.yuzhuo.agent.tool.ToolRegistry;
import com.yuzhuo.agent.tool.builtin.CalculatorTool;
import com.yuzhuo.agent.trace.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手写 ReAct 循环的核心测试。
 *
 * <p>全程不碰网络、不需要 API Key：模型被换成按剧本返回文本的 {@link FakeChatModel}，
 * 于是"循环会不会转、转几步、失败怎么办"变成完全确定的断言。
 */
class ReActAgentTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final FakeChatModel chatModel = new FakeChatModel();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("正常流程：调用工具 → 回灌 Observation → 得出最终答案")
    void runsToolThenAnswers() {
        chatModel.reset(
                """
                Thought: 需要算一下总额
                Action: calculator
                Action Input: {"expression": "(128000+39800)*0.06"}
                """,
                """
                Thought: 3 个点的提成是 10068，可以回答了
                Final Answer: 提成是 10068 元。
                """);

        RecordingListener listener = new RecordingListener();
        AgentResponse response = newAgent(List.of(new CalculatorTool()), 6, 5000)
                .run(new AgentRequest("s1", "帮我算提成"), listener);

        assertThat(response.status()).isEqualTo(AgentStatus.COMPLETED.name());
        assertThat(response.answer()).isEqualTo("提成是 10068 元。");
        assertThat(response.steps()).isEqualTo(2);
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).tool()).isEqualTo("calculator");
        assertThat(response.toolCalls().get(0).success()).isTrue();

        // 事件顺序与内容
        assertThat(listener.events).anyMatch(e -> e.startsWith("thought:1"))
                .anyMatch(e -> e.equals("tool_call:1:calculator"))
                .anyMatch(e -> e.startsWith("observation:1:calculator"))
                .anyMatch(e -> e.startsWith("thought:2"))
                .anyMatch(e -> e.startsWith("answer:"));

        // 关键断言：工具的结果确实作为 Observation 进了下一轮的上下文
        assertThat(chatModel.callCount()).isEqualTo(2);
        assertThat(chatModel.promptTextAt(1)).contains("Observation:").contains("10068");
    }

    @Test
    @DisplayName("工具抛异常：异常信息变成 Observation 回灌，整轮不中断")
    void toolFailureBecomesObservation() {
        chatModel.reset(
                """
                Thought: 试试这个工具
                Action: boom
                Action Input: {}
                """,
                """
                Thought: 那个工具坏了，我换个办法
                Final Answer: 工具坏了，但我还是把答案给你。
                """);

        RecordingListener listener = new RecordingListener();
        AgentResponse response = newAgent(List.of(new ThrowingTool("boom")), 6, 5000)
                .run(new AgentRequest("s2", "干点活"), listener);

        assertThat(response.status()).isEqualTo(AgentStatus.COMPLETED.name());
        assertThat(response.answer()).isEqualTo("工具坏了，但我还是把答案给你。");
        assertThat(response.steps()).isEqualTo(2);

        // 失败被记录，但没有让整轮崩掉
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).success()).isFalse();
        assertThat(response.toolCalls().get(0).resultSummary()).contains("工具执行失败");
        assertThat(listener.events).anyMatch(e -> e.contains("磁盘炸了"));
        assertThat(chatModel.promptTextAt(1)).contains("工具执行失败").contains("磁盘炸了");
    }

    @Test
    @DisplayName("未知工具：同样是回灌 Observation，模型有机会改用正确的工具名")
    void unknownToolBecomesObservation() {
        chatModel.reset(
                """
                Thought: 用这个工具
                Action: no_such_tool
                Action Input: {"x": 1}
                """,
                """
                Final Answer: 我改用别的方式回答了。
                """);

        AgentResponse response = newAgent(List.of(new CalculatorTool()), 6, 5000)
                .run(new AgentRequest("s3", "随便问问"), AgentListener.NOOP);

        assertThat(response.status()).isEqualTo(AgentStatus.COMPLETED.name());
        assertThat(response.toolCalls().get(0).success()).isFalse();
        assertThat(response.toolCalls().get(0).resultSummary()).contains("未知工具");
        assertThat(chatModel.promptTextAt(1)).contains("未知工具").contains("calculator");
    }

    @Test
    @DisplayName("模型输出格式不合法：解析失败原因回灌，循环继续")
    void invalidOutputBecomesObservation() {
        chatModel.reset(
                "我想想啊，这个问题嘛……",
                "Final Answer: 想清楚了，答案是 42。");

        AgentResponse response = newAgent(List.of(new CalculatorTool()), 6, 5000)
                .run(new AgentRequest("s4", "问题"), AgentListener.NOOP);

        assertThat(response.status()).isEqualTo(AgentStatus.COMPLETED.name());
        assertThat(response.steps()).isEqualTo(2);
        assertThat(response.answer()).isEqualTo("想清楚了，答案是 42。");
        assertThat(chatModel.promptTextAt(1)).contains("不符合格式要求");
    }

    @Test
    @DisplayName("maxSteps 上限：模型一直调工具不收敛时，循环在恰好 maxSteps 步停住")
    void stopsAtMaxSteps() {
        // 剧本给足 10 条，但配置只允许 3 步 —— 断言"多余的剧本没被消费"
        chatModel.reset(repeatAction(10));
        int maxSteps = 3;

        AgentResponse response = newAgent(List.of(new CalculatorTool()), maxSteps, 5000)
                .run(new AgentRequest("s5", "无限循环吧"), AgentListener.NOOP);

        assertThat(response.status()).isEqualTo(AgentStatus.MAX_STEPS_EXCEEDED.name());
        assertThat(response.errorCode()).isEqualTo(AgentErrorCode.MAX_STEPS_EXCEEDED.code());
        assertThat(response.steps()).isEqualTo(maxSteps);
        assertThat(chatModel.callCount()).isEqualTo(maxSteps);
        assertThat(response.toolCalls()).hasSize(maxSteps);
    }

    @Test
    @DisplayName("单步超时：模型迟迟不返回时抛 STEP_TIMEOUT，而不是把请求线程挂死")
    void singleStepTimeout() {
        chatModel.reset("Final Answer: 永远不会及时到达");
        chatModel.setDelayMs(1000);

        ReActAgent agent = newAgent(List.of(new CalculatorTool()), 6, 50);

        assertThatThrownBy(() -> agent.run(new AgentRequest("s6", "慢问题"), AgentListener.NOOP))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).getErrorCode())
                        .isEqualTo(AgentErrorCode.STEP_TIMEOUT));
    }

    @Test
    @DisplayName("没有 ChatModel（没配 API Key）：降级返回结构化错误码 1001，不抛异常")
    void degradesWhenNoChatModel() {
        AgentProperties properties = newProperties(6, 5000);
        ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool()));

        // 一个空的 BeanFactory：getIfAvailable() 返回 null，模拟"容器里没有 ChatModel"
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ObjectProvider<ChatModel> provider = beanFactory.getBeanProvider(ChatModel.class);

        ReActAgent agent = new ReActAgent(provider, registry, new ConversationMemory(properties),
                new TraceStore(), new SystemPromptBuilder(registry), new ActionParser(new ObjectMapper()),
                properties, executor);

        RecordingListener listener = new RecordingListener();
        AgentResponse response = agent.run(new AgentRequest("s7", "你好"), listener);

        assertThat(response.status()).isEqualTo(AgentStatus.ERROR.name());
        assertThat(response.errorCode()).isEqualTo(AgentErrorCode.MODEL_UNAVAILABLE.code());
        assertThat(response.steps()).isZero();
        assertThat(listener.events).anyMatch(e -> e.startsWith("error:1001"));
    }

    @Test
    @DisplayName("多轮对话：同一个 session 的第二问能带上第一问的历史")
    void keepsHistoryAcrossTurns() {
        ReActAgent agent = newAgent(List.of(new CalculatorTool()), 6, 5000);

        chatModel.reset("Final Answer: 第一问的答案");
        agent.run(new AgentRequest("same-session", "第一个问题"), AgentListener.NOOP);

        chatModel.reset("Final Answer: 第二问的答案");
        agent.run(new AgentRequest("same-session", "第二个问题"), AgentListener.NOOP);

        String secondPrompt = chatModel.promptTextAt(0);
        assertThat(secondPrompt).contains("第一个问题").contains("第一问的答案").contains("第二个问题");
    }

    // --- 测试夹具 ---------------------------------------------------------

    private ReActAgent newAgent(List<AgentTool> tools, int maxSteps, long timeoutMs) {
        AgentProperties properties = newProperties(maxSteps, timeoutMs);
        ToolRegistry registry = new ToolRegistry(tools);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("fakeChatModel", chatModel);

        return new ReActAgent(beanFactory.getBeanProvider(ChatModel.class), registry,
                new ConversationMemory(properties), new TraceStore(), new SystemPromptBuilder(registry),
                new ActionParser(new ObjectMapper()), properties, executor);
    }

    private static AgentProperties newProperties(int maxSteps, long timeoutMs) {
        AgentProperties properties = new AgentProperties();
        properties.setMaxSteps(maxSteps);
        properties.setStepTimeoutMs(timeoutMs);
        return properties;
    }

    private static String[] repeatAction(int times) {
        String[] script = new String[times];
        for (int i = 0; i < times; i++) {
            script[i] = "Thought: 再算一次\nAction: calculator\nAction Input: {\"expression\": \"1+1\"}";
        }
        return script;
    }

    /** 把所有事件按 "类型:...:内容" 记成字符串，断言起来一目了然。 */
    private static final class RecordingListener implements AgentListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onThought(int step, String thought) {
            events.add("thought:" + step + ":" + thought);
        }

        @Override
        public void onToolCall(int step, String toolName, java.util.Map<String, Object> arguments) {
            events.add("tool_call:" + step + ":" + toolName);
        }

        @Override
        public void onObservation(int step, String toolName, String observation) {
            events.add("observation:" + step + ":" + toolName + ":" + observation);
        }

        @Override
        public void onAnswer(String answer) {
            events.add("answer:" + answer);
        }

        @Override
        public void onError(AgentErrorCode errorCode, String message) {
            events.add("error:" + errorCode.code() + ":" + message);
        }
    }
}
