package com.yuzhuo.agent.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    @ParameterizedTest
    @DisplayName("四则运算与括号")
    @CsvSource({
            "'1+2*3', 7",
            "'(1+2)*3', 9",
            "'10/4', 2.5",
            "'-5+2', -3",
            "'2*(3+4)-5', 9",
            "'10%3', 1",
            "'(128000+39800)*0.06', 10068",
            "'1.5*2', 3"
    })
    void evaluatesExpressions(String expression, String expected) throws Exception {
        assertThat(tool.execute(Map.of("expression", expression)))
                .endsWith("= " + expected);
    }

    @Test
    @DisplayName("0.1+0.2 必须精确等于 0.3（这就是用 BigDecimal 而不是 double 的原因）")
    void avoidsFloatingPointNoise() throws Exception {
        assertThat(tool.execute(Map.of("expression", "0.1+0.2"))).endsWith("= 0.3");
    }

    @Test
    @DisplayName("整数结果不带小数点")
    void rendersIntegersCleanly() throws Exception {
        assertThat(tool.execute(Map.of("expression", "100*2"))).endsWith("= 200");
    }

    @Test
    @DisplayName("除以零：抛出，由循环回灌给模型（而不是返回 Infinity）")
    void divisionByZero() {
        assertThatThrownBy(() -> tool.execute(Map.of("expression", "1/0")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("语法错误：给出出错位置，便于模型自我纠正")
    void syntaxError() {
        assertThatThrownBy(() -> tool.execute(Map.of("expression", "1+")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(Map.of("expression", "(1+2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("括号不匹配");
        assertThatThrownBy(() -> tool.execute(Map.of("expression", "1+abc")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺少参数")
    void missingArgument() {
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填参数");
    }
}
