package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.bitdata.sqlworkbench.diagnostics.rules.DiagnosticRuleTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class JoinAmplificationRuleTest {

    private final JoinAmplificationRule rule = new JoinAmplificationRule();

    @Test
    void aggregateCountsDoNotProveCausality() {
        for(String op:List.of("Filter","Hash Join","Nested Loop","Result","Seq Scan")) {
            for(long actual:List.of(0L,1L,100000L)) {
                var child=planNode("Seq Scan",100,100);
                var parent=planNode(op,100,actual,List.of(child));
                assertTrue(rule.analyze(fragment("f1","sample"),parent,timing(10)).isEmpty(),op+"/"+actual);
            }
        }
    }

}
