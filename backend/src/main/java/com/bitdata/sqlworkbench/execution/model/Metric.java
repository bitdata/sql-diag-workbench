package com.bitdata.sqlworkbench.execution.model;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;

/** Exact database decimal. Legacy numeric PlanNode fields are not evidence. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record Metric(String state,String value,String unit,String basis,String sourceField,String errorReason) {
    public static Metric absent(String state,String unit,String basis,String field,String reason) {
        return new Metric(state,null,unit,basis,field,reason);
    }
    public static Metric read(JsonNode node,String field,String unit,String basis) {
        JsonNode raw=node.get(field);
        if(raw==null || raw.isNull()) return absent("NOT_COLLECTED",unit,basis,field,"METRIC_NOT_PROVIDED");
        try {
            if(!raw.isNumber() && !raw.isTextual()) throw new IllegalArgumentException();
            BigDecimal value=new BigDecimal(raw.asText());
            if(value.signum()<0) throw new IllegalArgumentException();
            return new Metric("AVAILABLE",value.stripTrailingZeros().toPlainString(),unit,basis,field,null);
        } catch(RuntimeException e) {
            return absent("PARSE_ERROR",unit,basis,field,"INVALID_METRIC_VALUE");
        }
    }
    public boolean available() { return "AVAILABLE".equals(state) && value!=null; }
    public BigDecimal decimal() { return available() ? new BigDecimal(value) : null; }
}
