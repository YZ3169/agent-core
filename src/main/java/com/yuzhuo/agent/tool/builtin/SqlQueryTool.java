package com.yuzhuo.agent.tool.builtin;

import com.yuzhuo.agent.config.AgentProperties;
import com.yuzhuo.agent.tool.AgentTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.yuzhuo.agent.tool.AgentTool.requireString;

/**
 * 只读 SQL 查询工具，跑在内存 H2 的演示库上。
 *
 * <p>三道防线：
 * <ol>
 *   <li>{@link SqlGuard} 在语法层面拦住非 SELECT、多语句、DDL/DML，并强制要求 LIMIT；</li>
 *   <li>{@code setMaxRows} 在 JDBC 层面硬截断行数——即使模型写了 {@code LIMIT 100000}，
 *       也不可能把整张表拖进上下文把 token 烧光；</li>
 *   <li>{@code setQueryTimeout} 防止一条慢查询把单个 step 拖死。</li>
 * </ol>
 */
@Component
public class SqlQueryTool implements AgentTool {

    /** 单个单元格渲染进上下文时的最大长度，防止一个 TEXT 字段吃光预算 */
    private static final int MAX_CELL_LENGTH = 60;

    private final JdbcTemplate jdbcTemplate;
    private final AgentProperties properties;

    public SqlQueryTool(JdbcTemplate jdbcTemplate, AgentProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "sql_query";
    }

    @Override
    public String description() {
        return "对只读演示库执行 SELECT 查询。库中有一张表 sales_order"
                + "(id, region, product, amount, order_date)，记录各区域软件订单。"
                + "只接受单条 SELECT 语句，必须显式写 LIMIT，禁止任何写操作。";
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object","properties":{\
                "sql":{"type":"string","description":"一条 SELECT 语句，必须显式带 LIMIT，例如 SELECT region, SUM(amount) AS total FROM sales_order GROUP BY region LIMIT 10"}\
                },"required":["sql"]}""";
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String rawSql = requireString(arguments, "sql");

        // 先过安全门。被拒绝时抛异常，由 ReActAgent 当作 Observation 回灌 —— 模型看到失败原因后
        // 有机会自己改对，而不是整轮任务直接失败。
        String safeSql = SqlGuard.validate(rawSql, properties.getTools().getSql().getMaxRows());

        int maxRows = properties.getTools().getSql().getMaxRows();
        int timeoutSeconds = properties.getTools().getSql().getQueryTimeoutSeconds();

        List<Map<String, Object>> rows = jdbcTemplate.query(
                connection -> {
                    PreparedStatement statement = connection.prepareStatement(safeSql);
                    statement.setMaxRows(maxRows);
                    statement.setQueryTimeout(timeoutSeconds);
                    return statement;
                },
                (resultSet, rowNumber) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    int columnCount = resultSet.getMetaData().getColumnCount();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(resultSet.getMetaData().getColumnLabel(i), resultSet.getObject(i));
                    }
                    return row;
                });

        return render(rows, maxRows);
    }

    /** 渲染成一段紧凑的表格文本。用文本而不是 JSON：同样的信息，token 更省、模型也更不容易读错。 */
    private String render(List<Map<String, Object>> rows, int maxRows) {
        if (rows.isEmpty()) {
            return "查询成功，返回 0 行。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("查询成功，返回 ").append(rows.size()).append(" 行");
        if (rows.size() >= maxRows) {
            builder.append("（已触及单次上限 ").append(maxRows).append(" 行，结果可能被截断）");
        }
        builder.append("：\n");

        List<String> columns = List.copyOf(rows.get(0).keySet());
        builder.append(String.join(" | ", columns)).append('\n');
        builder.append("-".repeat(Math.min(60, columns.size() * 12))).append('\n');
        for (Map<String, Object> row : rows) {
            builder.append(columns.stream()
                    .map(column -> truncate(String.valueOf(row.get(column))))
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("")).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private static String truncate(String value) {
        if ("null".equals(value)) {
            return "NULL";
        }
        return value.length() <= MAX_CELL_LENGTH ? value : value.substring(0, MAX_CELL_LENGTH) + "…";
    }
}
