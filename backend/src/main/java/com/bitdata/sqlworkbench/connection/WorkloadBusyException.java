package com.bitdata.sqlworkbench.connection;

import java.sql.SQLException;

/** Observable admission failure while an exclusive experiment owns the workload window. */
public final class WorkloadBusyException extends SQLException {
    public WorkloadBusyException(String message) {
        super(message, "HY000");
    }

    public String errorReason() { return "WORKLOAD_BUSY"; }
}
