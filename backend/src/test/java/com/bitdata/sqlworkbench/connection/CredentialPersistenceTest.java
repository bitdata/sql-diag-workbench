package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class CredentialPersistenceTest {
    static final Path root;
    static { try { root=Files.createTempDirectory("credential-contract-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("sql-workbench.data-dir",()->root.toString());
        r.add("sql-workbench.credentials.legacy-path",()->root.resolve("credentials.dat").toString());
    }
    @Autowired ConnectionService service;
    @Autowired ConnectionStore connections;
    @Autowired KeychainStore keychain;
    @Autowired JdbcTemplate jdbc;
    @MockBean CredentialCrypto crypto;
    @BeforeEach void setup() throws Exception {
        reset(crypto);
        when(crypto.protect(any())).thenAnswer(i->((byte[])i.getArgument(0)).clone());
        when(crypto.unprotect(any())).thenAnswer(i->((byte[])i.getArgument(0)).clone());
        jdbc.execute("DROP TRIGGER IF EXISTS fail_delete");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_update");
        jdbc.execute("DELETE FROM connections");
        jdbc.execute("DELETE FROM connection_credentials");
        Files.deleteIfExists(root.resolve("credentials.dat"));
    }
    static ConnectionRequest request(String password) {
        return new ConnectionRequest("fixture","localhost",5432,"fixture","fixture",password,"postgresql",null,false);
    }
    @Test void passwordAndConnectionRollbackOnProtectionFailure() {
        doThrow(new CredentialFailure("CREDENTIAL_SAVE_FAILED",503,"Synthetic failure")).when(crypto).protect(any());
        assertThrows(CredentialFailure.class,()->service.create(request("synthetic")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM connections",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM connection_credentials",Integer.class));
    }
    @Test void passwordEditRollbackAndEmptyMeansKeep() {
        var conn=service.create(request("synthetic-before"));
        service.update(conn.id(),request(""));
        assertEquals("synthetic-before",service.getPassword(conn.id()));
        doThrow(new CredentialFailure("CREDENTIAL_SAVE_FAILED",503,"Synthetic failure")).when(crypto).protect(any());
        assertThrows(CredentialFailure.class,()->service.update(conn.id(),request("synthetic-after")));
        assertEquals("synthetic-before",service.getPassword(conn.id()));
    }
    @Test void deleteRollsBackPasswordWhenMetadataDeleteFails() {
        var conn=service.create(request("synthetic"));
        jdbc.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON connections BEGIN SELECT RAISE(ABORT,'synthetic'); END");
        assertThrows(RuntimeException.class,()->service.delete(conn.id()));
        assertTrue(connections.findById(conn.id()).isPresent());
        assertEquals("synthetic",service.getPassword(conn.id()));
        jdbc.execute("DROP TRIGGER fail_delete");
        assertTrue(service.delete(conn.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM connection_credentials",Integer.class));
    }
    @Test void explicitNoPasswordAndUncertainLegacyAreDifferent() {
        var fresh=service.create(request(""));
        assertEquals("NO_PASSWORD",keychain.state(fresh.id()).intent());
        assertEquals("",service.getPassword(fresh.id()));
        var old=connections.insert("old","localhost",5432,"fixture","fixture","postgresql","legacy-old",true,null,false);
        assertTrue(keychain.migrate().degraded());
        assertEquals("NEEDS_REVIEW",keychain.state(old.id()).intent());
        assertThrows(CredentialFailure.class,()->service.getPassword(old.id()));
        service.update(old.id(),request(""));
        assertEquals("NEEDS_REVIEW",keychain.state(old.id()).intent());
        service.update(old.id(),new ConnectionRequest("old","localhost",5432,"fixture","fixture","","postgresql",null,false,true,null));
        assertEquals("NO_PASSWORD",keychain.state(old.id()).intent());
    }
    @Test void validLegacyMigratesIdempotentlyWithoutOverwritingReplacement() throws Exception {
        var old=connections.insert("old","localhost",5432,"fixture","fixture","postgresql","legacy-old",true,null,false);
        Files.writeString(root.resolve("credentials.dat"),"legacy-old="+legacy("synthetic-old")+"\n",StandardCharsets.UTF_8);
        assertEquals("completed",keychain.migrate().status());
        assertEquals("synthetic-old",service.getPassword(old.id()));
        service.update(old.id(),request("synthetic-new"));
        keychain.migrate();
        assertEquals("synthetic-new",service.getPassword(old.id()));
    }
    @Test void duplicateOrOrphanAndBadRowsNeverDeleteSource() throws Exception {
        var old=connections.insert("old","localhost",5432,"fixture","fixture","postgresql","legacy-old",true,null,false);
        String fixture="legacy-old="+legacy("synthetic")+"\nlegacy-old="+legacy("other")+"\norphan=invalid\nbadrow\n";
        Files.writeString(root.resolve("credentials.dat"),fixture);
        assertTrue(keychain.migrate().degraded());
        assertEquals("NEEDS_REVIEW",keychain.state(old.id()).intent());
        assertEquals(fixture,Files.readString(root.resolve("credentials.dat")));
    }
    @Test void authenticationFailureRequestsReentry() throws Exception {
        var old=connections.insert("old","localhost",5432,"fixture","fixture","postgresql","legacy-old",true,null,false);
        Files.writeString(root.resolve("credentials.dat"),"legacy-old="+Base64.getEncoder().encodeToString(new byte[40])+"\n");
        keychain.migrate();
        assertEquals("NEEDS_REENTRY",keychain.state(old.id()).intent());
    }
    @Test void cleanupRequiresConfirmationAndFreshHash() throws Exception {
        Files.writeString(root.resolve("credentials.dat"),"orphan=bad\n");
        var migration=keychain.migrate();
        assertThrows(CredentialFailure.class,()->keychain.cleanup(migration.sourceHash(),false));
        assertThrows(CredentialFailure.class,()->keychain.cleanup("stale",true));
        assertTrue(Files.exists(root.resolve("credentials.dat")));
        assertEquals("completed",keychain.cleanup(migration.sourceHash(),true).status());
        assertFalse(Files.exists(root.resolve("credentials.dat")));
    }
    @Test void corruptCurrentBlobIsVisibleAndCannotBeOverwrittenOrCleanedUp() throws Exception {
        var conn=service.create(request("synthetic-current"));
        Files.writeString(root.resolve("credentials.dat"),conn.keychainRef()+"="+legacy("synthetic-old")+"\n");
        doThrow(new CredentialFailure("CREDENTIAL_DECRYPT_FAILED",409,"Synthetic failure")).when(crypto).unprotect(any());
        assertEquals("NEEDS_REENTRY",keychain.state(conn.id()).intent());
        var migration=keychain.migrate();
        assertTrue(migration.degraded());
        assertThrows(CredentialFailure.class,()->keychain.cleanup(migration.sourceHash(),true));
        assertTrue(Files.exists(root.resolve("credentials.dat")));
        assertArrayEquals("synthetic-current".getBytes(StandardCharsets.UTF_8),jdbc.queryForObject("SELECT ciphertext FROM connection_credentials WHERE connection_id=?",byte[].class,conn.id()));
    }
    @Test void nativeBackendUnavailableFailsBeforePublishingConnection() {
        doThrow(new CredentialFailure("CREDENTIAL_BACKEND_UNAVAILABLE",503,"Synthetic failure")).when(crypto).requireAvailable();
        assertThrows(CredentialFailure.class,()->service.create(request("synthetic")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM connections",Integer.class));
        assertTrue(keychain.migrate().degraded());
    }
    @Test void missingEditingConnectionDoesNotSilentlyTestWithoutPassword() {
        var result=service.test(new ConnectionRequest("fixture","localhost",1,"fixture","fixture","","postgresql",null,false,false,"missing"));
        assertEquals("CREDENTIAL_MISSING",result.get("errorReason"));
        assertEquals(true,result.get("degraded"));
    }
    @Test void unreadableLegacySourceIsObservableAndRetained() throws Exception {
        Files.createDirectory(root.resolve("credentials.dat"));
        try { assertEquals("LEGACY_READ_FAILED",keychain.migrate().errorReason()); }
        finally { Files.delete(root.resolve("credentials.dat")); }
    }
    @Test void duplicateLegacyReferencesRecoverIndependentlyAndDeleteKeepsSiblingPassword() {
        var first=connections.insert("first","localhost",5432,"fixture","fixture","postgresql","shared-legacy",true,null,false);
        var second=connections.insert("second","localhost",5432,"fixture","fixture","postgresql","shared-legacy",true,null,false);
        assertTrue(keychain.migrate().degraded());
        service.update(first.id(),request("synthetic-first"));
        service.update(second.id(),new ConnectionRequest("second","localhost",5432,"fixture","fixture","","postgresql",null,false,true,null));
        assertEquals("synthetic-first",service.getPassword(first.id()));
        assertEquals("",service.getPassword(second.id()));
        service.update(second.id(),request("synthetic-second"));
        assertTrue(service.delete(first.id()));
        assertEquals("synthetic-second",service.getPassword(second.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM connection_credentials",Integer.class));
    }
    @Test void metadataFailureAfterPasswordReplacementRollsBackBoth() {
        var conn=service.create(request("synthetic-before"));
        jdbc.execute("CREATE TRIGGER fail_update BEFORE UPDATE ON connections BEGIN SELECT RAISE(ABORT,'synthetic'); END");
        var changed=new ConnectionRequest("changed","different-host",5433,"fixture","fixture","synthetic-after","postgresql",null,false);
        assertThrows(RuntimeException.class,()->service.update(conn.id(),changed));
        assertEquals("synthetic-before",service.getPassword(conn.id()));
        assertEquals("fixture",connections.findById(conn.id()).orElseThrow().name());
        assertEquals("localhost",connections.findById(conn.id()).orElseThrow().host());
        jdbc.execute("DROP TRIGGER fail_update");
        service.update(conn.id(),changed);
        assertEquals("synthetic-after",service.getPassword(conn.id()));
        assertEquals("changed",connections.findById(conn.id()).orElseThrow().name());
    }
    @Test void storedPasswordRetrievalDoesNotDecryptUnrelatedConnections() {
        var first=service.create(request("synthetic-first"));
        service.create(request("synthetic-second"));
        clearInvocations(crypto);
        assertEquals("synthetic-first",service.getPassword(first.id()));
        verify(crypto,times(1)).unprotect(any());
    }
    private static String legacy(String password) throws Exception {
        byte[] key=MessageDigest.getInstance("SHA-256").digest((System.getenv("COMPUTERNAME")+"/"+System.getProperty("user.name")).getBytes(StandardCharsets.UTF_8));
        byte[] iv=new byte[12]; new java.security.SecureRandom().nextBytes(iv);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        byte[] cipher=c.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] combined=Arrays.copyOf(iv,iv.length+cipher.length);
        System.arraycopy(cipher,0,combined,iv.length,cipher.length);
        return Base64.getEncoder().encodeToString(combined);
    }
}
