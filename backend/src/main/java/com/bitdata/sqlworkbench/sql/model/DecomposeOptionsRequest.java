package com.bitdata.sqlworkbench.sql.model;

public record DecomposeOptionsRequest(String sqlFragment, String dialect, String connectionId,
    com.bitdata.sqlworkbench.session.IdentityContext identityContext,
    java.util.List<com.bitdata.sqlworkbench.session.NodeProof> nodes
) implements com.bitdata.sqlworkbench.session.IdentityBoundRequest {
    public DecomposeOptionsRequest(String sqlFragment, String dialect, String connectionId) {
        this(sqlFragment,dialect,connectionId,null,null);
    }
    public DecomposeOptionsRequest(String sqlFragment, String dialect) { this(sqlFragment, dialect, null); }
}
