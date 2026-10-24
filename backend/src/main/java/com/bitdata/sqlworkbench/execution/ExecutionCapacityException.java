package com.bitdata.sqlworkbench.execution;

public final class ExecutionCapacityException extends RuntimeException {
    public ExecutionCapacityException() { super("Result retention capacity is full. Please submit again later."); }
}
