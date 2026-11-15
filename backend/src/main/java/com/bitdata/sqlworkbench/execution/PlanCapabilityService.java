package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.PlanCapabilities;
import com.bitdata.sqlworkbench.execution.model.PlanCapabilities.ModeCapability;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.util.concurrent.*;

@Component
public class PlanCapabilityService {
    private final ConnectionService connections;
    private final ExplainServiceFactory adapters;
    private final ThreadPoolExecutor probes=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
        r->{var t=new Thread(r,"plan-capability");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final java.util.Map<String,Probe> active=new ConcurrentHashMap<>();
    private final OracleNetworkTimeoutExecutor network=new OracleNetworkTimeoutExecutor(2,8);
    private volatile boolean stopping;
    private static final class Probe {
        volatile WorkloadLeaseManager.Ticket ticket,connectionTicket;volatile boolean finished;volatile String reason;
        volatile OracleNetworkTimeoutExecutor.Registration registration;
    }
    public PlanCapabilityService(ConnectionService connections,ExplainServiceFactory adapters) { this.connections=connections; this.adapters=adapters; }
    public PlanCapabilities get(String id) {
        String dialect=connections.getById(id).orElseThrow().dialect();
        if(stopping)return unknown(id,dialect,"APPLICATION_SHUTDOWN");
        if("oracle".equals(dialect)&&"SID".equals(connections.getById(id).orElseThrow().connectionType()))return unknown(id,dialect,"CONNECTION_TYPE_UNVERIFIED");
        if("sqlserver".equals(dialect)||"oracle".equals(dialect))return nativeCapability(id,dialect);
        try(Connection connection=connections.openJdbcConnectionWithTimeout(id,null,5000)) { return inspect(id,dialect,connection,adapters); }
        catch(CredentialFailure e) { throw e; }
        catch(Exception e) { return unknown(id,dialect,e instanceof WorkloadBusyException?"WORKLOAD_BUSY":"CAPABILITY_PROBE_FAILED"); }
    }
    private PlanCapabilities nativeCapability(String id,String dialect) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        var expired=new java.util.concurrent.atomic.AtomicBoolean();
        var configuration=connections.getById(id).orElseThrow();String revision=ConnectionIdentity.revision(configuration);
        var probe=new Probe();String probeId=java.util.UUID.randomUUID().toString();active.put(probeId,probe);
        Runnable checkpoint=()->{
            if(expired.get()||stopping||System.nanoTime()>=deadline)throw new IllegalStateException("CAPABILITY_BUDGET_EXCEEDED");
            var current=connections.getById(id).orElseThrow(()->new IllegalStateException("CONNECTION_CHANGED"));
            if(!revision.equals(ConnectionIdentity.revision(current))||!java.util.Objects.equals(configuration.credentialState(),current.credentialState())||!java.util.Objects.equals(configuration.keychainRef(),current.keychainRef()))throw new IllegalStateException("CONNECTION_CHANGED");
        };
        var task=new FutureTask<PlanCapabilities>(()->{
            Connection connection=null;WorkloadLeaseManager.Ticket connectionTicket=null;PlanCapabilities result;
            try {
                checkpoint.run();probe.ticket=connections.acquireOrdinaryWorkload();connectionTicket=probe.ticket.retain();probe.connectionTicket=connectionTicket;
                connection=connections.openOrdinaryWithTimeout(id,revision,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())),connectionTicket);
                checkpoint.run();
                if("oracle".equals(dialect)){
                    probe.registration=network.register(connectionTicket,reason->probe.reason=reason);
                    connection.setNetworkTimeout(probe.registration,(int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
                }
                result=inspect(id,dialect,connection,adapters,checkpoint);
            } finally {
                if(connection!=null)try{connection.close();}catch(java.sql.SQLException|RuntimeException failure){probe.reason="CAPABILITY_CLEANUP_UNCONFIRMED";}
                if(connectionTicket!=null&&connectionTicket.isReleased()&&!connectionTicket.isUnconfirmed()&&probe.registration!=null)probe.registration.connectionCloseConfirmed();
                probe.finished=true;if(probe.ticket!=null)probe.ticket.release();
                removeSettledProbes();
            }
            if(probe.reason!=null)return unknown(id,dialect,probe.reason);
            if(probe.ticket!=null&&!Boolean.TRUE.equals(probe.ticket.resourceSnapshot().get("entryReleased")))return unknown(id,dialect,"CAPABILITY_CLEANUP_PENDING");
            return result;
        });
        try {
            probes.execute(task);
            return task.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        } catch(TimeoutException timeout) {expired.set(true);return unknown(id,dialect,"CAPABILITY_BUDGET_EXCEEDED");}
        catch(InterruptedException interrupted) {expired.set(true);Thread.currentThread().interrupt();return unknown(id,dialect,"CAPABILITY_PROBE_INTERRUPTED");}
        catch(RejectedExecutionException busy) {active.remove(probeId);return unknown(id,dialect,"CAPABILITY_PROBE_BUSY");}
        catch(ExecutionException failure) {
            if(failure.getCause() instanceof CredentialFailure credential)throw credential;
            return unknown(id,dialect,failure.getCause() instanceof WorkloadBusyException?"WORKLOAD_BUSY":"CAPABILITY_PROBE_FAILED");
        }
    }
    private void removeSettledProbes(){
        active.entrySet().removeIf(e->{
            var p=e.getValue();
            boolean closed=p.connectionTicket!=null&&p.connectionTicket.isReleased()&&!p.connectionTicket.isUnconfirmed();
            if(closed&&p.registration!=null)p.registration.connectionCloseConfirmed();
            if(closed&&"CAPABILITY_CLEANUP_UNCONFIRMED".equals(p.reason))p.reason=null;
            return p.finished&&(p.registration==null||p.registration.pendingCallbacks()==0)
                &&(p.ticket==null||Boolean.TRUE.equals(p.ticket.resourceSnapshot().get("entryReleased")));
        });
    }
    public java.util.Map<String,Object> shutdownSnapshot(){
        removeSettledProbes();var rows=active.values().stream().map(p->java.util.Map.of("finished",p.finished,"errorReason",p.reason==null?"CAPABILITY_WORK_PENDING":p.reason,
            "resources",p.ticket==null?java.util.Map.of():p.ticket.resourceSnapshot())).toList();
        return java.util.Map.of("drained",rows.isEmpty(),"pending",rows.size(),"tasks",rows);
    }
    public void beginShutdown(){stopping=true;probes.shutdown();network.beginShutdown();}
    @jakarta.annotation.PreDestroy public void close() {beginShutdown();}
    static PlanCapabilities unknown(String id,String dialect,String reason) {
        return new PlanCapabilities(id,dialect,null,null,new ModeCapability("UNKNOWN",reason),new ModeCapability("UNKNOWN",reason),null,true,reason);
    }
    static PlanCapabilities inspect(String id,String dialect,Connection connection,ExplainServiceFactory adapters) throws java.sql.SQLException {
        return inspect(id,dialect,connection,adapters,()->{});
    }
    static PlanCapabilities inspect(String id,String dialect,Connection connection,ExplainServiceFactory adapters,Runnable checkpoint) throws java.sql.SQLException {
        if("oracle".equals(dialect))return OraclePlanCollector.inspect(id,connection,adapters,checkpoint);
        var metadata=connection.getMetaData();
        String product=metadata.getDatabaseProductName(), version=metadata.getDatabaseProductVersion();
        if(product==null || version==null || version.isBlank()) return unknown(id,dialect,"SERVER_METADATA_UNVERIFIED");
        if("sqlserver".equals(dialect)) {
            checkpoint.run();
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                try(var row=statement.executeQuery("SELECT CONVERT(nvarchar(128),SERVERPROPERTY('ProductVersion')) AS product_version, compatibility_level FROM sys.databases WHERE name=DB_NAME()")) {
                    if(!row.next())return unknown(id,dialect,"SERVER_METADATA_UNVERIFIED");
                    String nativeVersion=row.getString("product_version");
                    boolean supported="Microsoft SQL Server".equals(product)&&"17.0.800.3".equals(nativeVersion)&&row.getInt("compatibility_level")==170&&adapters.supports(dialect);
                    String reason=supported?null:"SERVER_VERSION_UNVERIFIED";
                    return new PlanCapabilities(id,dialect,product,nativeVersion,new ModeCapability(supported?"SUPPORTED":"UNKNOWN",reason),
                        new ModeCapability(supported?"SUPPORTED":"UNKNOWN",reason),java.time.Instant.now().toString(),!supported,reason);
                }
            }
        }
        boolean pg="postgresql".equals(dialect) && product.equalsIgnoreCase("PostgreSQL");
        boolean mysql="mysql".equals(dialect) && product.equalsIgnoreCase("MySQL");
        boolean supported=(pg || mysql) && adapters.supports(dialect);
        boolean validated=pg?version.matches("^16\\.14(?:\\D.*)?$"):mysql&&version.matches("^8\\.0\\.46(?:\\D.*)?$");
        if(supported&&!validated)return new PlanCapabilities(id,dialect,product,version,new ModeCapability("UNKNOWN","SERVER_VERSION_UNVERIFIED"),new ModeCapability("UNKNOWN","SERVER_VERSION_UNVERIFIED"),java.time.Instant.now().toString(),true,"SERVER_VERSION_UNVERIFIED");
        var estimated=new ModeCapability(supported?"SUPPORTED":"UNSUPPORTED",supported?null:"APPLICATION_CHANNEL_UNSUPPORTED");
        var actual=new ModeCapability(supported?"SUPPORTED":"UNSUPPORTED",supported?null:"APPLICATION_CHANNEL_UNSUPPORTED");
        return new PlanCapabilities(id,dialect,product,version,estimated,actual,java.time.Instant.now().toString(),false,null);
    }
}
