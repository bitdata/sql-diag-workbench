package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.sql.tsql.TsqlQueryLegality;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlSelect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TsqlQueryLegalityTest {
    private static final String PAGED = "SELECT id FROM t ORDER BY id OFFSET 1 ROWS FETCH NEXT 1 ROWS ONLY";

    @Test
    void bothPaginationDirectionsAreCheckedAcrossEveryNestedQueryScope() throws Exception {
        for (String sql : List.of(PAGED, "WITH c AS (" + PAGED + ") SELECT * FROM c",
                "SELECT * FROM (" + PAGED + ") q", "SELECT * FROM t WHERE EXISTS (" + PAGED + ")")) {
            assertNull(TsqlQueryLegality.rejectionReason(StructuralSqlCodec.parse(sql, "sqlserver")), sql);
            assertEquals("RETAINED_OFFSET_FETCH_REQUIRES_ORDER_BY", TsqlQueryLegality.rejectionReason(
                StructuralSqlCodec.parse(sql.replace(" ORDER BY id", ""), "sqlserver")), sql);
            String noPagination = sql.replace(" OFFSET 1 ROWS FETCH NEXT 1 ROWS ONLY", "");
            assertEquals(sql.equals(PAGED) ? null : "NESTED_ORDER_BY_REQUIRES_LIMITER",
                TsqlQueryLegality.rejectionReason(StructuralSqlCodec.parse(noPagination, "sqlserver")), sql);
        }
    }

    @Test
    void topAndOffsetOnlyAreSupportedLimitersAndTopDoesNotRequireOrdering() throws Exception {
        for (String sql : List.of("SELECT TOP (1) id FROM t", "SELECT TOP (1) id FROM t ORDER BY id",
                "WITH c AS (SELECT TOP (1) id FROM t ORDER BY id) SELECT * FROM c",
                "SELECT * FROM (SELECT id FROM t ORDER BY id OFFSET 1 ROWS) q")) {
            assertNull(TsqlQueryLegality.rejectionReason(StructuralSqlCodec.parse(sql, "sqlserver")), sql);
        }
        var select = (SqlSelect) StructuralSqlCodec.parse("SELECT id FROM t", "sqlserver");
        select.setOffset(SqlLiteral.createExactNumeric("1", select.getParserPosition()));
        assertEquals("RETAINED_OFFSET_FETCH_REQUIRES_ORDER_BY", TsqlQueryLegality.rejectionReason(select));
    }
}
