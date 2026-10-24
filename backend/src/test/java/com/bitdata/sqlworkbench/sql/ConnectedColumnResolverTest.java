package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.model.ParseRequest;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectedColumnResolverTest {
    static final String V1 = "SELECT e.emp_no,e.last_name,e.first_name,e.gender,s.salary FROM employees e JOIN salaries s ON e.emp_no=S.emp_no WHERE gender='M'";
    static final String V3 = "WITH v2 AS (SELECT d.dept_no,d.dept_name,m.emp_no FROM departments d JOIN dept_manager m ON m.dept_no=d.dept_no) SELECT e.last_name,e.first_name,dept_name,dept_no FROM v2 JOIN employees e ON e.emp_no=v2.emp_no WHERE e.gender='F'";
    static final Map<String,List<String>> SCHEMA = Map.of(
        "employees",List.of("emp_no","last_name","first_name","gender"),
        "salaries",List.of("emp_no","salary"),
        "departments",List.of("dept_no","dept_name"),
        "dept_manager",List.of("dept_no","emp_no"),
        "dept_emp",List.of("emp_no","dept_no"));
    final SqlDialectResolver dialects = RuleTestDeps.testResolver();
    final ConnectionService connections = mock(ConnectionService.class);
    final ConnectedColumnResolver columns = new ConnectedColumnResolver(connections,dialects);
    final SqlParseEngine parser = new SqlParseEngine(dialects);
    SqlParseService service() {
        return new SqlParseService(parser,new DecompositionService(new DecompositionRuleEngine(
            new ClauseStrippingRule(dialects),new SplitSetOpRule(dialects),new PredicateDecompositionRule(dialects),
            new CteExpansionRule(dialects),new JoinSplitRule(dialects),new SubqueryExtractionRule(dialects),dialects)),dialects,columns);
    }
    void database(String id, Map<String,List<String>> schema) throws Exception {
        when(connections.openJdbcConnection(id)).thenAnswer(invocation -> {
            Connection connection = mock(Connection.class);
            when(connection.createStatement()).thenAnswer(unused -> {
                Statement statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    String sql = query.getArgument(0);
                    String table = sql.replace("SELECT * FROM ","").replace(" WHERE 1=0","").toLowerCase(Locale.ROOT);
                    List<String> names = schema.get(table);
                    if(names==null) throw new SQLException("missing table");
                    ResultSet rows = mock(ResultSet.class);
                    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
                    when(rows.getMetaData()).thenReturn(metadata);
                    when(metadata.getColumnCount()).thenReturn(names.size());
                    for(int i=0;i<names.size();i++) when(metadata.getColumnLabel(i+1)).thenReturn(names.get(i));
                    return rows;
                });
                return statement;
            });
            return connection;
        });
    }
    @Test void resolvesOriginalBareGenderAndKeepsSourceEvidence() throws Exception {
        database("db",SCHEMA);
        var service=service();
        var parsed=service.parse(new ParseRequest(V1,"mysql","db"));
        assertEquals("COMPLETE",parsed.scan().status(),parsed.scan().toString());
        assertTrue(parsed.tree().sqlFragment().contains("WHERE gender ="),parsed.tree().sqlFragment());
        var result=service.decomposeDetailed(V1,"mysql","join-split","source","db");
        assertEquals("COMPLETE",result.status(),result.issues().toString());
        assertEquals(2,result.children().size());
        for(var child:result.children()) {
            String sql=child.sqlFragment();
            if(sql.contains("FROM salaries")) assertFalse(sql.contains("gender"),sql);
            else assertTrue(sql.contains("e.gender = 'M'"),sql);
            assertEquals(List.of("source"),child.parentIds());
        }
        assertEquals(com.bitdata.sqlworkbench.fragment.FragmentIdentity.sha256Hex(V1),result.outputs().get(0).effect().sourceSqlHash());
    }
    @Test void cteAndDerivedColumnsResolveInBothDialects() throws Exception {
        database("db",SCHEMA);
        for(String dialect:List.of("mysql","postgresql")) {
            for(String sql:List.of(V3,"SELECT dept_name,e.emp_no FROM (SELECT d.dept_name,m.emp_no FROM departments d JOIN dept_manager m ON m.dept_no=d.dept_no) v2 JOIN employees e ON e.emp_no=v2.emp_no")) {
                var batch=service().decomposeDetailed(sql,dialect,"join-split",null,"db");
                assertEquals("COMPLETE",batch.status(),dialect+batch.issues());
                assertEquals(2,batch.children().size());
            }
        }
    }
    @Test void fullOriginalWithChainResolves() throws Exception {
        database("db",SCHEMA);
        String sql="WITH v1 AS ("+V1+"), v2 AS (SELECT d.dept_no,d.dept_name,m.emp_no FROM departments d JOIN dept_manager m ON m.dept_no=d.dept_no), v3 AS (SELECT e.last_name,e.first_name,dept_name,dept_no FROM v2 JOIN employees e ON e.emp_no=v2.emp_no WHERE e.gender='F') SELECT v1.last_name,v1.first_name,v3.dept_name,v3.last_name AS \"manager_last_name\",v3.first_name AS \"manager_first_name\" FROM v1 JOIN dept_emp de ON de.emp_no=v1.emp_no JOIN v3 ON v3.dept_no=de.dept_no";
        var service=service();
        var parsed=service.parse(new ParseRequest(sql,"mysql","db"));
        assertTrue(parsed.errors().isEmpty());
        assertEquals("COMPLETE",parsed.scan().status(),parsed.scan().toString());
        var expanded=service.decomposeDetailed(sql,"mysql","cte-expansion",null,"db");
        assertEquals(2,expanded.children().size(),expanded.toString());
        for(var child:expanded.children()) {
            var batch=service.decomposeDetailed(child.sqlFragment(),"mysql","join-split",child.id(),"db");
            assertEquals("COMPLETE",batch.status(),batch.issues().toString());
            assertEquals(2,batch.children().size());
        }
    }
    @Test void noConnectionAndAmbiguousColumnsNeverGuess() throws Exception {
        assertEquals("PARTIAL",service().decomposeDetailed(V1,"mysql","join-split",null).status());
        Map<String,List<String>> ambiguous=new HashMap<>(SCHEMA);
        ambiguous.put("salaries",List.of("emp_no","salary","gender"));
        database("ambiguous",ambiguous);
        var result=service().decomposeDetailed(V1,"mysql","join-split",null,"ambiguous");
        assertEquals("PARTIAL",result.status());
        assertTrue(result.children().isEmpty());
        assertTrue(result.issues().stream().anyMatch(i->i.code().equals("AMBIGUOUS_COLUMN")));
    }
    @Test void connectionAndSchemaChangesCannotReuseCache() throws Exception {
        Map<String,List<String>> changing=new HashMap<>(SCHEMA);
        database("good",changing);database("bad",Map.of());
        var service=service();
        assertEquals("COMPLETE",service.parse(new ParseRequest(V1,"mysql","good")).scan().status());
        assertEquals("PARTIAL",service.parse(new ParseRequest(V1,"mysql","bad")).scan().status());
        changing.put("salaries",List.of("emp_no","salary","gender"));
        assertEquals("PARTIAL",service.parse(new ParseRequest(V1,"mysql","good")).scan().status());
        assertEquals("PARTIAL",service.parse(new ParseRequest(V1,"mysql")).scan().status());
    }
    @Test void metadataFailureIsObservableForAllThreeEntrypoints() throws Exception {
        when(connections.openJdbcConnection("db")).thenThrow(new SQLException("secret detail"));
        var service=service();
        var issues=List.of(service.parse(new ParseRequest(V1,"mysql","db")).scan().issues(),
            service.scanOptions(V1,"mysql","db").scan().issues(),
            service.decomposeDetailed(V1,"mysql","join-split",null,"db").issues());
        for(var list:issues) {
            assertTrue(list.stream().anyMatch(i->i.code().equals("SCHEMA_METADATA_UNAVAILABLE")));
            assertFalse(list.toString().contains("secret detail"));
        }
    }
    @Test void outputAliasesAndNestedShadowedScopesAreNotRebound() throws Exception {
        database("db",SCHEMA);
        String sql="SELECT e.emp_no AS gender,s.salary FROM employees e JOIN salaries s ON e.emp_no=s.emp_no WHERE gender='M' ORDER BY gender";
        var node=parser.parse(sql,"mysql");
        var resolved=columns.resolve(node,"mysql","db");
        String text=NodeToSql.toSql(resolved.node(),dialects.getProfile("mysql"));
        assertTrue(text.contains("WHERE e.gender"),text);
        assertTrue(text.contains("ORDER BY gender"),text);
        String nested="SELECT e.emp_no,s.salary FROM employees e JOIN salaries s ON e.emp_no=s.emp_no WHERE EXISTS (SELECT 1 FROM salaries e WHERE emp_no=1)";
        var result=service().decomposeDetailed(nested,"mysql","join-split",null,"db");
        assertEquals("PARTIAL",result.status());
        assertTrue(result.children().isEmpty());
    }
}
