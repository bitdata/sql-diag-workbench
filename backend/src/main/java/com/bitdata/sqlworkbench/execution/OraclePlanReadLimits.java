package com.bitdata.sqlworkbench.execution;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded Oracle JDBC evidence reader. Caller owns ResultSet/Statement/Connection cleanup. */
public final class OraclePlanReadLimits {
 public static final int SQL_CHARS=131072,SQL_BYTES=512*1024,FIELD_CHARS=1024,RAW_BYTES=512*1024,NODES=4096;
 public static final long BUSINESS_ROWS=1_000_000;
 static final ObjectMapper JSON=new ObjectMapper();
 public static final class Limited extends RuntimeException {Limited(String reason){super(reason);}}
 public record Rows(ArrayNode rows,int rawBytes) {}
 static void limit(boolean value,String reason){if(!value)throw new Limited(reason);}
 /** Reads at most maxChars+1 UTF-16 units and rejects malformed surrogate sequences. */
 public static String text(Reader reader,int maxChars,int maxBytes,String reason,Runnable checkpoint)throws IOException {
  if(reader==null)return null;
  try(reader){
   var value=new StringBuilder(Math.min(512,maxChars));char[] chunk=new char[256];int zeroReads=0;
   while(true){
    checkpoint.run();int n=reader.read(chunk,0,Math.min(chunk.length,maxChars-value.length()+1));checkpoint.run();
    if(n<0)break;
    if(n==0){limit(++zeroReads<=8,"TEXT_READER_NO_PROGRESS");continue;}zeroReads=0;
    limit(value.length()+n<=maxChars,reason);value.append(chunk,0,n);
   }
   String s=value.toString();
   for(int i=0;i<s.length();i++){
    char c=s.charAt(i);if(Character.isHighSurrogate(c)){limit(i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1)),"MALFORMED_EVIDENCE_TEXT");i++;}
    else limit(!Character.isLowSurrogate(c),"MALFORMED_EVIDENCE_TEXT");
   }
   limit(s.getBytes(StandardCharsets.UTF_8).length<=maxBytes,reason);return s;
  }
 }
 /** Fixed metadata types only: NUMBER is decoded exactly, text is streamed with a cap. */
 public static String cell(ResultSet rs,int index,int chars,int bytes,String reason,Runnable checkpoint)throws SQLException,IOException{
  checkpoint.run();int type=rs.getMetaData().getColumnType(index);
  String value;
  if(Set.of(Types.NUMERIC,Types.DECIMAL,Types.INTEGER,Types.BIGINT,Types.SMALLINT,Types.TINYINT,Types.FLOAT,Types.REAL,Types.DOUBLE).contains(type)){
   java.math.BigDecimal number=rs.getBigDecimal(index);checkpoint.run();if(number==null)return null;
   limit(number.precision()<=1024&&Math.abs((long)number.scale())<=1024,"PLAN_NUMBER_LIMIT");
   value=number.stripTrailingZeros().toPlainString();limit(value.length()<=chars&&value.getBytes(StandardCharsets.UTF_8).length<=bytes,reason);
  } else if(Set.of(Types.VARCHAR,Types.CHAR,Types.NVARCHAR,Types.NCHAR,Types.LONGVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(type)){
   value=text(rs.getCharacterStream(index),chars,bytes,reason,checkpoint);
  } else throw new Limited("PLAN_COLUMN_TYPE_UNSUPPORTED");
  checkpoint.run();return value;
 }
 public static String fullSql(ResultSet rs,Runnable checkpoint)throws SQLException,IOException{
  checkpoint.run();limit(rs.next(),"CURSOR_EVICTED");checkpoint.run();
  String value=text(rs.getCharacterStream(1),SQL_CHARS,SQL_BYTES,"SQL_TEXT_BUDGET_EXCEEDED",checkpoint);
  limit(value!=null,"SQL_TEXT_UNAVAILABLE");checkpoint.run();limit(!rs.next(),"MULTIPLE_CURSOR_ROWS");checkpoint.run();return value;
 }
 /** Columns are a fixed collector projection, never supplied from a user SQL identifier. */
 public static Rows planRows(ResultSet rs,List<String> columns,Runnable checkpoint)throws SQLException,IOException{
  limit(!columns.isEmpty()&&columns.size()<=16&&new HashSet<>(columns).size()==columns.size(),"INVALID_PLAN_PROJECTION");
  var rows=JSON.createArrayNode();int bytes=2;
  while(true){
   checkpoint.run();boolean next=rs.next();checkpoint.run();if(!next)break;
   limit(rows.size()<NODES,"PLAN_NODE_LIMIT");var row=JSON.createObjectNode();
   for(int i=0;i<columns.size();i++){
    checkpoint.run();String value=cell(rs,i+1,FIELD_CHARS,FIELD_CHARS*4,"PLAN_FIELD_LIMIT",checkpoint);
    row.put(columns.get(i),value);
   }
   byte[] serialized=JSON.writeValueAsBytes(row);int nextBytes=bytes+(rows.isEmpty()?0:1)+serialized.length;
   limit(nextBytes<=RAW_BYTES,"PLAN_RAW_LIMIT");rows.add(row);bytes=nextBytes;
  }
  return new Rows(rows,bytes);
 }
 /** Drain without reading or materializing any business cell. EOF is required for COMPLETE. */
 public static long drain(ResultSet rs,Runnable checkpoint)throws SQLException{
  long count=0;
  while(true){checkpoint.run();boolean next=rs.next();checkpoint.run();if(!next)return count;limit(++count<=BUSINESS_ROWS,"DATA_STREAM_LIMIT_EXCEEDED");}
 }
}
