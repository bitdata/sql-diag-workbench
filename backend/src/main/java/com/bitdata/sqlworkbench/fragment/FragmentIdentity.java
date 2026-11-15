package com.bitdata.sqlworkbench.fragment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * ADR-0001 — Fragment identity computation.
 * Identity = SHA-256(JSON tuple [policy, dialect, canonicalSql]), producing 64 hex chars.
 */
public final class FragmentIdentity {

    private FragmentIdentity() {}

    public static String sha256Hex(String input) {
        Objects.requireNonNull(input, "input");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on this JVM", e);
        }
    }

    public static String computeId(String canonicalSql, String dialect) {
        Objects.requireNonNull(canonicalSql, "canonicalSql");
        Objects.requireNonNull(dialect, "dialect");
        return computeId(canonicalSql,dialect,com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec.POLICY);
    }

    /** Versioned identity. The two-argument overload also uses the current policy; stored historical IDs are never recomputed. */
    public static String computeId(String canonicalSql, String dialect, String policy) {
        Objects.requireNonNull(canonicalSql, "canonicalSql");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(policy, "policy");
        try {
            return sha256Hex(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(java.util.List.of(policy, dialect, canonicalSql)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("IDENTITY_ENCODING_FAILED", error);
        }
    }
}
