package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.DecompositionResult.Issue;
import java.util.List;

public record RuleEvaluation(List<DecomposedFragment> fragments, List<Issue> issues) {}
