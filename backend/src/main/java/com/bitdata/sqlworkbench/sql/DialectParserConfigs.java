package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * 供 static 工具类使用的 dialect → {@link SqlParser.Config} 解析（P2-12d）。
 *
 * <p>{@code sql/diff} 下的 canonicalize / normalize 链路全是 static 工具类，
 * 拿不到 Spring 注入的 {@link SqlDialectResolver}。本类持有一个进程级
 * resolver，其方言属性仍来自 {@code DatabaseStrategy.getDialectProfile()}
 * ——不新增第二份方言表（见 CLAUDE.md 单一来源约束）。
 *
 * <p>装配源统一走 {@link DatabaseStrategyFactory#withBuiltinStrategies()}
 * （来自 {@link com.bitdata.sqlworkbench.connection.BuiltInStrategies#ALL}），
 * 与 Spring 自动收集的 strategy bean 列表同源；新增方言只需改
 * {@link com.bitdata.sqlworkbench.connection.BuiltInStrategies#ALL}。
 *
 * <p>Spring 上下文内的调用方应继续注入 {@link SqlDialectResolver}，不要用本类。
 */
public final class DialectParserConfigs {

    private static final SqlDialectResolver RESOLVER =
        new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());

    private DialectParserConfigs() {}

    /**
     * 未知或 null 方言回退到 postgresql（与 {@link SqlDialectResolver#getProfile}
     * 一致；该静默回退在 {@link SqlDialectResolver#getProfile} 内已加
     * SLF4J warn 以满足规则 #2 可观测降级要求）。
     */
    public static SqlParser.Config forDialect(String dialect) {
        // Canonical identity retains its historical folding independently of
        // execution AST spelling. Do not use these static diff configs to emit SQL.
        return RESOLVER.parserConfig(dialect)
            .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.TO_UPPER);
    }
}
