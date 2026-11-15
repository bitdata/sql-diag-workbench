package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.bitdata.sqlworkbench.sql.diff.ChangeAttribution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TsqlFunctionSerializationTest {
    @TestFactory List<DynamicTest> frozenFunctionShapes() throws Exception {
        var fixtures = new ObjectMapper().readTree(getClass().getResourceAsStream("/tsql-function-contract.json"));
        var profile = new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies()).getProfile("sqlserver");
        var tests = new ArrayList<DynamicTest>();
        for (var fixture : fixtures) tests.add(DynamicTest.dynamicTest(fixture.path("id").asText(), () -> {
            String sql = fixture.path("sql").asText();
            if (fixture.path("kind").asText().equals("reject")) {
                assertThrows(Exception.class, () -> NodeToSql.toQuotedSql(StructuralSqlCodec.parse(sql,"sqlserver"),profile));
                return;
            }
            String output = NodeToSql.toQuotedSql(StructuralSqlCodec.parse(sql,"sqlserver"),profile);
            if (fixture.has("expectedOutput")) assertEquals(fixture.path("expectedOutput").asText(), output);
            else assertFalse(output.matches("(?s).*\\[(COUNT_BIG|LEN|CHARINDEX|CONCAT|GETDATE|NEWID|CHECKSUM|HASHBYTES)\\]\\s*\\(.*"), output);
            var before = StructuralSqlCodec.canonicalize(sql,"sqlserver");
            var after = StructuralSqlCodec.canonicalize(output,"sqlserver");
            assertFalse(before.degraded()); assertFalse(after.degraded()); assertEquals(before.id(),after.id());
            var delta = ChangeAttribution.attributeChangeDetailed(sql,output,"sqlserver");
            assertFalse(delta.degraded()); assertTrue(delta.ops().isEmpty());
        }));
        return tests;
    }
}
