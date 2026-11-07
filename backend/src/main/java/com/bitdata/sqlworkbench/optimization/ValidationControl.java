package com.bitdata.sqlworkbench.optimization;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/** A stop request is never proof of cleanup. The worker retains ownership until close returns. */
final class ValidationControl {
    final long deadline;
    final AtomicBoolean cancelled=new AtomicBoolean();
    volatile Statement statement;
    volatile String cleanupReason;
    ValidationControl(int seconds) { deadline=System.nanoTime()+seconds*1_000_000_000L; }
    String stopReason() {
        return cancelled.get()?"CANCELLED":System.nanoTime()>=deadline?"DEADLINE_EXCEEDED":null;
    }
    void check() { String reason=stopReason(); if(reason!=null)throw OptimizationSql.reject(reason); }
    void attach(Statement s) throws java.sql.SQLException {
        check(); statement=s;
        s.setQueryTimeout(Math.max(1,(int)Math.ceil((deadline-System.nanoTime())/1e9)));
        check();
    }
    void detach(Statement s) { if(s==null || statement==s)statement=null; }
}
