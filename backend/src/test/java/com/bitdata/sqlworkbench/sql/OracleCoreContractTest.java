package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class OracleCoreContractTest {
    final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    final SqlParseEngine parser=new SqlParseEngine(resolver);
    final SelectOnlyGuard guard=new SelectOnlyGuard(resolver);
    final JsonNode corpus;
    OracleCoreContractTest() throws Exception {
        try(var input=getClass().getResourceAsStream("/oracle-core/bounded-corpus.json")){corpus=new ObjectMapper().readTree(input);}
    }
    Stream<JsonNode> rows(String field) {return StreamSupport.stream(corpus.path(field).spliterator(),false);}
    @TestFactory Stream<DynamicTest> boundedParserWriterCloneAndUtf16Positions() {
        return rows("parser").map(row->DynamicTest.dynamicTest(row.path("id").asText(),()->{
            String sql=row.path("sql").asText();
            if(!row.path("accepted").asBoolean()){assertThrows(IllegalArgumentException.class,()->guard.requireSingleSelect(sql,"oracle"));return;}
            guard.requireSingleSelect(sql,"oracle");var ast=parser.parse(sql,"oracle");
            var before=StructuralSqlCodec.canonicalize(sql,"oracle");assertFalse(before.degraded(),before.errorReason());
            var written=NodeToSql.toSql(ast,resolver.getProfile("oracle"));
            assertEquals(before.id(),StructuralSqlCodec.canonicalize(written,"oracle").id(),written);
            assertEquals(before.id(),StructuralSqlCodec.fromAst(ast.clone(ast.getParserPosition()),"oracle").id());
            for(var position:new SqlPositionService(resolver).extractPositions(sql,"oracle")) {
                assertTrue(position.start().offset()>=0);assertTrue(position.end().offset()>position.start().offset());
                assertTrue(position.end().offset()<=sql.length());
                int a=position.start().offset(),b=position.end().offset();
                assertFalse(a>0&&Character.isLowSurrogate(sql.charAt(a))&&Character.isHighSurrogate(sql.charAt(a-1)));
                assertFalse(b<sql.length()&&Character.isLowSurrogate(sql.charAt(b))&&Character.isHighSurrogate(sql.charAt(b-1)));
            }
        }));
    }
    @TestFactory Stream<DynamicTest> oracleIdentityDistinguishesTypesNamesAndNationalLiterals() {
        return rows("oraclePairs").map(row->DynamicTest.dynamicTest(row.path("id").asText(),()->{
            var a=StructuralSqlCodec.canonicalize(row.path("leftSql").asText(),"oracle");
            var b=StructuralSqlCodec.canonicalize(row.path("rightSql").asText(),"oracle");
            assertFalse(a.degraded(),a.errorReason());assertFalse(b.degraded(),b.errorReason());
            assertEquals(row.path("expected").asText().equals("same"),a.id().equals(b.id()));
        }));
    }
    @TestFactory Stream<DynamicTest> originalThreeDialectIdentityAndWriterBytesStayPinned() {
        return rows("legacy").map(row->DynamicTest.dynamicTest(row.path("dialect").asText()+" / "+row.path("sql").asText(),()->{
            String sql=row.path("sql").asText(),dialect=row.path("dialect").asText();var form=StructuralSqlCodec.canonicalize(sql,dialect);
            assertEquals(row.path("id").asText(),form.id());assertEquals(row.path("canonical").asText(),form.canonical());
            if(!row.path("output").isNull())assertEquals(row.path("output").asText(),NodeToSql.toSql(parser.parse(sql,dialect),resolver.getProfile(dialect)));
        }));
    }
}
