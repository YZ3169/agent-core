package com.yuzhuo.agent.agent.parse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把模型的一段自然语言输出解析成 {@link ParsedAction}。
 *
 * <p>为什么用纯文本协议（{@code Thought/Action/Action Input/Final Answer}）而不是
 * function calling 的结构化字段：ReAct 的原始论文就是一种提示词约定，
 * 手写解析器意味着"循环怎么转"完全由本项目掌控，不依赖任何框架的内部实现。
 * 代价是要自己处理模型不听话的情况 —— 这正是这个类存在的意义。
 *
 * <p>解析策略是逐条降级，任何一步失败都返回 {@link ActionType#INVALID} 而不是抛异常：
 * 解析失败是一次可回灌的观察，不是服务故障。
 */
@Component
public class ActionParser {

    private static final String THOUGHT_MARKER = "thought:";
    private static final String ACTION_MARKER = "action:";
    private static final String ACTION_INPUT_MARKER = "action input:";
    private static final String FINAL_ANSWER_MARKER = "final answer:";

    private final ObjectMapper objectMapper;

    public ActionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedAction parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return ParsedAction.invalid(null, "模型返回了空内容");
        }

        // 小写副本只用来定位标记，中文不受影响，ASCII 场景下替换前后下标一一对应
        String lower = modelOutput.toLowerCase();
        int finalAnswerIndex = lower.indexOf(FINAL_ANSWER_MARKER);
        int actionIndex = lower.indexOf(ACTION_MARKER);

        // Final Answer 优先：模型偶尔会既写 Action 又写 Final Answer，以"已经能回答"为准，
        // 避免在明明有答案的情况下又去执行一次多余的工具。
        if (finalAnswerIndex >= 0) {
            String thought = extractThought(modelOutput, lower, finalAnswerIndex);
            String answer = modelOutput.substring(finalAnswerIndex + FINAL_ANSWER_MARKER.length()).trim();
            if (answer.isEmpty()) {
                return ParsedAction.invalid(thought, "写了 Final Answer 但后面是空的");
            }
            return ParsedAction.finalAnswer(thought, stripCodeFence(answer));
        }

        if (actionIndex < 0) {
            return ParsedAction.invalid(null,
                    "输出里既没有 \"" + ACTION_MARKER + "\" 也没有 \"" + FINAL_ANSWER_MARKER + "\"");
        }

        String thought = extractThought(modelOutput, lower, actionIndex);
        int actionLineEnd = lineEnd(modelOutput, actionIndex);
        String toolName = modelOutput.substring(actionIndex + ACTION_MARKER.length(), actionLineEnd).trim();
        if (toolName.isEmpty()) {
            return ParsedAction.invalid(thought, "写了 Action 但没写工具名");
        }

        int actionInputIndex = lower.indexOf(ACTION_INPUT_MARKER, actionLineEnd);
        if (actionInputIndex < 0) {
            return ParsedAction.invalid(thought,
                    "调用了工具 \"" + toolName + "\" 但没有给出 \"" + ACTION_INPUT_MARKER + "\"");
        }

        String rawArguments = modelOutput.substring(actionInputIndex + ACTION_INPUT_MARKER.length()).trim();
        if (rawArguments.isEmpty()) {
            return ParsedAction.invalid(thought, "Action Input 是空的，请给出一个 JSON 对象");
        }

        Map<String, Object> arguments;
        try {
            arguments = objectMapper.readValue(
                    stripCodeFence(rawArguments), new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (Exception e) {
            return ParsedAction.invalid(thought,
                    "Action Input 不是合法的 JSON 对象（" + e.getMessage() + "），原文：" + abbreviate(rawArguments));
        }

        return ParsedAction.toolCall(thought, toolName, arguments);
    }

    /** 取 "Thought:" 到下一个标记之间的文本；模型没写 Thought 时返回 null，而不是编一个空串。 */
    private static String extractThought(String original, String lower, int nextMarkerIndex) {
        int thoughtIndex = lower.indexOf(THOUGHT_MARKER);
        if (thoughtIndex < 0 || thoughtIndex > nextMarkerIndex) {
            return null;
        }
        String thought = original.substring(thoughtIndex + THOUGHT_MARKER.length(), nextMarkerIndex).trim();
        return thought.isEmpty() ? null : thought;
    }

    private static int lineEnd(String text, int from) {
        int newline = text.indexOf('\n', from);
        return newline < 0 ? text.length() : newline;
    }

    /**
     * 去掉模型爱加的 markdown 代码围栏。DeepSeek 这类模型即使明确要求"只输出 JSON"，
     * 也常会写成 ```json ... ```，这属于模型的行为特性，解析器兜住比反复调提示词更划算。
     */
    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed.replace("```", "").trim();
        }
        String body = trimmed.substring(firstLineEnd + 1);
        int closingFence = body.lastIndexOf("```");
        if (closingFence >= 0) {
            body = body.substring(0, closingFence);
        }
        return body.trim();
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
