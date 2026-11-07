package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;

@Service
public class OptimizationTasks {
    private final ConnectionService connections;
    private final OptimizationSources sources;
    private final OptimizationReportStore store;
    private final ObjectMapper json;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->daemon(r,"optimization-worker"));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"optimization-deadline"));
    private final ThreadPoolExecutor canceller=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->daemon(r,"optimization-cancel"));
    private final Map<String,Task> tasks=new LinkedHashMap<>();
    private final Map<String,Long> expired=new LinkedHashMap<>();
    private static Thread daemon(Runnable r,String name) {var t=new Thread(r,name);t.setDaemon(true);return t;}
    private final class Task implements Runnable {
        final String id=UUID.randomUUID().toString();
        final ValidationRequest request;
        final Candidate evidence;
        final ValidationControl control;
        volatile Report report;
        volatile long terminalAt;
        volatile boolean cancelScheduled;
        Task(ValidationRequest request,Candidate evidence) {
            this.request=request;this.evidence=evidence;
            control=new ValidationControl(request.timeoutSeconds()==null?30:request.timeoutSeconds());
            report=build(this,"QUEUED","NOT_EVALUATED",null,null,null,null,List.of(),false,null,null,new Persistence("PENDING",null));
        }
        public void run() { execute(this); }
    }
    public OptimizationTasks(ConnectionService connections,OptimizationSources sources,OptimizationReportStore store,ObjectMapper json) {
        this.connections=connections;this.sources=sources;this.store=store;this.json=json;
        timer.scheduleWithFixedDelay(this::tick,50,50,TimeUnit.MILLISECONDS);
    }
    public synchronized Submission submit(ValidationRequest request) {
        if(request==null||request.source()==null)throw OptimizationFailure.invalid("INVALID_REQUEST");
        if(request.timeoutSeconds()!=null&&(request.timeoutSeconds()<1||request.timeoutSeconds()>120))throw OptimizationFailure.invalid("TIMEOUT_INVALID");
        sources.check(request.source());Candidate evidence=null;
        try {
            policy(request.source().sql(),request.source().dialect());policy(request.candidateSql(),request.source().dialect());
            var refs=new HashSet<String>();for(String sql:List.of(request.source().sql(),request.candidateSql()))for(var r:policy(sql,request.source().dialect()))refs.add(r.schema()+"\u0000"+r.table());
            if(refs.size()>8)throw reject("SOURCE_BUDGET_EXCEEDED");
            if(request.candidateId()!=null){OptimizationSources.token(request.candidateId());evidence=generate(request.source()).candidates().stream().filter(c->c.id().equals(request.candidateId())&&c.sql().equals(request.candidateSql())).findFirst().orElseThrow(()->OptimizationFailure.changed("SOURCE_CHANGED"));}
        }catch(OptimizationFailure e){throw e;}
        catch(IllegalArgumentException e){throw OptimizationFailure.invalid(e.getMessage());}
        catch(Exception e){throw OptimizationFailure.invalid("SQL_PARSE_FAILED");}
        prune();
        if(tasks.size()>=16)throw capacity();
        Task task=new Task(request,evidence);tasks.put(task.id,task);
        try{worker.execute(task);}catch(RejectedExecutionException e){tasks.remove(task.id);throw capacity();}
        return new Submission(task.id);
    }
    private OptimizationFailure capacity(){return new OptimizationFailure(429,"EXECUTION_CAPACITY_EXHAUSTED","VALIDATION_CAPACITY_EXHAUSTED");}
    public synchronized Report get(String id) {prune();return task(id).report;}
    private Task task(String id) {OptimizationSources.token(id);Task t=tasks.get(id);if(t==null)throw new OptimizationFailure(expired.containsKey(id)?410:404,expired.containsKey(id)?"OPTIMIZATION_TASK_EXPIRED":"OPTIMIZATION_TASK_NOT_FOUND",expired.containsKey(id)?"TASK_EXPIRED":"TASK_NOT_FOUND");return t;}
    public synchronized Report cancel(String id) {
        Task t=task(id);if(t.terminalAt==0){t.control.cancelled.set(true);stop(t);}return t.report;
    }
    private void prune() {
        long now=System.currentTimeMillis();var it=tasks.entrySet().iterator();
        while(it.hasNext()){var e=it.next();if(e.getValue().terminalAt>0&&now-e.getValue().terminalAt>=300_000){expired.put(e.getKey(),now);it.remove();}}
        expired.entrySet().removeIf(e->now-e.getValue()>=300_000);while(expired.size()>64)expired.remove(expired.keySet().iterator().next());
    }
    private synchronized void tick() {
        for(Task t:tasks.values())if(t.terminalAt==0&&t.control.stopReason()!=null)stop(t);
        prune();
    }
    private void stop(Task t) {
        if(worker.remove(t)) {finish(t,"CANCELLED".equals(t.control.stopReason())?"CANCELLED":"TIMED_OUT","INCONCLUSIVE",t.control.stopReason(),null,null,null,List.of(),false,null);return;}
        var r=t.report;
        String reason="CANCELLED".equals(t.control.stopReason())?"CANCEL_CLEANUP_PENDING":"DEADLINE_CLEANUP_PENDING";
        t.report=build(t,"RUNNING","INCONCLUSIVE",reason,r.snapshot(),r.original(),r.candidate(),List.of(),false,r.startedAt(),null,r.persistence());
        Statement statement=t.control.statement;
        if(statement!=null&&!t.cancelScheduled) {
            t.cancelScheduled=true;
            try{canceller.execute(()->{try{statement.cancel();}catch(Exception e){t.control.cleanupReason="CANCEL_SIGNAL_FAILED";}finally{t.cancelScheduled=false;}});}
            catch(RejectedExecutionException e){t.cancelScheduled=false;t.control.cleanupReason="CANCEL_SIGNAL_PENDING";}
        }
    }
    private void execute(Task t) {
        String started=Instant.now().toString(),status="COMPLETED",verdict="INCONCLUSIVE",reason=null;
        Snapshot snapshot=null;Side a=null,b=null;List<Difference> differences=new ArrayList<>();boolean truncated=false;
        Connection c=null;
        try {
            t.control.check();sources.check(t.request.source());
            synchronized(this){t.report=build(t,"RUNNING","NOT_EVALUATED",null,null,null,null,List.of(),false,started,null,new Persistence("PENDING",null));}
            c=connections.openJdbcConnection(t.request.source().connectionId(),t.request.source().connectionRevision());t.control.check();
            String dialect=t.request.source().dialect();
            var inspector=new SnapshotSources(t.control);
            // Session read-only policy must precede the first transaction and metadata snapshot.
            if(dialect.equals("mysql"))inspector.execute(c,"SET SESSION TRANSACTION READ ONLY");
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            inspector.prepare(c,dialect,t.request.source().sql(),t.request.candidateSql());
            String product=c.getMetaData().getDatabaseProductName(),version=c.getMetaData().getDatabaseProductVersion();
            if(dialect.equals("mysql")?(!product.equals("MySQL")||!version.equals("8.0.46")):(!product.equals("PostgreSQL")||!version.matches("16\\.14(?: .*|$)")))throw reject("SERVER_VERSION_UNVERIFIED");
            Map<String,String> settings=new TreeMap<>();String database,user,identity;
            if(dialect.equals("postgresql")) {
                if(!"pg_catalog".equals(inspector.scalar(c,"SELECT (current_schemas(true))[1]")))throw reject("ENVIRONMENT_UNAVAILABLE");
                settings.put("search_path",inspector.scalar(c,"SHOW search_path"));
                settings.put("transaction_isolation",inspector.scalar(c,"SHOW transaction_isolation"));
                settings.put("transaction_read_only",inspector.scalar(c,"SHOW transaction_read_only"));
                if(!"on".equals(settings.get("transaction_read_only"))||!"repeatable read".equals(settings.get("transaction_isolation")))throw reject("SNAPSHOT_UNVERIFIED");
                database=inspector.scalar(c,"SELECT current_database()");user=inspector.scalar(c,"SELECT current_user");identity=inspector.scalar(c,"SELECT pg_backend_pid()");
            } else {
                settings.put("sql_mode",inspector.scalar(c,"SELECT @@sql_mode"));settings.put("transaction_isolation",inspector.scalar(c,"SELECT @@transaction_isolation"));settings.put("transaction_read_only",inspector.scalar(c,"SELECT @@transaction_read_only"));
                if(!"1".equals(settings.get("transaction_read_only"))||!"REPEATABLE-READ".equals(settings.get("transaction_isolation")))throw reject("SNAPSHOT_UNVERIFIED");
                database=inspector.scalar(c,"SELECT DATABASE()");user=inspector.scalar(c,"SELECT CURRENT_USER()");identity=inspector.scalar(c,"SELECT CONNECTION_ID()");
            }
            inspector.check(c,dialect,t.request.source().sql());inspector.check(c,dialect,t.request.candidateSql());
            snapshot=new Snapshot("SHARED_RR",product,version,database,user,"REPEATABLE_READ",true,identity,Map.copyOf(settings),List.copyOf(inspector.proofs));
            t.control.check();var left=ExactResultBag.read(c,dialect,t.request.source().sql(),t.control);a=left.side();
            t.control.check();var right=ExactResultBag.read(c,dialect,t.request.candidateSql(),t.control);b=right.side();t.control.check();
            verdict=left.columns().equals(right.columns())&&left.counts().equals(right.counts())?"MATCH":"DIFFERENT";
            if(!left.columns().equals(right.columns()))reason="SCHEMA_DIFFERENT";
            else if(verdict.equals("DIFFERENT")) {
                Set<List<Cell>> keys=new HashSet<>(left.counts().keySet());keys.addAll(right.counts().keySet());int evidenceBytes=0;
                for(var key:keys) {long x=left.counts().getOrDefault(key,0L),y=right.counts().getOrDefault(key,0L);if(x==y)continue;
                    var d=new Difference(key,Long.toString(x),Long.toString(y));int bytes=json.writeValueAsBytes(d).length;
                    if(differences.size()>=20||evidenceBytes+bytes>256*1024){truncated=true;continue;}differences.add(d);evidenceBytes+=bytes;
                }
            }
        } catch(Exception e) {
            verdict="INCONCLUSIVE";reason=t.control.stopReason();
            if(reason==null)reason=e instanceof OptimizationFailure f?f.reason:e instanceof com.bitdata.sqlworkbench.connection.WorkloadBusyException?"WORKLOAD_BUSY":e instanceof IllegalArgumentException?e.getMessage():e instanceof SQLException se&&permissionDenied(se)?"PERMISSION_DENIED":"QUERY_FAILED";
            status=e instanceof IllegalArgumentException?"COMPLETED":"FAILED";
            if("CONNECTION_CHANGED".equals(e.getMessage()))reason="CONNECTION_CHANGED";
        } finally {
            if(c!=null) {
                try{if(!c.getAutoCommit())c.rollback();}catch(Exception e){reason="ROLLBACK_FAILED";verdict="INCONCLUSIVE";status="FAILED";}
                // A failed close cannot free this slot. Keep the connection owned and observable until closed.
                boolean closed=false;while(!closed) {
                    try{c.close();closed=c.isClosed();}catch(Exception e){t.control.cleanupReason="CONNECTION_CLEANUP_PENDING";}
                    if(!closed){synchronized(this){t.report=build(t,"RUNNING","INCONCLUSIVE","CONNECTION_CLEANUP_PENDING",snapshot,a,b,List.of(),false,started,null,new Persistence("PENDING",null));}java.util.concurrent.locks.LockSupport.parkNanos(200_000_000L);}
                }
            }
        }
        synchronized(this) {
            if(t.control.stopReason()!=null){reason=t.control.stopReason();status="CANCELLED".equals(reason)?"CANCELLED":"TIMED_OUT";verdict="INCONCLUSIVE";differences=List.of();truncated=false;}
            finish(t,status,verdict,reason,snapshot,a,b,differences,truncated,started);
        }
    }
    static boolean permissionDenied(SQLException e) {
        return "42501".equals(e.getSQLState()) || Set.of(1044,1045,1142,1143,1227).contains(e.getErrorCode());
    }
    private Report build(Task t,String status,String verdict,String reason,Snapshot snapshot,Side a,Side b,List<Difference> differences,boolean truncated,String started,String completed,Persistence persistence) {
        return new Report(1,t.id,status,verdict,reason,!Set.of("MATCH","DIFFERENT","NOT_EVALUATED").contains(verdict),t.request.source(),t.request.candidateSql(),t.request.candidateId(),t.evidence,"EXACT_TYPED_BAG_V1",snapshot,a,b,List.copyOf(differences),truncated,started,completed,persistence);
    }
    private void finish(Task t,String status,String verdict,String reason,Snapshot snapshot,Side a,Side b,List<Difference> differences,boolean truncated,String started) {
        String completed=Instant.now().toString();
        Report report=build(t,status,verdict,reason,snapshot,a,b,differences,truncated,started,completed,new Persistence("SAVED",null));
        try {
            if(json.writeValueAsBytes(report).length>1024*1024)report=build(t,status,verdict,reason,snapshot,a,b,List.of(),true,started,completed,new Persistence("SAVED",null));
            if(json.writeValueAsBytes(report).length>1024*1024)report=build(t,"COMPLETED","INCONCLUSIVE","REPORT_BUDGET_EXCEEDED",null,a,b,List.of(),true,started,completed,new Persistence("SAVED",null));
            store.save(report);
        }catch(Exception e){report=build(t,report.status(),report.verdict(),report.errorReason(),report.snapshot(),report.original(),report.candidate(),report.differences(),report.differencesTruncated(),started,completed,new Persistence("FAILED",e instanceof OptimizationFailure f?f.reason:"REPORT_SAVE_FAILED"));}
        t.report=report;t.terminalAt=System.currentTimeMillis();
    }
    @PreDestroy public synchronized void close() {for(Task t:tasks.values())if(t.terminalAt==0){t.control.cancelled.set(true);stop(t);}timer.shutdown();worker.shutdown();canceller.shutdown();}
}
