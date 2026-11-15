package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared admission for graph-associated endpoints, independent of renderer controls. */
@Component
public class WorkspaceIdentity {
    private final SessionStore store;
    public WorkspaceIdentity(SessionStore store) { this.store = store; }

    public static final class Failure extends RuntimeException {
        public final int status;
        public final String code;
        public Failure(int status, String code) { super(code); this.status = status; this.code = code; }
    }

    public Session require(IdentityContext context, String dialect) {
        if (context == null || context.sessionId() == null || context.identityPolicy() == null || context.workspaceRevision() == null)
            throw new Failure(400, "IDENTITY_CONTEXT_REQUIRED");
        if (context.sessionId().isBlank() || context.identityPolicy().isBlank() || context.workspaceRevision() < 0)
            throw new Failure(400, "IDENTITY_CONTEXT_INVALID");
        Session session = store.findById(context.sessionId()).orElseThrow(() -> new Failure(404, "WORKSPACE_NOT_FOUND"));
        if (!StructuralSqlCodec.POLICY.equals(session.identityPolicy())) throw new Failure(409, "IDENTITY_POLICY_READ_ONLY");
        if (!session.identityPolicy().equals(context.identityPolicy())) throw new Failure(409, "IDENTITY_POLICY_MISMATCH");
        if (session.workspaceRevision() != context.workspaceRevision()) throw new Failure(409, "WORKSPACE_REVISION_CONFLICT");
        if (dialect != null && !session.dialect().equals(dialect)) throw new Failure(409, "WORKSPACE_DIALECT_MISMATCH");
        return session;
    }

    public StructuralSqlCodec.Form verify(NodeProof proof, String dialect) {
        if (proof == null || proof.sqlFragment() == null || proof.id() == null || proof.canonicalSql() == null)
            throw new Failure(400, "NODE_PROOF_REQUIRED");
        var form = StructuralSqlCodec.canonicalize(proof.sqlFragment(), dialect);
        if (form.degraded()) throw new Failure(422, "CANONICAL_UNAVAILABLE");
        if (!Objects.equals(form.id(), proof.id()) || !Objects.equals(form.canonical(), proof.canonicalSql()))
            throw new Failure(409, "NODE_IDENTITY_MISMATCH");
        return form;
    }

    public Map<String,NodeProof> verifyNodes(List<NodeProof> proofs, String dialect) {
        Map<String,NodeProof> verified = new LinkedHashMap<>();
        if (proofs != null) for (var proof : proofs) {
            verify(proof, dialect);
            if (verified.put(proof.id(), proof) != null) throw new Failure(400, "DUPLICATE_NODE_PROOF");
        }
        return Map.copyOf(verified);
    }

    public void requireSource(String sourceId, String sql, String dialect, Map<String,NodeProof> verified) {
        if (sourceId == null || !verified.containsKey(sourceId)) throw new Failure(409, "SOURCE_NODE_PROOF_REQUIRED");
        var form = StructuralSqlCodec.canonicalize(sql, dialect);
        if (form.degraded()) throw new Failure(422, "CANONICAL_UNAVAILABLE");
        if (!form.id().equals(sourceId)) throw new Failure(409, "SOURCE_SQL_IDENTITY_MISMATCH");
    }
}
