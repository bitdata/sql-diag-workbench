package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.WorkloadBusyException;
import com.bitdata.sqlworkbench.connection.WorkloadLeaseManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.math.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.*;
import com.bitdata.sqlworkbench.optimization.OptimizationSql.Source;
import com.bitdata.sqlworkbench.optimization.OptimizationSql.Snapshot;
import com.bitdata.sqlworkbench.optimization.OptimizationSql.Side;
import com.bitdata.sqlworkbench.optimization.OptimizationSql.Difference;

/** PERF-04 task runner. It owns one exclusive experiment slot and never retries samples. */
@Service
public class PerformanceExperimentTasks {
    private static final long MAX_ROWS=1_000_000, MAX_CELLS=5_000_000, MAX_BYTES=128L*1024*1024;
    private final ConnectionService connections; private final OptimizationSources sources; private final PerformanceExperimentReportStore store; private final ObjectMapper json; private final PerformanceExperimentPlanCapture planCapture;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->daemon(r,"performance-experiment"));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"performance-experiment-timer"));
    private final ThreadPoolExecutor canceller=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->daemon(r,"performance-experiment-cancel"));
    private final Map<String,Task> tasks=new LinkedHashMap<>(); private final Map<String,Long> expired=new LinkedHashMap<>();
    // Revalidation descriptors are the reference for every timed read.
    private static Thread daemon(Runnable r,String n){var t=new Thread(r,n);t.setDaemon(true);return t;}
    final class Task implements Runnable { final String id=UUID.randomUUID().toString(); final Request request; final String seed; final ValidationControl control; final WorkloadLeaseManager.Ticket lease; volatile ActualPlans actualPlans; volatile Report report; volatile long terminalAt; volatile boolean cancelScheduled; volatile Statement cancelTarget; Side expectedOriginal,expectedCandidate; long cumulativeRows,cumulativeCells,cumulativeBytes;
        Task(Request r,String seed,WorkloadLeaseManager.Ticket lease){request=r;this.seed=seed;this.lease=lease;control=new ValidationControl(r.timeoutSeconds()==null?120:r.timeoutSeconds());report=build(this,"QUEUED","ADMISSION","NOT_EVALUATED",null,null,List.of(),new Summary("NOT_AVAILABLE",null,null,null,null,List.of("INCOMPLETE_OR_INCOMPARABLE")),new Persistence("PENDING",null),null,null);}
        public void run(){execute(this);}
    }
    public PerformanceExperimentTasks(ConnectionService c,OptimizationSources s,PerformanceExperimentReportStore store,ObjectMapper json,PerformanceExperimentPlanCapture planCapture){connections=c;sources=s;this.store=store;this.json=json;this.planCapture=planCapture;timer.scheduleWithFixedDelay(this::tick,100,100,TimeUnit.MILLISECONDS);}
    public synchronized Submission submit(Request request){validate(request);sources.check(request.source());try{OptimizationSql.policy(request.candidateSql(),request.source().dialect());}catch(Exception e){throw OptimizationFailure.invalid(e.getMessage());}prune();if(tasks.values().stream().anyMatch(t->t.terminalAt==0))throw new OptimizationFailure(409,"EXPERIMENT_BUSY","WORKLOAD_BUSY");if(tasks.size()>=16)throw new OptimizationFailure(429,"EXECUTION_CAPACITY_EXHAUSTED","EXPERIMENT_CAPACITY_EXHAUSTED");String seed=request.seed()==null?Integer.toUnsignedString(ThreadLocalRandom.current().nextInt()):request.seed();WorkloadLeaseManager.Ticket lease;try{lease=connections.acquireExclusiveWorkload();}catch(SQLException busy){throw new OptimizationFailure(409,"EXPERIMENT_BUSY",busy instanceof WorkloadBusyException ? "WORKLOAD_BUSY" : "WORKLOAD_ADMISSION_FAILED");}Task t=new Task(request,seed,lease);tasks.put(t.id,t);try{worker.submit(t);}catch(RejectedExecutionException rejected){tasks.remove(t.id);lease.release();throw new OptimizationFailure(429,"EXECUTION_CAPACITY_EXHAUSTED","EXPERIMENT_CAPACITY_EXHAUSTED");}return new Submission(t.id);}
    private void validate(Request r){if(r==null||r.version()!=1||r.source()==null||r.candidateSql()==null||r.candidateSql().isBlank())throw OptimizationFailure.invalid("INVALID_REQUEST");if(r.timeoutSeconds()!=null&&(r.timeoutSeconds()<1||r.timeoutSeconds()>300))throw OptimizationFailure.invalid("TIMEOUT_INVALID");if(r.seed()!=null)try{var n=new BigInteger(r.seed());if(!r.seed().matches("0|[1-9][0-9]{0,9}")||n.signum()<0||n.compareTo(BigInteger.valueOf(0xffffffffL))>0)throw new Exception();}catch(Exception e){throw OptimizationFailure.invalid("SEED_INVALID");}}
    public synchronized Report get(String id){prune();return task(id).report;}
    public synchronized Report cancel(String id){Task t=task(id);if(t.terminalAt==0){t.control.cancelled.set(true);signalCancel(t);}return t.report;}
    private Task task(String id){OptimizationSources.token(id);var t=tasks.get(id);if(t==null)throw new OptimizationFailure(expired.containsKey(id)?410:404,expired.containsKey(id)?"EXPERIMENT_EXPIRED":"EXPERIMENT_NOT_FOUND",expired.containsKey(id)?"EXPERIMENT_EXPIRED":"EXPERIMENT_NOT_FOUND");return t;}
    private synchronized void tick(){for(var t:tasks.values())if(t.terminalAt==0&&t.control.stopReason()!=null)signalCancel(t);prune();}
    private void signalCancel(Task task){
        Statement statement=task.control.statement;
        if(statement==null||task.cancelScheduled||task.cancelTarget==statement)return;
        task.cancelScheduled=true;
        task.cancelTarget=statement;
        try{canceller.execute(()->{try{statement.cancel();}catch(Exception failure){task.control.cleanupReason="CANCEL_SIGNAL_FAILED";}finally{task.cancelScheduled=false;}});}
        catch(RejectedExecutionException rejected){task.cancelScheduled=false;task.cancelTarget=null;task.control.cleanupReason="CANCEL_SIGNAL_PENDING";}
    }
    private void prune(){long now=System.currentTimeMillis();var it=tasks.entrySet().iterator();while(it.hasNext()){var e=it.next();if(e.getValue().terminalAt>0&&!e.getValue().lease.isUnconfirmed()&&now-e.getValue().terminalAt>=300000){expired.put(e.getKey(),now);it.remove();}}while(expired.size()>64)expired.remove(expired.keySet().iterator().next());}
    private void execute(Task t){String started=Instant.now().toString(),reason=null,status="COMPLETED",comparison="INCOMPARABLE";Snapshot snapshot=null;Validation validation=null;Environment environment=null;List<Sample> samples=new ArrayList<>();Summary summary=new Summary("NOT_AVAILABLE",null,null,null,null,List.of("INCOMPLETE_OR_INCOMPARABLE"));Connection c=null;
        try{t.report=build(t,"RUNNING","CONNECTING","NOT_EVALUATED",null,null,samples,summary,new Persistence("PENDING",null),started,null);c=connections.openJdbcConnection(t.request.source().connectionId(),t.request.source().connectionRevision(),t.lease);String d=t.request.source().dialect();checkServer(c,d);configurePreparation(c,d);var inspector=new SnapshotSources(t.control);if(d.equals("mysql"))inspector.execute(c,"SET SESSION TRANSACTION READ ONLY");c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);if(!c.isReadOnly()||c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)throw new ExperimentStop("TRANSACTION_POLICY_UNCONFIRMED");inspector.prepare(c,d,t.request.source().sql(),t.request.candidateSql());t.report=build(t,"RUNNING","VALIDATING","IN_PROGRESS",null,null,samples,summary,new Persistence("PENDING",null),started,null);
            inspector.check(c,d,t.request.source().sql());inspector.check(c,d,t.request.candidateSql());var a=ExactResultBag.read(c,d,t.request.source().sql(),t.control);var b=ExactResultBag.read(c,d,t.request.candidateSql(),t.control);synchronized(t){t.expectedOriginal=a.side();t.expectedCandidate=b.side();t.cumulativeBytes=a.bytes()+b.bytes();if(t.cumulativeBytes>MAX_BYTES)throw new ExperimentStop("EXPERIMENT_BYTE_BUDGET_EXCEEDED");t.cumulativeRows=a.rows()+b.rows();t.cumulativeCells=(long)a.rows()*a.columns().size()+(long)b.rows()*b.columns().size();if(t.cumulativeRows>MAX_ROWS)throw new ExperimentStop("EXPERIMENT_ROW_BUDGET_EXCEEDED");if(t.cumulativeCells>MAX_CELLS)throw new ExperimentStop("EXPERIMENT_CELL_BUDGET_EXCEEDED");}String verdict=a.columns().equals(b.columns())&&a.counts().equals(b.counts())?"MATCH":"DIFFERENT";validation=new Validation(verdict,verdict.equals("MATCH")?null:"RESULT_DIFFERENT","EXACT_TYPED_BAG_V1",null,a.side(),b.side(),List.of(),false);if(!verdict.equals("MATCH"))throw new ExperimentStop("RESULT_DIFFERENT");
            String product=c.getMetaData().getDatabaseProductName(),version=c.getMetaData().getDatabaseProductVersion();Map<String,String> before=environment(c,d,t.control);snapshot=new Snapshot("SHARED_RR",product,version,c.getCatalog()==null?"":c.getCatalog(),c.getMetaData().getUserName(),"REPEATABLE_READ",true,before.get("sessionIdentity"),before,List.copyOf(inspector.proofs));if(validation!=null)validation=new Validation(validation.verdict(),validation.errorReason(),validation.comparisonMode(),snapshot,validation.original(),validation.candidate(),validation.differences(),validation.differencesTruncated());
            var orders=orders(t.seed);for(int p=0;p<2;p++)for(char side:orders.get(p).toCharArray())appendSample(samples,c,d,side=='A'?t.request.source().sql():t.request.candidateSql(),p,"WARMUP",orders.get(p),side,t);
            for(int p=0;p<10;p++)for(char side:orders.get(p+2).toCharArray())appendSample(samples,c,d,side=='A'?t.request.source().sql():t.request.candidateSql(),p,"MEASURED",orders.get(p+2),side,t);
            Map<String,String> after=environment(c,d,t.control);environment=new Environment(before,after,before.equals(after),List.of("CACHES_NOT_ISOLATED","EXTERNAL_LOAD_NOT_ISOLATED"));if(!environment.comparable())throw new ExperimentStop("ENVIRONMENT_CHANGED");summary=summarize(samples,true);comparison="COMPLETE";
            t.report=build(t,"RUNNING","ACTUAL_PLAN","COMPLETE",null,null,samples,summary,new Persistence("PENDING",null),started,null);
            t.actualPlans=planCapture.capture(c,d,t.request.source().sql(),t.request.candidateSql(),t.request.source().connectionRevision(),t.control);
            String planStop=t.control.stopReason();if(planStop!=null){reason=planStop;status=planStop.equals("CANCELLED")?"CANCELLED":"TIMED_OUT";}
        }catch(SampleFailure e){reason=e.sample.errorReason();status=e.sample.status().equals("CANCELLED")?"CANCELLED":e.sample.status().equals("TIMED_OUT")?"TIMED_OUT":"FAILED";comparison="INCOMPARABLE";}catch(ExperimentStop e){reason=e.getMessage();status="CANCELLED".equals(reason)?"CANCELLED":"DEADLINE_EXCEEDED".equals(reason)?"TIMED_OUT":"COMPLETED";comparison="INCOMPARABLE";}catch(IllegalArgumentException e){reason=t.control.stopReason()!=null?t.control.stopReason():rejectionReason(e);status="CANCELLED".equals(reason)?"CANCELLED":"DEADLINE_EXCEEDED".equals(reason)?"TIMED_OUT":"COMPLETED";comparison="INCOMPARABLE";}catch(Exception e){reason=t.control.stopReason()!=null?t.control.stopReason():e instanceof OptimizationFailure f?f.reason:e instanceof SQLException?"QUERY_FAILED":"EXPERIMENT_FAILED";status="CANCELLED".equals(reason)?"CANCELLED":"DEADLINE_EXCEEDED".equals(reason)?"TIMED_OUT":"FAILED";comparison="INCOMPARABLE";}
        finally{if(t.actualPlans==null)t.actualPlans=skippedPlans(dialect(t),samples.size()==24?"ACTUAL_PLAN_NOT_CAPTURED":"SAMPLES_INCOMPLETE");boolean cleanupFailed=false;if(c!=null){try{c.rollback();}catch(Exception e){reason="ROLLBACK_FAILED";status="FAILED";comparison="INCOMPARABLE";cleanupFailed=true;}try{c.close();}catch(Exception e){reason="CONNECTION_CLEANUP_UNCONFIRMED";status="FAILED";comparison="INCOMPARABLE";cleanupFailed=true;}}if(cleanupFailed)summary=new Summary("NOT_AVAILABLE",null,null,null,null,List.of("INCOMPLETE_OR_INCOMPARABLE"));t.lease.release();if(validation==null)validation=new Validation("INCONCLUSIVE",reason,"EXACT_TYPED_BAG_V1",snapshot,null,null,List.of(),false);finish(t,status,comparison,reason,validation,environment,samples,summary,started);}
    }
    private Map<String,String> environment(Connection c,String d,ValidationControl control)throws Exception{var m=c.getMetaData();var x=new TreeMap<String,String>();x.put("product",m.getDatabaseProductName());x.put("version",m.getDatabaseProductVersion());x.put("driver",m.getDriverName());x.put("driverVersion",m.getDriverVersion());x.put("database",Objects.toString(c.getCatalog(),""));x.put("user",Objects.toString(m.getUserName(),""));x.put("readOnly",Boolean.toString(c.isReadOnly()));x.put("isolation",Integer.toString(c.getTransactionIsolation()));x.put("autoCommit",Boolean.toString(c.getAutoCommit()));if(d.equals("postgresql"))x.put("prepareThreshold",Integer.toString(c.unwrap(org.postgresql.PGConnection.class).getPrepareThreshold()));else{var properties=c.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getPropertySet();for(var key:List.of(com.mysql.cj.conf.PropertyKey.useServerPrepStmts,com.mysql.cj.conf.PropertyKey.cachePrepStmts,com.mysql.cj.conf.PropertyKey.useCursorFetch))x.put(key.getKeyName(),properties.getBooleanProperty(key).getValue().toString());}x.put("sessionIdentity",d.equals("postgresql")?scalar(c,"SELECT pg_backend_pid()",control):scalar(c,"SELECT CONNECTION_ID()",control));for(String setting:d.equals("postgresql")?List.of("enable_hashjoin","enable_mergejoin","enable_nestloop","enable_seqscan","jit","max_parallel_workers_per_gather","work_mem","search_path"):List.of("optimizer_switch","sql_mode","time_zone","collation_connection"))x.put("setting."+setting,scalar(c,d.equals("postgresql")?"SHOW "+setting:"SELECT @@session."+setting,control));return x;}
    private String scalar(Connection c,String sql,ValidationControl control)throws Exception{return new SnapshotSources(control).scalar(c,sql);}
    void appendSample(List<Sample> samples,Connection c,String d,String sql,int pair,String phase,String order,char side,Task t)throws SampleFailure{
        t.report=build(t,"RUNNING",phase,"IN_PROGRESS",null,null,samples,unavailable(),new Persistence("PENDING",null),t.report.startedAt(),null);
        Sample sample=readSample(c,d,sql,pair,phase,order,side,t);samples.add(sample);
        if(!"COMPLETE".equals(sample.status()))throw new SampleFailure(sample);
    }
    private Sample readSample(Connection c,String d,String sql,int pair,String phase,String order,char side,Task t){
        long start=System.nanoTime(),returned=0,drain=0,rows=0,bytes=0;
        try(PreparedStatement ps=c.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
            try{
                t.control.attach(ps);ps.setFetchSize(d.equals("mysql")?Integer.MIN_VALUE:128);
                try(ResultSet rs=ps.executeQuery()){
                    returned=System.nanoTime()-start;var md=rs.getMetaData();int n=md.getColumnCount();
                    if(n<1||n>ExactResultBag.MAX_COLUMNS)throw new ExperimentStop("COLUMN_BUDGET_EXCEEDED");
                    var columns=new ArrayList<OptimizationSql.Column>();
                    for(int i=1;i<=n;i++)columns.add(new OptimizationSql.Column(md.getColumnLabel(i),md.getColumnType(i),md.getColumnTypeName(i),md.getPrecision(i),md.getScale(i),md.isSigned(i)));
                    Side expected=side=='A'?t.expectedOriginal:t.expectedCandidate;
                    if(expected!=null&&!expected.columns().equals(columns))throw new ExperimentStop("SAMPLE_SCHEMA_CHANGED");
                    while(rs.next()){
                        t.control.check();rows++;if(rows>ExactResultBag.MAX_ROWS)throw new ExperimentStop("ROW_BUDGET_EXCEEDED");if(rows*n>ExactResultBag.MAX_CELLS)throw new ExperimentStop("CELL_COUNT_BUDGET_EXCEEDED");long rowBytes=8;
                        for(int i=1;i<=n;i++){var cell=ExactResultBag.cell(rs,i,md.getColumnType(i),t.control);rowBytes+=8+(cell.value()==null?0:cell.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);}
                        if(bytes+rowBytes>ExactResultBag.MAX_BYTES)throw new ExperimentStop("BYTE_BUDGET_EXCEEDED");
                        bytes+=rowBytes;synchronized(t){t.cumulativeRows++;t.cumulativeCells+=n;t.cumulativeBytes+=rowBytes;if(t.cumulativeRows>MAX_ROWS)throw new ExperimentStop("EXPERIMENT_ROW_BUDGET_EXCEEDED");if(t.cumulativeCells>MAX_CELLS)throw new ExperimentStop("EXPERIMENT_CELL_BUDGET_EXCEEDED");if(t.cumulativeBytes>MAX_BYTES)throw new ExperimentStop("EXPERIMENT_BYTE_BUDGET_EXCEEDED");}
                    }
                    t.control.check();if(expected!=null&&!expected.rowCount().equals(Long.toString(rows)))throw new ExperimentStop("SAMPLE_ROW_COUNT_CHANGED");
                    drain=System.nanoTime()-start-returned;
                }
            }finally{t.control.detach(null);}
        }catch(Exception failure){
            String reason=t.control.stopReason();if(reason==null)reason=failure instanceof ExperimentStop stop?stop.getMessage():failure instanceof OptimizationFailure rejected?rejected.reason:failure instanceof IllegalArgumentException rejected?rejectionReason(rejected):failure instanceof SQLException?"QUERY_FAILED":"EXPERIMENT_FAILED";
            if(reason==null||reason.isBlank())reason="EXPERIMENT_FAILED";
            String sampleStatus=reason.equals("CANCELLED")?"CANCELLED":reason.equals("DEADLINE_EXCEEDED")?"TIMED_OUT":"FAILED";
            return new Sample(pair,phase,order,String.valueOf(side),sampleStatus,Long.toString(System.nanoTime()-start),returned==0?null:Long.toString(returned),null,null,Long.toString(rows),Long.toString(bytes),reason);
        }
        long total=System.nanoTime()-start;return new Sample(pair,phase,order,String.valueOf(side),"COMPLETE",Long.toString(total),Long.toString(returned),Long.toString(drain),Long.toString(Math.max(0,total-returned-drain)),Long.toString(rows),Long.toString(bytes),null);
    }
    static List<String> orders(String seed){long x=Long.parseUnsignedLong(seed);var a=new ArrayList<>(List.of("AB","BA","AB","AB","AB","AB","BA","BA","BA","BA","BA","AB"));for(int i=11;i>2;i--){x=(1664525L*x+1013904223L)&0xffffffffL;Collections.swap(a,i,2+(int)(x%(i-1)));}return List.copyOf(a);}
    Summary summarize(List<Sample> all,boolean comparable){var m=all.stream().filter(s->s.phase().equals("MEASURED")&&s.status().equals("COMPLETE")).toList();if(!comparable||m.size()!=20)return new Summary("NOT_AVAILABLE",null,null,null,null,List.of("INCOMPLETE_OR_INCOMPARABLE"));var a=m.stream().filter(s->s.side().equals("A")).map(s->new BigDecimal(s.durationNs())).sorted().toList();var b=m.stream().filter(s->s.side().equals("B")).map(s->new BigDecimal(s.durationNs())).sorted().toList();if(a.size()!=10||b.size()!=10)return unavailable();
        var deltas=new ArrayList<BigDecimal>();
        for(int pair=0;pair<10;pair++){
            final int index=pair;var paired=m.stream().filter(sample->sample.pair()==index).toList();
            if(paired.size()!=2)return unavailable();
            String order=paired.get(0).order();
            if(!List.of("AB","BA").contains(order)||!order.equals(paired.get(1).order())||!paired.get(0).side().equals(order.substring(0,1))||!paired.get(1).side().equals(order.substring(1)))return unavailable();
            BigDecimal first=new BigDecimal(paired.get(0).durationNs()),second=new BigDecimal(paired.get(1).durationNs());
            deltas.add(order.equals("AB")?first.subtract(second):second.subtract(first));
        }
        Collections.sort(deltas);var da=distribution(a);var db=distribution(b);var am=median(a);var bm=median(b);String ratio=am.signum()==0?null:exact(am.subtract(bm).multiply(BigDecimal.valueOf(100)).divide(am,2,RoundingMode.HALF_UP));return new Summary("DESCRIPTIVE",da,db,exact(median(deltas)),ratio,List.of("DESCRIPTIVE_ONLY","CACHES_NOT_ISOLATED","EXTERNAL_LOAD_NOT_ISOLATED"));}
    private Summary unavailable(){return new Summary("NOT_AVAILABLE",null,null,null,null,List.of("INCOMPLETE_OR_INCOMPARABLE"));}
    private Distribution distribution(List<BigDecimal> a){return new Distribution(exact(median(a)),exact(median(a.subList(0,5))),exact(median(a.subList(5,10))),exact(a.get(0)),exact(a.get(9)));}
    private BigDecimal median(List<BigDecimal> a){int n=a.size();return n%2==1?a.get(n/2):a.get(n/2-1).add(a.get(n/2)).divide(BigDecimal.valueOf(2));}
    private String exact(BigDecimal n){return n.stripTrailingZeros().toPlainString();}
    private Report build(Task t,String status,String phase,String comparison,String reason,Snapshot snapshot,List<Sample> samples,Summary summary,Persistence persistence,String started,String completed){return new Report(1,t.id,status,phase,comparison,reason,!"COMPLETE".equals(comparison)||t.actualPlans!=null&&t.actualPlans.degraded(),t.request,new Protocol("PAIRED_SELECT_V1",t.seed,2,10,"LCG32_FISHER_YATES_V1","THRESHOLD_ZERO","CLIENT_PREPARED_STREAMING",t.request.source().dialect().equals("postgresql")?128:Integer.MIN_VALUE),null,null,List.copyOf(samples),summary,persistence,t.actualPlans,Instant.now().toString(),started,completed);}
    private void finish(Task t,String status,String comparison,String reason,Validation validation,Environment env,List<Sample> samples,Summary summary,String started){String phase="CONNECTION_CLEANUP_UNCONFIRMED".equals(reason)?"CLEANUP_UNCONFIRMED":status.equals("COMPLETED")?"DONE":"CLEANUP";var report=new Report(1,t.id,status,phase,comparison,reason,!"COMPLETE".equals(comparison)||t.actualPlans!=null&&t.actualPlans.degraded(),t.request,new Protocol("PAIRED_SELECT_V1",t.seed,2,10,"LCG32_FISHER_YATES_V1","THRESHOLD_ZERO","CLIENT_PREPARED_STREAMING",t.request.source().dialect().equals("postgresql")?128:Integer.MIN_VALUE),validation,env,List.copyOf(samples),summary,new Persistence("SAVED",null),t.actualPlans,Instant.now().toString(),started,Instant.now().toString());try{store.save(report);t.report=report;}catch(Exception e){t.report=new Report(report.version(),report.experimentId(),report.status(),report.phase(),report.comparisonState(),report.errorReason(),true,report.request(),report.protocol(),report.validation(),report.environment(),report.samples(),report.summary(),new Persistence("FAILED","REPORT_SAVE_FAILED"),report.actualPlans(),report.createdAt(),report.startedAt(),report.completedAt());}t.terminalAt=System.currentTimeMillis();}
    private String rejectionReason(IllegalArgumentException failure){String code=failure.getMessage();return code!=null&&code.matches("[A-Z][A-Z0-9_]{0,127}")?code:"EXPERIMENT_FAILED";}
    private String dialect(Task task){return task.request.source().dialect();}
    private void checkServer(Connection connection,String dialect)throws Exception {
        String product=connection.getMetaData().getDatabaseProductName(),version=connection.getMetaData().getDatabaseProductVersion();
        if(dialect.equals("mysql")?(!"MySQL".equals(product)||!"8.0.46".equals(version)):(!"PostgreSQL".equals(product)||version==null||!version.matches("16\\.14(?: .*|$)")))throw new ExperimentStop("SERVER_VERSION_UNVERIFIED");
    }
    private void configurePreparation(Connection connection,String dialect)throws Exception {
        if(dialect.equals("postgresql")){
            var pg=connection.unwrap(org.postgresql.PGConnection.class);
            pg.setPrepareThreshold(0);
            if(pg.getPrepareThreshold()!=0)throw new ExperimentStop("PREPARATION_POLICY_UNCONFIRMED");
        }else{
            var properties=connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getPropertySet();
            for(var key:List.of(com.mysql.cj.conf.PropertyKey.useServerPrepStmts,com.mysql.cj.conf.PropertyKey.cachePrepStmts,com.mysql.cj.conf.PropertyKey.useCursorFetch))
                if(properties.getBooleanProperty(key).getValue())throw new ExperimentStop("PREPARATION_POLICY_UNCONFIRMED");
        }
    }
    private ActualPlans skippedPlans(String dialect,String reason){String channel="postgresql".equals(dialect)?"POSTGRESQL_JSON":"MYSQL_TREE";var missing=new ActualPlanEvidence("SKIPPED",dialect,channel,null,null,null,Instant.now().toString(),null,null,null,null,reason,true);return new ActualPlans(missing,missing,true,reason);}
    @PreDestroy public void close(){worker.shutdownNow();timer.shutdownNow();canceller.shutdownNow();}
    private static final class ExperimentStop extends Exception{ExperimentStop(String m){super(m);}}
    static final class SampleFailure extends Exception{final Sample sample;SampleFailure(Sample sample){super(sample.errorReason());this.sample=sample;}}
}
