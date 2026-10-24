package com.bitdata.sqlworkbench.sql.diff;

import java.util.Objects;

/**
 * 单个原子变更操作（ADR-0004）。
 *
 * <p>由 {@link AstLcsDiffer} 产出。
 * 包含关系 {@code ΔP ⊆ ΔQ} 由 {@link #equals(Object)} 判定（基于 kind+nodeType+location+old/new text）。
 */
public final class ChangeOp {

    public enum Kind { ADD, DEL, MOD }

    private final Kind kind;
    private final String nodeType;
    private final String path;
    private final String oldText;   // null for ADD
    private final String newText;   // null for DEL

    public ChangeOp(Kind kind, String nodeType, String path, String oldText, String newText) {
        this.kind = Objects.requireNonNull(kind);
        this.nodeType = Objects.requireNonNull(nodeType);
        this.path = path != null ? path : "";
        this.oldText = oldText;
        this.newText = newText;
    }

    public static ChangeOp add(String nodeType, String path, String newText) {
        return new ChangeOp(Kind.ADD, nodeType, path, null, newText);
    }

    public static ChangeOp del(String nodeType, String path, String oldText) {
        return new ChangeOp(Kind.DEL, nodeType, path, oldText, null);
    }

    public static ChangeOp mod(String nodeType, String path, String oldText, String newText) {
        return new ChangeOp(Kind.MOD, nodeType, path, oldText, newText);
    }

    public Kind kind() { return kind; }
    public String nodeType() { return nodeType; }
    public String path() { return path; }
    public String oldText() { return oldText; }
    public String newText() { return newText; }

    /** 身份 = (kind, nodeType, path, old, new) — path 也参与，避免不同位置 op 合并。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangeOp other)) return false;
        return kind == other.kind
            && nodeType.equals(other.nodeType)
            && path.equals(other.path)
            && Objects.equals(oldText, other.oldText)
            && Objects.equals(newText, other.newText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, nodeType, path, oldText, newText);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case ADD -> "+[" + nodeType + " " + path + "] " + newText;
            case DEL -> "-[" + nodeType + " " + path + "] " + oldText;
            case MOD -> "~[" + nodeType + " " + path + "] " + oldText + " -> " + newText;
        };
    }
}