package com.bitdata.sqlworkbench.diagnostics.source;

import java.sql.SQLException;
import java.sql.Statement;

/** Task-owned JDBC budget and active-statement registration. Never opens a second connection. */
public interface PreparationJdbc {
    void checkpoint();
    void configure(Statement statement) throws SQLException;
    void clearStatement();
}
