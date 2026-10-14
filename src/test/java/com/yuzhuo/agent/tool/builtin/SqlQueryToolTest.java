package com.yuzhuo.agent.tool.builtin;

import com.yuzhuo.agent.config.AgentProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * sql_query 的端到端行为：真的连一个内存 H2，真的执行 SQL。
 *
 * <p>用具名内存库而不是 Spring 上下文：测试跑得快、且能确定性地控制表结构。
 * H2 是进程内的，整个测试不碰网络。
 */
class SqlQueryToolTest {

    private SqlQueryTool tool;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sqlguard-test;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS sales_order");
        jdbcTemplate.execute("CREATE TABLE sales_order (id BIGINT PRIMARY KEY, region VARCHAR(32), amount DECIMAL(12,2))");
        jdbcTemplate.execute("INSERT INTO sales_order VALUES (1, '华东', 128000.00), (2, '华北', 39800.00), (3, '华东', 25000.00)");

        AgentProperties properties = new AgentProperties();
        properties.getTools().getSql().setMaxRows(2);
        tool = new SqlQueryTool(jdbcTemplate, properties);
    }

    @Test
    @DisplayName("正常查询：返回行数据，并渲染成可读表格")
    void executesSelect() throws Exception {
        String observation = tool.execute(Map.of("sql", "SELECT region, amount FROM sales_order LIMIT 2"));

        assertThat(observation).contains("返回 2 行");
        // H2 对未加引号的标识符大小写处理随版本/配置而变，这里按大小写不敏感断言列头
        assertThat(observation.toUpperCase(Locale.ROOT)).contains("REGION").contains("AMOUNT");
        assertThat(observation).contains("华东");
    }

    @Test
    @DisplayName("行数上限由 setMaxRows 在 JDBC 层兜底：写 LIMIT 100 也只回灌 2 行")
    void capsRowsEvenWhenModelAsksForMore() throws Exception {
        String observation = tool.execute(Map.of("sql", "SELECT * FROM sales_order LIMIT 100"));

        assertThat(observation).contains("返回 2 行");
        assertThat(observation).contains("已触及单次上限 2 行");
    }

    @Test
    @DisplayName("零行结果也要给出明确反馈，而不是一段空白")
    void reportsEmptyResult() throws Exception {
        String observation = tool.execute(Map.of(
                "sql", "SELECT * FROM sales_order WHERE region = '不存在的区域' LIMIT 10"));

        assertThat(observation).contains("返回 0 行");
    }

    @Test
    @DisplayName("DDL 被安全策略拒绝（在碰到数据库之前）")
    void rejectsDdl() {
        assertThatThrownBy(() -> tool.execute(Map.of("sql", "DROP TABLE sales_order")))
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    @DisplayName("不写 LIMIT 被拒绝，错误信息告诉模型该怎么改")
    void rejectsMissingLimit() {
        assertThatThrownBy(() -> tool.execute(Map.of("sql", "SELECT * FROM sales_order")))
                .isInstanceOf(SqlGuard.SqlRejectedException.class)
                .hasMessageContaining("LIMIT");
    }

    @Test
    @DisplayName("缺少 sql 参数")
    void rejectsMissingArgument() {
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填参数");
    }

    @Test
    @DisplayName("SQL 语法错误由数据库抛出，会被上层当作 Observation 回灌")
    void surfacesSyntaxError() {
        assertThatThrownBy(() -> tool.execute(Map.of("sql", "SELECT FROM WHERE LIMIT 1")))
                .isInstanceOf(Exception.class);
    }
}
