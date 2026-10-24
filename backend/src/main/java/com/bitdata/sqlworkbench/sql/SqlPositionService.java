package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.model.SqlNodePosition;
import com.bitdata.sqlworkbench.sql.model.SqlPosition;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Walks a Calcite SqlNode AST and extracts positions of all significant SQL constructs.
 * Used to enable clickable node selection in the editor.
 */
@Service
public class SqlPositionService {

    private static final Logger log = LoggerFactory.getLogger(SqlPositionService.class);

    private final SqlDialectResolver dialectResolver;

    public SqlPositionService(SqlDialectResolver dialectResolver) {
        this.dialectResolver = dialectResolver;
    }

    /**
     * Extract positions of all significant SQL nodes from a parsed SQL string.
     */
    public List<SqlNodePosition> extractPositions(String sql, String dialect) {
        SqlParser.Config config = dialectResolver.parserConfig(dialect);

        try {
            SqlNode node = SqlParser.create(sql, config).parseQuery();
            PositionCollector collector = new PositionCollector(sql);
            collector.walk(node);
            return collector.getPositions();
        } catch (Exception e) {
            log.warn("Failed to extract positions: {}", e.getMessage());
            return List.of();
        }
    }

    private static class PositionCollector {
        private final String sql;
        private final List<SqlNodePosition> positions = new ArrayList<>();
        private int nodeCounter = 0;

        PositionCollector(String sql) {
            this.sql = sql;
        }

        List<SqlNodePosition> getPositions() {
            return positions;
        }

        void walk(SqlNode node) {
            if (node == null) return;

            if (node instanceof SqlSelect) {
                visitSelect((SqlSelect) node);
            } else if (node instanceof SqlJoin) {
                visitJoin((SqlJoin) node);
            } else if (node instanceof SqlBasicCall) {
                visitBasicCall((SqlBasicCall) node);
            } else if (node instanceof SqlIdentifier) {
                visitIdentifier((SqlIdentifier) node);
            } else if (node instanceof SqlNodeList) {
                for (SqlNode child : (SqlNodeList) node) {
                    walk(child);
                }
            } else if (node instanceof SqlWith withNode) {
                // CTE query - walk each CTE definition's query
                for (SqlNode item : withNode.withList) {
                    if (item instanceof SqlWithItem withItem) {
                        walk(withItem.query);
                    }
                }
                // Also walk the body
                walk(withNode.body);
            } else if (node instanceof SqlOrderBy ob) {
                // ORDER BY - visit the order list from the SqlOrderBy wrapper
                if (ob.orderList != null && !ob.orderList.isEmpty()) {
                    SqlParserPos orderPos = ob.orderList.getParserPosition();
                    if (isValidPos(orderPos)) {
                        positions.add(createPosition("ord_" + nodeCounter++, "ORDER_BY", "ORDER BY", orderPos));
                    }
                }
                walk(ob.query);
            }
            // Other node types are not walked for positions
        }

        private void visitSelect(SqlSelect select) {
            SqlParserPos pos = select.getParserPosition();
            if (isValidPos(pos)) {
                SqlNodeList selectList = select.getSelectList();
                if (selectList != null) {
                    SqlParserPos selectPos = selectList.getParserPosition();
                    if (isValidPos(selectPos)) {
                        positions.add(createPosition("sel_" + nodeCounter++, "SELECT_LIST", "SELECT list", selectPos));
                    }
                }

                if (select.getWhere() != null) {
                    SqlParserPos wherePos = select.getWhere().getParserPosition();
                    if (isValidPos(wherePos)) {
                        positions.add(createPosition("where_" + nodeCounter++, "WHERE_CLAUSE", "WHERE clause", wherePos));
                    }
                }

                if (select.getGroup() != null && !select.getGroup().isEmpty()) {
                    SqlParserPos groupPos = select.getGroup().getParserPosition();
                    if (isValidPos(groupPos)) {
                        positions.add(createPosition("grp_" + nodeCounter++, "GROUP_BY", "GROUP BY", groupPos));
                    }
                }

                if (select.getHaving() != null) {
                    SqlParserPos havingPos = select.getHaving().getParserPosition();
                    if (isValidPos(havingPos)) {
                        positions.add(createPosition("hav_" + nodeCounter++, "HAVING_CLAUSE", "HAVING clause", havingPos));
                    }
                }

                if (select.getOrderList() != null && !select.getOrderList().isEmpty()) {
                    SqlParserPos orderPos = select.getOrderList().getParserPosition();
                    if (isValidPos(orderPos)) {
                        positions.add(createPosition("ord_" + nodeCounter++, "ORDER_BY", "ORDER BY", orderPos));
                    }
                }
            }

            if (select.getFrom() != null) walk(select.getFrom());
            if (select.getSelectList() != null) walk(select.getSelectList());
            if (select.getWhere() != null) walk(select.getWhere());
            if (select.getHaving() != null) walk(select.getHaving());
            if (select.getGroup() != null) walk(select.getGroup());
            if (select.getOrderList() != null) walk(select.getOrderList());
        }

        private void visitJoin(SqlJoin join) {
            SqlParserPos pos = join.getParserPosition();
            if (isValidPos(pos)) {
                positions.add(createPosition("join_" + nodeCounter++, "JOIN_CONDITION",
                    "JOIN " + join.getJoinType().name(), pos));
            }
            if (join.getLeft() != null) walk(join.getLeft());
            if (join.getRight() != null) walk(join.getRight());
            if (join.getCondition() != null) walk(join.getCondition());
        }

        private void visitBasicCall(SqlBasicCall call) {
            SqlParserPos pos = call.getParserPosition();
            if (isValidPos(pos)) {
                String opName = call.getOperator().getName().toUpperCase();
                if (!opName.equals("AND") && !opName.equals("OR")) {
                    positions.add(createPosition("call_" + nodeCounter++, inferCallType(call), opName, pos));
                }
            }
            for (SqlNode operand : call.getOperandList()) {
                if (operand != null) walk(operand);
            }
        }

        private void visitIdentifier(SqlIdentifier id) {
            SqlParserPos pos = id.getParserPosition();
            if (isValidPos(pos)) {
                positions.add(createPosition("id_" + nodeCounter++, inferIdentifierType(id), id.toString(), pos));
            }
        }

        private SqlNodePosition createPosition(String nodeId, String type, String label, SqlParserPos pos) {
            SqlPosition start = SqlPosition.fromLineColumn(pos.getLineNum(), pos.getColumnNum(), sql);
            SqlPosition end = SqlPosition.fromLineColumn(pos.getEndLineNum(), pos.getEndColumnNum() + 1, sql);
            return new SqlNodePosition(nodeId, type, label, start, end);
        }

        private boolean isValidPos(SqlParserPos pos) {
            return pos != null && pos.getLineNum() > 0 && pos.getColumnNum() > 0;
        }

        private String inferIdentifierType(SqlIdentifier id) {
            String name = id.toString();
            if (name.length() <= 4 && name.equals(name.toUpperCase())) {
                return "TABLE_REF";
            }
            return "COLUMN_REF";
        }

        private String inferCallType(SqlBasicCall call) {
            String opName = call.getOperator().getName().toUpperCase();
            return switch (opName) {
                case "AND", "OR", "NOT", "=", "<>", "<", ">", "<=", ">=", "LIKE", "IN", "BETWEEN", "IS" -> "WHERE_CLAUSE";
                case "+", "-", "*", "/" -> "COLUMN_REF";
                default -> "EXPRESSION";
            };
        }
    }
}