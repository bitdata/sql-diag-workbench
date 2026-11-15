package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.edit.EditClassifyController;
import com.bitdata.sqlworkbench.fragment.FragmentController;
import com.bitdata.sqlworkbench.sql.model.DecomposeRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/** Runs before controller try/catch blocks and before parse/cache work for every typed graph request. */
@ControllerAdvice
public class GraphRequestAdmission extends RequestBodyAdviceAdapter {
    private final org.springframework.beans.factory.ObjectProvider<WorkspaceIdentity> identities;
    public GraphRequestAdmission(org.springframework.beans.factory.ObjectProvider<WorkspaceIdentity> identities) { this.identities = identities; }

    @Override public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return IdentityBoundRequest.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        var identity = identities.getObject(); // Required for every graph route; unrelated MVC slices need no store.
        var request = (IdentityBoundRequest) body;
        String dialect = request.dialect() == null ? "postgresql" : request.dialect();
        Session session = identity.require(request.identityContext(),dialect);
        var verified = identity.verifyNodes(request.nodes(),dialect);
        if (body instanceof DecomposeRequest decompose)
            identity.requireSource(decompose.sourceNodeId(),decompose.sqlFragment(),dialect,verified);
        if (body instanceof FragmentController.CreateUserEditRequest edit) {
            if (!verified.containsKey(edit.parentFragmentId())) throw new WorkspaceIdentity.Failure(409,"SOURCE_NODE_PROOF_REQUIRED");
            if (edit.dagContextIds() != null) for (String id : edit.dagContextIds()) {
                if (!verified.containsKey(id)) throw new WorkspaceIdentity.Failure(409,"DAG_NODE_PROOF_REQUIRED");
            }
        }
        if (body instanceof EditClassifyController.ClassifyRequest classify && classify.sessionId() == null)
            throw new WorkspaceIdentity.Failure(400,"WORKSPACE_SESSION_REQUIRED");
        if (body instanceof EditClassifyController.ClassifyRequest classify
                && !session.id().equals(classify.sessionId()))
            throw new WorkspaceIdentity.Failure(409,"WORKSPACE_SESSION_MISMATCH");
        return body;
    }
}
