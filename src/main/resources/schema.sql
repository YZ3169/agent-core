-- sql_query 工具的演示库结构。
-- 用 DROP ... IF EXISTS 是为了让 schema.sql 幂等：Boot 在每次启动时都会执行它。
DROP TABLE IF EXISTS sales_order;
CREATE TABLE sales_order
(
    id          BIGINT PRIMARY KEY,
    region      VARCHAR(32)  NOT NULL,
    product     VARCHAR(64)  NOT NULL,
    amount      DECIMAL(12, 2) NOT NULL,
    order_date  DATE         NOT NULL
);
