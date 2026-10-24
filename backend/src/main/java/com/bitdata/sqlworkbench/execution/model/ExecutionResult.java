package com.bitdata.sqlworkbench.execution.model;

import java.util.List;

public record ExecutionResult(
    String executionId,
    ExecutionStatus status,
    List<ColumnInfo> columns,
    List<List<Object>> rows,
    int rowCount,
    Timing timing,
    PlanNode plan,
    String planError,
    String error,
    Persistence historyPersistence,
    String errorReason,
    boolean degraded,
    ResultCompleteness resultCompleteness,
    java.time.Instant completedAt,
    PlanCapture planCapture
) {
    public ExecutionResult(String executionId,ExecutionStatus status,List<ColumnInfo> columns,List<List<Object>> rows,
            int rowCount,Timing timing,PlanNode plan,String planError,String error,Persistence persistence,String errorReason,
            boolean degraded,ResultCompleteness completeness,java.time.Instant completedAt) {
        this(executionId,status,columns,rows,rowCount,timing,plan,planError,error,persistence,errorReason,degraded,completeness,completedAt,null);
    }
    public ExecutionResult withCapture(PlanCapture capture) {
        return new ExecutionResult(executionId,status,columns,rows,rowCount,timing,plan,planError,error,historyPersistence,errorReason,degraded,resultCompleteness,completedAt,capture);
    }
    public ExecutionResult {
        resultCompleteness = ResultCompleteness.normalize(resultCompleteness);
    }
    public ExecutionResult(String executionId,ExecutionStatus status,List<ColumnInfo> columns,List<List<Object>> rows,
            int rowCount,Timing timing,PlanNode plan,String planError,String error,Persistence persistence,String errorReason,boolean degraded) {
        this(executionId,status,columns,rows,rowCount,timing,plan,planError,error,persistence,errorReason,degraded,null,null);
    }
    public record Persistence(String status,String errorReason) {}
    public ExecutionResult(String executionId,ExecutionStatus status,List<ColumnInfo> columns,List<List<Object>> rows,
            int rowCount,Timing timing,PlanNode plan,String planError,String error) {
        this(executionId,status,columns,rows,rowCount,timing,plan,planError,error,null,null,false);
    }
    public ExecutionResult(String executionId,ExecutionStatus status,List<ColumnInfo> columns,List<List<Object>> rows,
            int rowCount,Timing timing,PlanNode plan,String planError,String error,Persistence persistence) {
        this(executionId,status,columns,rows,rowCount,timing,plan,planError,error,persistence,null,false);
    }
    public ExecutionResult withCredentialFailure(String reason) {
        return new ExecutionResult(executionId,status,columns,rows,rowCount,timing,plan,planError,error,historyPersistence,reason,true,resultCompleteness,completedAt,planCapture);
    }
    public ExecutionResult withHistory(Persistence persistence) {
        return new ExecutionResult(executionId,status,columns,rows,rowCount,timing,plan,planError,error,persistence,errorReason,degraded,resultCompleteness,completedAt,planCapture);
    }
    public record ColumnInfo(String name, String type) {}
    public record Timing(long durationMs) {}

    /**
     * Structured predicate text carried by a plan node on the EXPLAIN JSON
     * channels. Each field is nullable — only the channels that emit the
     * respective predicate text populate it. {@code null} (not empty string)
     * means "the channel did not provide this predicate".
     *
     * <p>Field origins:
     * <ul>
     *   <li>{@code filter} — PostgreSQL {@code "Filter"} field; MySQL
     *       {@code attached_condition} on the synthetic Filter child.</li>
     *   <li>{@code indexCond} — PostgreSQL {@code "Index Cond"} (predicate
     *       used by an Index Scan to look up rows).</li>
     *   <li>{@code hashCond} — PostgreSQL {@code "Hash Cond"} (Hash Join
     *       join predicate).</li>
     *   <li>{@code joinFilter} — PostgreSQL {@code "Join Filter"} (filter
     *       applied AFTER a nested-loop join).</li>
     * </ul>
     *
     * <p>Locked-in scope (live-channel-assumption-sweep 2026-09-09 bug ⑧):
     * added all four PG predicate fields in one PR to satisfy rule #1
     * "exhaustive same-class sweep" — no plan-time predicate text left to
     * add in a future iteration.
     */
    public record PlanNodeConditions(
        String filter,
        String indexCond,
        String hashCond,
        String joinFilter
    ) {
        /** Empty when the channel carries no predicates at all. */
        public static final PlanNodeConditions EMPTY = new PlanNodeConditions(null, null, null, null);
    }

    public record PlanNode(
        String operation,
        double startupCost,
        double totalCost,
        long planRows,
        long actualRows,
        long actualDuration,
        List<PlanNode> children,
        /**
         * Structured relation name the node scans, taken from the EXPLAIN JSON
         * channels (PostgreSQL "Relation Name" / MySQL "table_name"). Null when the
         * channel does not carry one — on JSON channels {@code operation} is the
         * BARE node type (e.g. "Seq Scan"), so rules must NOT expect the psql
         * text suffix "Seq Scan on &lt;table&gt;" there (E2E landmine D, 2026-09-09).
         */
        String tableName,
        /**
         * Structured predicate text on this node. {@code null} means the
         * adapter did not populate any predicate for this node — callers
         * MUST treat that as "no predicate info" (not "no predicate exists"),
         * mirroring {@link #tableName} semantics.
         */
        PlanNodeConditions conditions,
        NodeMetrics metrics
    ) {
        public PlanNode {
            children = children == null ? List.of() : List.copyOf(children);
        }
        public PlanNode(String operation,double startupCost,double totalCost,long planRows,long actualRows,long actualDuration,
                List<PlanNode> children,String tableName,PlanNodeConditions conditions) {
            this(operation,startupCost,totalCost,planRows,actualRows,actualDuration,children,tableName,conditions,null);
        }
        /** Legacy 7-arg shape: text-form plans where operation carries all info. */
        public PlanNode(String operation, double startupCost, double totalCost,
                        long planRows, long actualRows, long actualDuration,
                        List<PlanNode> children) {
            this(operation, startupCost, totalCost, planRows, actualRows, actualDuration,
                children, null, PlanNodeConditions.EMPTY);
        }

        /** Legacy 8-arg shape: pre-PR-3 of bug ⑧. */
        public PlanNode(String operation, double startupCost, double totalCost,
                        long planRows, long actualRows, long actualDuration,
                        List<PlanNode> children, String tableName) {
            this(operation, startupCost, totalCost, planRows, actualRows, actualDuration,
                children, tableName, PlanNodeConditions.EMPTY);
        }
    }
}
