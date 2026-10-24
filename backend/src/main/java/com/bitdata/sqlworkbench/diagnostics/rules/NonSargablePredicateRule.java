package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Flags SQL text patterns that may affect index choices, e.g., function(col) = value,
 * col LIKE '%pattern', col IS NULL (depends on index).
 *
 * <p><b>Heuristic, not AST-based.</b> 匹配在 fragment SQL 文本上进行；
 * 聚合函数（count/sum/avg/min/max）已排除，字符串字面量内的伪匹配仍是已知边界。
 */
@Component
public class NonSargablePredicateRule implements DiagnosticRule {

    // Heuristic, NOT AST-based: 在 fragment SQL 上做正则匹配。fragment 未必能
    // 独立 parse，因此不做 AST 重写（见 spec 2026-08-26-track-c-cleanup §3.2）。
    // 已知边界：字符串字面量内的 "IS NULL" / "upper(" 会被误匹配。
    private static final String AGGREGATE_FUNCTIONS = "count|sum|avg|min|max";

    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "(?i)(function\\(|(?<![a-z_])(?!(?:" + AGGREGATE_FUNCTIONS + ")\\s*\\()[a-z_]+\\([^)]+\\)\\s*[=<>])");
    private static final Pattern LIKE_LEADING_WILDCARD_PATTERN = Pattern.compile(
        "(?i)LIKE\\s+['\"]%");
    private static final Pattern IS_NULL_PATTERN = Pattern.compile(
        "(?i)\\bIS\\s+NULL\\b");

    @Override
    public String id() {
        return "non-sargable-predicate";
    }

    @Override
    public String displayName() {
        return "Non-Sargable Predicate";
    }

    @Override
    public String description() {
        return "Flags SQL text patterns that may affect index choices due to wrapping columns in functions or using leading wildcards.";
    }

    @Override
    public Severity severity() {
        return Severity.MEDIUM;
    }

    @Override
    public List<DiagnosticFinding> analyze(FragmentNode fragment, PlanNode plan, Timing timing) {
        List<DiagnosticFinding> findings = new ArrayList<>();
        String sql = fragment.sqlFragment();

        if (sql == null || sql.isBlank()) {
            return findings;
        }

        if (hasFunctionPredicate(sql)) {
            findings.add(createFinding(fragment, plan,
                "Column wrapped in function",
                "Column '" + extractColumnFromFunction(sql) + "' is wrapped in a function call which may affect index choices. " +
                "Consider rewriting as a direct column comparison if an index exists."));
        }

        if (hasLeadingWildcardLike(sql)) {
            findings.add(createFinding(fragment, plan,
                "Leading wildcard in LIKE",
                "LIKE pattern starts with '%' which may affect index choices. Consider using a suffix wildcard or full-text search."));
        }

        if (hasIsNullPredicate(sql)) {
            findings.add(createFinding(fragment, plan,
                "IS NULL predicate",
                "IS NULL was matched by a SQL text heuristic. Databases can use indexes for IS NULL; inspect the actual plan before changing this predicate."));
        }

        return findings;
    }

    private DiagnosticFinding createFinding(FragmentNode fragment, PlanNode plan, String title, String message) {
        return DiagnosticFinding.builder()
            .id(id() + "-" + fragment.id())
            .ruleId(id())
            .severity(Severity.MEDIUM)
            .title(title)
            .message(message + " SQL text heuristic only; literals can match. This does not prove actual index usage.")
            .evidence(List.of("SQL fragment: " + fragment.sqlFragment()))
            .confidence(0.8)
            .affectedNodeId(fragment.id())
            .build();
    }

    private boolean hasFunctionPredicate(String sql) {
        return FUNCTION_PATTERN.matcher(sql).find();
    }

    private boolean hasLeadingWildcardLike(String sql) {
        return LIKE_LEADING_WILDCARD_PATTERN.matcher(sql).find();
    }

    private boolean hasIsNullPredicate(String sql) {
        return IS_NULL_PATTERN.matcher(sql).find();
    }

    private String extractColumnFromFunction(String sql) {
        // Match FUNCTION_NAME( column_name, ...) and capture the column name
        // inside the parentheses. The old regex matched the function name
        // itself (e.g. "UPPER" instead of "name" for UPPER(name) = 'X').
        Pattern colPattern = Pattern.compile(
            "(?i)[a-z_][a-z0-9_]*\\s*\\(\\s*([a-z_][a-z0-9_.]*)",
            Pattern.CASE_INSENSITIVE);
        var matcher = colPattern.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }
}
