package minidb.optimizer;

import minidb.catalog.Catalog;
import minidb.catalog.Catalog.*;
import minidb.parser.SQLParser.*;
import minidb.index.BPlusTree;
import java.util.*;

/**
 * QueryOptimizer - Cost-Based Query Optimizer for MiniDB.
 * 
 * Makes two key decisions:
 * 1. Access Path Selection: Table scan vs. Index scan
 * 2. Join Order Optimization: Which table to scan first in multi-table joins
 * 
 * Uses table statistics (row count, distinct values) for selectivity estimation,
 * then computes cost estimates to choose the cheapest execution plan.
 * 
 * Cost Model:
 * - Table Scan cost = number of pages in the table
 * - Index Scan cost = height of B+ tree + number of matching leaf pages
 * - Nested Loop Join cost = |outer| + |outer| * |inner| (pages)
 * 
 * Selectivity Estimation:
 * - Equality (col = val): 1 / distinct_values(col)
 * - Range (col < val): (val - min) / (max - min)  
 * - Default: 0.5 (conservative estimate)
 * 
 * Design Decision: Greedy join ordering rather than dynamic programming.
 * For N tables, DP would explore O(2^N) plans; greedy picks the cheapest
 * next join at each step, producing O(N^2) complexity. Good enough for
 * typical queries with 2-3 tables.
 */
public class QueryOptimizer {

    // ======================== QUERY PLAN ========================

    /**
     * Represents an optimized query execution plan.
     */
    public static class QueryPlan {
        public String accessMethod;    // "TABLE_SCAN" or "INDEX_SCAN"
        public String indexName;       // Index to use (if INDEX_SCAN)
        public double estimatedCost;   // Estimated I/O cost
        public double estimatedRows;   // Estimated result cardinality
        public double selectivity;     // WHERE clause selectivity
        public List<String> joinOrder; // Order of tables for joins
        public List<JoinPlan> joinPlans; // How to execute each join
        public String explanation;     // Human-readable plan description

        public QueryPlan() {
            joinOrder = new ArrayList<>();
            joinPlans = new ArrayList<>();
        }

        @Override
        public String toString() {
            return explanation;
        }
    }

    /**
     * Plan for a single join operation.
     */
    public static class JoinPlan {
        public String leftTable;
        public String rightTable;
        public String joinColumn;
        public String joinMethod; // "NESTED_LOOP", "INDEX_NESTED_LOOP"
        public double cost;
    }

    // ======================== OPTIMIZER STATE ========================

    private Catalog catalog;
    private Map<String, BPlusTree> indexes;

    public QueryOptimizer(Catalog catalog, Map<String, BPlusTree> indexes) {
        this.catalog = catalog;
        this.indexes = indexes;
    }

    // ======================== OPTIMIZE ========================

    /**
     * Generate an optimized query plan for a SELECT statement.
     * 
     * @param stmt The parsed SELECT statement
     * @return An optimized QueryPlan
     */
    public QueryPlan optimize(SelectStatement stmt) {
        QueryPlan plan = new QueryPlan();
        StringBuilder explanation = new StringBuilder();
        explanation.append("=== QUERY PLAN ===\n");

        // Step 1: Estimate selectivity of WHERE clause
        double selectivity = 1.0;
        if (stmt.where != null) {
            selectivity = estimateSelectivity(stmt.where, stmt.tables.get(0));
        }
        plan.selectivity = selectivity;

        // Step 2: Choose access method for each table
        for (int i = 0; i < stmt.tables.size(); i++) {
            String tableName = stmt.tables.get(i);
            Schema schema = catalog.getSchema(tableName);
            TableStats stats = catalog.getStats(tableName);
            
            if (schema == null) continue;

            // Calculate costs for both access methods
            double tableScanCost = estimateTableScanCost(tableName);
            double indexScanCost = Double.MAX_VALUE;
            String bestIndex = null;

            // Check if we can use an index
            if (stmt.where != null) {
                String whereCol = getWhereColumn(stmt.where);
                if (whereCol != null) {
                    // Strip table prefix
                    String bareCol = whereCol.contains(".") ? 
                            whereCol.substring(whereCol.indexOf('.') + 1) : whereCol;
                    
                    String idxName = catalog.getIndexForColumn(tableName, bareCol);
                    if (idxName != null && indexes.containsKey(idxName)) {
                        BPlusTree tree = indexes.get(idxName);
                        indexScanCost = estimateIndexScanCost(tree, selectivity, stats);
                        bestIndex = idxName;
                    }
                }
            }

            // Choose the cheaper access method
            if (bestIndex != null && indexScanCost < tableScanCost) {
                plan.accessMethod = "INDEX_SCAN";
                plan.indexName = bestIndex;
                plan.estimatedCost = indexScanCost;
                explanation.append(String.format("Table '%s': INDEX SCAN using %s (cost=%.1f)\n",
                        tableName, bestIndex, indexScanCost));
                explanation.append(String.format("  Reason: Index scan (%.1f) < Table scan (%.1f)\n",
                        indexScanCost, tableScanCost));
            } else {
                plan.accessMethod = "TABLE_SCAN";
                plan.estimatedCost = tableScanCost;
                explanation.append(String.format("Table '%s': TABLE SCAN (cost=%.1f)\n",
                        tableName, tableScanCost));
                if (bestIndex != null) {
                    explanation.append(String.format("  Reason: Table scan (%.1f) < Index scan (%.1f)\n",
                            tableScanCost, indexScanCost));
                } else {
                    explanation.append("  Reason: No applicable index found\n");
                }
            }

            // Estimate result rows
            int rowCount = stats != null ? stats.getRowCount() : 0;
            plan.estimatedRows = rowCount * selectivity;
            explanation.append(String.format("  Selectivity: %.4f, Estimated rows: %.0f\n",
                    selectivity, plan.estimatedRows));
        }

        // Step 3: Optimize join order (if multiple tables)
        if (stmt.tables.size() > 1 || !stmt.joins.isEmpty()) {
            List<String> allTables = new ArrayList<>(stmt.tables);
            for (JoinClause join : stmt.joins) {
                if (!allTables.contains(join.tableName)) {
                    allTables.add(join.tableName);
                }
            }

            plan.joinOrder = optimizeJoinOrder(allTables, stmt.joins);
            explanation.append("\nJoin Order: ").append(plan.joinOrder).append("\n");

            // Plan each join
            for (int i = 1; i < plan.joinOrder.size(); i++) {
                JoinPlan jp = new JoinPlan();
                jp.leftTable = plan.joinOrder.get(i - 1);
                jp.rightTable = plan.joinOrder.get(i);
                
                // Check if index exists on join column
                String joinCol = findJoinColumn(stmt.joins, jp.rightTable);
                if (joinCol != null) {
                    String idxName = catalog.getIndexForColumn(jp.rightTable, joinCol);
                    if (idxName != null && indexes.containsKey(idxName)) {
                        jp.joinMethod = "INDEX_NESTED_LOOP";
                        jp.joinColumn = joinCol;
                        explanation.append(String.format("Join %s ⋈ %s: INDEX NESTED LOOP on %s\n",
                                jp.leftTable, jp.rightTable, idxName));
                    } else {
                        jp.joinMethod = "NESTED_LOOP";
                        explanation.append(String.format("Join %s ⋈ %s: NESTED LOOP\n",
                                jp.leftTable, jp.rightTable));
                    }
                } else {
                    jp.joinMethod = "NESTED_LOOP";
                    explanation.append(String.format("Join %s ⋈ %s: NESTED LOOP\n",
                            jp.leftTable, jp.rightTable));
                }
                plan.joinPlans.add(jp);
            }
        }

        explanation.append("=================\n");
        plan.explanation = explanation.toString();
        return plan;
    }

    // ======================== COST ESTIMATION ========================

    /**
     * Estimate cost of a full table scan.
     * Cost = number of pages in the table.
     */
    private double estimateTableScanCost(String tableName) {
        TableStats stats = catalog.getStats(tableName);
        if (stats == null) return 1.0;
        // Assume ~40 tuples per page (rough estimate)
        return Math.max(1.0, Math.ceil(stats.getRowCount() / 40.0));
    }

    /**
     * Estimate cost of an index scan.
     * Cost = tree height + (selectivity * leaf pages)
     */
    private double estimateIndexScanCost(BPlusTree tree, double selectivity, TableStats stats) {
        int height = tree.getHeight();
        int totalRows = stats != null ? stats.getRowCount() : 0;
        double matchingRows = totalRows * selectivity;
        // Estimate leaf pages needed
        double leafPages = Math.ceil(matchingRows / 40.0);
        return height + leafPages;
    }

    // ======================== SELECTIVITY ESTIMATION ========================

    /**
     * Estimate the selectivity of a WHERE clause.
     * Selectivity = fraction of rows that satisfy the condition (0.0 to 1.0).
     */
    private double estimateSelectivity(WhereClause where, String tableName) {
        // Compound condition (AND/OR)
        if (where.logicalOp != null) {
            double leftSel = estimateSelectivity(where.left, tableName);
            double rightSel = estimateSelectivity(where.right, tableName);
            if (where.logicalOp.equals("AND")) {
                return leftSel * rightSel; // Independence assumption
            } else {
                return leftSel + rightSel - leftSel * rightSel; // Union
            }
        }

        // Simple condition
        String column = where.leftColumn;
        if (column.contains(".")) {
            column = column.substring(column.indexOf('.') + 1);
        }

        TableStats stats = catalog.getStats(tableName);
        if (stats == null) return 0.5;

        String operator = where.operator;
        
        switch (operator) {
            case "=":
                // Selectivity = 1 / distinct_values
                int distinct = stats.getDistinctValues(column);
                return 1.0 / Math.max(1, distinct);
                
            case "!=": case "<>":
                distinct = stats.getDistinctValues(column);
                return 1.0 - (1.0 / Math.max(1, distinct));
                
            case "<": case "<=":
                return estimateRangeSelectivity(stats, column, where.rightValue, true);
                
            case ">": case ">=":
                return estimateRangeSelectivity(stats, column, where.rightValue, false);
                
            default:
                return 0.5; // Conservative default
        }
    }

    /**
     * Estimate selectivity for range predicates using min/max statistics.
     */
    private double estimateRangeSelectivity(TableStats stats, String column, Object value, boolean isLess) {
        Object min = stats.getMinValue(column);
        Object max = stats.getMaxValue(column);
        
        if (min == null || max == null || value == null) {
            return 0.33; // Default for range
        }
        
        try {
            double minVal = ((Number) min).doubleValue();
            double maxVal = ((Number) max).doubleValue();
            double val = ((Number) value).doubleValue();
            
            if (maxVal == minVal) return 0.5;
            
            double fraction = (val - minVal) / (maxVal - minVal);
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            
            return isLess ? fraction : (1.0 - fraction);
        } catch (ClassCastException e) {
            return 0.33;
        }
    }

    // ======================== JOIN ORDER OPTIMIZATION ========================

    /**
     * Optimize join order using a greedy algorithm.
     * Strategy: Start with the smallest table, then join the next cheapest.
     * 
     * This is a simplified version of the dynamic programming approach
     * used in real optimizers. For small numbers of tables (2-4),
     * greedy produces good results with O(N^2) complexity.
     */
    private List<String> optimizeJoinOrder(List<String> tables, List<JoinClause> joins) {
        if (tables.size() <= 1) return tables;

        List<String> remaining = new ArrayList<>(tables);
        List<String> order = new ArrayList<>();

        // Start with the smallest table
        String smallest = null;
        int minRows = Integer.MAX_VALUE;
        for (String table : remaining) {
            TableStats stats = catalog.getStats(table);
            int rows = stats != null ? stats.getRowCount() : 0;
            if (rows < minRows) {
                minRows = rows;
                smallest = table;
            }
        }
        order.add(smallest);
        remaining.remove(smallest);

        // Greedily add the cheapest next table
        while (!remaining.isEmpty()) {
            String bestNext = remaining.get(0);
            double bestCost = Double.MAX_VALUE;

            for (String table : remaining) {
                double cost = estimateJoinCost(order, table);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestNext = table;
                }
            }

            order.add(bestNext);
            remaining.remove(bestNext);
        }

        return order;
    }

    /**
     * Estimate the cost of joining a new table to the current result.
     */
    private double estimateJoinCost(List<String> currentTables, String newTable) {
        // Estimate current result size
        double currentSize = 1;
        for (String t : currentTables) {
            TableStats stats = catalog.getStats(t);
            currentSize *= stats != null ? stats.getRowCount() : 1;
        }
        
        TableStats newStats = catalog.getStats(newTable);
        double newSize = newStats != null ? newStats.getRowCount() : 1;
        
        // Nested loop join cost: outer * inner (in pages)
        return (currentSize / 40.0) + (currentSize / 40.0) * (newSize / 40.0);
    }

    // ======================== HELPERS ========================

    private String getWhereColumn(WhereClause where) {
        if (where.logicalOp != null) {
            return getWhereColumn(where.left); // Use left condition for index selection
        }
        return where.leftColumn;
    }

    private String findJoinColumn(List<JoinClause> joins, String tableName) {
        for (JoinClause join : joins) {
            if (join.tableName.equalsIgnoreCase(tableName)) {
                if (join.rightColumn != null) {
                    String col = join.rightColumn;
                    if (col.contains(".")) col = col.substring(col.indexOf('.') + 1);
                    return col;
                }
            }
        }
        return null;
    }
}
