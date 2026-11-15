package com.bitdata.sqlworkbench.execution;

import java.sql.*;
import java.util.*;
import java.util.function.*;

/** Dedicated connection, separate SET batches, bounded full result stream. */
public final class SqlServerPlanCollector {
    private SqlServerPlanCollector() {}
    public record Outcome(String status,String errorReason,List<SqlServerPlanParser.NativeParsed> documents,String cleanup) {}
    @FunctionalInterface public interface Cleanup {
        String finish(Connection connection,Statement setup,Statement query,String setting,boolean lost);
    }

    public static Outcome capture(Connection connection,String sql,boolean actual,IntSupplier timeout,
                                  Runnable checkpoint,Consumer<Statement> active,Cleanup cleanupOwner) throws SQLException {
        String setting=actual?"STATISTICS XML":"SHOWPLAN_XML";
        var documents=new ArrayList<SqlServerPlanParser.NativeParsed>();
        String status="COMPLETE",reason=null,cleanup="NOT_STARTED";
        boolean lost=false;
        Statement setup=null,query=null;
        try {
            setup=connection.createStatement();query=connection.createStatement();
            try {
                checkpoint.run(); active.accept(setup); setup.setQueryTimeout(Math.min(5,timeout.getAsInt()));
                setup.execute("SET "+setting+" ON");
                checkpoint.run(); active.accept(query); query.setQueryTimeout(timeout.getAsInt());
                boolean result=query.execute(sql),finished=false;
                int sets=0; long rows=0;
                for(int step=0;step<64;step++) {
                    checkpoint.run();
                    if(result) {
                        sets++;
                        try(ResultSet rs=query.getResultSet()) {
                            if(!actual||sets>1) {
                                var meta=rs.getMetaData();
                                if(meta.getColumnCount()!=1||!"Microsoft SQL Server 2005 XML Showplan".equals(meta.getColumnLabel(1)))
                                    throw new Limited("PLAN_RESULT_SHAPE_UNSUPPORTED");
                                while(rs.next()) {
                                    checkpoint.run();
                                    if(documents.size()>=4)throw new Limited("PLAN_RESULT_LIMIT_EXCEEDED");
                                    String raw;
                                    try { raw=SqlServerPlanParser.bounded(rs.getCharacterStream(1)); }
                                    catch(SqlServerPlanParser.Limited limit) { throw new Limited(limit.getMessage()); }
                                    catch(SQLException sqlFailure) { throw sqlFailure; }
                                    catch(Exception readFailure) { throw new SQLException("PLAN_TEXT_READ_FAILED",readFailure); }
                                    var parsed=SqlServerPlanParser.parseNative(raw);documents.add(parsed);
                                    if(parsed.plan()==null&&!"NOT_PROVIDED".equals(parsed.status()))throw new Limited(parsed.errorReason());
                                    if("PARTIAL".equals(parsed.status())) { status="PARTIAL";reason=parsed.errorReason(); }
                                }
                            } else {
                                // Drain rows without materializing cells or applying UI maxRows.
                                while(rs.next()) {
                                    if(++rows>1_000_000)throw new Limited("DATA_STREAM_LIMIT_EXCEEDED");
                                    // Owner checks read local SQLite. Bound their frequency while draining;
                                    // HTTP cancellation/deadline publication remains independent of this worker.
                                    if((rows&255)==0)checkpoint.run();
                                }
                            }
                        }
                    } else if(query.getUpdateCount()==-1) {finished=true;break;}
                    result=query.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }
                if(!finished)throw new Limited("RESULT_STREAM_LIMIT_EXCEEDED");
                if(documents.size()>1) {status="UNKNOWN";reason="MULTIPLE_PLAN_DOCUMENTS_UNSUPPORTED";}
                else if(documents.isEmpty()||documents.get(0).plan()==null) {status="NOT_PROVIDED";reason="PLAN_NOT_PROVIDED";}
            } catch(Limited limit) { status="UNKNOWN";reason=limit.getMessage(); }
            catch(SQLException failure) {lost=transportLost(failure);throw failure;}
            finally {
                active.accept(null);
            }
        } catch(SQLException failure) {
            lost=transportLost(failure);throw failure;
        } finally {
            cleanup=cleanupOwner.finish(connection,setup,query,setting,lost);
        }
        if(!"CONFIRMED".equals(cleanup)) {status="PARTIAL";reason=cleanup;}
        return new Outcome(status,reason,List.copyOf(documents),cleanup);
    }
    private static boolean transportLost(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().startsWith("08"))return true;
        return false;
    }
    /** Called only by the cleanup owner, which pins its lease until this physically returns. */
    static String closeResources(Connection connection,Statement setup,Statement query,String setting,boolean lost) {
        String reason=lost?"UNCONFIRMED_CONNECTION_LOST":"CONFIRMED";
        if(!lost && setup!=null)try {setup.setQueryTimeout(3);setup.execute("SET "+setting+" OFF");}
        catch(SQLException|RuntimeException failure) {reason="PLAN_CLEANUP_FAILED";}
        if(query!=null)try {query.close();} catch(SQLException|RuntimeException failure) {reason="PLAN_CLEANUP_FAILED";}
        if(setup!=null)try {setup.close();} catch(SQLException|RuntimeException failure) {reason="PLAN_CLEANUP_FAILED";}
        try {connection.close();} catch(SQLException|RuntimeException failure) {reason="CONNECTION_CLEANUP_UNCONFIRMED";}
        return reason;
    }
    private static final class Limited extends RuntimeException { Limited(String reason) {super(reason);} }
}
