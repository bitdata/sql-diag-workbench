package com.bitdata.sqlworkbench.session;

/** Unsaved nodes must be recomputed by the server; supplied IDs alone are not proof. */
public record NodeProof(String id, String sqlFragment, String canonicalSql) {}
