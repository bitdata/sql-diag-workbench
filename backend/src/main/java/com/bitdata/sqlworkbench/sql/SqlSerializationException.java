package com.bitdata.sqlworkbench.sql;

/** An AST accepted by Calcite that cannot be emitted as executable target SQL. */
public final class SqlSerializationException extends IllegalArgumentException {
    public static final String CODE = "SQL_SERIALIZATION_UNSUPPORTED";
    private final String reason;

    public SqlSerializationException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() { return reason; }

    /** Extractors and legacy rule APIs may wrap the original failure. */
    public static SqlSerializationException find(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause())
            if (failure instanceof SqlSerializationException typed) return typed;
        return null;
    }
}
