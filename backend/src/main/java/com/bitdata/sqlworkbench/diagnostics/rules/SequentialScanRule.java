package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.diagnostics.PlanNodeWalker;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags tables that use Seq Scan instead of Index Scan. For large tables, sequential scans are expensive.
 */
@Component
public class SequentialScanRule implements DiagnosticRule {

    private static final Pattern SEQ_SCAN_PATTERN = Pattern.compile("Seq Scan", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_NAME_PATTERN =
        Pattern.compile("Seq Scan on (\\S+)", Pattern.CASE_INSENSITIVE);
    private static final long LARGE_TABLE_THRESHOLD = 100_000L;

    @Override
    public String id() {
        return "sequential-scan";
    }

    @Override
    public String displayName() {
        return "Sequential Scan Detection";
    }

    @Override
    public String description() {
        return "Flags tables that use Seq Scan instead of Index Scan. Sequential scans on large tables are expensive.";
    }

    @Override
    public Severity severity() {
        return Severity.MEDIUM;
    }

    @Override
    public List<DiagnosticFinding> analyze(FragmentNode fragment, PlanNode plan, Timing timing) {
        if(plan==null || fragment==null) return List.of();
        var findings=new java.util.ArrayList<DiagnosticFinding>();
        for(var node:PlanNodeWalker.findAll(plan,n -> isSeqScan(n.operation()))) {
            if(node.metrics()==null) continue;
            var metrics=node.metrics();
            String table=resolveTableName(node);
            findings.add(DiagnosticFinding.builder().id(id()+"-"+fragment.id()+"-"+findings.size()).ruleId(id()).severity(Severity.LOW)
                .title("Sequential scan on "+(table==null?"unknown relation":table))
                .message("The plan uses a sequential scan. Output rows are not table size; this observation alone does not establish that an index would improve performance.")
                .evidence(List.of("Operation: "+node.operation(),"Estimated output rows: "+metricText(metrics.estimatedRows()),
                    "Actual output rows (per loop): "+metricText(metrics.actualRows())))
                .confidence(0.7).affectedNodeId(fragment.id()).build());
        }
        return findings;
    }

    private static String metricText(com.bitdata.sqlworkbench.execution.model.Metric value) {
        return value.available()?value.value():value.state()+" ("+value.errorReason()+")";
    }

    /**
     * Finds the scanned table on the first Seq Scan node that reveals one.
     * Recursively searches children if the current node is not a Seq Scan.
     *
     * <p>Two channels, in precedence order:
     * <ol>
     *   <li><b>Structured</b> {@link PlanNode#tableName()} — the ONLY source on
     *       the JSON EXPLAIN channels (PostgreSQL "Relation Name", MySQL
     *       "table_name"), where operation carries the bare node type and the
     *       text regex below can never match (E2E landmine D, 2026-09-09: the
     *       title used to fall back to fragment.label()).</li>
     *   <li><b>Text form</b> "Seq Scan on &lt;table&gt;" in operation — kept as
     *       the fallback for the psql-style text EXPLAIN channel.</li>
     * </ol>
     *
     * <p>Strips schema prefix (e.g. "public.orders" → "orders").
     */
    private String extractTableName(PlanNode plan) {
        return PlanNodeWalker.findFirst(plan, this::revealsScannedTable)
            .map(SequentialScanRule::resolveTableName)
            .orElse(null);
    }

    /** A node reveals a table only if it is itself the Seq Scan carrying one. */
    private boolean revealsScannedTable(PlanNode node) {
        if (!isSeqScan(node.operation())) {
            return false; // Index Scan nodes carry Relation Name too — never take those
        }
        return hasStructuredTableName(node)
            || TABLE_NAME_PATTERN.matcher(node.operation()).find();
    }

    private static String resolveTableName(PlanNode node) {
        String raw = node.tableName();
        if (raw == null || raw.isBlank()) {
            Matcher m = TABLE_NAME_PATTERN.matcher(node.operation());
            raw = m.find() ? m.group(1) : null;
        }
        if (raw == null) {
            return null;
        }
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private static boolean hasStructuredTableName(PlanNode node) {
        return node.tableName() != null && !node.tableName().isBlank();
    }

    private boolean isSeqScan(String operation) {
        return operation != null && SEQ_SCAN_PATTERN.matcher(operation).find();
    }

    private boolean hasSeqScanChild(PlanNode node) {
        return !PlanNodeWalker.findAll(node,
            n -> n.operation() != null && SEQ_SCAN_PATTERN.matcher(n.operation()).find()
        ).isEmpty();
    }
}
