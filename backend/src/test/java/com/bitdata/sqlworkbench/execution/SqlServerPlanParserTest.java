package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlServerPlanParserTest {
    static final String NS="http://schemas.microsoft.com/sqlserver/2004/07/showplan";
    static String xml(String inner) {return "<ShowPlanXML xmlns=\""+NS+"\"><BatchSequence><Batch><Statements><StmtSimple><QueryPlan>"+inner+"</QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>";}
    static final String PLAN=xml("<RelOp NodeId=\"0\" PhysicalOp=\"Clustered Index Scan\" LogicalOp=\"Clustered Index Scan\" EstimateRows=\"3\" EstimatedTotalSubtreeCost=\"0.00331809\"><RunTimeInformation><RunTimeCountersPerThread Thread=\"0\" ActualRows=\"9007199254740993\" ActualExecutions=\"2\" ActualElapsedms=\"7\" ActualCPUms=\"0\" ActualLogicalReads=\"3\"/></RunTimeInformation></RelOp><QueryTimeStats CpuTime=\"0\" ElapsedTime=\"8\"/>");
    @Test void preservesExactNativeScopesAndNeverInventsLoopsOrNodeTime() {
        var parsed=SqlServerPlanParser.parseNative(PLAN);assertEquals("COMPLETE",parsed.status());
        var m=parsed.plan().metrics();
        assertEquals("3",m.estimatedRows().value());
        for(var unknown:List.of(m.actualRows(),m.loops(),m.actualDurationMs(),m.totalCost()))assertEquals("UNKNOWN",unknown.state());
        var thread=parsed.nodes().get(0).threads().get(0);
        assertEquals("9007199254740993",thread.metrics().get("ActualRows").value());
        assertEquals("REPORTED_THREAD_TOTAL",thread.metrics().get("ActualExecutions").basis());
        assertEquals("0",thread.metrics().get("ActualCPUms").value());
        assertEquals("NOT_COLLECTED",thread.metrics().get("ActualPhysicalReads").state());
        assertEquals("REPORTED_QUERY_TOTAL",parsed.queryStats().get(0).metrics().get("ElapsedTime").basis());
    }
    @Test void additiveProductionDtoRoundTripsAndOldConstructorRemainsValid() throws Exception {
        var details=SqlServerPlanParser.details(SqlServerPlanParser.parseNative(PLAN),new PlanEvidence.Environment("db","user",Map.of(),null));
        var mapper=new ObjectMapper();
        assertEquals(details,mapper.readValue(mapper.writeValueAsBytes(details),PlanDetails.class));
        var old=new PlanDetails(1,"POSTGRESQL_JSON","[]",null,null,Map.of(),null,null,List.of(),null);
        assertNull(mapper.readValue(mapper.writeValueAsBytes(old),PlanDetails.class).nativeEvidence());
    }
    @Test void noOperatorXmlIsNotProvidedWithOriginalEvidence() {
        String raw=xml("");var p=SqlServerPlanParser.parseNative(raw);
        assertEquals("NOT_PROVIDED",p.status());assertEquals(raw,p.rawPlan());assertNull(p.plan());
    }
    @Test void rejectsDtdForeignNamespaceAndOverDepthBeforeDom() {
        for(String bad:List.of("<!DOCTYPE ShowPlanXML [<!ENTITY x SYSTEM 'file:///invalid'>]>"+PLAN,
            PLAN.replace(NS,"urn:untrusted"),xml("<x>".repeat(97)+"</x>".repeat(97)))) {
            var p=SqlServerPlanParser.parseNative(bad);assertNull(p.plan());assertEquals("UNKNOWN",p.status());assertNotNull(p.errorReason());
        }
    }
    @Test void boundedReaderNeverAllocatesUnboundedInput() throws Exception {
        var reader=new StringReader("x".repeat(SqlServerPlanParser.MAX_CHARS+100));
        assertThrows(SqlServerPlanParser.Limited.class,()->SqlServerPlanParser.bounded(reader));
    }
    @Test void invalidNativeMetricIsObservableAndMultipleRootsAreUnsupported() {
        var p=SqlServerPlanParser.parseNative(PLAN.replace("ActualRows=\"9007199254740993\"","ActualRows=\"-1\""));
        assertEquals("PARTIAL",p.status());assertEquals("INVALID_NATIVE_METRIC",p.errorReason());
        var multiple=SqlServerPlanParser.parseNative(xml("<RelOp PhysicalOp=\"UnknownNative\"/><RelOp PhysicalOp=\"UnknownNative\"/>"));
        assertNull(multiple.plan());assertEquals("MULTI_ROOT_PLAN_UNSUPPORTED",multiple.errorReason());
    }
    @Test void bothAdapterOverloadsUseXmlAndRejectWrongChannel() {
        var parser=new SqlServerPlanParser();
        assertNotNull(parser.parseDetailed(PLAN,"sqlserver").plan());
        assertNull(parser.parseDetailed(PLAN,"sqlserver","POSTGRESQL_JSON").plan());
    }
}
