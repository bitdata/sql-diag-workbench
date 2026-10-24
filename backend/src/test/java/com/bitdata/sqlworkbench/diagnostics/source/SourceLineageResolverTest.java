package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceLineageResolverTest {
    private final SqlDialectResolver dialects=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    private SourceLineageResolver resolver(String dialect) {
        return new SourceLineageResolver(dialect,dialects,(name,schema)-> {
            String table=name.get(name.size()-1),ns=name.size()==2?name.get(0):schema==null?"fixture":schema;
            return switch(table) {
                case "items" -> new SourceLineageResolver.Relation(ns,table,List.of("parent","amount","note"),null,Map.of("parent","text:default","amount","numeric:default","note","text:default"));
                case "payments" -> new SourceLineageResolver.Relation(ns,table,List.of("parent","mode"),null,Map.of("parent","text:default","mode","text:default"));
                case "ints" -> new SourceLineageResolver.Relation(ns,table,List.of("n"),null,Map.of("n","int:default"));
                case "nums" -> new SourceLineageResolver.Relation(ns,table,List.of("n"),null,Map.of("n","numeric:default"));
                case "other_collation" -> new SourceLineageResolver.Relation(ns,table,List.of("parent"),null,Map.of("parent","text:other"));
                case "heterogeneous_view" -> new SourceLineageResolver.Relation(ns,table,List.of("n"),"SELECT n FROM ints UNION ALL SELECT n FROM nums");
                case "homogeneous_view" -> new SourceLineageResolver.Relation(ns,table,List.of("n"),"SELECT n FROM ints UNION ALL SELECT n FROM ints");
                case "enums" -> new SourceLineageResolver.Relation(ns,table,List.of("n"),null,Map.of("n","enum('a','b'):utf8mb4:default"));
                case "untyped" -> new SourceLineageResolver.Relation(ns,table,List.of("n"),null);
                case "v" -> new SourceLineageResolver.Relation(ns,table,List.of("invoice"),"SELECT parent FROM items WHERE parent='hidden'");
                case "v2" -> new SourceLineageResolver.Relation(ns,table,List.of("invoice"),"SELECT invoice FROM v");
                case "vpg" -> new SourceLineageResolver.Relation(ns,table,List.of("invoice")," SELECT parent AS invoice FROM items WHERE ((parent)::text = 'hidden'::text);");
                case "loop" -> new SourceLineageResolver.Relation(ns,table,List.of("invoice"),"SELECT invoice FROM loop");
                default -> throw new PhysicalSourcePlanService.Unverified("SOURCE_RELATION_UNAVAILABLE");
            };
        });
    }
    @Test void joinColumnsResolveToTheirOwnRelationsWithoutKeepingJoinFilters() throws Exception {
        for(String d:List.of("postgresql","mysql")) {
            var r=resolver(d).resolve("SELECT i.parent AS invoice,p.mode FROM items i JOIN payments p ON i.parent=p.parent WHERE p.mode='Cash'");
            assertEquals(2,r.leaves().size());assertEquals("items",r.columns().get(0).bindings().get(0).leaf().relation().name());
            assertEquals("payments",r.columns().get(1).bindings().get(0).leaf().relation().name());
            assertEquals("invoice",r.columns().get(0).name());assertTrue(r.columns().get(0).bindings().get(0).expression().toString().contains("parent"));
        }
    }
    @Test void selfJoinOccurrencesAreNeverMergedByPhysicalTableName() throws Exception {
        var r=resolver("mysql").resolve("SELECT a.parent AS lhs,b.parent AS rhs FROM items a JOIN items b ON a.parent=b.parent");
        assertNotEquals(r.columns().get(0).bindings().get(0).leaf().occurrence(),r.columns().get(1).bindings().get(0).leaf().occurrence());
    }
    @Test void cteColumnListsAliasesAndShadowingReachTheUnderlyingColumn() throws Exception {
        for(String d:List.of("postgresql","mysql")) {
            var r=resolver(d).resolve("WITH items(invoice) AS (SELECT parent FROM payments) SELECT invoice FROM items");
            assertEquals("payments",r.leaves().get(0).relation().name());
            assertTrue(r.columns().get(0).bindings().get(0).expression().toString().contains("parent"));
            assertNull(r.columns().get(0).errorReason());
        }
    }
    @Test void repeatedCteUsesHaveDistinctOccurrencesAndEarlierLexicalScope() throws Exception {
        var r=resolver("mysql").resolve("WITH a AS (SELECT parent FROM items), items AS (SELECT parent FROM payments) SELECT x.parent AS x,y.parent AS y FROM a x JOIN a y ON x.parent=y.parent");
        assertEquals(2,r.leaves().size());assertTrue(r.leaves().stream().allMatch(l->l.relation().name().equals("items")));
        assertNotEquals(r.leaves().get(0).occurrence(),r.leaves().get(1).occurrence());
    }
    @Test void derivedAndNestedViewsRemoveSourceFiltersButKeepProjectionAndSchema() throws Exception {
        var r=resolver("mysql").resolve("SELECT d.invoice FROM (SELECT invoice FROM other.v2 WHERE invoice='hidden') d");
        assertEquals("other",r.leaves().get(0).relation().schema());assertEquals("items",r.leaves().get(0).relation().name());
        assertTrue(r.leaves().get(0).path().stream().anyMatch(p->p.equals("view:other.v")));
        assertFalse(r.columns().get(0).bindings().get(0).expression().toString().contains("hidden"));
    }
    @Test void outerCteCannotShadowATablesNameInsideAView() throws Exception {
        var r=resolver("mysql").resolve("WITH items AS (SELECT parent FROM payments) SELECT invoice FROM v");
        assertEquals("items",r.leaves().get(0).relation().name());
    }
    @Test void scalarExpressionIsEvaluatedAtSourceWithoutInvertingTheExpectedValue() throws Exception {
        var r=resolver("mysql").resolve("WITH a AS (SELECT CONCAT(parent,'-x') AS invoice FROM items) SELECT LOWER(invoice) AS key_value FROM a");
        var c=r.columns().get(0);assertNull(c.errorReason());String expression=c.bindings().get(0).expression().toString();
        assertTrue(expression.contains("LOWER"));assertTrue(expression.contains("CONCAT"));assertTrue(expression.contains("parent"));assertTrue(expression.contains("'-x'"));
    }
    @Test void unionUsesOutputPositionAndRetainsBothSourceBranches() throws Exception {
        var r=resolver("mysql").resolve("SELECT parent AS invoice FROM items UNION ALL SELECT mode AS anything FROM payments");
        assertEquals("invoice",r.columns().get(0).name());assertEquals(2,r.columns().get(0).bindings().size());
        assertTrue(r.columns().get(0).bindings().get(1).expression().toString().contains("mode"));
        assertTrue(r.leaves().get(1).path().contains("UNION:right"));
    }
    @Test void unsupportedExpressionsAreObservablePerColumnAndKeepValidSiblings() throws Exception {
        var r=resolver("mysql").resolve("SELECT i.parent,SUM(i.amount) AS total,RAND() AS random_value,CONCAT(i.parent,p.mode) AS combined FROM items i JOIN payments p ON i.parent=p.parent GROUP BY i.parent,p.mode");
        assertNull(r.columns().get(0).errorReason());assertEquals("SOURCE_AGGREGATE_KEY",r.columns().get(1).errorReason());
        assertEquals("SOURCE_EXPRESSION_UNSUPPORTED",r.columns().get(2).errorReason());assertEquals("SOURCE_EXPRESSION_MULTIPLE_RELATIONS",r.columns().get(3).errorReason());
    }
    @Test void ambiguousColumnsAndConstantsNeverProduceInventedMappings() throws Exception {
        var r=resolver("mysql").resolve("SELECT parent,1 AS constant_key FROM items i JOIN payments p ON i.parent=p.parent");
        assertEquals("SOURCE_COLUMN_AMBIGUOUS",r.columns().get(0).errorReason());assertEquals("SOURCE_CONSTANT_KEY",r.columns().get(1).errorReason());
    }
    @Test void qualifiedStarsRetainOnlyTheSelectedSideButInventoryIncludesBoth() throws Exception {
        var r=resolver("mysql").resolve("SELECT p.* FROM items i JOIN payments p ON i.parent=p.parent");
        assertEquals(2,r.columns().size());assertEquals(2,r.leaves().size());
        assertTrue(r.columns().stream().allMatch(c->c.bindings().get(0).leaf().relation().name().equals("payments")));
    }
    @Test void cyclesAndRecursiveDefinitionsAreExplicitUnknowns() {
        assertEquals("SOURCE_VIEW_CYCLE",assertThrows(PhysicalSourcePlanService.Unverified.class,()->resolver("mysql").resolve("SELECT invoice FROM loop")).code);
        assertEquals("SOURCE_RECURSIVE_UNSUPPORTED",assertThrows(PhysicalSourcePlanService.Unverified.class,()->resolver("mysql").resolve("WITH RECURSIVE a AS (SELECT parent FROM items UNION ALL SELECT parent FROM a) SELECT parent FROM a")).code);
    }
    @Test void postgresCatalogDefinitionIncludesItsTerminatorAndServerCasts() throws Exception {
        var r=resolver("postgresql").resolve("SELECT invoice FROM vpg");
        assertNull(r.columns().get(0).errorReason());
    }
    @Test void postgresDeparsedViewNamesUseConnectionSearchPathRatherThanViewsSchema() throws Exception {
        var r=resolver("postgresql").resolve("SELECT invoice FROM other.v");
        assertEquals("fixture",r.leaves().get(0).relation().schema());
    }
    @Test void setCoercionsNeverDisappearWhenComposingExpressionsAcrossWrappers() throws Exception {
        for(String dialect:List.of("postgresql","mysql"))for(String op:List.of("UNION ALL","UNION","INTERSECT","EXCEPT")) {
            String set="SELECT n FROM ints "+op+" SELECT n FROM nums";
            for(String sql:List.of(set,"SELECT n / 2 AS k FROM ("+set+") u",
                    "WITH u(k) AS ("+set+") SELECT k / 2 AS answer FROM u",
                    "SELECT n FROM ("+set+") u UNION ALL SELECT n FROM ints")) {
                var c=resolver(dialect).resolve(sql).columns().get(0);
                assertEquals("SOURCE_SET_COERCION_UNVERIFIED",c.errorReason(),sql);assertTrue(c.bindings().isEmpty(),sql);
            }
        }
        for(String dialect:List.of("postgresql","mysql")) {
            var c=resolver(dialect).resolve("SELECT n / 2 AS k FROM heterogeneous_view").columns().get(0);
            assertEquals("SOURCE_SET_COERCION_UNVERIFIED",c.errorReason());assertTrue(c.bindings().isEmpty());
        }
    }
    @Test void equalDeclaredTypesRetainDirectAndComputedSetMappings() throws Exception {
        for(String dialect:List.of("postgresql","mysql"))for(String op:List.of("UNION ALL","UNION","INTERSECT","EXCEPT")) {
            String set="SELECT n FROM ints "+op+" SELECT n FROM ints";
            for(String sql:List.of(set,"SELECT n / 2 AS k FROM ("+set+") u",
                    "WITH u(k) AS ("+set+") SELECT k / 2 AS answer FROM u","SELECT n / 2 AS k FROM homogeneous_view")) {
                var c=resolver(dialect).resolve(sql).columns().get(0);assertNull(c.errorReason(),sql);assertEquals(2,c.bindings().size(),sql);
            }
        }
    }
    @Test void uncertifiedComputedBranchAndDifferentCollationRemainUnknown() throws Exception {
        for(String dialect:List.of("postgresql","mysql"))for(String sql:List.of(
                "SELECT n + 1 AS k FROM ints UNION ALL SELECT n + 1 FROM ints",
                "SELECT parent FROM items UNION ALL SELECT parent FROM other_collation",
                "SELECT n FROM untyped UNION ALL SELECT n FROM untyped")) {
            var c=resolver(dialect).resolve(sql).columns().get(0);
            assertEquals("SOURCE_SET_COERCION_UNVERIFIED",c.errorReason());assertTrue(c.bindings().isEmpty());
        }
    }
    @Test void mysqlMatchingEnumDeclarationsCannotCertifyTheUnionResultType() throws Exception {
        var c=resolver("mysql").resolve("SELECT n + 0 AS k FROM (SELECT n FROM enums UNION ALL SELECT n FROM enums) u").columns().get(0);
        assertEquals("SOURCE_SET_COERCION_UNVERIFIED",c.errorReason());assertTrue(c.bindings().isEmpty());
    }
    @Test void coercionFailureIsPerOutputColumnAndDoesNotDropVerifiedSiblingKeys() throws Exception {
        for(String dialect:List.of("postgresql","mysql")) {
            var result=resolver(dialect).resolve("SELECT n AS stable,n AS mixed FROM ints UNION ALL SELECT i.n,n.n FROM ints i JOIN nums n ON 1=1");
            assertNull(result.columns().get(0).errorReason());assertEquals(2,result.columns().get(0).bindings().size());
            assertEquals("SOURCE_SET_COERCION_UNVERIFIED",result.columns().get(1).errorReason());
            assertTrue(result.columns().get(1).bindings().isEmpty());
        }
    }

}
