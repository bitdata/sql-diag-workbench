package com.bitdata.sqlworkbench.sql.model;

public record ParseRequest(String sql, String dialect, String connectionId) {
    public ParseRequest(String sql, String dialect) { this(sql, dialect, null); }
}
