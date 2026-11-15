package com.bitdata.sqlworkbench.sql.model;

public record ParseRequest(String sql, String dialect, String connectionId,
    com.bitdata.sqlworkbench.session.IdentityContext identityContext,
    java.util.List<com.bitdata.sqlworkbench.session.NodeProof> nodes
) implements com.bitdata.sqlworkbench.session.IdentityBoundRequest {
    public ParseRequest(String sql, String dialect, String connectionId) {
        this(sql,dialect,connectionId,null,null);
    }
    public ParseRequest(String sql, String dialect) { this(sql, dialect, null); }
}
