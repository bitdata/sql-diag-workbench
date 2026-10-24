package com.bitdata.sqlworkbench.storage;

import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class StorageService {
    public record Policy(boolean enabled, int retentionDays, int maxStorageMiB, long revision) {}
    public record Size(long mainBytes, long walBytes, long shmBytes, long totalBytes, long reusableBytes,
                       boolean degraded, String errorReason) {}
    public record Candidate(String id, String name, String updatedAt, long estimatedBytes) {}
    public record Preview(long revision, Policy policy, Size size, long protectedSessions, long unknownSessions,
                          Map<String,Long> expiredCounts, List<Candidate> unknown, boolean degraded, String errorReason) {}
    public record Job(String id, String trigger, String status, String phase, long policyRevision, Map<String,Integer> deleted,
                      Size before, Size after, boolean targetMet, boolean degraded, String errorReason, List<String> skippedReasons) {}
    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"storage-cleanup"); t.setDaemon(true); return t; });
    private volatile boolean protectionReady;
    private volatile boolean cancel;
    private volatile Job job;
    private volatile String schedulerError;
    private static final Map<String,String> TIME = Map.of("sessions","updated_at","history","executed_at","diagnostic_reports","created_at","edit_audit","classified_at");
    private static final String SESSION_SAFE = "EXISTS(SELECT 1 FROM storage_reclaimable r WHERE r.session_id=s.id) AND NOT EXISTS(SELECT 1 FROM storage_protection p WHERE p.session_id=s.id)";

    @org.springframework.beans.factory.annotation.Autowired
    public StorageService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    StorageService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc=jdbc; this.clock=clock;
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public Policy policy() {
        return jdbc.queryForObject("SELECT * FROM storage_policy WHERE id=1", (r,n) -> new Policy(r.getInt("enabled")!=0,r.getInt("retention_days"),r.getInt("max_storage_mib"),r.getLong("revision")));
    }
    public Policy apply(boolean enabled, int days, int mib, long expected) {
        if (days<1 || days>365 || mib<16 || mib>102400) throw new Failure(400,"INVALID_POLICY","Days must be 1–365 and storage must be 16–102400 MiB.");
        return tx.execute(s -> {
            int changed=jdbc.update("UPDATE storage_policy SET enabled=?,retention_days=?,max_storage_mib=?,revision=revision+1 WHERE id=1 AND revision=?",enabled?1:0,days,mib,expected);
            if (changed==0) throw new Failure(409,"POLICY_CONFLICT","Policy changed. Reload it before applying your draft.");
            return policy();
        });
    }
    private void beginWrite() { jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1"); }
    private long generation() { return jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1",Long.class); }
    public void protect(String profile, List<String> ids) {
        if (profile==null || !profile.matches("[0-9a-fA-F-]{36}") || ids==null || ids.size()>1000 || ids.stream().anyMatch(id -> id==null || id.isBlank() || id.length()>200))
            throw new Failure(400,"INVALID_PROTECTION","Invalid workspace protection inventory.");
        tx.executeWithoutResult(s -> {
            beginWrite();
            for (String id:new HashSet<>(ids)) {
                jdbc.update("INSERT OR IGNORE INTO storage_protection(profile_id,session_id) VALUES(?,?)",profile,id);
                jdbc.update("DELETE FROM storage_reclaimable WHERE session_id=?",id);
            }
        });
        protectionReady=true;
    }
    public void reclaimable(List<String> ids, long expected) {
        if (ids==null || ids.isEmpty() || ids.size()>200 || ids.stream().anyMatch(id -> id==null || id.isBlank())) throw new Failure(400,"INVALID_CANDIDATES","Choose up to 200 unknown workspaces.");
        tx.executeWithoutResult(s -> {
            // Acquire the writer lock before checking a preview; external writers cannot interleave.
            jdbc.update("UPDATE storage_generation SET revision=revision WHERE id=1");
            if (generation()!=expected) throw new Failure(409,"PREVIEW_CHANGED","Workspace protection changed. Refresh the preview.");
            for(String id:ids) {
                if (jdbc.queryForObject("SELECT count(*) FROM sessions WHERE id=?",Integer.class,id)==0 || jdbc.queryForObject("SELECT count(*) FROM storage_protection WHERE session_id=?",Integer.class,id)>0)
                    throw new Failure(409,"WORKSPACE_PROTECTED","This workspace is protected or no longer exists.");
                jdbc.update("INSERT OR IGNORE INTO storage_reclaimable VALUES(?)",id);
            }
            beginWrite();
        });
    }
    public Size measure() {
        try {
            String file=jdbc.queryForList("PRAGMA database_list").stream().filter(r -> "main".equals(r.get("name"))).map(r -> String.valueOf(r.get("file"))).findFirst().orElseThrow();
            if(file.isBlank()) throw new IllegalStateException("Not a file database");
            Path main=Path.of(file);
            long db=Files.size(main), wal=optionalSize(Path.of(file+"-wal")), shm=optionalSize(Path.of(file+"-shm"));
            if(!Files.exists(main)) throw new NoSuchFileException(file);
            long free=jdbc.queryForObject("PRAGMA freelist_count",Long.class)*jdbc.queryForObject("PRAGMA page_size",Long.class);
            return new Size(db,wal,shm,Math.addExact(db,Math.addExact(wal,shm)),free,false,null);
        } catch(Exception e) { return new Size(0,0,0,0,0,true,"SIZE_UNAVAILABLE"); }
    }
    private long optionalSize(Path path) throws java.io.IOException {
        try { return Files.size(path); } catch(NoSuchFileException absent) { return 0; }
    }
    private String safe(String table) {
        if(table.equals("sessions")) return SESSION_SAFE;
        if(table.equals("history")) return "1=1";
        return "(NOT EXISTS(SELECT 1 FROM sessions owner WHERE owner.id=s.session_id) OR EXISTS(SELECT 1 FROM storage_reclaimable r WHERE r.session_id=s.session_id)) AND NOT EXISTS(SELECT 1 FROM storage_protection p WHERE p.session_id=s.session_id)";
    }
    public Preview preview() {
        Policy policy=policy(); String threshold=clock.instant().minusSeconds(policy.retentionDays()*86400L).toString();
        Map<String,Long> counts=new TreeMap<>();
        for(var entry:TIME.entrySet()) counts.put(entry.getKey(),jdbc.queryForObject("SELECT count(*) FROM "+entry.getKey()+" s WHERE "+safe(entry.getKey())+" AND julianday("+entry.getValue()+")<julianday(?)",Long.class,threshold));
        var unknown=jdbc.query("SELECT id,name,updated_at,length(CAST(sql_text AS BLOB))+COALESCE(length(CAST(workspace_snapshot_json AS BLOB)),0)+COALESCE(length(CAST(fragment_tree_json AS BLOB)),0) AS bytes FROM sessions s WHERE NOT EXISTS(SELECT 1 FROM storage_protection p WHERE p.session_id=s.id) AND NOT EXISTS(SELECT 1 FROM storage_reclaimable r WHERE r.session_id=s.id) ORDER BY updated_at,id LIMIT 200",
            (r,n)->new Candidate(r.getString("id"),r.getString("name"),r.getString("updated_at"),r.getLong("bytes")));
        long protectedCount=jdbc.queryForObject("SELECT count(*) FROM sessions s WHERE EXISTS(SELECT 1 FROM storage_protection p WHERE p.session_id=s.id)",Long.class);
        long unknownCount=jdbc.queryForObject("SELECT count(*) FROM sessions s WHERE NOT EXISTS(SELECT 1 FROM storage_protection p WHERE p.session_id=s.id) AND NOT EXISTS(SELECT 1 FROM storage_reclaimable r WHERE r.session_id=s.id)",Long.class);
        boolean invalid=false;
        for(var entry:TIME.entrySet()) if(jdbc.queryForObject("SELECT count(*) FROM "+entry.getKey()+" WHERE julianday("+entry.getValue()+") IS NULL",Long.class)>0) invalid=true;
        Size size=measure();
        return new Preview(generation(),policy,size,protectedCount,unknownCount,counts,unknown,invalid||size.degraded(),invalid?"INVALID_RETENTION_TIMESTAMP":size.errorReason());
    }
    public Map<String,Object> status() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("policy",policy()); result.put("size",measure()); result.put("protectionReady",protectionReady);
        result.put("job",job); result.put("degraded",!protectionReady||schedulerError!=null);
        result.put("errorReason",schedulerError!=null?schedulerError:!protectionReady?"PROTECTION_UNCONFIRMED":null);
        return result;
    }
    public synchronized Job start(long expected, String trigger) {
        if(job!=null && (job.status().equals("queued") || job.status().equals("running"))) return job;
        if(trigger.equals("scheduled") && !protectionReady) throw new Failure(409,"PROTECTION_UNCONFIRMED","Workspace protection has not been confirmed.");
        Policy p=policy();
        if(p.revision()!=expected) throw new Failure(409,"POLICY_CONFLICT","Policy changed. Refresh before cleaning.");
        if(!protectionReady) throw new Failure(409,"PROTECTION_UNCONFIRMED","Workspace protection has not been confirmed.");
        cancel=false;
        job=new Job(UUID.randomUUID().toString(),trigger,"queued","measure",p.revision(),Map.of(),measure(),null,false,false,null,List.of());
        Job started=job;
        executor.submit(()->run(started,p));
        return started;
    }
    public Job get(String id) { Job current=job; if(current==null || !current.id().equals(id)) throw new Failure(404,"JOB_NOT_FOUND","Cleanup job is unavailable."); return current; }
    public synchronized Job cancel(String id) { Job current=get(id); if(current.status().equals("queued")||current.status().equals("running")) cancel=true; return current; }
    private boolean stopped(Policy original) { Policy now=policy(); return cancel || now.revision()!=original.revision() || (job.trigger().equals("scheduled") && !now.enabled()); }
    private void publish(Job original, String status, String phase, Map<String,Integer> counts, Size after, boolean target, String reason, List<String> skipped) {
        job=new Job(original.id(),original.trigger(),status,phase,original.policyRevision(),Map.copyOf(counts),original.before(),after,target,reason!=null||!skipped.isEmpty(),reason,List.copyOf(skipped));
    }
    private void run(Job original, Policy policy) {
        Map<String,Integer> counts=new TreeMap<>(); List<String> skipped=new ArrayList<>();
        Size size=original.before(); String reason=null;
        try {
            if(size.degraded()) throw new Failure(503,"SIZE_UNAVAILABLE","Storage size cannot be measured.");
            publish(original,"running","retention",counts,size,false,null,skipped);
            for(var entry:TIME.entrySet()) if(jdbc.queryForObject("SELECT count(*) FROM "+entry.getKey()+" WHERE julianday("+entry.getValue()+") IS NULL",Long.class)>0 && !skipped.contains("INVALID_RETENTION_TIMESTAMP")) skipped.add("INVALID_RETENTION_TIMESTAMP");
            for(String table:List.of("history","diagnostic_reports","edit_audit","sessions")) {
                while(!stopped(policy)) {
                    Batch batch=deleteBatch(table,policy,true);
                    int changed=batch.changed();
                    batch.deleted().forEach((key,n)->counts.merge(key,n,Integer::sum));
                    if(changed==0) break;
                    publish(original,"running","retention",counts,size,false,null,skipped);
                }
            }
            if(stopped(policy)) reason="CLEANUP_STOPPED";
            if(reason==null) {
                publish(original,"running","compact",counts,size,false,null,skipped);
                compact(skipped); size=measure();
                long target=policy.maxStorageMiB()*1024L*1024L;
                // Reclaim empty pages before evicting any younger records.
                while(!size.degraded() && size.totalBytes()>target && !stopped(policy) && !skipped.contains("COMPACTION_DEFERRED")) {
                    int changed=0;
                    for(String table:List.of("history","diagnostic_reports","edit_audit","sessions")) {
                        Batch batch=deleteBatch(table,policy,false);
                        int n=batch.changed(); changed+=n; batch.deleted().forEach((key,count)->counts.merge(key,count,Integer::sum));
                        if(n>0) break;
                    }
                    if(changed==0) { reason="PROTECTED_DATA_EXCEEDS_TARGET"; break; }
                    publish(original,"running","capacity",counts,size,false,null,skipped);
                    compact(skipped); size=measure();
                }
                if(stopped(policy)) reason="CLEANUP_STOPPED";
                if(size.degraded()) reason="SIZE_UNAVAILABLE";
                else if(size.totalBytes()>target && skipped.contains("COMPACTION_DEFERRED")) reason="COMPACTION_DEFERRED";
            }
            boolean met=!size.degraded()&&size.totalBytes()<=policy.maxStorageMiB()*1024L*1024L;
            publish(original,reason==null&&skipped.isEmpty()?"completed":"partial","done",counts,size,met,reason,skipped);
        } catch(Exception error) {
            reason=error instanceof Failure f?f.code:"CLEANUP_FAILED";
            publish(original,counts.values().stream().anyMatch(n->n>0)?"partial":"failed","done",counts,measure(),false,reason,skipped);
        }
    }
    private record Batch(int changed, Map<String,Integer> deleted) {}
    private Batch deleteBatch(String table, Policy original, boolean expired) {
        return tx.execute(s -> {
            beginWrite();
            if(stopped(original)) return new Batch(0,Map.of());
            String time=TIME.get(table), threshold=clock.instant().minusSeconds(original.retentionDays()*86400L).toString();
            String filter=safe(table)+" AND julianday("+time+") IS NOT NULL"+(expired?" AND julianday("+time+")<julianday(?)":"");
            String query="SELECT id,"+time+" AS stamp FROM "+table+" s WHERE "+filter+" ORDER BY julianday("+time+"),id LIMIT 200";
            var rows=expired?jdbc.queryForList(query,threshold):jdbc.queryForList(query);
            int count=0;
            Map<String,Integer> deleted=new TreeMap<>();
            for(var row:rows) {
                // SQLite's timestamp parser is also the indexed preview/cutoff parser.
                // An additional Java parser would strand later valid rows behind a batch of legacy dates.
                if(table.equals("sessions")) {
                    for(String child:List.of("diagnostic_reports","edit_audit")) {
                        int n=jdbc.queryForObject("SELECT count(*) FROM "+child+" WHERE session_id=?",Integer.class,row.get("id"));
                        deleted.merge(child,n,Integer::sum);
                    }
                }
                count+=jdbc.update("DELETE FROM "+table+" WHERE id=?",row.get("id"));
            }
            deleted.merge(table,count,Integer::sum);
            return new Batch(count,deleted);
        });
    }
    private void compact(List<String> skipped) {
        try {
            if(!(jdbc.getDataSource() instanceof StorageDataSource ds)) throw new IllegalStateException("No maintenance gate");
            ds.compact();
        } catch(Exception error) { if(!skipped.contains("COMPACTION_DEFERRED")) skipped.add("COMPACTION_DEFERRED"); }
    }
    @Scheduled(initialDelay=60000, fixedDelay=86400000)
    public void scheduled() {
        try { Policy p=policy(); if(p.enabled()) start(p.revision(),"scheduled"); schedulerError=null; }
        catch(Exception error) { schedulerError=error instanceof Failure f?f.code:"CLEANUP_SCHEDULE_FAILED"; }
    }
    @PreDestroy public void close() { cancel=true; executor.shutdown(); }
}
