package com.bitdata.sqlworkbench.sql.model;

public record CteDependencyRequest(
    String sql,
    String dialect
) {}