package com.bitdata.sqlworkbench.sql.tsql;

import org.apache.calcite.sql.*;

import java.util.Arrays;

/** Ordering/pagination constraints for independently executable T-SQL candidates. */
public final class TsqlQueryLegality {
    private TsqlQueryLegality() {}

    public static String rejectionReason(SqlNode query) {
        return check(query, false);
    }

    private static String check(SqlNode node, boolean nested) {
        if (node == null) return null;
        if (node instanceof SqlWith with) {
            for (var item : with.withList) {
                String reason = check(((SqlWithItem) item).query, true);
                if (reason != null) return reason;
            }
            return check(with.body, nested);
        }
        if (node instanceof SqlOrderBy order) {
            boolean ordered = order.orderList != null && !order.orderList.isEmpty();
            if (order.offset != null && !ordered) return "RETAINED_OFFSET_FETCH_REQUIRES_ORDER_BY";
            boolean innerTop = order.query instanceof SqlSelect select && select.getFetch() != null;
            if (ordered && nested && order.offset == null && order.fetch == null && !innerTop)
                return "NESTED_ORDER_BY_REQUIRES_LIMITER";
            String reason = check(order.query, nested);
            if (reason != null) return reason;
            for (SqlNode child : Arrays.asList(order.orderList, order.offset, order.fetch)) {
                reason = check(child, true);
                if (reason != null) return reason;
            }
            return null;
        }
        if (node instanceof SqlSelect select) {
            boolean ordered = select.getOrderList() != null && !select.getOrderList().isEmpty();
            if (select.getOffset() != null && !ordered) return "RETAINED_OFFSET_FETCH_REQUIRES_ORDER_BY";
            if (ordered && nested && select.getFetch() == null && select.getOffset() == null)
                return "NESTED_ORDER_BY_REQUIRES_LIMITER";
        }
        if (node instanceof SqlCall call) {
            for (SqlNode child : call.getOperandList()) {
                String reason = check(child, true);
                if (reason != null) return reason;
            }
        } else if (node instanceof SqlNodeList list) {
            for (SqlNode child : list) {
                String reason = check(child, nested);
                if (reason != null) return reason;
            }
        }
        return null;
    }
}
