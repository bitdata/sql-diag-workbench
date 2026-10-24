package com.bitdata.sqlworkbench.diagnostics.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.PreparedStatement;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TraceQueryBuilderTest {

    private final TraceQueryBuilder builder = new TraceQueryBuilder();

    @Test
    void buildExistsSql_singleKey() {
        String sql = builder.buildExistsSql(
            "SELECT * FROM orders",
            List.of(new KeyValue("order_id", 12345))
        );
        assertEquals(
            "SELECT EXISTS(SELECT 1 FROM (SELECT * FROM orders) AS __trace_sub WHERE order_id = ?) AS has_rows",
            sql
        );
    }

    @Test
    void buildExistsSql_multipleKeys() {
        String sql = builder.buildExistsSql(
            "SELECT * FROM orders o JOIN items i ON o.id = i.order_id",
            List.of(new KeyValue("order_id", 12345), new KeyValue("status", "active"))
        );
        assertEquals(
            "SELECT EXISTS(SELECT 1 FROM (SELECT * FROM orders o JOIN items i ON o.id = i.order_id) AS __trace_sub WHERE order_id = ? AND status = ?) AS has_rows",
            sql
        );
    }

    @Test
    void buildExistsSql_nullValue_usesIsNull() {
        String sql = builder.buildExistsSql(
            "SELECT * FROM orders",
            List.of(new KeyValue("deleted_at", null))
        );
        assertEquals(
            "SELECT EXISTS(SELECT 1 FROM (SELECT * FROM orders) AS __trace_sub WHERE deleted_at IS NULL) AS has_rows",
            sql
        );
    }

    @Test
    void buildExistsSql_mixedNullAndNonNull() {
        String sql = builder.buildExistsSql(
            "SELECT * FROM orders",
            List.of(new KeyValue("order_id", 12345), new KeyValue("deleted_at", null), new KeyValue("status", "active"))
        );
        assertEquals(
            "SELECT EXISTS(SELECT 1 FROM (SELECT * FROM orders) AS __trace_sub WHERE order_id = ? AND deleted_at IS NULL AND status = ?) AS has_rows",
            sql
        );
    }

    @Test
    void buildExistsSql_qualifiedColumn_stripsAliasToBareColumn() {
        // WHERE targets __trace_sub's bare output column names; an alias-qualified
        // key (o.order_id) must be reduced to its last segment or the outer
        // derived-table WHERE always fails to resolve.
        String sql = builder.buildExistsSql(
            "SELECT * FROM orders o",
            List.of(new KeyValue("o.order_id", 12345))
        );
        assertEquals(
            "SELECT EXISTS(SELECT 1 FROM (SELECT * FROM orders o) AS __trace_sub WHERE order_id = ?) AS has_rows",
            sql
        );
    }

    @Test
    void countParameterPlaceholders_nullValuesExcluded() {
        List<KeyValue> keys = List.of(
            new KeyValue("order_id", 12345),
            new KeyValue("deleted_at", null),
            new KeyValue("status", "active")
        );
        assertEquals(2, builder.countParameters(keys));
    }

    @Test
    void countParameterPlaceholders_allNull() {
        List<KeyValue> keys = List.of(new KeyValue("a", null), new KeyValue("b", null));
        assertEquals(0, builder.countParameters(keys));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_id", "o.order_id", "_col", "Col123", "a.b.c"})
    void validateColumnName_valid(String column) {
        assertDoesNotThrow(() -> TraceQueryBuilder.validateColumnName(column));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order id", "id; DROP", "1col", "id--", "id/*", "", "  "})
    void validateColumnName_invalid(String column) {
        assertThrows(IllegalArgumentException.class,
            () -> TraceQueryBuilder.validateColumnName(column));
    }

    @Test
    void bindParameters_integerKey() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        List<KeyValue> keys = List.of(new KeyValue("id", 42));
        builder.bindParameters(stmt, keys);
        verify(stmt).setInt(1, 42);
    }

    @Test
    void bindParameters_longKey() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        List<KeyValue> keys = List.of(new KeyValue("id", 100000000000L));
        builder.bindParameters(stmt, keys);
        verify(stmt).setLong(1, 100000000000L);
    }

    @Test
    void bindParameters_stringKey() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        List<KeyValue> keys = List.of(new KeyValue("name", "alice"));
        builder.bindParameters(stmt, keys);
        verify(stmt).setString(1, "alice");
    }

    @Test
    void bindParameters_skipsNullValues() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        List<KeyValue> keys = List.of(
            new KeyValue("a", null),
            new KeyValue("b", 42)
        );
        builder.bindParameters(stmt, keys);
        verify(stmt).setInt(1, 42); // b binds at index 1 (a was skipped)
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void bindParameters_booleanKey() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        List<KeyValue> keys = List.of(new KeyValue("active", true));
        builder.bindParameters(stmt, keys);
        verify(stmt).setBoolean(1, true);
    }

    @Test
    void bindParameters_doubleKey() throws Exception {
        PreparedStatement stmt = mock(PreparedStatement.class);
        List<KeyValue> keys = List.of(new KeyValue("price", 19.99));
        builder.bindParameters(stmt, keys);
        verify(stmt).setDouble(1, 19.99);
    }
}
