package com.yuzhuo.agent.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全策略必须能脱离数据库单独验证 —— 否则"我做了 SQL 校验"这句话没法自证。
 */
class SqlGuardTest {

    private static final int MAX_ROWS = 50;

    @Test
    @DisplayName("合法 SELECT 通过，并返回规范化后的 SQL")
    void acceptsPlainSelect() {
        String sql = SqlGuard.validate("SELECT region, amount FROM sales_order LIMIT 10", MAX_ROWS);
        assertThat(sql).isEqualTo("SELECT region, amount FROM sales_order LIMIT 10");
    }

    @Test
    @DisplayName("结尾的分号被去掉后仍然放行（模型很爱加分号）")
    void toleratesTrailingSemicolon() {
        assertThat(SqlGuard.validate("SELECT 1 FROM sales_order LIMIT 1;", MAX_ROWS))
                .isEqualTo("SELECT 1 FROM sales_order LIMIT 1");
    }

    // --- 必须拒绝的输入 ---------------------------------------------------

    @ParameterizedTest
    @DisplayName("DDL / DML 一律拒绝")
    @ValueSource(strings = {
            "DROP TABLE sales_order",
            "DELETE FROM sales_order",
            "UPDATE sales_order SET amount = 0",
            "INSERT INTO sales_order (id) VALUES (99)",
            "TRUNCATE TABLE sales_order",
            "ALTER TABLE sales_order ADD COLUMN note VARCHAR(10)",
            "CREATE TABLE evil (id INT)",
            "GRANT ALL ON sales_order TO sa"
    })
    void rejectsWriteStatements(String sql) {
        assertThatThrownBy(() -> SqlGuard.validate(sql, MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    @DisplayName("没有 LIMIT 一律拒绝：这是行数上限的第一道闸")
    void rejectsSelectWithoutLimit() {
        assertThatThrownBy(() -> SqlGuard.validate("SELECT * FROM sales_order", MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class)
                .hasMessageContaining("必须显式写出 LIMIT");
    }

    @Test
    @DisplayName("多语句拼接：拒绝")
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> SqlGuard.validate(
                "SELECT * FROM sales_order LIMIT 1; DROP TABLE sales_order", MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class)
                .hasMessageContaining("多条语句");
    }

    @Test
    @DisplayName("用注释把危险语句藏起来也要拦住")
    void rejectsCommentSmuggling() {
        // 注释被剥掉后剩下 "SELECT 1 FROM sales_order LIMIT 1 ; DROP TABLE sales_order"
        assertThatThrownBy(() -> SqlGuard.validate(
                "SELECT 1 FROM sales_order LIMIT 1 -- 无害的注释\n; DROP TABLE sales_order", MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    @DisplayName("以 SELECT 开头但内嵌写操作（子查询/CTE 伪装）也要拦住")
    void rejectsWriteHiddenInsideSelectShapedText() {
        assertThatThrownBy(() -> SqlGuard.validate(
                "SELECT * FROM (MERGE INTO sales_order KEY(id) VALUES (1)) LIMIT 1", MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class)
                .hasMessageContaining("MERGE");
    }

    @Test
    @DisplayName("SELECT ... INTO 会写出新表，同样拒绝")
    void rejectsSelectInto() {
        assertThatThrownBy(() -> SqlGuard.validate(
                "SELECT * INTO backup_table FROM sales_order LIMIT 5", MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    @DisplayName("空 SQL")
    void rejectsBlank() {
        assertThatThrownBy(() -> SqlGuard.validate("   ", MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
        assertThatThrownBy(() -> SqlGuard.validate(null, MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    // --- 不能误伤的输入 ---------------------------------------------------

    @Test
    @DisplayName("字符串字面量里的关键字不算违规")
    void doesNotFlagKeywordsInsideStringLiterals() {
        assertThatCode(() -> SqlGuard.validate(
                "SELECT 'drop table' AS note FROM sales_order LIMIT 1", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("含关键字的列名不算违规（created_at 里没有独立的 create）")
    void doesNotFlagKeywordsAsPartOfIdentifiers() {
        assertThatCode(() -> SqlGuard.validate(
                "SELECT created_at, updated_by FROM sales_order LIMIT 1", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("大小写不敏感")
    void isCaseInsensitive() {
        assertThatCode(() -> SqlGuard.validate("select * from sales_order limit 1", MAX_ROWS))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SqlGuard.validate("drop table x", MAX_ROWS))
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    @DisplayName("正常注释被剥掉后不影响合法查询")
    void stripsCommentsFromValidQuery() {
        String sql = SqlGuard.validate("SELECT /* 取前几行 */ id FROM sales_order LIMIT 3", MAX_ROWS);
        assertThat(sql).doesNotContain("/*").contains("LIMIT 3");
    }
}
