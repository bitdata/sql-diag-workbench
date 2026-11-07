package com.bitdata.sqlworkbench.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stable public configuration identity; excludes credentials and keychain state. */
public final class ConnectionIdentity {
    private ConnectionIdentity() {}
    private record Identity(String id,String host,int port,String database,String username,String dialect,
                            String updatedAt,String connectionType,boolean trustServerCertificate) {}
    public static String revision(ConnectionConfig c) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(
                new Identity(c.id(),c.host(),c.port(),c.database(),c.username(),c.dialect(),c.updatedAt(),c.connectionType(),c.trustServerCertificate()))));
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("CONNECTION_IDENTITY_UNAVAILABLE", e);
        }
    }
}
