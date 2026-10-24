package com.bitdata.sqlworkbench.sql.diff;

/**
 * 序列化序列的一个元素（ADR-0004：原子变更单元的最小载体）。
 *
 * <p>身份由 {@code nodeType} 与 {@code canonicalText} 共同决定——LCS 按这两个字段匹配。
 * {@code path} 是描述性元数据，仅用于 op 输出定位，不参与匹配。
 *
 * @param nodeType      Calcite SqlNode 类型标签（如 "WHERE"、"PREDICATE"、"LITERAL"）
 * @param canonicalText 规范化后的文本（用于内容匹配与 LCS）
 * @param path          节点在树中的位置描述（如 "root.WHERE.PREDICATE[0]"）
 */
public record NodeToken(String nodeType, String canonicalText, String path) {

    /** 内容相等 = nodeType 相同且 canonicalText 相同（path 不参与匹配）。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeToken other)) return false;
        return nodeType.equals(other.nodeType)
            && canonicalText.equals(other.canonicalText);
    }

    @Override
    public int hashCode() {
        return 31 * nodeType.hashCode() + canonicalText.hashCode();
    }
}