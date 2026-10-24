package com.bitdata.sqlworkbench.connection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** DPAPI ciphertext and intent are published in the caller's SQLite transaction. */
@Component
public class KeychainStore {
    private final JdbcTemplate jdbc;
    private final CredentialCrypto crypto;
    private final TransactionTemplate tx;
    private final Path legacyPath;
    public record State(String intent, String errorReason, boolean degraded) {}
    public record Migration(String status, int unresolved, String errorReason, boolean degraded, String sourceHash, boolean sourcePresent) {
        public Migration(String status,int unresolved,String errorReason,boolean degraded) {
            this(status,unresolved,errorReason,degraded,null,false);
        }
    }

    public KeychainStore(JdbcTemplate jdbc, CredentialCrypto crypto,
            @Value("${sql-workbench.credentials.legacy-path:}") String legacyPath) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        String testRoot = System.getenv("SQL_WORKBENCH_TEST_ROOT");
        if (testRoot != null && !testRoot.isBlank()) {
            Path root = Paths.get(testRoot).toAbsolutePath().normalize();
            this.legacyPath = root.resolve("data/credentials.dat");
            if (!legacyPath.isBlank() && !Paths.get(legacyPath).toAbsolutePath().normalize().equals(this.legacyPath))
                throw new IllegalArgumentException("Test credential source must be inside the test root.");
        } else if (!legacyPath.isBlank()) this.legacyPath = Paths.get(legacyPath).toAbsolutePath().normalize();
        else {
            String appdata = System.getenv("APPDATA");
            this.legacyPath = appdata == null ? null : Paths.get(appdata,"sql-diag-workbench","data","credentials.dat");
        }
    }
    public void requireAvailable() { crypto.requireAvailable(); }
    private void requireConnection(String id) {
        if (jdbc.queryForObject("SELECT count(*) FROM connections WHERE id=?",Integer.class,id)!=1)
            throw new CredentialFailure("CREDENTIAL_MISSING",409,"Connection credentials could not be located. Reopen the connection settings.");
    }
    public void store(String id, String password) {
        requireConnection(id);
        byte[] clear = password.getBytes(StandardCharsets.UTF_8);
        byte[] sealed = null, verified = null;
        try {
            sealed = crypto.protect(clear);
            verified = crypto.unprotect(sealed);
            if (!MessageDigest.isEqual(clear,verified)) throw new CredentialFailure("CREDENTIAL_SAVE_FAILED",503,"Password protection could not be verified. Retry saving.");
            jdbc.update("INSERT INTO connection_credentials(connection_id,intent,format,ciphertext,error_reason) VALUES(?,'AVAILABLE','dpapi-user-v1',?,NULL) ON CONFLICT(connection_id) DO UPDATE SET intent='AVAILABLE',format='dpapi-user-v1',ciphertext=excluded.ciphertext,error_reason=NULL",id,sealed);
        } finally {
            Arrays.fill(clear,(byte)0);
            if(verified!=null) Arrays.fill(verified,(byte)0);
            if(sealed!=null) Arrays.fill(sealed,(byte)0);
        }
    }
    public void noPassword(String id) {
        crypto.requireAvailable();
        requireConnection(id);
        stateWrite(id,"NO_PASSWORD",null);
    }
    private void stateWrite(String id,String intent,String reason) {
        jdbc.update("INSERT INTO connection_credentials(connection_id,intent,error_reason) VALUES(?,?,?) ON CONFLICT(connection_id) DO UPDATE SET intent=excluded.intent,format=NULL,ciphertext=NULL,error_reason=excluded.error_reason",id,intent,reason);
    }
    private void markPending(String id,String intent,String reason) {
        if (jdbc.queryForObject("SELECT count(*) FROM connections WHERE id=?",Integer.class,id)==0) return;
        State current=storedState(id);
        if(!current.intent.equals("AVAILABLE")&&!current.intent.equals("NO_PASSWORD")) stateWrite(id,intent,reason);
    }
    public String retrieve(String id) {
        crypto.requireAvailable();
        requireConnection(id);
        var rows=jdbc.queryForList("SELECT intent,format,ciphertext FROM connection_credentials WHERE connection_id=?",id);
        if(rows.isEmpty()) {
            migrate();
            rows=jdbc.queryForList("SELECT intent,format,ciphertext FROM connection_credentials WHERE connection_id=?",id);
        }
        if(rows.isEmpty()) throw new CredentialFailure("CREDENTIAL_MISSING",409,"Edit the connection to confirm its password settings.");
        var row=rows.get(0);
        if("NO_PASSWORD".equals(row.get("intent"))) return "";
        if(!"AVAILABLE".equals(row.get("intent"))) throw pending(String.valueOf(row.get("intent")));
        if(!"dpapi-user-v1".equals(row.get("format")) || !(row.get("ciphertext") instanceof byte[] blob))
            throw new CredentialFailure("CREDENTIAL_DECRYPT_FAILED",409,"The saved password is damaged. Edit the connection and re-enter it.");
        byte[] clear=crypto.unprotect(blob);
        try { return new String(clear,StandardCharsets.UTF_8); }
        finally { Arrays.fill(clear,(byte)0); }
    }
    public State state(String id) {
        State state=storedState(id);
        if (!state.intent.equals("AVAILABLE")) return state;
        var row=jdbc.queryForMap("SELECT format,ciphertext FROM connection_credentials WHERE connection_id=?",id);
        byte[] clear=null;
        try {
            if (!"dpapi-user-v1".equals(row.get("format")) || !(row.get("ciphertext") instanceof byte[] blob))
                return new State("NEEDS_REENTRY","CREDENTIAL_DECRYPT_FAILED",true);
            clear=crypto.unprotect(blob);
            return state;
        } catch(CredentialFailure e) { return new State("NEEDS_REENTRY",e.code,true); }
        finally { if(clear!=null) Arrays.fill(clear,(byte)0); }
    }
    private State storedState(String id) {
        try { crypto.requireAvailable(); }
        catch(CredentialFailure e) { return new State("UNAVAILABLE",e.code,true); }
        var rows=jdbc.queryForList("SELECT intent,error_reason FROM connection_credentials WHERE connection_id=?",id);
        if(rows.isEmpty()) return new State("NEEDS_REVIEW","CREDENTIAL_NEEDS_REVIEW",true);
        String intent=String.valueOf(rows.get(0).get("intent"));
        return new State(intent,(String)rows.get(0).get("error_reason"), !intent.equals("AVAILABLE")&&!intent.equals("NO_PASSWORD"));
    }
    public void delete(String id) {
        jdbc.update("DELETE FROM connection_credentials WHERE connection_id=?",id);
    }
    private static CredentialFailure pending(String intent) {
        return new CredentialFailure(intent.equals("NEEDS_REENTRY")?"CREDENTIAL_DECRYPT_FAILED":"CREDENTIAL_NEEDS_REVIEW",409,
            "Edit the connection and enter its password, or explicitly confirm that it uses no password.");
    }

    /** Per-connection transactions; source remains intact until an explicit cleanup. */
    public synchronized Migration migrate() {
        var connections=jdbc.queryForList("SELECT id,keychain_ref FROM connections");
        try { crypto.requireAvailable(); }
        catch(CredentialFailure e) {
            jdbc.update("UPDATE credential_migration SET status='partial',unresolved=?,error_reason=? WHERE id=1",connections.size(),e.code);
            return new Migration("partial",connections.size(),e.code,true);
        }
        List<String> lines;
        String hash;
        try {
            byte[] bytes=legacyPath!=null && Files.exists(legacyPath)?Files.readAllBytes(legacyPath):new byte[0];
            hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            lines=Arrays.asList(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().split("\\R",-1));
        } catch(Exception e) {
            jdbc.update("UPDATE credential_migration SET status='partial',error_reason='LEGACY_READ_FAILED',unresolved=? WHERE id=1",connections.size());
            return new Migration("partial",connections.size(),"LEGACY_READ_FAILED",true);
        }
        jdbc.update("UPDATE credential_migration SET status='running' WHERE id=1");
        Map<String,List<String>> entries=new HashMap<>();
        int unresolved=0;
        for(String line:lines) {
            if(line.isEmpty()) continue;
            int at=line.indexOf('=');
            if(at<=0) { unresolved++; continue; }
            entries.computeIfAbsent(line.substring(0,at), k->new ArrayList<>()).add(line.substring(at+1));
        }
        Set<String> references=new HashSet<>();
        Map<String,Long> refCounts=new HashMap<>();
        for(var conn:connections) refCounts.merge((String)conn.get("keychain_ref"),1L,Long::sum);
        for(var conn:connections) {
            String id=(String)conn.get("id"), ref=(String)conn.get("keychain_ref");
            references.add(ref);
            var values=entries.get(ref);
            if(refCounts.get(ref)>1) {
                tx.execute(s->{markPending(id,"NEEDS_REVIEW","CREDENTIAL_NEEDS_REVIEW");return null;});
                unresolved++; continue;
            }
            State existing=storedState(id);
            if(existing.intent.equals("AVAILABLE")||existing.intent.equals("NO_PASSWORD")) {
                if(state(id).degraded) unresolved++;
                if(values!=null && values.size()!=1) unresolved+=values.size();
                else if(values!=null) {
                    try { if(Base64.getDecoder().decode(values.get(0)).length<28) unresolved++; }
                    catch(IllegalArgumentException error) { unresolved++; }
                }
                continue;
            }
            if(values==null||values.size()!=1) {
                tx.execute(s->{markPending(id,"NEEDS_REVIEW","CREDENTIAL_NEEDS_REVIEW"); return null;});
                unresolved+=values==null?1:values.size();
                continue;
            }
            byte[] combined;
            try {
                combined=Base64.getDecoder().decode(values.get(0));
                if(combined.length<28) throw new IllegalArgumentException();
            } catch(IllegalArgumentException e) {
                tx.execute(s->{markPending(id,"NEEDS_REVIEW","LEGACY_FORMAT_INVALID"); return null;});
                unresolved++; continue;
            }
            byte[] clear;
            try {
                String seed=System.getenv("COMPUTERNAME")+"/"+System.getProperty("user.name");
                byte[] key=MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
                try {
                    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(combined,12)));
                    clear=cipher.doFinal(combined,12,combined.length-12);
                } finally { Arrays.fill(key,(byte)0); }
            } catch(Exception e) {
                tx.execute(s->{markPending(id,"NEEDS_REENTRY","CREDENTIAL_DECRYPT_FAILED"); return null;});
                unresolved++; continue;
            }
            try {
                tx.execute(s->{
                    if (jdbc.queryForObject("SELECT count(*) FROM connections WHERE id=?",Integer.class,id)==0) return null;
                    State current=storedState(id);
                    if(!current.intent.equals("AVAILABLE") && !current.intent.equals("NO_PASSWORD"))
                        store(id,new String(clear,StandardCharsets.UTF_8));
                    return null;
                });
            } catch(CredentialFailure e) {
                unresolved++;
                tx.execute(s->{markPending(id,"NEEDS_REENTRY",e.code);return null;});
            } finally { Arrays.fill(clear,(byte)0); }
        }
        for(var entry:entries.entrySet()) if(!references.contains(entry.getKey())) unresolved+=entry.getValue().size();
        String status=unresolved==0?"completed":"partial";
        String reason=unresolved==0?null:"CREDENTIAL_MIGRATION_PARTIAL";
        jdbc.update("UPDATE credential_migration SET status=?,unresolved=?,error_reason=?,source_hash=? WHERE id=1",status,unresolved,reason,hash);
        return new Migration(status,unresolved,reason,unresolved>0,hash,legacyPath!=null&&Files.exists(legacyPath));
    }

    /** Explicit user confirmation also covers records belonging to other data directories. */
    public synchronized Migration cleanup(String expectedHash,boolean confirmDiscard) {
        crypto.requireAvailable();
        Migration current=migrate();
        if(!confirmDiscard || expectedHash==null || !expectedHash.equals(current.sourceHash))
            throw new CredentialFailure("CREDENTIAL_SOURCE_CHANGED",409,"The legacy file changed. Review the migration summary again before removing it.");
        // Prove every AVAILABLE target is readable before removing any recovery material.
        for(var row:jdbc.queryForList("SELECT c.id FROM connections c JOIN connection_credentials p ON p.connection_id=c.id WHERE p.intent='AVAILABLE'"))
            retrieve((String)row.get("id"));
        try {
            if(legacyPath!=null && Files.exists(legacyPath)) {
                String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(legacyPath)));
                if(!expectedHash.equals(actual)) throw new CredentialFailure("CREDENTIAL_SOURCE_CHANGED",409,"The legacy file changed. Review the migration summary again.");
                Files.delete(legacyPath);
            }
        } catch(CredentialFailure e) { throw e; }
        catch(Exception e) { throw new CredentialFailure("CREDENTIAL_CLEANUP_FAILED",503,"The legacy file could not be removed. It has been retained for recovery. Retry cleanup."); }
        return migrate();
    }
}
