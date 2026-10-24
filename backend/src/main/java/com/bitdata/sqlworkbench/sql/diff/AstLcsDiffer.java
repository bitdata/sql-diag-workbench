package com.bitdata.sqlworkbench.sql.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Myers LCS 实现的 AST 序列 differ（ADR-0004 + 0005）。
 *
 * <p>输入：两条 {@link NodeToken} 序列（已规范化）。
 * 输出：{@link ChangeOp} 集合。
 *
 * <p>匹配策略：LCS 按 NodeToken.equals（nodeType + canonicalText）匹配——内容匹配，
 * 非位置匹配（git-diff 语义）。这解决了"增删子树后剩余节点正确对齐"的稳定性问题。
 *
 * <p><b>按值去重（HashSet&lt;ChangeOp&gt;）是有意保留的</b>：重复谓词的 path 唯一性
 * 由 renumberCommutativePaths 保证，评审所述漏报影响面高估。
 * 见 docs/review-verification-2026-08-16.md P2-13 裁决。
 */
public final class AstLcsDiffer {

    private static final Logger log = LoggerFactory.getLogger(AstLcsDiffer.class);

    /**
     * LCS DP 表的单元格上限。超过时降级为 path 差集（无 LCS 对齐）。
     * 4_000_000 ≈ 2000×2000 token，int 表约 16MB。
     */
    static final int MAX_LCS_CELLS = 4_000_000;

    private AstLcsDiffer() {}

    /**
     * 保留旧入口：返回 ops 集合。等价于 {@code diffResult(before, after).ops()}。
     * 不带降级标志——需要可观测降级的调用方应改用 {@link #diffResult}。
     */
    public static Set<ChangeOp> diff(List<NodeToken> before, List<NodeToken> after) {
        return diffResult(before, after).ops();
    }

    /**
     * 与 {@link #diff} 相同的算法，但返回 {@link DiffResult}，附带降级标志与方法名
     * （ADR-0005 + CLAUDE.md「降级必须可观测」）。规模超限时 {@code degraded=true}、
     * {@code method="PATH_SET"}；否则 {@code degraded=false}、{@code method="LCS"}。
     */
    public static DiffResult diffResult(List<NodeToken> before, List<NodeToken> after) {
        long cells = (long) before.size() * after.size();
        if (cells > MAX_LCS_CELLS) {
            log.warn("AST diff 降级为 path 差集：{}x{}={} 单元格超过上限 {}，归因精度下降",
                before.size(), after.size(), cells, MAX_LCS_CELLS);
            return new DiffResult(pathSetDiff(before, after), true, "PATH_SET");
        }
        int[][] lcs = computeLcsTable(before, after);
        Set<ChangeOp> raw = new HashSet<>();
        backtrack(before, after, lcs, before.size(), after.size(), raw);
        return new DiffResult(mergeDelAddIntoMod(raw), false, "LCS");
    }

    /**
     * 降级路径：按 path 对齐产出 ADD / DEL / MOD，不做 LCS。
     * 仅在超限时使用；实际触发面预期为零（trace SQL 是碎片化小查询，
     * 且 SqlParseService.MAX_SQL_LENGTH = 100_000 限制了输入规模）。
     */
    private static Set<ChangeOp> pathSetDiff(List<NodeToken> before, List<NodeToken> after) {
        Map<String, NodeToken> beforeByPath = new LinkedHashMap<>();
        for (NodeToken t : before) {
            beforeByPath.putIfAbsent(t.path(), t);
        }
        Map<String, NodeToken> afterByPath = new LinkedHashMap<>();
        for (NodeToken t : after) {
            afterByPath.putIfAbsent(t.path(), t);
        }

        Set<ChangeOp> ops = new HashSet<>();
        for (Map.Entry<String, NodeToken> e : afterByPath.entrySet()) {
            NodeToken b = beforeByPath.get(e.getKey());
            NodeToken a = e.getValue();
            if (b == null) {
                ops.add(ChangeOp.add(a.nodeType(), a.path(), a.canonicalText()));
            } else if (!b.canonicalText().equals(a.canonicalText())) {
                ops.add(ChangeOp.mod(a.nodeType(), a.path(), b.canonicalText(), a.canonicalText()));
            }
        }
        for (Map.Entry<String, NodeToken> e : beforeByPath.entrySet()) {
            if (!afterByPath.containsKey(e.getKey())) {
                NodeToken b = e.getValue();
                ops.add(ChangeOp.del(b.nodeType(), b.path(), b.canonicalText()));
            }
        }
        return ops;
    }

    /** 标准 LCS 动态规划表。 */
    private static int[][] computeLcsTable(List<NodeToken> a, List<NodeToken> b) {
        int m = a.size(), n = b.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    /** 回溯 LCS，产出 change ops。位置信息来自 a/b 的索引（路径化在 NodeToken 里）。 */
    private static void backtrack(
        List<NodeToken> a, List<NodeToken> b, int[][] dp,
        int i, int j, Set<ChangeOp> ops
    ) {
        if (i == 0 && j == 0) return;
        if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
            backtrack(a, b, dp, i - 1, j - 1, ops);
            return;
        }
        if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            // after 中多了 → ADD
            NodeToken t = b.get(j - 1);
            ops.add(ChangeOp.add(t.nodeType(), t.path(), t.canonicalText()));
            backtrack(a, b, dp, i, j - 1, ops);
        } else if (i > 0) {
            // before 中多了 → DEL
            NodeToken t = a.get(i - 1);
            ops.add(ChangeOp.del(t.nodeType(), t.path(), t.canonicalText()));
            backtrack(a, b, dp, i - 1, j, ops);
        }
    }

    /**
     * MOD 合并：按 path 配对 DEL + ADD → MOD。nodeType 不强求相同（操作符变化如
     * LESS_THAN → LESS_THAN_OR_EQUAL 也算 MOD，因为同位置的不同内容）。</p>
     */
    private static Set<ChangeOp> mergeDelAddIntoMod(Set<ChangeOp> raw) {
        List<ChangeOp> dels = new java.util.ArrayList<>();
        List<ChangeOp> adds = new java.util.ArrayList<>();
        for (ChangeOp op : raw) {
            if (op.kind() == ChangeOp.Kind.DEL) dels.add(op);
            else if (op.kind() == ChangeOp.Kind.ADD) adds.add(op);
        }
        Set<ChangeOp> merged = new HashSet<>();
        java.util.Set<ChangeOp> consumedAdds = new java.util.HashSet<>();
        for (ChangeOp del : dels) {
            for (ChangeOp add : adds) {
                if (consumedAdds.contains(add)) continue;
                if (del.path().equals(add.path())) {
                    // MOD 用 ADD 的 nodeType（修改后类型）
                    merged.add(ChangeOp.mod(add.nodeType(), del.path(),
                        del.oldText(), add.newText()));
                    consumedAdds.add(add);
                    break;
                }
            }
        }
        // 保留：未配对的 ADD + 原有 MOD
        for (ChangeOp op : raw) {
            if (op.kind() == ChangeOp.Kind.DEL) continue;
            if (op.kind() == ChangeOp.Kind.ADD) {
                if (!consumedAdds.contains(op)) merged.add(op);
                continue;
            }
            merged.add(op);
        }
        return merged;
    }
}