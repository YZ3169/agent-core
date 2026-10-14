package com.yuzhuo.agent.agent;

import com.yuzhuo.agent.agent.parse.ActionParser;
import com.yuzhuo.agent.agent.parse.ParsedAction;
import com.yuzhuo.agent.agent.prompt.SystemPromptBuilder;
import com.yuzhuo.agent.config.AgentProperties;
import com.yuzhuo.agent.error.AgentErrorCode;
import com.yuzhuo.agent.error.AgentException;
import com.yuzhuo.agent.memory.ConversationMemory;
import com.yuzhuo.agent.tool.ToolRegistry;
import com.yuzhuo.agent.trace.ModelCallRecord;
import com.yuzhuo.agent.trace.ToolCallRecord;
import com.yuzhuo.agent.trace.TraceSession;
import com.yuzhuo.agent.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 手写的 ReAct 循环 —— 本项目的核心。
 *
 * <p>为什么不用 Spring AI 的 {@code ChatClient} + {@code @Tool} 自动 tool-calling：
 * 那套机制把"什么时候调用工具、调用几次、失败怎么办"全藏在框架内部，
 * 上层只剩一个 black box。这个项目要证明的恰恰是"我理解并掌控了这个循环"，
 * 所以：Spring AI 只负责把 Prompt 发出去、把文本收回来，编排逻辑全部写在这里。
 *
 * <p>循环的四个护栏，缺一不可：
 * <ol>
 *   <li><b>步数上限</b>：{@code maxSteps} 到顶就停，防止模型在两个工具之间来回打转烧钱；</li>
 *   <li><b>单步超时</b>：模型调用放到独立线程池里 {@code Future.get(timeout)}，
 *       否则一个卡住的 HTTP 连接会让请求线程一直挂着；</li>
 *   <li><b>工具异常回灌</b>：工具抛异常时把异常信息变成 Observation 发回给模型，
 *       而不是让整轮崩掉 —— 这是 ReAct 真正能自我纠错的地方；</li>
 *   <li><b>解析失败回灌</b>：模型输出不符合格式时同样回灌错误原因，让它重写。</li>
 * </ol>
 */
@Component
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    /** 写进 trace 的结果摘要上限。trace 是排障用的，不需要存全文。 */
    private static final int TRACE_SUMMARY_LENGTH = 200;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory memory;
    private final TraceStore traceStore;
    private final SystemPromptBuilder promptBuilder;
    private final ActionParser actionParser;
    private final AgentProperties properties;
    private final ExecutorService stepExecutor;

    public ReActAgent(ObjectProvider<ChatModel> chatModelProvider,
                      ToolRegistry toolRegistry,
                      ConversationMemory memory,
                      TraceStore traceStore,
                      SystemPromptBuilder promptBuilder,
                      ActionParser actionParser,
                      AgentProperties properties,
                      @Qualifier("agentStepExecutor") ExecutorService stepExecutor) {
        this.chatModelProvider = chatModelProvider;
        this.toolRegistry = toolRegistry;
        this.memory = memory;
        this.traceStore = traceStore;
        this.promptBuilder = promptBuilder;
        this.actionParser = actionParser;
        this.properties = properties;
        this.stepExecutor = stepExecutor;
    }

    /**
     * 跑一轮完整的 ReAct 循环。
     *
     * @param request  已规范化的请求（sessionId 非空）
     * @param listener 过程事件出口，可为 {@link AgentListener#NOOP}
     */
    public AgentResponse run(AgentRequest request, AgentListener listener) {
        String sessionId = request.sessionId();
        long startedAt = System.currentTimeMillis();
        TraceSession trace = traceStore.startRun(sessionId);

        // 降级点：没有 ChatModel（= 没配 API Key）时，返回结构化错误码，绝不抛 500。
        // getIfAvailable 在容器里没有该类型 Bean 时返回 null，而不是抛异常。
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            long duration = System.currentTimeMillis() - startedAt;
            trace.finish(AgentStatus.ERROR.name(), 0, duration);
            listener.onError(AgentErrorCode.MODEL_UNAVAILABLE, AgentErrorCode.MODEL_UNAVAILABLE.message());
            log.warn("没有可用的 ChatModel，本次请求降级返回 {}（sessionId={}）",
                    AgentErrorCode.MODEL_UNAVAILABLE.code(), sessionId);
            return AgentResponse.error(sessionId, AgentErrorCode.MODEL_UNAVAILABLE,
                    AgentErrorCode.MODEL_UNAVAILABLE.message(), duration);
        }

        memory.append(sessionId, new UserMessage(request.message()));

        int step = 0;
        String answer = null;
        String status = AgentStatus.COMPLETED.name();
        String errorCode = null;
        String errorMessage = null;

        try {
            // 用带标签的 break 而不是 "return"：这样"答完了"和"步数耗尽"会汇聚到同一段收尾逻辑，
            // trace 的 finish 只写一处，不会漏。
            reactLoop:
            while (step < properties.getMaxSteps()) {
                step++;

                String output = callModel(chatModel, sessionId, step, trace);
                ParsedAction action = actionParser.parse(output);

                if (action.thought() != null) {
                    listener.onThought(step, action.thought());
                }

                switch (action.type()) {
                    case FINAL_ANSWER -> {
                        memory.append(sessionId, new AssistantMessage(output));
                        answer = action.finalAnswer();
                        listener.onAnswer(answer);
                        log.debug("session={} 在第 {} 步得出最终答案", sessionId, step);
                        break reactLoop;
                    }
                    case TOOL_CALL -> {
                        executeTool(sessionId, step, output, action, trace, listener);
                    }
                    case INVALID -> {
                        // 模型没按格式输出：把原因当作 Observation 回灌，给它一次自我纠正的机会。
                        // 这条路径和"工具失败"是同一个思想 —— 可恢复的错误不该终结整轮。
                        String observation = "你的上一条回复不符合格式要求：" + action.invalidReason()
                                + "。请严格按照 Thought/Action/Action Input 或 Thought/Final Answer 的格式重新回复。";
                        memory.append(sessionId, new AssistantMessage(output));
                        memory.append(sessionId, new UserMessage("Observation: " + observation));
                        listener.onObservation(step, null, observation);
                        log.debug("session={} 第 {} 步输出格式不合法：{}", sessionId, step, action.invalidReason());
                    }
                }
            }

            // 走到这里只有两种可能：拿到了最终答案，或者步数耗尽
            if (answer == null) {
                status = AgentStatus.MAX_STEPS_EXCEEDED.name();
                errorCode = AgentErrorCode.MAX_STEPS_EXCEEDED.code();
                errorMessage = AgentErrorCode.MAX_STEPS_EXCEEDED.message();
                answer = "已达到最大步数上限（" + properties.getMaxSteps()
                        + " 步），未能收敛到最终答案。可以补充更具体的条件后重试。";
                listener.onAnswer(answer);
                log.warn("session={} 达到 max-steps={} 仍未收敛", sessionId, properties.getMaxSteps());
            }
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - startedAt;
            trace.finish(AgentStatus.ERROR.name(), step, duration);
            throw e;
        }

        // step 就是真实消耗的轮数：答完了是"答在第几步"，步数耗尽是 maxSteps
        int usedSteps = step;
        long duration = System.currentTimeMillis() - startedAt;
        trace.finish(status, usedSteps, duration);

        if (AgentStatus.COMPLETED.name().equals(status)) {
            return AgentResponse.completed(sessionId, answer, usedSteps, trace.getToolCalls(), duration);
        }
        return new AgentResponse(sessionId, status, answer, usedSteps, errorCode, errorMessage,
                trace.getToolCalls(), duration);
    }

    /** 执行一次工具调用，成功与失败都变成 Observation 回灌，并落一条 trace。 */
    private void executeTool(String sessionId, int step, String modelOutput, ParsedAction action,
                             TraceSession trace, AgentListener listener) {
        Map<String, Object> arguments = action.arguments() == null ? Map.of() : action.arguments();
        listener.onToolCall(step, action.toolName(), arguments);

        long toolStartedAt = System.currentTimeMillis();
        String observation;
        boolean success;
        try {
            observation = toolRegistry.execute(action.toolName(), arguments);
            success = true;
        } catch (Exception e) {
            // 核心设计：工具失败不终结整轮。把异常翻译成模型能读懂的一句话回灌，
            // 模型下一轮可以改参数重来、或者换一个工具 —— 这正是 ReAct 的自我纠错能力所在。
            observation = "工具执行失败：" + describeFailure(e) + "。请修正参数或改用其他工具后重试。";
            success = false;
            log.debug("session={} 第 {} 步工具 {} 执行失败：{}", sessionId, step, action.toolName(), e.getMessage());
        }
        long toolDuration = System.currentTimeMillis() - toolStartedAt;

        trace.addToolCall(new ToolCallRecord(step, action.toolName(), arguments, toolDuration, success,
                summarize(observation)));

        memory.append(sessionId, new AssistantMessage(modelOutput));
        memory.append(sessionId, new UserMessage("Observation: " + observation));
        listener.onObservation(step, action.toolName(), observation);
    }

    /**
     * 一次模型调用，带单步超时。
     *
     * <p>把调用提交到独立线程池再 {@code get(timeout)}，是因为 REST 客户端的超时未必可靠
     * （DNS 卡住、连接池排队都可能超出预期）。Future 超时后 {@code cancel(true)} 中断它，
     * 至少能让请求线程立刻返回，而不是无限期挂住一个 Tomcat 工作线程。
     */
    private String callModel(ChatModel chatModel, String sessionId, int step, TraceSession trace) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptBuilder.build()));
        messages.addAll(memory.history(sessionId));

        int promptChars = messages.stream()
                .mapToInt(message -> message.getText() == null ? 0 : message.getText().length())
                .sum();

        long startedAt = System.currentTimeMillis();
        Future<ChatResponse> future = stepExecutor.submit(() -> chatModel.call(new Prompt(messages)));

        ChatResponse response;
        try {
            response = future.get(properties.getStepTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentException(AgentErrorCode.STEP_TIMEOUT,
                    "第 " + step + " 步超过 " + properties.getStepTimeoutMs() + "ms 未返回");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED,
                    "模型调用失败：" + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(AgentErrorCode.INTERNAL_ERROR, "等待模型返回时线程被中断", e);
        }

        String text = extractText(response);
        trace.addModelCall(new ModelCallRecord(step, System.currentTimeMillis() - startedAt, promptChars, text.length()));
        return text;
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED, "模型返回的结构为空");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED, "模型返回了空内容");
        }
        return text;
    }

    /**
     * 把异常翻译成一句给模型看的话。
     *
     * <p>参数类错误（IllegalArgumentException：缺参数、SQL 被拒、路径越界）的 message 是写给模型看的，
     * 直接透传；其他异常（NPE、IO 等）的 message 往往没有信息量，补上类名方便排障。
     */
    private static String describeFailure(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= TRACE_SUMMARY_LENGTH
                ? oneLine
                : oneLine.substring(0, TRACE_SUMMARY_LENGTH) + "…";
    }
}
