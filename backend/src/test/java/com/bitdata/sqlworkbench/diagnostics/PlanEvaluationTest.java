package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.diagnostics.rules.*;
import com.bitdata.sqlworkbench.execution.ExecutionEngine.PlanObservation;
import com.bitdata.sqlworkbench.execution.PostgreSqlPlanAdapter;
import com.bitdata.sqlworkbench.execution.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanEvaluationTest {
    @Test void allSevenRulesDeclareCoverageAndLegacyEvidenceIsNotAuthenticated() {
        try(var context=new StaticApplicationContext()) {
            var rules=List.of(new EstimatedVsActualRule(),new SequentialScanRule(),new JoinAmplificationRule(),
                new ExpectedRowEliminatedAtFilterRule(),new ExpectedRowEliminatedAtJoinRule(),new ExpectedRowMissingAtSourceRule(),new NonSargablePredicateRule());
            rules.forEach(rule -> context.getBeanFactory().registerSingleton(rule.id(),rule));
            var registry=new RuleRegistry(context); registry.init();
            var fragment=DiagnosticRuleTestFixture.fragmentWithSql("f","SELECT 1 WHERE upper('x')='X'");
            var legacy=registry.evaluate(fragment,new PlanObservation("UNVERIFIED","UNVERIFIED_PLAN_SOURCE",null),null);
            assertEquals(7,legacy.evaluation().rules().size());
            assertTrue(legacy.findings().stream().allMatch(f -> f.ruleId().equals("non-sargable-predicate")));
            assertTrue(legacy.evaluation().rules().stream().anyMatch(r -> "UNVERIFIED_PLAN_SOURCE".equals(r.errorReason())));
            var plan=new PostgreSqlPlanAdapter().parse("[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Plan Rows\":100,\"Actual Rows\":0,\"Actual Loops\":1}}]","postgresql");
            var result=new ExecutionResult("e",ExecutionStatus.COMPLETED,null,null,0,null,plan,null,null);
            var verified=registry.evaluate(fragment,new PlanObservation("VERIFIED",null,result),"e");
            assertTrue(verified.findings().stream().anyMatch(f -> f.ruleId().equals("estimated-vs-actual") && f.title().contains("0 actual")));
            assertTrue(verified.evaluation().rules().stream().anyMatch(r -> "INCOMPARABLE_OPERATOR_COUNTS".equals(r.errorReason())));
            assertTrue(verified.evaluation().rules().stream().anyMatch(r -> "PLAN_COUNTS_DO_NOT_PROVE_ROW_ELIMINATION".equals(r.errorReason())));
        }
    }

    @Test void existingDatabaseMigratesIdempotentlyAndKeepsLegacyArrays() throws Exception {
        var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE diagnostic_reports(id TEXT PRIMARY KEY,session_id TEXT,fragment_id TEXT,findings_json TEXT,created_at TEXT)");
            jdbc.update("INSERT INTO diagnostic_reports VALUES('old','s','f','[]','2026-10-15T00:00:00Z')");
            var config=new com.bitdata.sqlworkbench.config.SqliteConfig();
            var init=config.getClass().getDeclaredMethod("initSchema",JdbcTemplate.class); init.setAccessible(true);
            init.invoke(config,jdbc); init.invoke(config,jdbc);
            var store=new DiagnosticReportStore(jdbc);
            assertNull(store.findBySessionId("s").get(0).evaluationJson());
            store.save(new DiagnosticReport("new","s","g","[]",java.time.Instant.now(),"{\"version\":1}"));
            var report=store.findLatestBySessionAndFragment("s","g").orElseThrow();
            assertEquals("[]",report.findingsJson()); assertEquals("{\"version\":1}",report.evaluationJson());
            assertEquals(report,store.findBySessionId("s").stream().filter(r -> r.id().equals("new")).findFirst().orElseThrow());
        } finally { ds.destroy(); }
    }
}
