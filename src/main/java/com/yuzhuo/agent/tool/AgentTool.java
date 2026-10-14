package com.yuzhuo.agent.tool;

import java.util.Map;

/**
 * 一个 Agent 可以调用的工具。
 *
 * <p>为什么用接口 + Spring Bean 而不是 if-else 分支：新增工具时只需要新建一个
 * {@code @Component} 实现类，{@link ToolRegistry} 会自动收集，提示词里的工具清单也跟着更新——
 * 扩展点只有一个，不需要改任何已有代码。
 *
 * <p>{@link #execute} 允许抛异常。这是有意为之：工具失败（SQL 被安全策略拒绝、文件不存在）
 * 在 Agent 语境下是"一次可用的观察结果"，不是"整轮失败"。由调用方（ReActAgent）
 * 决定把异常信息回灌给模型，而不是让它冒泡成 500。
 */
public interface AgentTool {

    /** 工具名。模型在 {@code Action:} 行里写的就是它，必须全局唯一。 */
    String name();

    /** 给模型看的一句话说明：它做什么、什么时候该用、有什么限制。 */
    String description();

    /**
     * 参数的 JSON-Schema 文本，会被原样渲染进提示词。
     *
     * <p>这里返回 String 而不是对象，是为了让"提示词里长什么样"在源码里一眼可读，
     * 不需要在脑子里过一遍序列化。
     */
    String parametersSchema();

    /**
     * 执行工具。
     *
     * @param arguments 模型给出的参数，已由 ActionParser 解析成 Map
     * @return 作为 Observation 回灌给模型的文本。应当是稳定、简短、可读的。
     * @throws Exception 参数非法、安全拒绝、执行失败等，由调用方决定如何处理
     */
    String execute(Map<String, Object> arguments) throws Exception;

    /**
     * 取必填字符串参数的公共逻辑。工具里到处都要写，放接口上避免重复。
     */
    static String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + key);
        }
        return value.toString();
    }
}
