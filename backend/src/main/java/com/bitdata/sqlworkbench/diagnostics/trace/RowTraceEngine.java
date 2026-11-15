package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.diagnostics.RuleRegistry;
import com.bitdata.sqlworkbench.execution.QueryTimeoutClassifier;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.bitdata.sqlworkbench.sql.SqlParseEngine;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core row trace engine. Walks the fragment DAG leaf→root,
 * executing EXISTS queries at each executable node to determine
 * if the target row is present. Identifies the elimination point
 * where the row first disappears.
 *
 * <p>Uses an independent 2-worker thread pool so trace operations
 * never compete with {@code ExecutionEngine} workers.
 */
@Component
public class RowTraceEngine {

    private static final Logger log = LoggerFactory.getLogger(RowTraceEngine.class);

    private final ConnectionService connectionService;
    private final DatabaseStrategyFactory strategyFactory;
    private final TraceStore traceStore;
    private final RuleRegistry ruleRegistry;
    private final SelectOnlyGuard selectOnlyGuard;
    private final int defaultTimeoutSeconds;

    private final TraceQueryBuilder queryBuilder = new TraceQueryBuilder();
    private final DagWalker dagWalker = new DagWalker();
    private final EliminationDetector eliminationDetector = new EliminationDetector();
    private final EliminationEdgeFactory eliminationEdgeFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ThreadPoolExecutor executor;
    private final com.bitdata.sqlworkbench.execution.OracleNetworkTimeoutExecutor networkCallbacks=new com.bitdata.sqlworkbench.execution.OracleNetworkTimeoutExecutor(2,8);
    private DiagnosticAdmission admission;
    private com.bitdata.sqlworkbench.diagnostics.source.SourceObservationReceipts sourceReceipts;
    @org.springframework.beans.factory.annotation.Autowired
    void configureAdmission(DiagnosticAdmission admission,com.bitdata.sqlworkbench.diagnostics.source.SourceObservationReceipts receipts) {
        this.admission=admission;this.sourceReceipts=receipts;
    }

    /** HTTP entry point. Internal execution tests use submit without bypassing any exposed route. */
    public String submitAuthenticated(TraceRequest request) {
        if(admission==null)throw DiagnosticAdmission.failure(503,"DIAGNOSTIC_ADMISSION_UNAVAILABLE");
        var session=admission.require(request.diagnosticContext(),request.connectionId());
        String version=admission.connectionVersion(request.connectionId());
        TraceRequest executable;
        if(request.sourceReceiptId()!=null)executable=sourceReceipts.resolve(request,session.dialect());
        else {admission.graph(request);executable=request;}
        return submit(executable,version,request.sourceReceiptId()!=null);
    }
    public void authorizeTask(String id) {
        var state=traceStore.get(id);
        if(state!=null) {
            if(admission==null||state.request.diagnosticContext()==null)throw DiagnosticAdmission.failure(409,"DIAGNOSTIC_CONTEXT_REQUIRED");
            admission.revalidate(state.request.diagnosticContext(),state.request.connectionId(),state.connectionVersion);
        }
    }
    private void revalidate(String id) {var state=traceStore.get(id);if(state!=null&&state.request.diagnosticContext()!=null)authorizeTask(id);}

    public RowTraceEngine(
        ConnectionService connectionService,
        DatabaseStrategyFactory strategyFactory,
        TraceStore traceStore,
        RuleRegistry ruleRegistry,
        SelectOnlyGuard selectOnlyGuard,
        SqlParseEngine sqlParseEngine,
        @Value("${sql-workbench.execution.query-timeout-seconds:30}") int defaultTimeoutSeconds
    ) {
        this.connectionService = connectionService;
        this.strategyFactory = strategyFactory;
        this.traceStore = traceStore;
        this.ruleRegistry = ruleRegistry;
        this.selectOnlyGuard = selectOnlyGuard;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.eliminationEdgeFactory = new EliminationEdgeFactory(sqlParseEngine);
        this.executor = new ThreadPoolExecutor(
            2, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(5),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /** Submit a trace for async execution. Returns traceId immediately. */
    public String submit(TraceRequest request) {
        return submit(request,null,false);
    }
    private String submit(TraceRequest request,String version,boolean source) {
        var config=connectionService.getById(request.connectionId());
        if(config.isPresent()) {
            var profile=strategyFactory.get(config.get().dialect()).getDialectProfile();
            Set<String> names=new HashSet<>();
            for(KeyValue key:request.keys()) {
                if(Set.of("sqlserver","oracle").contains(config.get().dialect())&&key.type()==null)throw new IllegalArgumentException("TYPED_KEY_REQUIRED");
                String bare=TraceQueryBuilder.bareColumnName(key.column());
                if(!names.add(profile.caseSensitive()?bare:bare.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Duplicate output key: "+bare);
            }
        }
        String traceId = traceStore.create(request,config.map(c->c.dialect()).orElse(null));
        traceStore.get(traceId).connectionVersion=version;
        traceStore.get(traceId).observationKind=source?"INDEPENDENT_SOURCE":"GRAPH";
        try {
            executor.execute(new TraceJob(traceId,() -> executeTraceSync(traceId, request)));
        } catch (RejectedExecutionException e) {
            traceStore.fail(traceId, "Trace pool exhausted. Please wait for running traces to complete.");
            traceStore.sealNeverStarted(traceId,"EXECUTOR_REJECTED_BEFORE_START");
        }
        return traceId;
    }

    /** Cancel a running trace. */
    public boolean cancel(String traceId) {
        return traceStore.cancel(traceId);
    }

    /**
     * Execute a trace synchronously (called from the thread pool).
     * Visible for testing.
     */
    void executeTraceSync(String traceId, TraceRequest request) {
        if(!traceStore.canExecute(traceId)){traceStore.sealNeverStarted(traceId,"TERMINAL_BEFORE_WORKER_ADMISSION");return;}
        com.bitdata.sqlworkbench.connection.WorkloadLeaseManager.Ticket owner;
        try { owner=connectionService.acquireOrdinaryWorkload(); }
        catch(SQLException failure) { traceStore.fail(traceId,"Worker admission failed",failure instanceof com.bitdata.sqlworkbench.connection.WorkloadBusyException busy?busy.errorReason():"WORKLOAD_ADMISSION_FAILED");traceStore.sealNeverStarted(traceId,"WORKLOAD_ADMISSION_REJECTED");return; }
        var state=traceStore.get(traceId);
        synchronized(state) {
            if(!traceStore.canExecute(traceId)){owner.release();traceStore.sealNeverStarted(traceId,"TERMINAL_BEFORE_OWNER_REGISTRATION");return;}
            state.workerTicket=owner;
            state.resources=new TaskResources(owner,networkCallbacks);
        }
        try { executeTraceOwned(traceId,request); }
        finally {
            synchronized(state){state.workerTicket=null;}
            owner.release();
            state.resources.workerReturned();
        }
    }
    private void executeTraceOwned(String traceId, TraceRequest request) {
        if(request.mode()==TraceMode.CARDINALITY) { executeCardinality(traceId,request); return; }
        traceStore.setRunning(traceId);
        if(!traceStore.canExecute(traceId)) return;
        long startTime = System.currentTimeMillis();

        try {
            revalidate(traceId);
            FragmentNode root = objectMapper.readValue(request.fragmentTreeJson(), FragmentNode.class);
            var scope = new TraceInputValidator().validate(root);
            Map<String, FragmentNode> allNodes = scope.nodes();
            traceStore.configureScope(traceId, root.id(), allNodes.size());
            if (!traceStore.canExecute(traceId)) return;
            List<String> sortedIds = scope.leafFirst();

            // 3. Open connection and configure read-only
            int timeout = request.queryTimeoutSeconds() != null && request.queryTimeoutSeconds()>0
                ? request.queryTimeoutSeconds()
                : defaultTimeoutSeconds;

            String dialect = connectionService.getById(request.connectionId())
                .map(c -> c.dialect())
                .orElse("postgresql");
            DatabaseStrategy strategy = strategyFactory.get(dialect);

            // S5 — validate every executable fragment SQL as a single SELECT
            // BEFORE embedding it into EXISTS(...) wrappers. One bad fragment
            // fails the whole trace with a clear error instead of being
            // concatenated into SQL.
            try {
                for (FragmentNode node : allNodes.values()) {
                    if (node.executable() && node.sqlFragment() != null && !node.sqlFragment().isBlank()) {
                        selectOnlyGuard.requireSingleSelect(node.sqlFragment(), dialect);
                    }
                }
            } catch (IllegalArgumentException e) {
                traceStore.fail(traceId, "Trace rejected: " + e.getMessage());
                return;
            }

            revalidate(traceId);
            try (Connection conn = openConnection(traceId,request)) {
                if (strategy.supportsReadOnly()) {
                    strategy.configureReadOnly(conn);
                }

                // 4. Execute each node in topological order
                int stepIndex = 0;
                for (String nodeId : sortedIds) {
                    TraceStore.MutableTraceState ctx = traceStore.get(traceId);
                    if (!traceStore.canExecute(traceId)) return;
                    revalidate(traceId);

                    FragmentNode node = allNodes.get(nodeId);
                    TraceStep step = executeStep(stepIndex++, node, request.keys(), conn, timeout, traceId, dialect,false)
                        .withObservation(node.sqlFragment(),null);
                    traceStore.addStep(traceId, step);
                    traceStore.clearActiveStatement(traceId);
                }
            }

            // 5. Find elimination point and run diagnostic rules
            TraceStore.MutableTraceState ctx = traceStore.get(traceId);
            if (ctx != null && traceStore.canExecute(traceId)) {
                var transforms=new TransformVerifier(new com.bitdata.sqlworkbench.sql.SqlDialectResolver(strategyFactory))
                    .verify(allNodes,request.transformEdges(),dialect,()->traceStore.canExecute(traceId));
                if(!traceStore.canExecute(traceId)) return;
                EliminationOutcome outcome = eliminationDetector.findEliminationOutcome(
                    ctx.completedSteps, allNodes, root.id(),transforms::allows);

                TraceStep elimination = null;
                if (outcome instanceof EliminationOutcome.Edge e) {
                    elimination = e.parentStep();
                } else if (outcome instanceof EliminationOutcome.MissingAtSource m) {
                    elimination = m.leafStep();
                }

                EliminationEdge eliminationEdge = null;
                String errorReason = null;
                List<DiagnosticFinding> engineFindings = new ArrayList<>();
                if (outcome instanceof EliminationOutcome.Edge e) {
                    eliminationEdge = eliminationEdgeFactory.build(e, allNodes,
                        strategy.getDialectProfile(), strategy.dialect());
                } else if (outcome instanceof EliminationOutcome.Error err) {
                    errorReason = err.reason();
                    engineFindings.add(buildErrorFinding(err));
                }

                // Build preliminary result for rule analysis
                TraceResult preliminaryResult = new TraceResult(
                    List.copyOf(ctx.completedSteps), elimination,
                    System.currentTimeMillis() - startTime, null, List.of(),
                    outcome, eliminationEdge, errorReason);

                // 6. Run trace-based diagnostic rules
                TraceContext diagCtx = new TraceContext(
                    request.connectionId(), root, allNodes,
                    preliminaryResult, request.keys());
                List<DiagnosticFinding> ruleFindings = ruleRegistry.analyzeAllTrace(diagCtx);

                List<DiagnosticFinding> allFindings = new ArrayList<>(engineFindings);
                allFindings.addAll(ruleFindings);
                List<TraceLimitation> extra = eliminationEdge != null && eliminationEdge.degraded()
                    ? List.of(new TraceLimitation("EXPLANATION_FAILED", elimination.fragmentId(), null, eliminationEdge.errorReason())) : List.of();
                List<TraceLimitation> limitations=new ArrayList<>(transforms.limitations());limitations.addAll(extra);
                limitations.add(new TraceLimitation("MULTI_STATEMENT_SNAPSHOT_UNVERIFIED",null,null,
                    "Observations use separate statements without a guaranteed shared snapshot."));
                int candidates=eliminationDetector.candidateEdges(ctx.completedSteps,allNodes,transforms::allows).size();
                if(outcome instanceof EliminationOutcome.Edge && candidates>1)
                    limitations.add(new TraceLimitation("MULTIPLE_ELIMINATION_EDGES",null,null,
                        candidates+" observed comparable disappearance edges; the selected edge is not a unique cause."));
                if(!traceStore.canExecute(traceId)) return;
                traceStore.complete(traceId, elimination, allFindings, outcome, errorReason, limitations);
            }

            log.info("Trace {} completed in {}ms", traceId, System.currentTimeMillis() - startTime);

        } catch (com.bitdata.sqlworkbench.session.WorkspaceIdentity.Failure invalid) {
            traceStore.fail(traceId,invalid.getMessage(),invalid.code);
        } catch (TraceInputValidator.Invalid invalid) {
            traceStore.fail(traceId,invalid.getMessage(),invalid.code);
        } catch (com.bitdata.sqlworkbench.connection.CredentialFailure e) {
            traceStore.fail(traceId,e.getMessage(),e.code);
        } catch (com.bitdata.sqlworkbench.connection.WorkloadBusyException e) {
            traceStore.fail(traceId,e.getMessage(),e.errorReason());
        } catch (Exception e) {
            log.error("Trace {} failed: {}", traceId, e.getMessage(), e);
            traceStore.fail(traceId, e.getMessage()!=null?e.getMessage():"Trace failed","TRACE_FAILED");
        }
    }

    private void executeCardinality(String traceId, TraceRequest request) {
        traceStore.setRunning(traceId);
        if(!traceStore.canExecute(traceId)) return;
        try {
            revalidate(traceId);
            FragmentNode root=objectMapper.readValue(request.fragmentTreeJson(),FragmentNode.class);
            var scope=new TraceInputValidator().validate(root);
            traceStore.configureScope(traceId,root.id(),scope.nodes().size());
            var config=connectionService.getById(request.connectionId());
            if(config.isEmpty()) { traceStore.fail(traceId,"Connection not found","CONNECTION_NOT_FOUND"); return; }
            String dialect=config.get().dialect();
            if(!List.of("postgresql","mysql","sqlserver","oracle").contains(dialect)) {
                traceStore.fail(traceId,"Quantity observation is unavailable for this dialect","UNSUPPORTED_DIALECT"); return;
            }
            for(FragmentNode node:scope.nodes().values()) if(node.executable()) selectOnlyGuard.requireSingleSelect(node.sqlFragment(),dialect);
            int requested=request.queryTimeoutSeconds()!=null && request.queryTimeoutSeconds()>0 ? request.queryTimeoutSeconds():defaultTimeoutSeconds;
            traceStore.remainingTimeout(traceId,requested);
            if(!traceStore.canExecute(traceId)) return;
            var strategy=strategyFactory.get(dialect);
            List<TraceStep> steps=new ArrayList<>();
            revalidate(traceId);
            try(Connection conn=openConnection(traceId,request)) {
                if(strategy.supportsReadOnly()) strategy.configureReadOnly(conn);
                int index=0;
                for(FragmentNode node:scope.nodes().values()) {
                    traceStore.remainingTimeout(traceId,requested);
                    if(!traceStore.canExecute(traceId)) return;
                    revalidate(traceId);
                    TraceStep step=executeStep(index++,node,request.keys(),conn,requested,traceId,dialect,true);
                    step=step.withObservation(node.sqlFragment(),step.count());
                    traceStore.addStep(traceId,step); steps.add(step);
                    if(conn.isClosed()) { traceStore.fail(traceId,"Database connection closed","CONNECTION_FAILED"); return; }
                }
            }
            if(!traceStore.canExecute(traceId)) return;
            var transforms=new TransformVerifier(new com.bitdata.sqlworkbench.sql.SqlDialectResolver(strategyFactory))
                .verify(scope.nodes(),request.transformEdges(),dialect,()->traceStore.canExecute(traceId));
            if(!traceStore.canExecute(traceId)) return;
            CardinalityStructureInspector.Analysis analysis;
            try {
                analysis=new CardinalityStructureInspector(new com.bitdata.sqlworkbench.sql.SqlDialectResolver(strategyFactory))
                    .inspect(scope,steps,request.keys(),dialect,transforms);
            } catch(Exception invalidStructure) {
                analysis=new CardinalityStructureInspector.Analysis(List.of(),List.of(
                    new TraceLimitation("STRUCTURE_UNVERIFIED",null,null,"Structure comparison failed; count facts remain available."),
                    new TraceLimitation("MULTI_STATEMENT_SNAPSHOT_UNVERIFIED",null,null,"Counts use separate statements without a shared snapshot guarantee.")));
            }
            if(!traceStore.canExecute(traceId)) return;
            List<TraceLimitation> limits=new ArrayList<>(analysis.limitations());limits.addAll(transforms.limitations());
            traceStore.completeQuantity(traceId,analysis.comparisons(),limits);
        } catch(com.bitdata.sqlworkbench.session.WorkspaceIdentity.Failure invalid) {
            traceStore.fail(traceId,invalid.getMessage(),invalid.code);
        } catch(TraceInputValidator.Invalid invalid) {
            traceStore.fail(traceId,invalid.getMessage(),invalid.code);
        } catch(com.bitdata.sqlworkbench.connection.CredentialFailure failure) {
            traceStore.fail(traceId,failure.getMessage(),failure.code);
        } catch(com.bitdata.sqlworkbench.connection.WorkloadBusyException failure) {
            traceStore.fail(traceId,failure.getMessage(),failure.errorReason());
        } catch(Exception failure) {
            traceStore.fail(traceId,failure.getMessage()!=null?failure.getMessage():"Quantity observation failed","TRACE_FAILED");
        }
    }

    private TraceStep executeStep(int stepIndex, FragmentNode node, List<KeyValue> keys,
                                  Connection conn, int timeoutSeconds, String traceId,
                                  String dialect, boolean count) {
        String fragmentId = node.id();
        String label = node.label();

        if (!node.executable()) {
            return new TraceStep(stepIndex, fragmentId, label, "",
                TraceStatus.SKIPPED, -1, 0, null);
        }

        String fragmentSql = node.sqlFragment();
        if (fragmentSql == null || fragmentSql.isBlank()) {
            return new TraceStep(stepIndex, fragmentId, label, "",
                TraceStatus.ERROR, -1, 0, "Fragment SQL is empty");
        }

        long start = System.currentTimeMillis();
        String attemptedSql = "SELECT * FROM (" + fragmentSql + ") AS __trace_sub WHERE 1=0";
        try {
            revalidate(traceId);
            if(Set.of("sqlserver","oracle").contains(dialect))conn.setNetworkTimeout(ForkJoinPool.commonPool(),traceStore.remainingTimeout(traceId,120)*1000);
            if("sqlserver".equals(dialect))attemptedSql=SqlServerObservationSql.build(fragmentSql,keys,"METADATA").sql();
            if("oracle".equals(dialect))attemptedSql=OracleObservationSql.metadata(fragmentSql).sql();
            var profile = strategyFactory.get(dialect).getDialectProfile();
            TraceKeyProjection.Match projection;
            List<OracleObservationSql.Column> oracleColumns=new ArrayList<>();
            try (PreparedStatement metadataStmt = conn.prepareStatement(attemptedSql)) {
                metadataStmt.setQueryTimeout(traceStore.remainingTimeout(traceId,timeoutSeconds));
                if (!traceStore.registerActiveStatement(traceId, metadataStmt) || !traceStore.canExecute(traceId))
                    return new TraceStep(stepIndex, fragmentId, label, "", TraceStatus.SKIPPED, -1, 0, "Trace cancelled", "CANCELLED", List.of());
                try (ResultSet metadataRows = metadataStmt.executeQuery()) {
                    ResultSetMetaData metadata=metadataRows.getMetaData();
                    projection = TraceKeyProjection.match(metadata, keys, profile);
                    if("oracle".equals(dialect))for(int i=1;i<=metadata.getColumnCount();i++)
                        oracleColumns.add(new OracleObservationSql.Column(metadata.getColumnLabel(i),metadata.getColumnType(i)));
                }
            } finally { traceStore.clearActiveStatement(traceId); }
            if (projection.reason() != null)
                return new TraceStep(stepIndex, fragmentId, label, attemptedSql, TraceStatus.SKIPPED, -1,
                    System.currentTimeMillis() - start, "Key cannot be compared in this fragment: " + String.join(", ", projection.unavailableKeys()),
                    projection.reason(), projection.unavailableKeys());
            String existsSql = "oracle".equals(dialect)?OracleObservationSql.build(fragmentSql,oracleColumns,keys,projection.labels(),count?"COUNT":"EXISTS").sql():
                "sqlserver".equals(dialect)?SqlServerObservationSql.build(fragmentSql,keys,projection.labels(),count?"COUNT":"EXISTS").sql():
                count ? queryBuilder.buildCountSql(fragmentSql,keys,projection.labels(),profile.quoteChar()) :
                queryBuilder.buildExistsSql(fragmentSql, keys, projection.labels(), profile.quoteChar());
            attemptedSql = existsSql;

            try (PreparedStatement stmt = conn.prepareStatement(existsSql)) {
                if (!traceStore.registerActiveStatement(traceId, stmt))
                    return new TraceStep(stepIndex, fragmentId, label, "", TraceStatus.SKIPPED, -1, 0, "Trace cancelled", "CANCELLED", List.of());
                if("oracle".equals(dialect))OracleObservationSql.bind(stmt,keys,oracleColumns,projection.labels());
                else if("sqlserver".equals(dialect))SqlServerObservationSql.bind(stmt,keys.stream().filter(k->k.value()!=null).toList());
                else queryBuilder.bindParameters(stmt, keys);
                stmt.setQueryTimeout(traceStore.remainingTimeout(traceId,timeoutSeconds));
                if(count) stmt.setMaxRows(1);

                if (!traceStore.canExecute(traceId))
                    return new TraceStep(stepIndex, fragmentId, label, "", TraceStatus.SKIPPED, -1, 0, "Trace cancelled", "CANCELLED", List.of());

                try (ResultSet rs = stmt.executeQuery()) {
                    if(count) {
                        String value=null;
                        try {
                            if(!rs.next() || rs.getMetaData().getColumnCount()!=1) throw new ArithmeticException();
                            java.math.BigDecimal scalar=rs.getBigDecimal(1);
                            if(scalar==null || scalar.signum()<0) throw new ArithmeticException();
                            value=Long.toString(scalar.longValueExact());
                            if(rs.next()) throw new ArithmeticException();
                        } catch(ArithmeticException invalid) {
                            return new TraceStep(stepIndex,fragmentId,label,existsSql,TraceStatus.ERROR,-1,System.currentTimeMillis()-start,
                                "COUNT did not return one nonnegative signed-long scalar","COUNT_RESULT_INVALID",List.of());
                        }
                        return new TraceStep(stepIndex,fragmentId,label,existsSql,value.equals("0")?TraceStatus.NO_ROWS:TraceStatus.HAS_ROWS,
                            -1,System.currentTimeMillis()-start,null).withObservation(fragmentSql,value);
                    }
                    boolean hasRows = rs.next() && rs.getBoolean(1);
                    long elapsed = System.currentTimeMillis() - start;

                    return new TraceStep(stepIndex, fragmentId, label, existsSql,
                        hasRows ? TraceStatus.HAS_ROWS : TraceStatus.NO_ROWS,
                        -1, elapsed, null);
                }
            }
        } catch (SQLTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            return new TraceStep(stepIndex, fragmentId, label, attemptedSql,
                TraceStatus.TIMEOUT, -1, elapsed,
                "Query timed out after " + timeoutSeconds + "s");
        } catch (SQLException e) {
            if(e.getSQLState()!=null&&e.getSQLState().startsWith("08")) {
                traceStore.fail(traceId,"Database connection failed","CONNECTION_FAILED");
                return new TraceStep(stepIndex,fragmentId,label,attemptedSql,TraceStatus.ERROR,-1,System.currentTimeMillis()-start,
                    "Database connection failed","CONNECTION_FAILED",List.of());
            }
            // Per-request timeout may surface as a plain SQLException carrying the
            // server's cancel-signal (e.g. PG "canceling statement due to user
            // request", sqlstate 57014) rather than SQLTimeoutException. Classify
            // it so the trace step is marked TIMEOUT, not ERROR (Follow-up 2/3,
            // same-class sweep with ExecutionEngine).
            boolean timedOut = QueryTimeoutClassifier.isTimeout(e, dialect);
            long elapsed = System.currentTimeMillis() - start;
            TraceStatus status = timedOut ? TraceStatus.TIMEOUT : TraceStatus.ERROR;
            String reason = timedOut
                ? "Query timed out after " + timeoutSeconds + "s"
                : e.getMessage();
            return new TraceStep(stepIndex, fragmentId, label, attemptedSql,
                status, -1, elapsed, reason);
        } catch(com.bitdata.sqlworkbench.sql.SqlSerializationException e) {
            return new TraceStep(stepIndex,fragmentId,label,attemptedSql,TraceStatus.SKIPPED,-1,System.currentTimeMillis()-start,e.getMessage(),e.reason(),List.of());
        } catch(com.bitdata.sqlworkbench.session.WorkspaceIdentity.Failure e) { throw e;
        } catch(Exception e) {
            return new TraceStep(stepIndex,fragmentId,label,attemptedSql,TraceStatus.SKIPPED,-1,System.currentTimeMillis()-start,"Observation SQL unsupported","OBSERVATION_SQL_UNSUPPORTED",List.of());
        } finally { traceStore.clearActiveStatement(traceId); }
    }
    private Connection openConnection(String id,TraceRequest request)throws SQLException {
        var state=traceStore.get(id);
        if(state.connectionVersion==null) {
            var connectionOwner=state.workerTicket.retain();state.resources.bindConnectionTicket(connectionOwner);
            return state.resources.wrap(connectionService.openOrdinaryWithTimeout(request.connectionId(),null,120000,connectionOwner));
        }
        revalidate(id);
        var config=connectionService.getById(request.connectionId()).orElseThrow(()->DiagnosticAdmission.failure(404,"CONNECTION_NOT_FOUND"));
        int seconds=traceStore.remainingTimeout(id,120);
        if(!traceStore.canExecute(id))throw DiagnosticAdmission.failure(409,"TRACE_BUDGET_EXCEEDED");
        var connectionOwner=state.workerTicket.retain();state.resources.bindConnectionTicket(connectionOwner);
        Connection connection=state.resources.wrap(connectionService.openOrdinaryWithTimeout(request.connectionId(),
            com.bitdata.sqlworkbench.connection.ConnectionIdentity.revision(config),seconds*1000,connectionOwner));
        try {revalidate(id);return connection;} catch(RuntimeException e) {connection.close();throw e;}
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
        traceStore.stopForShutdown();
        var discarded=new java.util.ArrayList<Runnable>();executor.getQueue().drainTo(discarded);
        for(Runnable abandoned:discarded) {
            if(abandoned instanceof TraceJob job) {
                traceStore.fail(job.traceId,"Trace stopped before execution during shutdown","TRACE_SHUTDOWN_BEFORE_START");
                job.cancel(false);
                traceStore.sealNeverStarted(job.traceId,"EXECUTOR_DISCARDED_BEFORE_START");
            }
        }
        networkCallbacks.beginShutdown();
    }
    private static final class TraceJob extends FutureTask<Void> {
        final String traceId;
        TraceJob(String id,Runnable run){super(run,null);traceId=id;}
    }

    /** K5 — an ERROR/TIMEOUT step must surface a finding, not be silently swallowed. */
    private DiagnosticFinding buildErrorFinding(EliminationOutcome.Error err) {
        TraceStep step = err.step();
        return DiagnosticFinding.builder()
            .id("trace-error-" + step.fragmentId())
            .ruleId("row-trace-engine")
            .severity(DiagnosticRule.Severity.HIGH)
            .title("Cannot determine elimination point: " + step.fragmentLabel())
            .message("Fragment execution failed, so the elimination point cannot be identified: "
                + err.reason())
            .evidence(List.of(
                "Fragment: " + step.fragmentLabel(),
                "Status: " + step.status().name(),
                "Error: " + err.reason()))
            .confidence(1.0)
            .affectedNodeId(step.fragmentId())
            .build();
    }

}
