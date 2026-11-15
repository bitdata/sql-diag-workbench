package com.bitdata.sqlworkbench.sql.model;

public record CteDependencyRequest(
    String sql,
    String dialect,
    com.bitdata.sqlworkbench.session.IdentityContext identityContext,
    java.util.List<com.bitdata.sqlworkbench.session.NodeProof> nodes
) implements com.bitdata.sqlworkbench.session.IdentityBoundRequest {
    public CteDependencyRequest(String sql, String dialect) {
        this(sql,dialect,null,null);
    }}