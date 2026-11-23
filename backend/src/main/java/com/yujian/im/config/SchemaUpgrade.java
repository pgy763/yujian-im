package com.yujian.im.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时的"增量表结构升级"。
 *
 * <p><b>为什么需要这个类：</b>{@code schema.sql} 里全是
 * {@code CREATE TABLE IF NOT EXISTS} —— 表不存在时它会建，但表<b>已经存在</b>时
 * 它什么都不做，新加的列（{@code revoked}、{@code left_at}）永远不会落到老库上。
 * 于是我本地跑得好好的，别人拿旧库启动就会报
 * {@code Unknown column 'revoked' in 'field list'}。
 *
 * <p><b>为什么不用 Flyway/Liquibase：</b>它们当然更正规，但要引入依赖、
 * 维护 {@code V1__xxx.sql} 命名、还要处理 {@code flyway_schema_history} 表。
 * 这个项目只需要"加两列"这一件事，用 {@code information_schema} 判断一次
 * 再决定 ALTER 不 ALTER 就够了，20 行代码解决的问题不值得引一整套框架。
 * 如果以后迁移变成"改字段类型 + 回填数据 + 分库分表"，就该换成 Flyway。
 *
 * <p><b>幂等性：</b>每次启动都查 {@code information_schema.columns}，
 * 列已存在就跳过。所以重复启动 N 次和执行 1 次的效果完全一样，
 * 不会出现"第二次启动报 Duplicate column name"。
 *
 * <p>{@code @Order(1)} 让它排在其他 {@code ApplicationRunner} 前面执行，
 * 保证 Netty 开始收包时表结构已经是新的。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaUpgrade implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    /** 每一项是 {表名, 列名, 列定义}。加新列就往这里追加一行，不用改别的地方。 */
    private static final String[][] COLUMNS = {
            {"im_message", "revoked", "TINYINT NOT NULL DEFAULT 0 COMMENT '1=已撤回'"},
            {"im_message", "revoked_at", "DATETIME(3) DEFAULT NULL COMMENT '撤回时间'"},
            {"im_conversation_member", "left_at", "DATETIME DEFAULT NULL COMMENT '非空表示已退群'"},
    };

    @Override
    public void run(ApplicationArguments args) {
        for (String[] col : COLUMNS) {
            String table = col[0];
            String name = col[1];
            String definition = col[2];
            try {
                if (exists(table, name)) {
                    continue;
                }
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + definition);
                log.info("表结构升级：{}.{} 已添加", table, name);
            } catch (Exception e) {
                // 升级失败不能静默——后面每条 SQL 都会因为缺列而报错，早暴露比晚暴露好。
                // 但也不能直接抛出去让应用起不来：表可能还没建（首次启动 schema.sql 之后会建），
                // 所以这里只记 error，让健康检查把问题暴露出来。
                log.error("表结构升级失败：{}.{} —— {}", table, name, e.getMessage());
            }
        }
    }

    private boolean exists(String table, String column) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return n != null && n > 0;
    }
}
