package com.bitdata.sqlworkbench.sql.diff;

import java.util.Set;

/**
 * AST diff 的结果，附带可观测的降级标志（ADR-0005 + CLAUDE.md「降级必须可观测」）。
 *
 * <p>{@code degraded=true} 表示因输入规模超过 {@link AstLcsDiffer#MAX_LCS_CELLS}
 * 而跳过 LCS 对齐、退回更粗的 path 差集算法。ops 仍然有效，但归因精度下降
 * （无跨位置对齐）。把 diff 结果透传给用户的调用方（如 DiffController）应当
 * 暴露此标志，让用户能区分「精确结果」与「降级结果」。
 *
 * @param ops      变更操作集合（可能为空）
 * @param degraded true 当且仅当 diff 退回了 path 差集
 * @param method   产出 ops 的算法名："LCS" 或 "PATH_SET"
 */
public record DiffResult(Set<ChangeOp> ops, boolean degraded, String method) {
}
