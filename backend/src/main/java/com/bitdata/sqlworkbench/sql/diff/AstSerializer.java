package com.bitdata.sqlworkbench.sql.diff;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import java.util.List;

/** Balanced structural tokens. Matching content retains typed names, expressions and grouping. */
public final class AstSerializer {
    private AstSerializer() {}
    public static List<NodeToken> serialize(String sql, String dialect) throws SqlParseException {
        return serializeNode(StructuralSqlCodec.parse(sql,dialect));
    }
    public static List<NodeToken> serializeNode(SqlNode root) {
        var form = StructuralSqlCodec.fromAst(root,"postgresql");
        if (!form.degraded()) return form.tokens();
        // A diagnostic token never establishes an identity; callers must propagate degradation.
        String diagnostic = root == null ? "null" : root.getClass().getSimpleName() + "#" +
            com.bitdata.sqlworkbench.fragment.FragmentIdentity.sha256Hex(root.toSqlString(
                org.apache.calcite.sql.dialect.PostgresqlSqlDialect.DEFAULT).getSql()).substring(0,12);
        return List.of(new NodeToken("UNKNOWN",diagnostic + ":" + form.errorReason(),"root"));
    }
}
