package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.trace.KeyValue;
import com.bitdata.sqlworkbench.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.sql.*;

class PhysicalSourcePlanServiceTest {
    private final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    @Test void directAliasedKeyRetainsActualSourceSpellingAndRemovesOnlyOuterPage() throws Exception {
        for(String dialect:List.of("postgresql","mysql")) {
            var s=PhysicalSourcePlanService.shape("SELECT i.parent AS invoice, SUM(i.base_amount) AS total FROM sales_invoice_item i WHERE i.parent='INV-3' GROUP BY i.parent ORDER BY invoice LIMIT 4 OFFSET 2",dialect,resolver);
            assertEquals("sales_invoice_item",s.table());assertTrue(s.accepts("invoice","parent",dialect));
            assertFalse(s.accepts("total","base_amount",dialect));
            assertTrue(s.metadataSql().contains("WHERE"));assertTrue(s.metadataSql().contains("GROUP BY"));
            assertFalse(s.metadataSql().contains("OFFSET 2"));assertFalse(s.metadataSql().contains("source_origin_probe"));
            assertTrue(s.metadataSql().matches("(?s).*(LIMIT 0|FETCH NEXT 0 ROWS ONLY).*"),s.metadataSql());
        }
    }
    @Test void quotedPostgresNamesAreNotFolded() throws Exception {
        var s=PhysicalSourcePlanService.shape("SELECT \"Parent\" AS \"Invoice\" FROM \"Mixed\".\"Items\"","postgresql",resolver);
        assertEquals("Mixed",s.schema());assertEquals("Items",s.table());assertTrue(s.accepts("Invoice","Parent","postgresql"));
        assertFalse(s.accepts("invoice","parent","postgresql"));
    }
    @Test void starOnlyMatchesAnUnchangedLabel() throws Exception {
        var s=PhysicalSourcePlanService.shape("SELECT i.* FROM sales_invoice_item i","mysql",resolver);
        assertTrue(s.accepts("parent","parent","mysql"));assertFalse(s.accepts("invoice","parent","mysql"));
    }
    @Test void expressionAndForeignQualifierCannotCertifyAKey() throws Exception {
        var s=PhysicalSourcePlanService.shape("SELECT CONCAT(parent, 'x') AS invoice, other.parent AS wrong FROM sales_invoice_item","mysql",resolver);
        assertFalse(s.accepts("invoice","parent","mysql"));assertFalse(s.accepts("wrong","parent","mysql"));
    }
    @Test void complexSourcesAreExplicitlyUnverified() {
        for(String sql:List.of("SELECT i.parent FROM sales_invoice_item i JOIN sales_invoice_payment p ON i.parent=p.parent",
                "WITH i AS (SELECT parent FROM sales_invoice_item) SELECT parent FROM i",
                "SELECT parent FROM sales_invoice_item UNION ALL SELECT parent FROM sales_invoice_payment",
                "SELECT parent FROM (SELECT parent FROM sales_invoice_item) i")) {
            var e=assertThrows(PhysicalSourcePlanService.Unverified.class,()->PhysicalSourcePlanService.shape(sql,"mysql",resolver));
            assertEquals("SOURCE_STRUCTURE_UNSUPPORTED",e.code);
        }
    }
    @Test void unregisteredDriverCannotGuessOrigin() throws Exception {
        var e=assertThrows(PhysicalSourcePlanService.Unverified.class,()->PhysicalSourcePlanService.origin(mock(ResultSetMetaData.class),1,"mysql"));
        assertEquals("SOURCE_DRIVER_UNSUPPORTED",e.code);
    }
    @Test void unsupportedStructureAndMutationAreRejectedBeforeOpeningAnyDatabase() throws Exception {
        var connections=mock(ConnectionService.class);
        var config=mock(ConnectionConfig.class);when(config.dialect()).thenReturn("mysql");
        when(connections.getById("c1")).thenReturn(Optional.of(config));
        var service=new PhysicalSourcePlanService(connections,DatabaseStrategyFactory.withBuiltinStrategies(),resolver,new SelectOnlyGuard(resolver));
        var keys=List.of(new KeyValue("parent","INV-3",KeyValue.Type.TEXT));
        for(String sql:List.of("SELECT parent FROM sales_invoice_item; DELETE FROM sales_invoice_item","DELETE FROM sales_invoice_item",
                "SELECT i.parent FROM sales_invoice_item i JOIN sales_invoice_payment p ON i.parent=p.parent")) {
            var plan=service.inspect(new PhysicalSourcePlanService.Request("c1",sql,keys));
            assertFalse(plan.verified());assertEquals("UNKNOWN",plan.status());assertNotNull(plan.errorReason());assertNull(plan.sourceSql());
        }
        verify(connections,never()).openJdbcConnection(anyString());
    }
    @Test void missingConnectionAndEmptyKeysNeverBecomeAnAbsence() {
        var connections=mock(ConnectionService.class);when(connections.getById("missing")).thenReturn(Optional.empty());
        var service=new PhysicalSourcePlanService(connections,DatabaseStrategyFactory.withBuiltinStrategies(),resolver,new SelectOnlyGuard(resolver));
        assertEquals("SOURCE_CONNECTION_UNAVAILABLE",service.inspect(new PhysicalSourcePlanService.Request("missing","SELECT 1",List.of(new KeyValue("id",1)))).errorReason());
        assertEquals("SOURCE_INPUT_INVALID",service.inspect(new PhysicalSourcePlanService.Request("c1","SELECT 1",List.of())).errorReason());
    }
    @Test void identifierQuotingCannotEscapeTheDeclaredRelation() {
        assertEquals("\"a\"\"b\"",PhysicalSourcePlanService.quote("a\"b",'"'));
        assertEquals("`a``b`",PhysicalSourcePlanService.quote("a`b",'`'));
    }
    @Test void nullTypedValueSurvivesTheApplicationsNonNullSerializationPolicy() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        var key=new PhysicalSourcePlanService.BoundKey("note",null,KeyValue.Type.NULL);
        var wire=json.readTree(json.writeValueAsString(key));
        assertTrue(wire.has("value"));assertTrue(wire.get("value").isNull());assertEquals("NULL",wire.get("type").asText());
    }
}
