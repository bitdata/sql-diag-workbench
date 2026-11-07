package com.bitdata.sqlworkbench.diagnostics.mapping;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MappingSqlIndexTest {
    private final SqlDialectResolver dialects = new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());

    @Test
    void groupAndWindowSubqueriesRetainRelationOccurrences() throws Exception {
        for (String dialect : List.of("postgresql", "mysql")) {
            for (String sql : List.of(
                    "SELECT count(*) FROM orders GROUP BY (SELECT max(id) FROM customers)",
                    "SELECT row_number() OVER w FROM orders WINDOW w AS (ORDER BY (SELECT max(id) FROM customers))")) {
                var index = MappingSqlIndex.parse(sql, dialect, dialects);
                var customers = index.candidates("customers");
                assertEquals(1, customers.size(), dialect + ": " + sql);
                var span = customers.get(0).span();
                assertEquals("customers", sql.substring(span.startOffset(), span.endOffset()));
                assertEquals(1, index.candidates("orders").size(), "FROM must only be visited once");
            }
        }
    }

    @Test
    void nonRelationIdentifiersNeverBecomeRelationCandidates() throws Exception {
        String sql = "SELECT orders, count(*) FROM preorders GROUP BY orders WINDOW orders AS (ORDER BY orders) ORDER BY orders";
        for (String dialect : List.of("postgresql", "mysql")) {
            var index = MappingSqlIndex.parse(sql, dialect, dialects);
            assertTrue(index.candidates("orders").isEmpty(), dialect);
            assertEquals(1, index.candidates("preorders").size(), dialect);
        }
    }
}
