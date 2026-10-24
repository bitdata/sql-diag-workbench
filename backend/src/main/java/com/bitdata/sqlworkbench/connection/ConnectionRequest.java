package com.bitdata.sqlworkbench.connection;
public record ConnectionRequest(String name, String host, int port, String database, String username,
    String password, String dialect, String connectionType, Boolean trustServerCertificate, Boolean noPassword, String connectionId) {
    public ConnectionRequest(String name,String host,int port,String database,String username,String password,
        String dialect,String connectionType,Boolean trustServerCertificate) {
        this(name,host,port,database,username,password,dialect,connectionType,trustServerCertificate,null,null);
    }
    @Override public String toString() { return "ConnectionRequest[redacted]"; }
}
