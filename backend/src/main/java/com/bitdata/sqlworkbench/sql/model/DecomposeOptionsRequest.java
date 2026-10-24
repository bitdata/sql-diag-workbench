package com.bitdata.sqlworkbench.sql.model;

public record DecomposeOptionsRequest(String sqlFragment, String dialect, String connectionId) {
    public DecomposeOptionsRequest(String sqlFragment, String dialect) { this(sqlFragment, dialect, null); }
}
