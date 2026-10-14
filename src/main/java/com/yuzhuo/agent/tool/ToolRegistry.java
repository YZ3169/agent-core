package com.yuzhuo.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心。
 *
 * <p>Spring 会把容器里所有 {@link AgentTool} 实现注入到构造器里，这里只做三件事：
 * 按名字建索引、渲染工具清单进提示词、按名字执行。
 *
 * <p>刻意不做的：不做"模型直接调用工具"的自动编排。编排逻辑在 ReActAgent 里手写。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> discoveredTools) {
        // 按名字排序存 LinkedHashMap：让提示词里的工具清单每次顺序一致。
        // 顺序不稳定会让同一问题的提示词发生无谓变化，影响可复现性（也更贵）。
        Map<String, AgentTool> sorted = new LinkedHashMap<>();
        discoveredTools.stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(tool -> {
                    if (sorted.put(tool.name(), tool) != null) {
                        throw new IllegalStateException("工具名重复: " + tool.name() + "，工具名必须全局唯一");
                    }
                });
        this.tools = Collections.unmodifiableMap(sorted);
    }

    /** 注册在案的全部工具，顺序稳定。 */
    public List<AgentTool> all() {
        return List.copyOf(tools.values());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 按名字执行工具。
     *
     * <p>工具不存在时抛异常而不是返回 null：这样 ReActAgent 只有一条"异常 → 回灌 Observation"
     * 的处理路径，不用为"未知工具"单独写一个分支。
     */
    public String execute(String name, Map<String, Object> arguments) throws Exception {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException(
                    "未知工具 \"" + name + "\"。可用工具: " + String.join(", ", tools.keySet()));
        }
        return tool.execute(arguments);
    }
}
