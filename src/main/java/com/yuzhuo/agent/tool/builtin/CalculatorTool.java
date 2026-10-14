package com.yuzhuo.agent.tool.builtin;

import com.yuzhuo.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import static com.yuzhuo.agent.tool.AgentTool.requireString;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 四则运算工具。
 *
 * <p>为什么手写递归下降解析器，而不是用脚本引擎：JDK 17 已经移除了 Nashorn，
 * 引入 Groovy/JEXL 只为了算个加减乘除，等于把一个可以执行任意代码的入口交给模型输出。
 * 手写一个只认数字和四种运算符的解析器，攻击面小得多，也更能讲清楚。
 *
 * <p>用 BigDecimal 而不是 double：{@code 0.1 + 0.2} 用 double 会得到 0.30000000000000004，
 * 一个"计算器"工具返回这种东西是丢人的。
 */
@Component
public class CalculatorTool implements AgentTool {

    private static final MathContext MATH_CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算一个四则运算表达式的数值结果。支持 + - * / % 、括号、小数与一元负号。"
                + "适用于任何需要精确算术的场合，不要心算。";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{\
                "expression":{"type":"string","description":"要计算的表达式，例如 (128000+39800)*0.06"}\
                },"required":["expression"]}""";
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String expression = requireString(arguments, "expression");
        BigDecimal result = new ExpressionParser(expression).parse();
        return expression.trim() + " = " + toPlainString(result);
    }

    /** 去掉多余的小数位：12.500 显示成 12.5，120 显示成 120。 */
    private static String toPlainString(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        // stripTrailingZeros 会把 120 变成 1.2E+2，用 toPlainString 还原成 120
        return stripped.toPlainString();
    }

    /**
     * 递归下降解析器，文法：
     * <pre>
     *   expression := term (('+' | '-') term)*
     *   term       := factor (('*' | '/' | '%') factor)*
     *   factor     := '-' factor | '(' expression ')' | number
     * </pre>
     */
    private static final class ExpressionParser {

        private final String input;
        private int pos;

        ExpressionParser(String input) {
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException("表达式不能为空");
            }
            this.input = input;
        }

        BigDecimal parse() {
            BigDecimal value = expression();
            skipSpaces();
            if (pos < input.length()) {
                throw new IllegalArgumentException("表达式在位置 " + pos + " 处有无法解析的字符: '" + input.charAt(pos) + "'");
            }
            return value;
        }

        private BigDecimal expression() {
            BigDecimal left = term();
            while (true) {
                skipSpaces();
                if (consume('+')) {
                    left = left.add(term(), MATH_CONTEXT);
                } else if (consume('-')) {
                    left = left.subtract(term(), MATH_CONTEXT);
                } else {
                    return left;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal left = factor();
            while (true) {
                skipSpaces();
                if (consume('*')) {
                    left = left.multiply(factor(), MATH_CONTEXT);
                } else if (consume('/')) {
                    left = left.divide(factor(), MATH_CONTEXT);
                } else if (consume('%')) {
                    left = left.remainder(factor(), MATH_CONTEXT);
                } else {
                    return left;
                }
            }
        }

        private BigDecimal factor() {
            skipSpaces();
            if (consume('-')) {
                return factor().negate();
            }
            if (consume('+')) {
                return factor();
            }
            if (consume('(')) {
                BigDecimal value = expression();
                skipSpaces();
                if (!consume(')')) {
                    throw new IllegalArgumentException("括号不匹配：缺少 ')'");
                }
                return value;
            }
            return number();
        }

        private BigDecimal number() {
            skipSpaces();
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("位置 " + pos + " 处期望一个数字，实际是: "
                        + (pos < input.length() ? "'" + input.charAt(pos) + "'" : "表达式结尾"));
            }
            String literal = input.substring(start, pos);
            try {
                return new BigDecimal(literal);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无法识别的数字: " + literal);
            }
        }

        private boolean consume(char expected) {
            skipSpaces();
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
