package com.bitdata.sqlworkbench.integration;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.integration.support.IntegrationFixtureSupport;
import com.bitdata.sqlworkbench.testsupport.*;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class BetweenSerializationIntegrationTest {
    final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    final SqlParseEngine parser=new SqlParseEngine(resolver);
    final DecompositionRuleEngine engine=new DecompositionRuleEngine(new ClauseStrippingRule(resolver),new SplitSetOpRule(resolver),new PredicateDecompositionRule(resolver),new CteExpansionRule(resolver),new JoinSplitRule(resolver),new SubqueryExtractionRule(resolver),resolver);
    record Rows(List<Integer> types,List<List<String>> values) {}

    @Test @RequiresTestDatabase(Dialect.POSTGRESQL) void postgres() throws Exception { run(Dialect.POSTGRESQL); }
    @Test @RequiresTestDatabase(Dialect.MYSQL) void mysql() throws Exception { run(Dialect.MYSQL); }

    private Rows rows(Connection connection,String sql) throws SQLException {
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(5);
            try(var rs=statement.executeQuery(sql)) {
                var types=new ArrayList<Integer>();var values=new ArrayList<List<String>>();
                for(int i=1;i<=rs.getMetaData().getColumnCount();i++)types.add(rs.getMetaData().getColumnType(i));
                while(rs.next()) {
                    var row=new ArrayList<String>();
                    for(int i=1;i<=types.size();i++) { var value=rs.getObject(i);row.add(value==null?null:value.toString()); }
                    values.add(row);
                }
                // Bag comparison retains duplicates and NULL; row order is not promised without ORDER BY.
                values.sort(Comparator.comparing(Object::toString));
                return new Rows(types,values);
            }
        }
    }

    private void run(Dialect database) throws Exception {
        String dialect=database.name().toLowerCase(Locale.ROOT);
        var fixture=IntegrationFixtureSupport.connect(database);
        IntegrationFixtureSupport.applyFixture(database,fixture);
        int equivalent=0,executedChildren=0,coveredRules=0;
        try(var connection=DriverManager.getConnection(fixture.jdbcUrl(),fixture.entry().username(),fixture.entry().password())) {
            connection.setReadOnly(true);
            var scalar=List.of(
                "SELECT 2 BETWEEN 1 AND 3 AS p", "SELECT 2 NOT BETWEEN 1 AND 3 AS p",
                "SELECT NULL BETWEEN 1 AND 3 AS p", "SELECT 2 BETWEEN NULL AND 3 AS p",
                "SELECT 2 NOT BETWEEN 3 AND 1 AS p", "SELECT 2 BETWEEN 3 AND 1 AS p",
                "SELECT 2 BETWEEN 2 AND 2 AS p", "SELECT NOT (2 BETWEEN 1 AND 3) OR 2 BETWEEN 1 AND 4 AS p",
                "SELECT (1=1) BETWEEN (1=0 AND 1=1) AND (1=1 OR 1=0) AS p",
                "SELECT 'b' BETWEEN 'a' AND 'c' AS p", "SELECT DATE '2026-10-27' BETWEEN DATE '2026-10-01' AND DATE '2026-10-31' AS p",
                "SELECT v, v BETWEEN 1 AND 3 AS p FROM (SELECT 1 AS v UNION ALL SELECT 1 AS v UNION ALL SELECT 4 AS v UNION ALL SELECT NULL AS v) t",
                "SELECT 'BETWEEN ASYMMETRIC' AS marker, 2 BETWEEN 1 AND 3 AS p");
            for(String source:scalar) {
                SqlNode ast=parser.parse(source,dialect);
                assertEquals(rows(connection,source),rows(connection,NodeToSql.toSql(ast,resolver.getProfile(dialect))),source);
                equivalent++;
            }
            String base="SELECT o.id FROM sqldiag_it_orders o WHERE o.amount BETWEEN 10 AND 30";
            Map<String,List<String>> cases=new LinkedHashMap<>();
            cases.put("clause-stripping",List.of(base+" ORDER BY o.id",base));
            cases.put("predicate-decomposition",List.of(base+" AND o.id > 0",base));
            cases.put("join-split",List.of("SELECT o.id FROM sqldiag_it_orders o JOIN sqldiag_it_customers c ON o.customer_id=c.id WHERE o.amount BETWEEN 10 AND 30","SELECT o.id,o.customer_id FROM sqldiag_it_orders o WHERE o.amount BETWEEN 10 AND 30"));
            cases.put("set-op-split",List.of(base+" UNION ALL "+base,base));
            cases.put("cte-expansion",List.of("WITH bounded AS ("+base+") SELECT id FROM bounded",base));
            cases.put("subquery-extraction",List.of("SELECT d.id FROM ("+base+") d",base));
            for(var entry:cases.entrySet()) {
                String source=entry.getValue().get(0),expected=entry.getValue().get(1);
                var batch=engine.decomposeDetailed(parser.parse(source,dialect),source,dialect,entry.getKey(),"source");
                boolean matched=false;
                for(var child:batch.children()) {
                    var result=rows(connection,child.sqlFragment());executedChildren++;
                    if(child.sqlFragment().contains("BETWEEN")) {
                        assertEquals(rows(connection,expected),result,entry.getKey()+": "+child.sqlFragment());
                        matched=true;
                    }
                }
                assertTrue(matched,entry.getKey()+": "+batch);coveredRules++;
            }
            var ast=parser.parse("SELECT 2 BETWEEN 1 AND 3 AS p",dialect);
            if(dialect.equals("mysql")) {
                var old=ast.toSqlString(resolver.getDialect(dialect)).getSql();
                var failure=assertThrows(SQLException.class,()->rows(connection,old));
                assertEquals("42000",failure.getSQLState());assertEquals(1064,failure.getErrorCode());
                assertThrows(SqlSerializationException.class,()->NodeToSql.toSql(parser.parse("SELECT 2 BETWEEN SYMMETRIC 3 AND 1",dialect),resolver.getProfile(dialect)));
            } else {
                String symmetric="SELECT 2 BETWEEN SYMMETRIC 3 AND 1 AS p";
                var expected=rows(connection,symmetric);
                assertEquals("true",expected.values().get(0).get(0));
                assertEquals(expected,rows(connection,NodeToSql.toSql(parser.parse(symmetric,dialect),resolver.getProfile(dialect))));
                assertNotEquals(expected,rows(connection,"SELECT 2 BETWEEN 3 AND 1 AS p"));
            }
            System.out.println("BETWEEN_ACCEPTANCE "+dialect+" version="+connection.getMetaData().getDatabaseProductVersion()+" equivalent="+equivalent+" rules="+coveredRules+" children="+executedChildren);
        } finally { IntegrationFixtureSupport.dropFixture(database,fixture); }
    }
}
