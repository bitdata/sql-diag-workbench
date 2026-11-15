package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.session.IdentityContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** Captured workspace creation identity; revisions alone can repeat after deletion. */
public record DiagnosticContext(String sessionId,String identityPolicy,
        @JsonDeserialize(using=IdentityContext.RevisionDeserializer.class) Long workspaceRevision,
        String workspaceInstanceId) {
    public IdentityContext identityContext() { return new IdentityContext(sessionId,identityPolicy,workspaceRevision); }
}
