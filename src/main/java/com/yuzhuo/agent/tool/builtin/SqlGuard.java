package com.yuzhuo.agent.tool.builtin;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code sql_query} 的准入检查。
 *
 * <p>抽成独立的类是为了能被单独测试：安全策略这种东西，必须能脱离数据库、脱离模型，
 * 用一串输入输出直接验，否则"我做了 SQL 校验"这句话是没法自证的。
 *
 * <p><b>它是什么</b>：一个正则层面的白名单过滤器。挡住的是最现实的那一类问题——
 * 模型（或诱导模型的人）让它 {@code DROP TABLE}、{@code DELETE FROM}、一次塞多条语句。
 *
 * <p><b>它不是什么</b>：不是 SQL 语法分析器，也替代不了数据库侧的权限。
 * 真正上线应该做的是给这个连接一个只有 SELECT 权限的数据库账号——那才是可靠边界。
 * 这里的过滤器是"纵深防御"里靠前的那一层，不是唯一一层。README 里如实写了这一点。
 */
public final class SqlGuard {

    /** 出现即拒绝的关键字。用 \b 做词边界，`created_at` 这类含关键字的列名不会被误杀。 */
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|merge"
                    + "|call|exec|execute|attach|detach|shutdown|rename|replace|into)\\b");

    private static final Pattern SELECT_PREFIX = Pattern.compile("(?i)^select\\b");
    private static final Pattern HAS_LIMIT = Pattern.compile("(?i)\\blimit\\b");
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 单引号字符串字面量。扫描关键字前要先把它抹掉，否则 '张三 drop 了表' 会被误判。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    private SqlGuard() {
    }

    /**
     * 校验并规范化一条只读查询。
     *
     * @param rawSql  模型给出的原始 SQL
     * @param maxRows 行数上限，只用于生成提示信息（实际截断由 Statement.setMaxRows 完成）
     * @return 去掉注释和结尾分号后的、可以安全执行的 SQL
     * @throws SqlRejectedException 任何一条规则不通过
     */
    public static String validate(String rawSql, int maxRows) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new SqlRejectedException("SQL 不能为空");
        }

        // 先剥注释：注释是绕过关键字检查最经典的招数（SELECT/**/1 或 -- 后面的 drop）
        String sql = BLOCK_COMMENT.matcher(LINE_COMMENT.matcher(rawSql).replaceAll(" ")).replaceAll(" ");
        sql = sql.trim();

        // 去掉结尾的分号后，如果还剩下分号，说明是想一次执行多条语句
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new SqlRejectedException("一条语句只能包含一个查询：检测到分号，拒绝执行多条语句");
        }

        if (!SELECT_PREFIX.matcher(sql).find()) {
            throw new SqlRejectedException("只允许 SELECT 查询，检测到的语句不是以 SELECT 开头");
        }

        // 关键字扫描在"抹掉字符串字面量"的副本上做，避免把数据内容里的词当成语法
        String forKeywordScan = STRING_LITERAL.matcher(sql).replaceAll("''");
        Matcher forbidden = FORBIDDEN_KEYWORDS.matcher(forKeywordScan);
        if (forbidden.find()) {
            String keyword = forbidden.group().toUpperCase(Locale.ROOT);
            throw new SqlRejectedException("检测到被禁止的关键字 " + keyword + "，本工具只接受只读 SELECT 查询");
        }

        if (!HAS_LIMIT.matcher(sql).find()) {
            throw new SqlRejectedException(
                    "查询必须显式写出 LIMIT，本次单次返回上限为 " + maxRows + " 行。例如：SELECT ... LIMIT 10");
        }

        return sql;
    }

    /** 校验不通过时抛出。继承 IllegalArgumentException，便于上层按"参数非法"统一处理。 */
    public static class SqlRejectedException extends IllegalArgumentException {
        public SqlRejectedException(String message) {
            super(message);
        }
    }
}
