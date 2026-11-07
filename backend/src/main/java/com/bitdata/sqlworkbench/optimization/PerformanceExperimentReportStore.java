package com.bitdata.sqlworkbench.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.*;

@Repository
public class PerformanceExperimentReportStore {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final TransactionTemplate tx;
    public PerformanceExperimentReportStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))); }
    public void save(Report report) throws Exception {
        String text=json.writeValueAsString(report); if(text.getBytes(StandardCharsets.UTF_8).length>1024*1024) throw new IllegalArgumentException("REPORT_BUDGET_EXCEEDED");
        tx.executeWithoutResult(s->{ jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            if(jdbc.update("INSERT INTO optimization_experiment_reports(id,session_id,version,report_json,created_at) SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM sessions WHERE id=?)",report.experimentId(),report.request().source().sessionId(),report.version(),text,report.completedAt(),report.request().source().sessionId())==0) throw OptimizationFailure.changed("SESSION_GONE"); });
    }
    public com.fasterxml.jackson.databind.JsonNode find(String id) {
        OptimizationSources.token(id); var rows=jdbc.queryForList("SELECT report_json FROM optimization_experiment_reports WHERE id=?",String.class,id);
        if(rows.isEmpty()) throw new OptimizationFailure(404,"EXPERIMENT_NOT_FOUND","EXPERIMENT_NOT_FOUND");
        try{return json.readTree(rows.get(0));}catch(Exception e){throw new OptimizationFailure(500,"EXPERIMENT_REPORT_UNAVAILABLE","REPORT_DECODE_FAILED");}
    }
    public Page page(String sessionId,String cursor) {
        OptimizationSources.token(sessionId); String stamp="9999", id="~";
        if(cursor!=null&&!cursor.isBlank()) try { String[] p=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1); if(p.length!=2)throw new IllegalArgumentException(); Instant.parse(p[0]); OptimizationSources.token(p[1]); stamp=p[0];id=p[1]; } catch(Exception e){throw OptimizationFailure.invalid("INVALID_CURSOR");}
        var rows=jdbc.queryForList("SELECT id,version,created_at,coalesce(json_extract(report_json,'$.validation.verdict'),'UNKNOWN') verdict,coalesce(json_extract(report_json,'$.comparisonState'),'UNKNOWN') comparison,substr(coalesce(json_extract(report_json,'$.request.source.sql'),''),1,128) original,substr(coalesce(json_extract(report_json,'$.request.candidateSql'),''),1,128) candidate FROM optimization_experiment_reports WHERE session_id=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT 51",sessionId,stamp,stamp,id);
        List<HistoryItem> items=new ArrayList<>(); for(var row:rows.subList(0,Math.min(50,rows.size()))) items.add(new HistoryItem(((Number)row.get("version")).intValue(),(String)row.get("id"),(String)row.get("created_at"),(String)row.get("verdict"),(String)row.get("comparison"),(String)row.get("original"),(String)row.get("candidate")));
        String next=null; if(rows.size()>50){var row=rows.get(49);next=Base64.getUrlEncoder().withoutPadding().encodeToString((row.get("created_at")+"\n"+row.get("id")).getBytes(StandardCharsets.UTF_8));}
        return new Page(1,List.copyOf(items),next);
    }
}
