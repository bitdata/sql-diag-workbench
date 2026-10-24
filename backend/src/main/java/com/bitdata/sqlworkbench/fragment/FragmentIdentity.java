package com.bitdata.sqlworkbench.fragment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * ADR-0001 — Fragment identity computation.
 * Identity = SHA-256(canonicalSql + "|" + dialect), producing a 64-char hex string.
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
        return sha256Hex(canonicalSql + "|" + dialect);
    }
}
