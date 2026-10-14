package com.yuzhuo.agent.agent.prompt;

import com.yuzhuo.agent.tool.AgentTool;
import com.yuzhuo.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * 拼装系统提示词：把工具清单渲染进 ReAct 的格式约定里。
 *
 * <p>它是 Agent 与工具之间的"契约文档"，Agent 能不能可靠地工作，一大半取决于这段文字
 * 有没有把格式说死、把工具的边界讲清。所以工具清单不是硬编码的字符串，而是从
 * {@link ToolRegistry} 现取 —— 加一个工具，提示词自动跟着长。
 */
@Component
public class SystemPromptBuilder {

    private final ToolRegistry toolRegistry;

    public SystemPromptBuilder(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public String build() {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                你是一个遵循 ReAct（Reasoning + Acting）范式的智能体。
                解决用户问题的方式是：先推理，需要外部信息时调用工具，拿到观察结果后继续推理，
                直到可以给出最终答案。

                可用工具：
                """);

        for (AgentTool tool : toolRegistry.all()) {
            prompt.append("\n### ").append(tool.name()).append('\n');
            prompt.append("描述：").append(tool.description()).append('\n');
            prompt.append("参数 JSON-Schema：").append(tool.parametersSchema()).append('\n');
        }

        prompt.append("""

                你的每一次回复必须是下面两种格式之一，且只能是一种：

                格式 A —— 需要调用工具：
                Thought: <你的推理，说明为什么需要这个工具>
                Action: <工具名，必须是上面列出的名字之一>
                Action Input: <一个 JSON 对象，键名必须符合该工具的 JSON-Schema>

                格式 B —— 可以给出最终答案了：
                Thought: <你的推理，说明为什么现在可以回答>
                Final Answer: <给用户的最终答案>

                硬性规则：
                1. 每轮只输出一个 Action，不要一次调用多个工具。
                2. Action Input 必须是合法 JSON 对象，不要用 markdown 代码块包裹。
                3. 工具的执行结果会以 "Observation: " 开头的消息发给你，你必须基于它继续推理。
                4. 如果工具返回了错误，不要放弃：读懂错误原因，修正参数或换一个工具再试。
                5. 不要在 Thought 里编造工具的执行结果，一切以 Observation 为准。
                6. 最终答案用中文，直接回答用户的问题，不要复述格式标记。
                """);

        return prompt.toString();
    }
}
