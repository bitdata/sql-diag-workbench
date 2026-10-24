package com.bitdata.sqlworkbench.sql.diff;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;

import java.util.List;
import java.util.Set;

/**
 * ADR-0005 变更归因的公开入口。
 *
 * <p>Pipeline：{@code parse → P1 normalize(AST) → serialize → P0 canonicalize(token seq) → LCS diff → ops}
 *
 * <p>不变式：
 * <ul>
 *   <li>归因是 (before, after, dialect) 的纯函数，确定性。</li>
 *   <li>无变更：{@code attributeChange(s, s, d)} 返回空集。</li>
 *   <li>P1 规范化（方向归一 / De Morgan / &lt;&gt;↔!=）使语义等价的 SQL 形态产生相同 canonical form。</li>
 * </ul>
 */
public final class ChangeAttribution {

    private ChangeAttribution() {}

    public static Set<ChangeOp> attributeChange(String before, String after, String dialect)
            throws SqlParseException {
        return attributeChangeDetailed(before, after, dialect).ops();
    }

    /**
     * 与 {@link #attributeChange} 相同的 pipeline，但返回 {@link DiffResult}，
     * 暴露降级标志与方法名（CLAUDE.md「降级必须可观测」）。需要把降级状态透传
     * 给用户的调用方（如 DiffController）应使用此入口。
     */
    public static DiffResult attributeChangeDetailed(String before, String after, String dialect)
            throws SqlParseException {
        SqlNode beforeNorm = SqlPredicateNormalizer.normalize(before, dialect);
        SqlNode afterNorm  = SqlPredicateNormalizer.normalize(after, dialect);
        List<NodeToken> seqBefore = AstSerializer.serializeNode(beforeNorm);
        List<NodeToken> seqAfter  = AstSerializer.serializeNode(afterNorm);
        List<NodeToken> canonBefore = SqlCanonicalizer.canonicalize(seqBefore);
        List<NodeToken> canonAfter  = SqlCanonicalizer.canonicalize(seqAfter);
        return AstLcsDiffer.diffResult(canonBefore, canonAfter);
    }
}