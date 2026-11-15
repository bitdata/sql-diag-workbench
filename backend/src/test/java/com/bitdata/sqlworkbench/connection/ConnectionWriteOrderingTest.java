package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.storage.StorageDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite/Spring transactions, no remote JDBC, deterministic competing write at first read. */
class ConnectionWriteOrderingTest {
    @TempDir Path root;
    StorageDataSource ds;
    ObservedJdbc jdbc;
    ConnectionStore store;
    KeychainStore keys;
    ConnectionService service;
    WorkloadLeaseManager leases;
    ExecutorService worker;
    final List<Future<?>> writers = new ArrayList<>();
    boolean failProtect;
    class ObservedJdbc extends JdbcTemplate {
        boolean armed;
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ObservedJdbc(StorageDataSource ds) { super(ds); }
        void beforeRead() {
            if (!armed || !TransactionSynchronizationManager.isActualTransactionActive()) return;
            Object resource = TransactionSynchronizationManager.getResource(ds);
            if (!seen.add(resource)) return;
            var entered = new CountDownLatch(1);
            Future<?> future = worker.submit(() -> {
                entered.countDown();
                new JdbcTemplate(ds).update("UPDATE sessions SET name='concurrent' WHERE id='owner'");
            });
            writers.add(future);
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> future.get(100,TimeUnit.MILLISECONDS),
                    "Competing write must not overtake a transaction's first read");
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            beforeRead(); return super.queryForObject(sql,type,args);
        }
    }
    @BeforeEach void setup() throws Exception {
        ds=new StorageDataSource(); ds.setUrl("jdbc:sqlite:"+root.resolve("db.sqlite"));
        jdbc=new ObservedJdbc(ds); jdbc.execute("PRAGMA journal_mode=WAL");
        var init=SqliteConfig.class.getDeclaredMethod("initSchema",JdbcTemplate.class);
        init.setAccessible(true); init.invoke(new SqliteConfig(),jdbc);
        new com.bitdata.sqlworkbench.session.SessionStore(jdbc).save(
            com.bitdata.sqlworkbench.session.Session.builder().id("owner").name("owner").sqlText("SELECT 1")
                .dialect("sqlserver").identityPolicy("structural-p0p1-v2").build());
        worker=Executors.newCachedThreadPool();
        keys=new KeychainStore(jdbc,new CredentialCrypto() {
            public void requireAvailable() {}
            public byte[] protect(byte[] clear) { if(failProtect) throw new CredentialFailure("CREDENTIAL_SAVE_FAILED",503,"Synthetic"); return clear.clone(); }
            public byte[] unprotect(byte[] bytes) { return bytes.clone(); }
        },root.resolve("legacy.dat").toString());
        store=new ConnectionStore(jdbc) {
            @Override public Optional<ConnectionConfig> findById(String id) {
                jdbc.beforeRead(); return super.findById(id);
            }
        };
        leases=new WorkloadLeaseManager();
        var factory=new ProxyFactory(new ConnectionService(store,keys,DatabaseStrategyFactory.withBuiltinStrategies(),leases));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(ConnectionService)factory.getProxy();
    }
    @AfterEach void close() throws Exception {
        jdbc.armed=false;
        try { for(var future:writers) future.get(7,TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); leases.close(); }
    }
    ConnectionRequest request() { return new ConnectionRequest("synthetic","localhost",1,"synthetic","synthetic",null,"sqlserver",null,true); }
    long generation() { return jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1",Long.class); }
    @ParameterizedTest @ValueSource(strings={"delete","update"})
    void mutationReservesBeforeReading(String operation) {
        var conn=service.create(request()); long generation=generation();
        jdbc.armed=true;
        if(operation.equals("delete")) assertTrue(service.delete(conn.id()));
        else assertTrue(service.update(conn.id(),request()).isPresent());
        jdbc.armed=false;
        assertFalse(writers.isEmpty()); assertEquals(generation,generation());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM sessions",Integer.class));
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-reference","missing","invalid-base64","decrypt-failed","valid","protect-failed"})
    void everyMigrationTransactionReservesBeforeReading(String mode) throws Exception {
        var old=store.insert("old","localhost",1,"synthetic","synthetic","sqlserver","legacy",true,null,false);
        if(mode.equals("duplicate-reference")) store.insert("other","localhost",1,"synthetic","synthetic","sqlserver","legacy",true,null,false);
        String value=switch(mode) {
            case "invalid-base64" -> "!";
            case "decrypt-failed" -> Base64.getEncoder().encodeToString(new byte[40]);
            case "valid", "protect-failed" -> legacy();
            default -> null;
        };
        if(value!=null) Files.writeString(root.resolve("legacy.dat"),"legacy="+value+"\n");
        failProtect=mode.equals("protect-failed");
        long generation=generation(); jdbc.armed=true;
        var migration=keys.migrate(); jdbc.armed=false;
        assertFalse(writers.isEmpty()); assertEquals(generation,generation());
        if(mode.equals("valid")) { assertFalse(migration.degraded()); assertEquals("synthetic",keys.retrieve(old.id())); }
        else assertTrue(migration.degraded());
        if(mode.equals("protect-failed")) assertEquals(2,jdbc.seen.size(),"normal callback rollback plus pending fallback");
    }
    @Test void migratedCredentialCannotOverwriteConfirmedReplacementOrResurrectDeletedConnection() throws Exception {
        var conn=service.create(request());
        Files.writeString(root.resolve("legacy.dat"),conn.keychainRef()+"="+legacy()+"\n");
        keys.migrate(); assertEquals("NO_PASSWORD",keys.state(conn.id()).intent());
        service.delete(conn.id()); keys.migrate();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM connection_credentials",Integer.class));
    }
    @Test void concurrentDeletesCommitOnceAndRetainWorkspace() throws Exception {
        var conn=service.create(request());
        var both=Executors.newFixedThreadPool(2);
        try {
            var first=both.submit(()->service.delete(conn.id()));
            var second=both.submit(()->service.delete(conn.id()));
            assertNotEquals(first.get(7,TimeUnit.SECONDS),second.get(7,TimeUnit.SECONDS));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM connection_credentials",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM sessions",Integer.class));
        } finally {both.shutdownNow();}
    }
    @Test void heldWriterTimesOutObservablyThenFreshRequestSucceeds() throws Exception {
        var conn=service.create(request());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ConnectionController(service,keys))
            .setControllerAdvice(new com.bitdata.sqlworkbench.storage.StorageErrorAdvice()).build();
        var acquired=new CountDownLatch(1); var release=new CountDownLatch(1);
        var holder=worker.submit(()->new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->{
            com.bitdata.sqlworkbench.storage.SqliteWriteReservation.acquire(jdbc); acquired.countDown();
            try {assertTrue(release.await(10,TimeUnit.SECONDS));} catch(InterruptedException e){throw new RuntimeException(e);} return null;
        }));
        try {
            assertTrue(acquired.await(2,TimeUnit.SECONDS));
            long start=System.nanoTime();
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/connections/"+conn.id())).andReturn().getResponse();
            assertEquals(500,response.getStatus());
            assertTrue(response.getContentAsString().contains("STORAGE_ERROR"));
            assertTrue(response.getContentAsString().contains("\"degraded\":true"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<8000);
        } finally {release.countDown();holder.get(7,TimeUnit.SECONDS);}
        assertTrue(store.findById(conn.id()).isPresent());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM connection_credentials",Integer.class));
        assertTrue(service.delete(conn.id()));
    }
    @Test void maintenanceIs503AndDoesNotPartiallyDelete() throws Exception {
        var conn=service.create(request());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ConnectionController(service,keys))
            .setControllerAdvice(new com.bitdata.sqlworkbench.storage.StorageErrorAdvice()).build();
        var gate=(java.util.concurrent.locks.ReentrantReadWriteLock)org.springframework.test.util.ReflectionTestUtils.getField(ds,"gate");
        gate.writeLock().lock();
        try {worker.submit(()->{
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/connections/"+conn.id())).andReturn().getResponse();
            assertEquals(503,response.getStatus());assertTrue(response.getContentAsString().contains("STORAGE_MAINTENANCE"));
            assertTrue(response.getContentAsString().contains("\"degraded\":true"));return null;
        }).get(3,TimeUnit.SECONDS);} finally {gate.writeLock().unlock();}
        assertTrue(store.findById(conn.id()).isPresent());assertTrue(service.delete(conn.id()));
    }
    private String legacy() throws Exception {
        byte[] key=MessageDigest.getInstance("SHA-256").digest((System.getenv("COMPUTERNAME")+"/"+System.getProperty("user.name")).getBytes(StandardCharsets.UTF_8));
        byte[] iv=new byte[12]; var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        byte[] sealed=cipher.doFinal("synthetic".getBytes(StandardCharsets.UTF_8));
        byte[] combined=Arrays.copyOf(iv,12+sealed.length);System.arraycopy(sealed,0,combined,12,sealed.length);
        return Base64.getEncoder().encodeToString(combined);
    }
}
