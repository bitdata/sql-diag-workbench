package com.bitdata.sqlworkbench.sql.diff;

import org.apache.calcite.sql.parser.SqlParseException;
import java.util.List;

/** Shared structural P0/P1 normalization. canonicalSql is opaque, never executable SQL. */
public final class SqlCanonicalizer {
    private SqlCanonicalizer() {}
    public record CanonicalForm(String canonicalSql, List<String> unsupportedNodeTypes) {
        public boolean degraded() { return !unsupportedNodeTypes.isEmpty(); }
    }
    public static String canonicalizeSql(String sql, String dialect) throws SqlParseException {
        CanonicalForm form = canonicalizeSqlWithStatus(sql,dialect);
        if (form.degraded()) throw new IllegalArgumentException("CANONICAL_UNAVAILABLE:" + form.unsupportedNodeTypes());
        return form.canonicalSql();
    }
    public static CanonicalForm canonicalizeSqlWithStatus(String sql, String dialect) throws SqlParseException {
        var node = StructuralSqlCodec.parse(sql,dialect);
        var form = StructuralSqlCodec.fromAst(node,dialect);
        return new CanonicalForm(form.canonical(),form.degraded() ? List.of(form.errorReason()) : List.of());
    }
    public static List<NodeToken> canonicalize(List<NodeToken> sequence) {
        return StructuralSqlCodec.canonicalizeTokens(sequence);
    }
}
