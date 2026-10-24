package com.bitdata.sqlworkbench.session;

/** Independent SQL workspaces; these are not decomposition edges. */
public record SessionEditLink(String linkId, long sequence, String sourceSessionId,
        String sourceNodeId, String sourceTabName, String targetSessionId,
        String targetNodeId, String targetTabName, String createdAt,
        boolean sourceAlive, boolean targetAlive) {
    public record Origin(String sourceSessionId, String sourceNodeId,
                         String sourceTabName, String targetNodeId) {}
}
