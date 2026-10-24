package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseStrategy {

    String getJdbcUrl(String host, int port, String database);

    String getExplainSql(String sql);

    String getAnalyzeSql(String sql);

    boolean supportsReadOnly();

    void configureReadOnly(Connection conn) throws SQLException;

    String dialect();

    /**
     * Returns the parsing profile for this dialect, encapsulating Calcite
     * SqlDialect, quote character, reserved keywords, and case sensitivity.
     */
    DialectProfile getDialectProfile();

    /**
     * S5 — wraps an already-validated single-SELECT verification query with a
     * dialect-specific one-row limit, so verification never fetches more than
     * it needs. The argument MUST be validated by SelectOnlyGuard first; this
     * method does string composition, not validation.
     */
    String wrapVerificationSql(String innerSelect);
}
