package com.bitdata.sqlworkbench.optimization;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;

@Repository
public class OptimizationReportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public OptimizationReportStore(JdbcTemplate jdbc,ObjectMapper json) {
        this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public void save(Report report) throws Exception {
        String text=json.writeValueAsString(report);
        if(text.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw new IllegalArgumentException("REPORT_BUDGET_EXCEEDED");
        tx.executeWithoutResult(s->{
            jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            if(jdbc.update("INSERT INTO optimization_validation_reports(id,session_id,version,report_json,created_at) SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM sessions WHERE id=?)",
                report.validationId(),report.source().sessionId(),report.version(),text,report.completedAt(),report.source().sessionId())==0)
                throw OptimizationFailure.changed("SESSION_GONE");
        });
    }
    public JsonNode find(String id) {
        OptimizationSources.token(id);
        var rows=jdbc.queryForList("SELECT report_json FROM optimization_validation_reports WHERE id=?",String.class,id);
        if(rows.isEmpty())throw new OptimizationFailure(404,"REPORT_NOT_FOUND","REPORT_NOT_FOUND");
        try{return json.readTree(rows.get(0));}catch(Exception e){throw new OptimizationFailure(500,"OPTIMIZATION_REPORT_UNAVAILABLE","REPORT_DECODE_FAILED");}
    }
    public ReportPage page(String sessionId,String cursor) {
        OptimizationSources.token(sessionId);
        String stamp="9999",id="~";
        if(cursor!=null&&!cursor.isBlank()) {
            try {
                if(cursor.length()>1024)throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1);
                if(parts.length!=2||parts[0].length()>64)throw new IllegalArgumentException();
                java.time.Instant.parse(parts[0]);OptimizationSources.token(parts[1]);stamp=parts[0];id=parts[1];
            }catch(Exception e){throw OptimizationFailure.invalid("INVALID_CURSOR");}
        }
        var rows=jdbc.queryForList("SELECT id,version,created_at,CASE WHEN version=1 THEN coalesce(json_extract(report_json,'$.verdict'),'UNKNOWN') ELSE 'UNKNOWN' END AS verdict,substr(coalesce(json_extract(report_json,'$.source.sql'),''),1,128) AS original,substr(coalesce(json_extract(report_json,'$.candidateSql'),''),1,128) AS candidate FROM optimization_validation_reports WHERE session_id=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT 51",sessionId,stamp,stamp,id);
        List<ReportSummary> items=new ArrayList<>();
        for(var r:rows.subList(0,Math.min(50,rows.size())))items.add(new ReportSummary(((Number)r.get("version")).intValue(),(String)r.get("id"),(String)r.get("created_at"),(String)r.get("verdict"),(String)r.get("original"),(String)r.get("candidate")));
        String next=null;if(rows.size()>50){var r=rows.get(49);next=Base64.getUrlEncoder().withoutPadding().encodeToString((r.get("created_at")+"\n"+r.get("id")).getBytes(StandardCharsets.UTF_8));}
        return new ReportPage(1,List.copyOf(items),next);
    }
}
