package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.stream.*;
import org.w3c.dom.*;
import org.xml.sax.*;

/** Bounded SQL Server Showplan parser; thread facts never become per-loop metrics. */
@org.springframework.stereotype.Component
public final class SqlServerPlanParser implements ExplainService {
  static final String NS="http://schemas.microsoft.com/sqlserver/2004/07/showplan";
  static final int MAX_CHARS=262144,MAX_ELEMENTS=32768,MAX_NODES=2048,MAX_DEPTH=96,MAX_THREADS=8192;
  static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
  public record ThreadEvidence(String sourcePath,String threadId,Map<String,Metric> metrics) {}
  public record NodeEvidence(String sourcePath,String physicalOp,String logicalOp,Map<String,String> attributes,Map<String,Metric> metrics,List<ThreadEvidence> threads) {}
  public record QueryEvidence(String sourcePath,Map<String,Metric> metrics) {}
  public record NativeParsed(String status,String errorReason,String rawPlan,PlanNode plan,List<NodeEvidence> nodes,List<QueryEvidence> queryStats) {}
  static class Limited extends Exception { Limited(String reason){super(reason);} }
  static String bounded(Reader reader)throws Exception {
    if(reader==null)throw new Limited("PLAN_TEXT_UNAVAILABLE");
    try(reader){var out=new StringBuilder();char[] buffer=new char[4096];int n;
      while((n=reader.read(buffer,0,Math.min(buffer.length,MAX_CHARS+1-out.length())))!=-1){out.append(buffer,0,n);if(out.length()>MAX_CHARS)throw new Limited("PLAN_TEXT_LIMIT_EXCEEDED");}
      return out.toString();}
  }
  static void preflight(String raw)throws Exception {
    if(raw==null||raw.isBlank())throw new Limited("PLAN_NOT_PROVIDED");
    if(raw.length()>MAX_CHARS)throw new Limited("PLAN_TEXT_LIMIT_EXCEEDED");
    var f=XMLInputFactory.newFactory();f.setProperty(XMLInputFactory.SUPPORT_DTD,false);f.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
    f.setXMLResolver((a,b,c,d)->{throw new XMLStreamException("EXTERNAL_RESOURCE_FORBIDDEN");});
    var r=f.createXMLStreamReader(new StringReader(raw));int depth=0,elements=0,nodes=0,threads=0;
    try{while(r.hasNext()){int event=r.next();if(event==XMLStreamConstants.DTD)throw new Limited("XML_DTD_FORBIDDEN");
      if(event==XMLStreamConstants.START_ELEMENT){
        if(++depth>MAX_DEPTH)throw new Limited("PLAN_DEPTH_LIMIT_EXCEEDED");
        if(++elements>MAX_ELEMENTS)throw new Limited("PLAN_ELEMENT_LIMIT_EXCEEDED");
        if(elements==1&&(!NS.equals(r.getNamespaceURI())||!"ShowPlanXML".equals(r.getLocalName())))throw new Limited("UNSUPPORTED_PLAN_ROOT");
        if(NS.equals(r.getNamespaceURI())&&"RelOp".equals(r.getLocalName())&&++nodes>MAX_NODES)throw new Limited("PLAN_NODE_LIMIT_EXCEEDED");
        if(NS.equals(r.getNamespaceURI())&&"RunTimeCountersPerThread".equals(r.getLocalName())&&++threads>MAX_THREADS)throw new Limited("PLAN_THREAD_LIMIT_EXCEEDED");
      }else if(event==XMLStreamConstants.END_ELEMENT)depth--;}}
    finally{r.close();}
  }
  static Element direct(Element parent,String local){for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&NS.equals(e.getNamespaceURI())&&local.equals(e.getLocalName()))return e;return null;}
  static Map<String,String> attributes(Element e){var result=new LinkedHashMap<String,String>();for(int i=0;i<e.getAttributes().getLength();i++){var a=e.getAttributes().item(i);result.put(a.getNodeName(),a.getNodeValue());}return Map.copyOf(result);}
  static Element localObject(Element parent) {
    for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element e&&NS.equals(e.getNamespaceURI())) {
      if("RelOp".equals(e.getLocalName()))continue;
      if("Object".equals(e.getLocalName()))return e;
      Element found=localObject(e);if(found!=null)return found;
    }
    return null;
  }
  static String path(Element e){var parts=new ArrayList<String>();for(Node n=e;n instanceof Element p;n=p.getParentNode()){if(!NS.equals(p.getNamespaceURI()))throw new IllegalArgumentException("FOREIGN_NAMESPACE_ANCESTOR");int ordinal=1;for(Node s=p.getPreviousSibling();s!=null;s=s.getPreviousSibling())if(s instanceof Element other&&NS.equals(other.getNamespaceURI())&&p.getLocalName().equals(other.getLocalName()))ordinal++;parts.add("sp:"+p.getLocalName()+"["+ordinal+"]");}Collections.reverse(parts);return "/"+String.join("/",parts);}
  static Metric metric(Element e,String field,String unit,String basis){return e.hasAttribute(field)?Metric.exact(e.getAttribute(field),unit,basis,field):Metric.absent("NOT_COLLECTED",unit,basis,field,"METRIC_NOT_PROVIDED");}
  static Metric unknown(String unit,String field,String reason){return Metric.absent("UNKNOWN",unit,"UNKNOWN",field,reason);}
  static Map<String,Metric> threadMetrics(Element e){var result=new LinkedHashMap<String,Metric>();for(String field:List.of("ActualRows","ActualRowsRead","ActualExecutions","ActualElapsedms","ActualCPUms","ActualLogicalReads","ActualPhysicalReads","ActualReadAheads"))result.put(field,metric(e,field,field.endsWith("ms")?"MILLISECONDS":field.contains("Reads")||field.equals("ActualReadAheads")?"READS":field.equals("ActualExecutions")?"EXECUTIONS":"ROWS","REPORTED_THREAD_TOTAL"));return Map.copyOf(result);}
  static PlanNode node(Element e,List<NodeEvidence> evidence){
    var children=new ArrayList<PlanNode>();collectChildren(e,children,evidence);
    String p=path(e);var threads=new ArrayList<ThreadEvidence>();Element runtime=direct(e,"RunTimeInformation");
    if(runtime!=null)for(Node c=runtime.getFirstChild();c!=null;c=c.getNextSibling())if(c instanceof Element t&&NS.equals(t.getNamespaceURI())&&"RunTimeCountersPerThread".equals(t.getLocalName()))threads.add(new ThreadEvidence(path(t),t.getAttribute("Thread"),threadMetrics(t)));
    var nativeAttributes=new LinkedHashMap<>(attributes(e));Element object=localObject(e);
    if(object!=null) {attributes(object).forEach((key,value)->nativeAttributes.put("Object."+key,value));nativeAttributes.put("Object.sourcePath",path(object));}
    evidence.add(new NodeEvidence(p,e.getAttribute("PhysicalOp"),e.getAttribute("LogicalOp"),Map.copyOf(nativeAttributes),Map.of("EstimateRows",metric(e,"EstimateRows","ROWS","PER_INVOCATION"),"EstimatedTotalSubtreeCost",metric(e,"EstimatedTotalSubtreeCost","OPTIMIZER_COST","NATIVE_SUBTREE")),List.copyOf(threads)));
    var metrics=new NodeMetrics(1,"DATABASE_NODE",p,metric(e,"EstimateRows","ROWS","PER_INVOCATION"),
      unknown("ROWS","ActualRows","SQLSERVER_THREAD_TOTAL_NOT_PER_INVOCATION"),unknown("MILLISECONDS","ActualElapsedms","SQLSERVER_THREAD_TIME_NOT_NODE_DURATION"),
      unknown("LOOPS","ActualExecutions","SQLSERVER_EXECUTIONS_NOT_PG_LOOPS"),unknown("COST","startupCost","METRIC_NOT_PROVIDED"),unknown("COST","EstimatedTotalSubtreeCost","SQLSERVER_NATIVE_SUBTREE_COST_IN_NATIVE_EVIDENCE"));
    return new PlanNode(e.getAttribute("PhysicalOp"),0,0,0,0,0,children,object==null?null:object.getAttribute("Table"),ExecutionResult.PlanNodeConditions.EMPTY,metrics);
  }
  static void collectChildren(Element parent,List<PlanNode> children,List<NodeEvidence> evidence){for(Node c=parent.getFirstChild();c!=null;c=c.getNextSibling())if(c instanceof Element e&&NS.equals(e.getNamespaceURI())){if("RelOp".equals(e.getLocalName()))children.add(node(e,evidence));else collectChildren(e,children,evidence);}}
  public static NativeParsed parseNative(String raw){try{
    preflight(raw);var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");f.setXIncludeAware(false);f.setExpandEntityReferences(false);
    var b=f.newDocumentBuilder();b.setErrorHandler(new ErrorHandler(){public void warning(SAXParseException e)throws SAXException{throw e;}public void error(SAXParseException e)throws SAXException{throw e;}public void fatalError(SAXParseException e)throws SAXException{throw e;}});
    var doc=b.parse(new InputSource(new StringReader(raw)));var root=doc.getDocumentElement();var evidence=new ArrayList<NodeEvidence>();var roots=new ArrayList<PlanNode>();collectChildren(root,roots,evidence);
    var times=new ArrayList<QueryEvidence>();var nativeTimes=root.getElementsByTagNameNS(NS,"QueryTimeStats");for(int i=0;i<nativeTimes.getLength();i++){var t=(Element)nativeTimes.item(i);times.add(new QueryEvidence(path(t),Map.of("ElapsedTime",metric(t,"ElapsedTime","MILLISECONDS","REPORTED_QUERY_TOTAL"),"CpuTime",metric(t,"CpuTime","MILLISECONDS","REPORTED_QUERY_TOTAL"))));}
    if(roots.size()!=1)return new NativeParsed(roots.isEmpty()?"NOT_PROVIDED":"UNKNOWN",roots.isEmpty()?"PLAN_NOT_PROVIDED":"MULTI_ROOT_PLAN_UNSUPPORTED",raw,null,List.copyOf(evidence),List.copyOf(times));
    boolean bad=evidence.stream().anyMatch(n->n.metrics().values().stream().anyMatch(m->"PARSE_ERROR".equals(m.state()))||n.threads().stream().flatMap(t->t.metrics().values().stream()).anyMatch(m->"PARSE_ERROR".equals(m.state())))||times.stream().flatMap(t->t.metrics().values().stream()).anyMatch(m->"PARSE_ERROR".equals(m.state()));
    return new NativeParsed(bad?"PARTIAL":"COMPLETE",bad?"INVALID_NATIVE_METRIC":null,raw,roots.get(0),List.copyOf(evidence),List.copyOf(times));
  }catch(Exception e){return new NativeParsed("UNKNOWN",e instanceof Limited?e.getMessage():"XML_REJECTED_OR_INVALID",raw!=null&&raw.length()<=MAX_CHARS?raw:null,null,List.of(),List.of());}}

  @Override public String dialect() { return "sqlserver"; }
  @Override public PlanNode parse(String raw,String dialect) { return parseNative(raw).plan(); }
  @Override public ExplainService.Parsed parseDetailed(String raw,String dialect) {return parseDetailed(raw,dialect,"SQLSERVER_XML");}
  @Override public ExplainService.Parsed parseDetailed(String raw,String dialect,String channel) {
    if(!"sqlserver".equals(dialect)||!"SQLSERVER_XML".equals(channel))return ExplainService.failed("PLAN_CHANNEL_UNSUPPORTED");
    var p=parseNative(raw);
    return new ExplainService.Parsed(p.plan(),p.status(),p.errorReason()==null?List.of():List.of(new PlanCapture.Issue(p.errorReason(),null,"Native plan unavailable or incomplete")));
  }
  public static PlanDetails details(NativeParsed parsed,PlanEvidence.Environment environment) {
    var nodes=parsed.nodes().stream().map(n->new PlanDetails.NativeNode(n.sourcePath(),n.physicalOp(),n.logicalOp(),n.attributes(),n.metrics(),
      n.threads().stream().map(t->new PlanDetails.NativeThread(t.sourcePath(),t.threadId(),t.metrics())).toList())).toList();
    var query=parsed.queryStats().stream().map(q->new PlanDetails.NativeQuery(q.sourcePath(),q.metrics())).toList();
    var missing=Metric.absent("NOT_COLLECTED","MILLISECONDS","TOTAL","native query time","SEE_NATIVE_QUERY_STATS");
    var local=parsed.nodes().stream().map(n->new PlanDetails.NodeEvidence(n.sourcePath(),n.attributes().toString(),missing)).toList();
    return new PlanDetails(1,"SQLSERVER_XML",parsed.rawPlan(),environment.database(),environment.databaseUser(),environment.settings(),missing,missing,local,
      environment.errorReason(),new PlanDetails.NativeEvidence(1,nodes,query));
  }
}
