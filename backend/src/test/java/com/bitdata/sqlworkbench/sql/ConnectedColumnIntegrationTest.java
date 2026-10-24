package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.testsupport.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

@Tag("integration")
class ConnectedColumnIntegrationTest {
    @ParameterizedTest
    @EnumSource(value=Dialect.class,names={"MYSQL","POSTGRESQL"})
    void realMetadataResolvesBareColumnsAndOutputsExecute(Dialect dialect) throws Exception {
        assumeTrue(TestDatabaseResolver.isAvailable(dialect), () -> "No usable test database for " + dialect);
        var entry=TestDatabaseResolver.resolve(dialect);
        String url=TestDatabaseResolver.buildJdbcUrl(dialect,entry);
        var harness=new ConnectedColumnResolverTest();
        when(harness.connections.openJdbcConnection("live")).thenAnswer(unused -> fixture(url,entry.username(),entry.password()));
        String name=dialect==Dialect.MYSQL?"mysql":"postgresql";
        for(String sql:java.util.List.of(ConnectedColumnResolverTest.V1,ConnectedColumnResolverTest.V3)) {
            var result=harness.service().decomposeDetailed(sql,name,"join-split",null,"live");
            assertEquals("COMPLETE",result.status(),result.issues().toString());
            assertEquals(2,result.children().size());
            try(Connection connection=fixture(url,entry.username(),entry.password()); Statement statement=connection.createStatement()) {
                for(var child:result.children()) try(ResultSet rows=statement.executeQuery(child.sqlFragment())) {
                    assertTrue(rows.next(),child.sqlFragment());
                }
            }
        }
    }
    private Connection fixture(String url,String user,String password) throws SQLException {
        Connection connection=DriverManager.getConnection(url,user,password);
        try(Statement statement=connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE employees (emp_no INT,last_name VARCHAR(20),first_name VARCHAR(20),gender VARCHAR(1))");
            statement.execute("CREATE TEMPORARY TABLE salaries (emp_no INT,salary INT)");
            statement.execute("CREATE TEMPORARY TABLE departments (dept_no INT,dept_name VARCHAR(20))");
            statement.execute("CREATE TEMPORARY TABLE dept_manager (dept_no INT,emp_no INT)");
            statement.execute("INSERT INTO employees VALUES (1,'Male','One','M'),(2,'Female','Two','F')");
            statement.execute("INSERT INTO salaries VALUES (1,100),(2,200)");
            statement.execute("INSERT INTO departments VALUES (10,'Engineering')");
            statement.execute("INSERT INTO dept_manager VALUES (10,2)");
            return connection;
        } catch(SQLException e) { connection.close(); throw e; }
    }
}
