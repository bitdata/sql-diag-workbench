package com.bitdata.sqlworkbench.optimization;

import java.sql.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;

/** Bounded exact multiset, including duplicates and nulls. */
final class ExactResultBag {
    static final int MAX_ROWS=100_000, MAX_COLUMNS=32, MAX_CELL_BYTES=65_536, MAX_CELLS=250_000;
    static final long MAX_BYTES=16L*1024*1024;
    record Bag(List<Column> columns,Map<List<Cell>,Long> counts,long rows) {
        Side side() { return new Side(Long.toString(rows),true,columns); }
        long bytes() {
            long total=0;
            for(var entry:counts.entrySet()){
                long rowBytes=8;
                for(Cell cell:entry.getKey())rowBytes+=8+(cell.value()==null?0:cell.value().getBytes(StandardCharsets.UTF_8).length);
                total=Math.addExact(total,Math.multiplyExact(rowBytes,entry.getValue()));
            }
            return total;
        }
    }
    static String family(int type) {
        return switch(type) {
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL -> "EXACT_NUMBER";
            case Types.VARCHAR,Types.LONGVARCHAR -> "TEXT";
            default -> throw reject("TYPE_UNSUPPORTED");
        };
    }
    static void numericDomain(int precision,int scale) {
        if(precision<1||precision>65||scale<0||scale>30)throw reject("NUMERIC_DOMAIN_UNSUPPORTED");
    }
    static Cell cell(ResultSet r,int i,int type,ValidationControl control) throws Exception {
        String kind=family(type),value;
        if(kind.equals("EXACT_NUMBER")) {
            BigDecimal n=r.getBigDecimal(i);
            if(n==null)return new Cell("NULL",null);
            if(n.precision()>65||n.scale()>30||n.scale() < -65)throw reject("NUMERIC_DOMAIN_UNSUPPORTED");
            value=n.stripTrailingZeros().toPlainString();
        } else {
            try(var reader=r.getCharacterStream(i)) {
                if(reader==null)return new Cell("NULL",null);
                StringBuilder text=new StringBuilder();char[] block=new char[1024];int size;
                while((size=reader.read(block))!=-1) {
                    control.check();
                    if(text.length()+size>MAX_CELL_BYTES)throw reject("CELL_BUDGET_EXCEEDED");
                    text.append(block,0,size);
                }
                value=text.toString();
            }
        }
        if(value.getBytes(StandardCharsets.UTF_8).length>MAX_CELL_BYTES)throw reject("CELL_BUDGET_EXCEEDED");
        return new Cell(kind,value);
    }
    static Bag read(Connection c,String dialect,String sql,ValidationControl control) throws Exception {
        control.check();
        List<Column> columns=new ArrayList<>();Map<List<Cell>,Long> counts=new HashMap<>();long rows=0,bytes=0;
        try(PreparedStatement s=c.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)) {
            try {
                control.attach(s); s.setFetchSize(dialect.equals("mysql")?Integer.MIN_VALUE:128);
                try(ResultSet r=s.executeQuery()) {
                    var m=r.getMetaData();int n=m.getColumnCount();
                    if(n<1||n>MAX_COLUMNS)throw reject("COLUMN_BUDGET_EXCEEDED");
                    for(int i=1;i<=n;i++) {
                        if(family(m.getColumnType(i)).equals("EXACT_NUMBER")) {
                            numericDomain(m.getPrecision(i),m.getScale(i));
                            if(!m.isSigned(i))throw reject("TYPE_UNSUPPORTED");
                        }
                        columns.add(new Column(m.getColumnLabel(i),m.getColumnType(i),m.getColumnTypeName(i),m.getPrecision(i),m.getScale(i),m.isSigned(i)));
                    }
                    while(r.next()) {
                        control.check();
                        if(++rows>MAX_ROWS)throw reject("ROW_BUDGET_EXCEEDED");
                        if(rows*n>MAX_CELLS)throw reject("CELL_COUNT_BUDGET_EXCEEDED");
                        List<Cell> row=new ArrayList<>();long rowBytes=8;
                        for(int i=1;i<=n;i++) { var v=cell(r,i,m.getColumnType(i),control);row.add(v);rowBytes+=8+(v.value()==null?0:v.value().getBytes(StandardCharsets.UTF_8).length); }
                        bytes+=rowBytes;if(bytes>MAX_BYTES)throw reject("BYTE_BUDGET_EXCEEDED");
                        counts.merge(List.copyOf(row),1L,Math::addExact);
                    }
                    control.check();
                }
            } finally { control.detach(s); }
        }
        return new Bag(List.copyOf(columns),Map.copyOf(counts),rows);
    }
}
