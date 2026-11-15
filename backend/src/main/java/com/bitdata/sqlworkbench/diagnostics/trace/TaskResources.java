package com.bitdata.sqlworkbench.diagnostics.trace;
import com.bitdata.sqlworkbench.connection.WorkloadLeaseManager;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
/** Resource event bridge from actual JDBC method return/throw, never polls a driver. */
final class TaskResources {
 final WorkloadLeaseManager.Ticket root;
 final com.bitdata.sqlworkbench.execution.OracleNetworkTimeoutExecutor networks;
 WorkloadLeaseManager.Ticket connectionTicket,producerTicket;
 com.bitdata.sqlworkbench.execution.OracleNetworkTimeoutExecutor.Registration registration;
 boolean producerClosed;
 final Map<String,Map<String,String>> resources=new LinkedHashMap<>();
 final Map<String,WorkloadLeaseManager.Ticket> childOwners=new LinkedHashMap<>();
 final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.locks.ReentrantLock> closeLocks=new java.util.concurrent.ConcurrentHashMap<>();
 final List<Map<String,String>> closeErrors=new ArrayList<>();
 final Map<Object,Object> wrappers=new IdentityHashMap<>();Connection connectionWrapper;
 boolean workerReturned,networkInstalled;volatile String instrumentationReason;int sequence;
 TaskResources(WorkloadLeaseManager.Ticket root,com.bitdata.sqlworkbench.execution.OracleNetworkTimeoutExecutor networks){this.root=root;this.networks=networks;}
 synchronized void bindConnectionTicket(WorkloadLeaseManager.Ticket ticket){connectionTicket=ticket;}
 synchronized void reconcileProducer(){
  if(registration!=null&&!producerClosed&&connectionTicket!=null&&connectionTicket.isReleased()){
   producerClosed=true;registration.connectionCloseConfirmed();producerTicket.release();
  }
  if(connectionTicket!=null&&connectionTicket.isReleased())for(var e:childOwners.entrySet()){
   var resource=resources.get(e.getKey());if(Set.of("OPEN","UNCONFIRMED").contains(resource.get("state"))){
    e.getValue().clearUnconfirmed();e.getValue().release();resource.put("state","RETURNED");resource.put("confirmationBasis","PHYSICAL_CONNECTION_CLOSE_RETURNED");
   }
  }
 }
 synchronized String register(String kind){String id=kind+":"+(++sequence);resources.put(id,new LinkedHashMap<>(Map.of("state","OPEN","errorReason","")));closeLocks.put(id,new java.util.concurrent.locks.ReentrantLock());return id;}
 synchronized void finish(String id,String reason){resources.get(id).put("state",reason==null?"RETURNED":"UNCONFIRMED");if(reason!=null){resources.get(id).put("errorReason",reason);closeErrors.add(Map.of("resource",id,"errorReason",reason));}else resources.get(id).put("confirmationBasis","OWN_CLOSE_RETURNED");}
 synchronized void bindChild(String id,WorkloadLeaseManager.Ticket ticket){childOwners.put(id,ticket);}
 synchronized void workerReturned(){workerReturned=true;}
 synchronized Map<String,Object> snapshot(boolean terminal){
  reconcileProducer();
  var lease=root.resourceSnapshot();var reasons=(List<?>)lease.get("reasons");int references=(Integer)lease.get("references");
  boolean complete=(!networkInstalled||producerClosed)&&instrumentationReason==null;
  String state=!reasons.isEmpty()?"UNCONFIRMED":references>0||!workerReturned?"PENDING":!complete?"UNKNOWN":terminal?"CONFIRMED":"PENDING";
  String reason=!reasons.isEmpty()?"RESOURCE_CLEANUP_UNCONFIRMED":references>0||!workerReturned?"RESOURCE_WORK_OUTSTANDING":!complete?instrumentationReason!=null?instrumentationReason:"NETWORK_CALLBACK_PRODUCER_LIFETIME_UNVERIFIED":!closeErrors.isEmpty()?"CLEANUP_CONFIRMED_WITH_CLOSE_ERRORS":"";
  var copied=new LinkedHashMap<String,Object>();resources.forEach((k,v)->copied.put(k,Map.copyOf(v)));
  return Map.of("state",state,"errorReason",reason,"inventorySealed",complete,"lease",lease,"resources",copied,"closeErrors",List.copyOf(closeErrors),"degraded",!state.equals("CONFIRMED")||!closeErrors.isEmpty());
 }
 synchronized Connection wrap(Connection connection){
  if(wrappers.containsKey(connection))return (Connection)wrappers.get(connection);
  if(connectionWrapper!=null)throw new IllegalStateException("MULTIPLE_TASK_CONNECTIONS_UNVERIFIED");
  connectionWrapper=proxy(Connection.class,connection,(method,args)->{
   if(Set.of("prepareCall","abort","createBlob","createClob","createNClob","createSQLXML","createArrayOf","createStruct").contains(method.getName()))
    throw new SQLFeatureNotSupportedException("TASK_JDBC_API_UNSUPPORTED");
   if(method.getName().equals("setNetworkTimeout")&&args!=null&&args.length==2){
    synchronized(this){
     networkInstalled=true;
     if(registration==null){producerTicket=root.retain();try{registration=networks.register(producerTicket,reason->{instrumentationReason=reason;});}catch(RuntimeException failure){producerTicket.release();instrumentationReason="NETWORK_REGISTRATION_FAILED";throw failure;}}
    }
    var changed=args.clone();changed[0]=registration;return invoke(connection,method,changed);
   }
   Object value;
   try{value=invoke(connection,method,args);}finally{if(method.getName().equals("close"))reconcileProducer();}
   return owned(value,null,method.getReturnType());
  });
  wrappers.put(connection,connectionWrapper);return connectionWrapper;
 }
 synchronized Object owned(Object value,Statement parent,Class<?> declaredReturnType){
  if(value==null)return null;
  if(declaredReturnType==ResultSetMetaData.class)return value;
  if(value instanceof Connection)return connectionWrapper;
  if(wrappers.containsKey(value))return wrappers.get(value);
  if(value instanceof PreparedStatement ps)return statement(ps,PreparedStatement.class);
  if(value instanceof Statement st)return statement(st,Statement.class);
  if(value instanceof ResultSet rs)return rows(rs,parent);
  if(value instanceof DatabaseMetaData meta){
   var wrapped=proxy(DatabaseMetaData.class,meta,(m,a)->m.getName().equals("getConnection")?connectionWrapper:owned(invoke(meta,m,a),null,m.getReturnType()));
   wrappers.put(meta,wrapped);return wrapped;
  }
  return value;
 }
 synchronized <T extends Statement>T statement(T delegate,Class<T> type){
  if(wrappers.containsKey(delegate))return type.cast(wrappers.get(delegate));
  var ticket=root.retain();String id=register("statement");bindChild(id,ticket);
  var holder=new Statement[1];
  T wrapped=proxy(type,delegate,(method,args)->{
   if(method.getName().equals("getConnection"))return connectionWrapper;
   if(method.getName().equals("close")&&method.getParameterCount()==0)return close(delegate,method,args,ticket,id,"STATEMENT_CLOSE_UNCONFIRMED");
   return owned(invoke(delegate,method,args),holder[0],method.getReturnType());
  });
  holder[0]=wrapped;wrappers.put(delegate,wrapped);return wrapped;
 }
 synchronized ResultSet rows(ResultSet delegate,Statement parent){
  if(wrappers.containsKey(delegate))return (ResultSet)wrappers.get(delegate);
  var ticket=root.retain();String id=register("resultSet");bindChild(id,ticket);
  var wrapped=proxy(ResultSet.class,delegate,(method,args)->{
   if(method.getName().equals("getStatement"))return parent;
   if(method.getName().equals("close")&&method.getParameterCount()==0)return close(delegate,method,args,ticket,id,"RESULTSET_CLOSE_UNCONFIRMED");
   return owned(invoke(delegate,method,args),parent,method.getReturnType());
  });wrappers.put(delegate,wrapped);return wrapped;
 }
 Object close(Object delegate,Method method,Object[] args,WorkloadLeaseManager.Ticket ticket,String id,String reason)throws Throwable{
  var lock=closeLocks.get(id);lock.lock();
  try{
   synchronized(this){if(resources.get(id).get("state").equals("RETURNED"))return null;resources.get(id).put("state","CLOSING");}
   try{Object value=invoke(delegate,method,args);ticket.clearUnconfirmed();finish(id,null);return value;}
   catch(Throwable failure){ticket.markUnconfirmed(reason);finish(id,reason);throw failure;}
   finally{ticket.release();}
  }finally{lock.unlock();}
 }
 @FunctionalInterface interface Call{Object run(Method method,Object[] args)throws Throwable;}
 static <T>T proxy(Class<T> type,Object delegate,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->{
  if(m.getName().equals("unwrap")&&a!=null){if(((Class<?>)a[0]).isInstance(p))return p;throw new SQLFeatureNotSupportedException("TASK_JDBC_UNWRAP_UNSUPPORTED");}
  if(m.getName().equals("isWrapperFor")&&a!=null)return ((Class<?>)a[0]).isInstance(p);
  if(m.getName().equals("equals"))return p==a[0];
  if(m.getName().equals("hashCode"))return System.identityHashCode(p);
  return call.run(m,a);
 }));}
 static Object invoke(Object delegate,Method method,Object[] args)throws Throwable{try{return method.invoke(delegate,args);}catch(InvocationTargetException e){throw e.getCause();}}
}
