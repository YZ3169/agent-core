package com.yuzhuo.agent.tool;

import com.yuzhuo.agent.agent.prompt.SystemPromptBuilder;
import com.yuzhuo.agent.config.AgentProperties;
import com.yuzhuo.agent.support.ThrowingTool;
import com.yuzhuo.agent.tool.builtin.CalculatorTool;
import com.yuzhuo.agent.tool.builtin.FileReadTool;
import com.yuzhuo.agent.tool.builtin.SqlQueryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private final CalculatorTool calculator = new CalculatorTool();
    private final ThrowingTool boom = new ThrowingTool("boom");

    @Test
    @DisplayName("自动收集所有 AgentTool 实现，并按名字排序（让提示词稳定可复现）")
    void collectsAndSortsTools() {
        ToolRegistry registry = new ToolRegistry(List.of(calculator, boom));

        assertThat(registry.all()).extracting(AgentTool::name).containsExactly("boom", "calculator");
        assertThat(registry.contains("calculator")).isTrue();
        assertThat(registry.contains("nope")).isFalse();
    }

    @Test
    @DisplayName("工具名重复时启动即失败，而不是悄悄覆盖")
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(calculator, new CalculatorTool())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具名重复");
    }

    @Test
    @DisplayName("按名字执行工具")
    void executesByName() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(calculator));

        assertThat(registry.execute("calculator", Map.of("expression", "2*21"))).endsWith("= 42");
    }

    @Test
    @DisplayName("未知工具：抛出的错误信息里带上可用工具清单，便于模型自我纠正")
    void unknownToolListsAvailableNames() {
        ToolRegistry registry = new ToolRegistry(List.of(calculator));

        assertThatThrownBy(() -> registry.execute("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知工具")
                .hasMessageContaining("calculator");
    }

    @Test
    @DisplayName("工具抛出的异常原样冒泡，由 ReActAgent 决定如何回灌")
    void propagatesToolFailure() {
        ToolRegistry registry = new ToolRegistry(List.of(boom));

        assertThatThrownBy(() -> registry.execute("boom", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("磁盘炸了");
    }

    @Test
    @DisplayName("系统提示词把每个工具的清单都渲染进去")
    void rendersToolCatalogIntoPrompt() {
        ToolRegistry registry = new ToolRegistry(List.of(calculator, boom));
        String prompt = new SystemPromptBuilder(registry).build();

        assertThat(prompt)
                .contains("### calculator")
                .contains("### boom")
                .contains(calculator.description())
                .contains(calculator.parametersSchema())
                .contains("Action Input:")
                .contains("Final Answer:")
                .contains("Observation:");
    }

    @Test
    @DisplayName("内置工具的自描述都完整：名字/说明/JSON-Schema 齐备，且 Schema 是 JSON 对象")
    void builtInToolsExposeCompleteMetadata() {
        // SqlQueryTool 的自描述与数据库无关，构造时给个空 JdbcTemplate 即可（不会真的连库）
        AgentProperties properties = new AgentProperties();
        List<AgentTool> builtIn = List.of(
                calculator,
                new SqlQueryTool(new JdbcTemplate(), properties));

        for (AgentTool tool : builtIn) {
            assertThat(tool.name()).isNotBlank();
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.parametersSchema()).startsWith("{").endsWith("}");
        }
        assertThat(builtIn).extracting(AgentTool::name).containsExactly("calculator", "sql_query");

        // FileReadTool 的构造会创建白名单根目录，测试里指到系统临时目录，避免污染工作区
        AgentProperties fileProperties = new AgentProperties();
        fileProperties.getTools().getFileRead().setRoot(System.getProperty("java.io.tmpdir"));
        assertThat(new FileReadTool(fileProperties).name()).isEqualTo("file_read");
    }
}
