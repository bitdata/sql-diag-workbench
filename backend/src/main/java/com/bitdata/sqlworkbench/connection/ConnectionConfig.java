package com.bitdata.sqlworkbench.connection;
import com.fasterxml.jackson.annotation.JsonIgnore;
public record ConnectionConfig(String id, String name, String host, int port, String database, String username,
    String dialect, @JsonIgnore String keychainRef, boolean readonly, String createdAt, String updatedAt,
    String connectionType, boolean trustServerCertificate, KeychainStore.State credentialState) {
    public ConnectionConfig(String id,String name,String host,int port,String database,String username,String dialect,
        String keychainRef,boolean readonly,String createdAt,String updatedAt,String connectionType,boolean trustServerCertificate) {
        this(id,name,host,port,database,username,dialect,keychainRef,readonly,createdAt,updatedAt,connectionType,trustServerCertificate,null);
    }
    public ConnectionConfig withCredentials(KeychainStore.State state) {
        return new ConnectionConfig(id,name,host,port,database,username,dialect,keychainRef,readonly,createdAt,updatedAt,connectionType,trustServerCertificate,state);
    }
}
