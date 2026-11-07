package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.session.SessionStore;
import org.springframework.stereotype.Service;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;

@Service
public class OptimizationSources {
    private final ConnectionService connections;
    private final SessionStore sessions;
    public OptimizationSources(ConnectionService connections, SessionStore sessions) {
        this.connections=connections; this.sessions=sessions;
    }
    static void token(String s) { if(s==null||s.isBlank()||s.length()>256)throw OptimizationFailure.invalid("INVALID_REQUEST"); }
    public Source capture(CandidateRequest request) {
        if(request==null)throw OptimizationFailure.invalid("INVALID_REQUEST");
        token(request.sessionId()); token(request.sourceRevision()); token(request.connectionId());
        if(sessions.findById(request.sessionId()).isEmpty())throw OptimizationFailure.changed("SESSION_GONE");
        var config=connections.getById(request.connectionId()).orElseThrow(()->OptimizationFailure.changed("CONNECTION_CHANGED"));
        if(!java.util.Objects.equals(request.dialect(),config.dialect()))throw OptimizationFailure.changed("CONNECTION_CHANGED");
        return new Source(request.sessionId(),request.sourceRevision(),request.sql(),config.id(),ConnectionIdentity.revision(config),config.dialect());
    }
    public void check(Source source) {
        if(source==null)throw OptimizationFailure.invalid("INVALID_REQUEST");
        token(source.connectionRevision());
        Source current=capture(new CandidateRequest(source.sessionId(),source.sourceRevision(),source.sql(),source.connectionId(),source.dialect()));
        if(!current.equals(source))throw OptimizationFailure.changed("CONNECTION_CHANGED");
    }
    public Generation generate(CandidateRequest request) {
        try { return OptimizationSql.generate(capture(request)); }
        catch(OptimizationFailure e){throw e;}
        catch(IllegalArgumentException e){throw OptimizationFailure.invalid(e.getMessage());}
        catch(Exception e){throw OptimizationFailure.invalid("SQL_PARSE_FAILED");}
    }
}
