package com.bitdata.sqlworkbench.session;

import java.util.List;

/** Common envelope fields augment each endpoint's existing business fields. */
public interface IdentityBoundRequest {
    IdentityContext identityContext();
    List<NodeProof> nodes();
    String dialect();
}
