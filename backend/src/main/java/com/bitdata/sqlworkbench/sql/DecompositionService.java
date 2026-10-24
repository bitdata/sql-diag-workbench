package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.decompose.DecompositionRuleEngine;
import com.bitdata.sqlworkbench.sql.model.DecomposeOption;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;

import org.apache.calcite.sql.SqlNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Standalone decomposition service — takes a pre-parsed SqlNode and produces
 * a FragmentNode tree. Decoupled from SQL parsing so decomposition rules
 * can be tested with programmatically constructed ASTs.
 *
 * Supports two modes:
 * 1. Full auto-decomposition (legacy) — {@link #decompose(SqlNode, String)}
 * 2. Interactive single-rule decomposition — {@link #decompose(SqlNode, String, String)}
 */
@Service
public class DecompositionService {

    private static final Logger log = LoggerFactory.getLogger(DecompositionService.class);

    private final DecompositionRuleEngine ruleEngine;

    public DecompositionService(DecompositionRuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public com.bitdata.sqlworkbench.sql.model.DecompositionResult.Options scanOptions(SqlNode node,String dialect) {
        return ruleEngine.scanOptions(node,dialect);
    }
    public com.bitdata.sqlworkbench.sql.model.DecompositionResult.Batch decomposeDetailed(SqlNode node,String sourceSql,String dialect,String ruleName,String sourceId) {
        return ruleEngine.decomposeDetailed(node,sourceSql,dialect,ruleName,sourceId);
    }

    public com.bitdata.sqlworkbench.sql.model.DecompositionResult.Batch decomposeDetailed(SqlNode node,SqlNode original,String sourceSql,String dialect,String ruleName,String sourceId) {
        return ruleEngine.decomposeDetailed(node,original,sourceSql,dialect,ruleName,sourceId);
    }

    /** Full auto-decomposition (legacy, for tests). */
    public FragmentNode decompose(SqlNode sqlNode, String dialect) {
        log.debug("Decomposing AST: {}", sqlNode.getClass().getSimpleName());
        return ruleEngine.build(sqlNode, dialect);
    }

    /** Create a root FragmentNode without any decomposition. */
    public FragmentNode createRootNode(SqlNode sqlNode, String dialect) {
        return ruleEngine.createRootNode(sqlNode, dialect);
    }

    /** Return available decomposition options for a parsed AST node. */
    public List<DecomposeOption> getAvailableOptions(SqlNode sqlNode, String dialect) {
        return ruleEngine.getAvailableOptions(sqlNode, dialect);
    }

    /** Apply a single decomposition rule to a parsed AST node, tracking lineage via sourceNodeId. */
    public List<FragmentNode> decompose(SqlNode sqlNode, String dialect, String ruleName, String sourceNodeId) {
        return ruleEngine.decompose(sqlNode, dialect, ruleName, sourceNodeId);
    }

    /** Back-compat overload — derives a synthetic parent id when the caller doesn't supply one. */
    public List<FragmentNode> decompose(SqlNode sqlNode, String dialect, String ruleName) {
        return ruleEngine.decompose(sqlNode, dialect, ruleName);
    }
}
