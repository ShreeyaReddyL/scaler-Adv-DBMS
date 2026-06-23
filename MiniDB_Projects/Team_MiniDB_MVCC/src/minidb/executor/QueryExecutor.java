package minidb.executor;

import minidb.catalog.Catalog;
import minidb.catalog.Catalog.*;
import minidb.storage.*;
import minidb.index.BPlusTree;
import minidb.parser.SQLParser.*;
import minidb.optimizer.QueryOptimizer;
import minidb.optimizer.QueryOptimizer.*;
import minidb.transaction.*;
import minidb.transaction.TransactionManager.*;
import minidb.recovery.WALManager;
import minidb.mvcc.MVCCManager;
import java.util.*;
import java.util.stream.*;
import java.io.*;

/**
 * QueryExecutor - Executes parsed SQL statements against the MiniDB engine.
 * 
 * Implements a Volcano-style iterator model for query processing.
 * Each operation (scan, filter, join, project) is implemented as a
 * method that produces a list of result tuples.
 * 
 * Execution pipeline:
 *   SQL → Parser → AST → Optimizer → Plan → Executor → Results
 * 
 * Supports:
 * - CREATE TABLE / DROP TABLE
 * - INSERT INTO ... VALUES (...)
 * - SELECT with WHERE, JOIN, ORDER BY, GROUP BY, aggregates
 * - DELETE FROM ... WHERE ...
 * - CREATE INDEX
 * - Transaction control (BEGIN, COMMIT, ROLLBACK)
 * - EXPLAIN (shows query plan without executing)
 */
public class QueryExecutor {

    private Catalog catalog;
    private BufferPool bufferPool;
    private DiskManager diskManager;
    private Map<String, HeapFile> heapFiles;     // tableName -> HeapFile
    private Map<String, BPlusTree> indexes;       // indexName -> BPlusTree
    private TransactionManager txnManager;
    private LockManager lockManager;
    private WALManager walManager;
    private MVCCManager mvccManager;
    private QueryOptimizer optimizer;

    // Current transaction context (per-session)
    private Transaction currentTxn;
    private boolean autoCommit = true;

    public QueryExecutor(Catalog catalog, BufferPool bufferPool, DiskManager diskManager,
                         TransactionManager txnManager, LockManager lockManager,
                         WALManager walManager, MVCCManager mvccManager) {
        this.catalog = catalog;
        this.bufferPool = bufferPool;
        this.diskManager = diskManager;
        this.txnManager = txnManager;
        this.lockManager = lockManager;
        this.walManager = walManager;
        this.mvccManager = mvccManager;
        this.heapFiles = new HashMap<>();
        this.indexes = new HashMap<>();
        this.optimizer = new QueryOptimizer(catalog, indexes);

        // Initialize heap files for existing tables
        for (Schema schema : catalog.getAllSchemas()) {
            heapFiles.put(schema.getTableName(),
                    new HeapFile(schema.getTableId(), schema.getTableName(), bufferPool, diskManager));
        }

        // Load existing indexes
        for (Map.Entry<String, String> entry : catalog.getIndexes().entrySet()) {
            String indexName = entry.getKey();
            String dataDir = catalog.getDataDir();
            BPlusTree tree = BPlusTree.load(dataDir + File.separator + "index_" + indexName + ".dat");
            if (tree != null) {
                indexes.put(indexName, tree);
            }
        }
    }

    // ======================== EXECUTE ========================

    /**
     * Execute a SQL statement and return the result as formatted string.
     */
    public String execute(Statement stmt) {
        try {
            switch (stmt.getType()) {
                case "CREATE_TABLE": return executeCreateTable((CreateTableStatement) stmt);
                case "DROP_TABLE": return executeDropTable((DropTableStatement) stmt);
                case "INSERT": return executeInsert((InsertStatement) stmt);
                case "SELECT": return executeSelect((SelectStatement) stmt);
                case "DELETE": return executeDelete((DeleteStatement) stmt);
                case "CREATE_INDEX": return executeCreateIndex((CreateIndexStatement) stmt);
                case "BEGIN": return executeBegin();
                case "COMMIT": return executeCommit();
                case "ROLLBACK": return executeRollback();
                case "SHOW": return executeShow((ShowStatement) stmt);
                case "SET": return executeSet((SetStatement) stmt);
                default: return "Unknown statement type: " + stmt.getType();
            }
        } catch (Exception e) {
            // On error during transaction, abort
            if (currentTxn != null && !autoCommit) {
                // Don't auto-abort, let user decide
            }
            return "ERROR: " + e.getMessage();
        }
    }

    // ======================== DDL EXECUTION ========================

    private String executeCreateTable(CreateTableStatement stmt) {
        List<Column> columns = new ArrayList<>();
        for (ColumnDef def : stmt.columns) {
            DataType type = DataType.fromString(def.type);
            columns.add(new Column(def.name, type, def.length));
        }

        Schema schema = catalog.createTable(stmt.tableName, columns);
        if (stmt.primaryKey != null) {
            schema.setPrimaryKey(stmt.primaryKey);
        }

        // Create heap file
        HeapFile hf = new HeapFile(schema.getTableId(), schema.getTableName(), bufferPool, diskManager);
        heapFiles.put(schema.getTableName().toLowerCase(), hf);

        // Auto-create primary key index
        String pkCol = schema.getPrimaryKey();
        if (pkCol != null) {
            String idxName = "pk_" + schema.getTableName();
            BPlusTree tree = new BPlusTree(idxName, schema.getTableName(), pkCol);
            indexes.put(idxName, tree);
            catalog.createIndex(idxName, schema.getTableName(), pkCol);
        }

        catalog.save();
        return "Table '" + stmt.tableName + "' created. " + schema;
    }

    private String executeDropTable(DropTableStatement stmt) {
        Schema schema = catalog.getSchema(stmt.tableName);
        if (schema == null) {
            return "ERROR: Table '" + stmt.tableName + "' does not exist.";
        }

        // Remove heap file
        heapFiles.remove(stmt.tableName.toLowerCase());
        diskManager.deleteTable(schema.getTableId());
        bufferPool.evictTable(schema.getTableId());

        // Remove indexes
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, BPlusTree> entry : indexes.entrySet()) {
            if (entry.getValue().getTableName().equalsIgnoreCase(stmt.tableName)) {
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(indexes::remove);

        catalog.dropTable(stmt.tableName);
        catalog.save();
        return "Table '" + stmt.tableName + "' dropped.";
    }

    // ======================== INSERT ========================

    private String executeInsert(InsertStatement stmt) {
        Schema schema = catalog.getSchema(stmt.tableName);
        if (schema == null) return "ERROR: Table '" + stmt.tableName + "' does not exist.";

        HeapFile hf = heapFiles.get(stmt.tableName.toLowerCase());
        if (hf == null) return "ERROR: Heap file not found for table '" + stmt.tableName + "'";

        // Type-convert values to match schema
        Object[] fields = new Object[schema.getColumnCount()];
        for (int i = 0; i < Math.min(stmt.values.size(), fields.length); i++) {
            fields[i] = convertValue(stmt.values.get(i), schema.getColumns().get(i).getType());
        }

        // Transaction handling
        long txnId = getActiveTxnId();

        // Acquire exclusive lock (2PL)
        if (!mvccManager.isEnabled()) {
            Object pkValue = fields[schema.getColumnIndex(schema.getPrimaryKey())];
            lockManager.acquireExclusive(txnId,
                    LockManager.rowResource(stmt.tableName, pkValue));
        }

        // Create tuple and insert
        Tuple tuple = new Tuple(fields, stmt.tableName);
        tuple.setXmin(txnId);
        Tuple.RecordId rid = hf.insertTuple(tuple);

        // Log to WAL
        walManager.logInsert(txnId, stmt.tableName, fields, rid.getPageId(), rid.getSlotId());

        // Update primary key index
        String pkCol = schema.getPrimaryKey();
        if (pkCol != null) {
            int pkIdx = schema.getColumnIndex(pkCol);
            Object pkValue = fields[pkIdx];
            String idxName = catalog.getIndexForColumn(stmt.tableName, pkCol);
            if (idxName != null && indexes.containsKey(idxName)) {
                indexes.get(idxName).insert((Comparable) pkValue, rid);
            }
        }

        // MVCC: Insert version
        if (mvccManager.isEnabled()) {
            Object pkValue = fields[schema.getColumnIndex(schema.getPrimaryKey())];
            mvccManager.insertVersion(stmt.tableName, pkValue, fields, txnId);
        }

        // Update statistics
        TableStats stats = catalog.getStats(stmt.tableName);
        if (stats != null) {
            stats.incrementRowCount();
            for (int i = 0; i < fields.length; i++) {
                String colName = schema.getColumns().get(i).getName();
                stats.setDistinctValues(colName,
                        Math.min(stats.getRowCount(), stats.getDistinctValues(colName) + 1));
                // Update min/max
                if (fields[i] instanceof Comparable) {
                    Object min = stats.getMinValue(colName);
                    Object max = stats.getMaxValue(colName);
                    if (min == null || Tuple.compareValues(fields[i], min) < 0)
                        stats.setMinValue(colName, fields[i]);
                    if (max == null || Tuple.compareValues(fields[i], max) > 0)
                        stats.setMaxValue(colName, fields[i]);
                }
            }
        }

        // Record undo info for rollback
        if (currentTxn != null) {
            Object pkVal = fields[schema.getColumnIndex(schema.getPrimaryKey())];
            currentTxn.addUndoRecord(new UndoRecord(
                    UndoRecord.UndoType.INSERT, stmt.tableName, fields,
                    rid.getPageId(), rid.getSlotId(), pkVal));
        }

        // Auto-commit if not in explicit transaction
        if (autoCommit && currentTxn == null) {
            walManager.logCommit(txnId);
            txnManager.commit(txnId);
        }

        return "1 row inserted.";
    }

    // ======================== SELECT ========================

    private String executeSelect(SelectStatement stmt) {
        // Run optimizer
        QueryPlan plan = optimizer.optimize(stmt);

        // EXPLAIN: show plan only, don't execute
        if (stmt.isExplain) {
            return plan.toString();
        }

        long txnId = getActiveTxnId();

        // Get schema for main table
        Schema schema = catalog.getSchema(stmt.tables.get(0));
        if (schema == null) return "ERROR: Table '" + stmt.tables.get(0) + "' does not exist.";

        // Step 1: Scan the base table
        List<Tuple> results;
        
        if (plan.accessMethod != null && plan.accessMethod.equals("INDEX_SCAN") && plan.indexName != null) {
            // INDEX SCAN
            results = executeIndexScan(stmt, schema, plan, txnId);
        } else {
            // TABLE SCAN
            results = executeTableScan(stmt.tables.get(0), schema, txnId);
        }

        // Step 2: Apply WHERE filter (for table scan, or conditions not covered by index)
        if (stmt.where != null) {
            results = filterResults(results, stmt.where, schema, stmt);
        }

        // Step 3: Execute JOINs
        if (!stmt.joins.isEmpty()) {
            for (JoinClause join : stmt.joins) {
                results = executeJoin(results, schema, join, txnId);
                // Merge schemas for subsequent operations
                Schema joinSchema = catalog.getSchema(join.tableName);
                if (joinSchema != null) {
                    schema = mergeSchemas(schema, joinSchema);
                }
            }
        }

        // Step 4: Apply GROUP BY and aggregates
        if (!stmt.groupBy.isEmpty() || !stmt.aggregates.isEmpty()) {
            results = executeGroupBy(results, schema, stmt);
        }

        // Step 5: Apply ORDER BY
        if (!stmt.orderBy.isEmpty()) {
            results = executeOrderBy(results, schema, stmt.orderBy);
        }

        // Step 6: Apply DISTINCT
        if (stmt.distinct) {
            results = applyDistinct(results);
        }

        // Step 7: Apply LIMIT
        if (stmt.limit > 0 && results.size() > stmt.limit) {
            results = results.subList(0, stmt.limit);
        }

        // Step 8: Project columns
        List<String> projCols = stmt.columns;

        // Release locks for read-only query in auto-commit mode
        if (autoCommit && currentTxn == null && !mvccManager.isEnabled()) {
            lockManager.releaseAll(txnId);
        }

        // Format output
        return formatResults(results, projCols, schema);
    }

    /**
     * Execute a table scan (sequential scan of all pages).
     */
    private List<Tuple> executeTableScan(String tableName, Schema schema, long txnId) {
        HeapFile hf = heapFiles.get(tableName.toLowerCase());
        if (hf == null) return new ArrayList<>();

        // Acquire shared lock on table (2PL)
        if (!mvccManager.isEnabled()) {
            lockManager.acquireShared(txnId, LockManager.tableResource(tableName));
        }

        List<Tuple> results = hf.scan();
        
        // Filter out deleted tuples
        results.removeIf(Tuple::isDeleted);
        
        // Set table name for all tuples
        for (Tuple t : results) {
            if (t.getTableName() == null) t.setTableName(tableName);
        }
        
        return results;
    }

    /**
     * Execute an index scan using the B+ tree.
     */
    private List<Tuple> executeIndexScan(SelectStatement stmt, Schema schema, 
                                          QueryPlan plan, long txnId) {
        BPlusTree tree = indexes.get(plan.indexName);
        if (tree == null) {
            return executeTableScan(stmt.tables.get(0), schema, txnId);
        }

        String tableName = stmt.tables.get(0);
        HeapFile hf = heapFiles.get(tableName.toLowerCase());
        if (hf == null) return new ArrayList<>();

        // Get the search key from WHERE clause
        Object searchKey = getWhereValue(stmt.where);
        String operator = getWhereOperator(stmt.where);

        List<Tuple> results = new ArrayList<>();

        if ("=".equals(operator) && searchKey != null) {
            // Point lookup
            Tuple.RecordId rid = tree.search((Comparable) searchKey);
            if (rid != null) {
                Tuple t = hf.getTuple(rid);
                if (t != null && !t.isDeleted()) {
                    t.setTableName(tableName);
                    results.add(t);
                }
            }
        } else {
            // Fall back to table scan for non-equality predicates
            return executeTableScan(tableName, schema, txnId);
        }

        return results;
    }

    /**
     * Execute a nested-loop join between results and another table.
     */
    private List<Tuple> executeJoin(List<Tuple> leftTuples, Schema leftSchema,
                                     JoinClause join, long txnId) {
        Schema rightSchema = catalog.getSchema(join.tableName);
        if (rightSchema == null) {
            throw new RuntimeException("Table '" + join.tableName + "' does not exist.");
        }

        List<Tuple> rightTuples = executeTableScan(join.tableName, rightSchema, txnId);
        List<Tuple> results = new ArrayList<>();

        // Resolve join column indexes
        int leftColIdx = resolveColumnIndex(join.leftColumn, leftSchema, null);
        int rightColIdx = resolveColumnIndex(join.rightColumn, rightSchema, join.tableName);

        // Nested Loop Join
        for (Tuple left : leftTuples) {
            for (Tuple right : rightTuples) {
                Object leftVal = left.getField(leftColIdx);
                Object rightVal = right.getField(rightColIdx);

                if (Tuple.compareValues(leftVal, rightVal) == 0) {
                    // Create combined tuple
                    Object[] combined = new Object[left.getFieldCount() + right.getFieldCount()];
                    System.arraycopy(left.getFields(), 0, combined, 0, left.getFieldCount());
                    System.arraycopy(right.getFields(), 0, combined, left.getFieldCount(), right.getFieldCount());
                    Tuple joinedTuple = new Tuple(combined);
                    results.add(joinedTuple);
                }
            }
        }

        return results;
    }

    // ======================== FILTER ========================

    /**
     * Filter tuples based on WHERE clause conditions.
     */
    private List<Tuple> filterResults(List<Tuple> tuples, WhereClause where,
                                       Schema schema, SelectStatement stmt) {
        List<Tuple> results = new ArrayList<>();
        Schema mergedSchema = schema;
        
        // If there are joins, create merged schema
        if (stmt != null && !stmt.joins.isEmpty()) {
            for (JoinClause join : stmt.joins) {
                Schema joinSchema = catalog.getSchema(join.tableName);
                if (joinSchema != null) {
                    mergedSchema = mergeSchemas(mergedSchema, joinSchema);
                }
            }
        }

        for (Tuple tuple : tuples) {
            if (evaluateWhere(tuple, where, mergedSchema)) {
                results.add(tuple);
            }
        }
        return results;
    }

    /**
     * Evaluate a WHERE clause against a single tuple.
     */
    private boolean evaluateWhere(Tuple tuple, WhereClause where, Schema schema) {
        // Compound condition
        if (where.logicalOp != null) {
            boolean leftResult = evaluateWhere(tuple, where.left, schema);
            boolean rightResult = evaluateWhere(tuple, where.right, schema);
            return where.logicalOp.equals("AND") ? (leftResult && rightResult) : (leftResult || rightResult);
        }

        // Simple condition
        int colIdx = resolveColumnIndex(where.leftColumn, schema, null);
        if (colIdx < 0 || colIdx >= tuple.getFieldCount()) return false;

        Object tupleVal = tuple.getField(colIdx);
        Object compareVal;

        // Column-to-column comparison (for join conditions in WHERE)
        if (where.rightColumn != null) {
            int rightIdx = resolveColumnIndex(where.rightColumn, schema, null);
            if (rightIdx < 0 || rightIdx >= tuple.getFieldCount()) return false;
            compareVal = tuple.getField(rightIdx);
        } else {
            compareVal = where.rightValue;
        }

        int cmp = Tuple.compareValues(tupleVal, compareVal);

        switch (where.operator) {
            case "=": return cmp == 0;
            case "!=": case "<>": return cmp != 0;
            case "<": return cmp < 0;
            case ">": return cmp > 0;
            case "<=": return cmp <= 0;
            case ">=": return cmp >= 0;
            default: return false;
        }
    }

    // ======================== DELETE ========================

    private String executeDelete(DeleteStatement stmt) {
        Schema schema = catalog.getSchema(stmt.tableName);
        if (schema == null) return "ERROR: Table '" + stmt.tableName + "' does not exist.";

        HeapFile hf = heapFiles.get(stmt.tableName.toLowerCase());
        if (hf == null) return "ERROR: Heap file not found.";

        long txnId = getActiveTxnId();

        // Scan for tuples matching WHERE
        List<Tuple> allTuples = hf.scan();
        int deleted = 0;

        for (Tuple tuple : allTuples) {
            if (tuple.isDeleted()) continue;
            if (stmt.where == null || evaluateWhere(tuple, stmt.where, schema)) {
                // Acquire exclusive lock (2PL)
                if (!mvccManager.isEnabled()) {
                    int pkIdx = schema.getColumnIndex(schema.getPrimaryKey());
                    if (pkIdx >= 0) {
                        lockManager.acquireExclusive(txnId,
                                LockManager.rowResource(stmt.tableName, tuple.getField(pkIdx)));
                    }
                }

                // Log deletion
                Tuple.RecordId rid = tuple.getRecordId();
                if (rid != null) {
                    walManager.logDelete(txnId, stmt.tableName, tuple.getFields(),
                            rid.getPageId(), rid.getSlotId());

                    // Record undo for rollback
                    if (currentTxn != null) {
                        int pkIdx = schema.getColumnIndex(schema.getPrimaryKey());
                        Object pkVal = pkIdx >= 0 ? tuple.getField(pkIdx) : null;
                        currentTxn.addUndoRecord(new UndoRecord(
                                UndoRecord.UndoType.DELETE, stmt.tableName, tuple.getFields(),
                                rid.getPageId(), rid.getSlotId(), pkVal));
                    }

                    // Delete from heap file
                    hf.deleteTuple(rid);

                    // Delete from index
                    int pkIdx = schema.getColumnIndex(schema.getPrimaryKey());
                    if (pkIdx >= 0) {
                        String idxName = catalog.getIndexForColumn(stmt.tableName, schema.getPrimaryKey());
                        if (idxName != null && indexes.containsKey(idxName)) {
                            indexes.get(idxName).delete((Comparable) tuple.getField(pkIdx));
                        }
                    }

                    // MVCC: Mark version as deleted
                    if (mvccManager.isEnabled() && pkIdx >= 0) {
                        mvccManager.deleteVersion(stmt.tableName, tuple.getField(pkIdx), txnId);
                    }

                    deleted++;
                }
            }
        }

        // Update statistics
        TableStats stats = catalog.getStats(stmt.tableName);
        if (stats != null) {
            for (int i = 0; i < deleted; i++) stats.decrementRowCount();
        }

        // Auto-commit
        if (autoCommit && currentTxn == null) {
            walManager.logCommit(txnId);
            txnManager.commit(txnId);
        }

        return deleted + " row(s) deleted.";
    }

    // ======================== INDEX ========================

    private String executeCreateIndex(CreateIndexStatement stmt) {
        Schema schema = catalog.getSchema(stmt.tableName);
        if (schema == null) return "ERROR: Table '" + stmt.tableName + "' does not exist.";

        if (schema.getColumnIndex(stmt.columnName) < 0) {
            return "ERROR: Column '" + stmt.columnName + "' does not exist in table '" + stmt.tableName + "'.";
        }

        // Create B+ tree
        BPlusTree tree = new BPlusTree(stmt.indexName, stmt.tableName, stmt.columnName);
        
        // Build index from existing data
        HeapFile hf = heapFiles.get(stmt.tableName.toLowerCase());
        if (hf != null) {
            int colIdx = schema.getColumnIndex(stmt.columnName);
            List<Tuple> tuples = hf.scan();
            for (Tuple t : tuples) {
                if (!t.isDeleted() && t.getRecordId() != null) {
                    Object key = t.getField(colIdx);
                    if (key instanceof Comparable) {
                        tree.insert((Comparable) key, t.getRecordId());
                    }
                }
            }
        }

        indexes.put(stmt.indexName.toLowerCase(), tree);
        catalog.createIndex(stmt.indexName, stmt.tableName, stmt.columnName);
        catalog.save();

        // Save index to disk
        tree.save(catalog.getDataDir() + File.separator + "index_" + stmt.indexName.toLowerCase() + ".dat");

        return "Index '" + stmt.indexName + "' created on " + stmt.tableName + "(" + stmt.columnName + "). " + tree;
    }

    // ======================== TRANSACTIONS ========================

    private String executeBegin() {
        if (currentTxn != null) {
            return "ERROR: Transaction already in progress (TXN " + currentTxn.getTxnId() + ")";
        }
        currentTxn = txnManager.begin();
        autoCommit = false;
        walManager.logBegin(currentTxn.getTxnId());

        // MVCC: Create snapshot
        if (mvccManager.isEnabled()) {
            mvccManager.createSnapshot(currentTxn.getTxnId());
        }

        return "Transaction " + currentTxn.getTxnId() + " started.";
    }

    private String executeCommit() {
        if (currentTxn == null) {
            return "ERROR: No active transaction.";
        }
        long txnId = currentTxn.getTxnId();
        walManager.logCommit(txnId);
        txnManager.commit(txnId);

        // MVCC: Commit versions
        if (mvccManager.isEnabled()) {
            mvccManager.onCommit(txnId);
        }

        // Flush all dirty pages
        bufferPool.flushAllPages();

        // Save indexes
        for (Map.Entry<String, BPlusTree> entry : indexes.entrySet()) {
            entry.getValue().save(catalog.getDataDir() + File.separator +
                    "index_" + entry.getKey() + ".dat");
        }
        catalog.save();

        currentTxn = null;
        autoCommit = true;
        return "Transaction " + txnId + " committed.";
    }

    private String executeRollback() {
        if (currentTxn == null) {
            return "ERROR: No active transaction.";
        }
        long txnId = currentTxn.getTxnId();

        // Get undo records and roll back changes
        List<UndoRecord> undoRecords = txnManager.abort(txnId);

        for (UndoRecord undo : undoRecords) {
            HeapFile hf = heapFiles.get(undo.tableName.toLowerCase());
            Schema schema = catalog.getSchema(undo.tableName);
            if (hf == null || schema == null) continue;

            if (undo.type == UndoRecord.UndoType.INSERT) {
                // Undo INSERT = DELETE the inserted tuple
                Tuple.RecordId rid = new Tuple.RecordId(undo.pageId, undo.slotId);
                hf.deleteTuple(rid);

                // Remove from index
                if (undo.primaryKeyValue != null) {
                    String idxName = catalog.getIndexForColumn(undo.tableName, schema.getPrimaryKey());
                    if (idxName != null && indexes.containsKey(idxName)) {
                        indexes.get(idxName).delete((Comparable) undo.primaryKeyValue);
                    }
                }

                TableStats stats = catalog.getStats(undo.tableName);
                if (stats != null) stats.decrementRowCount();

            } else if (undo.type == UndoRecord.UndoType.DELETE) {
                // Undo DELETE = re-INSERT the deleted tuple
                Tuple tuple = new Tuple(undo.tupleData, undo.tableName);
                Tuple.RecordId rid = hf.insertTuple(tuple);

                // Re-add to index
                if (undo.primaryKeyValue != null) {
                    String idxName = catalog.getIndexForColumn(undo.tableName, schema.getPrimaryKey());
                    if (idxName != null && indexes.containsKey(idxName)) {
                        indexes.get(idxName).insert((Comparable) undo.primaryKeyValue, rid);
                    }
                }

                TableStats stats = catalog.getStats(undo.tableName);
                if (stats != null) stats.incrementRowCount();
            }
        }

        walManager.logAbort(txnId);

        // MVCC: Abort versions
        if (mvccManager.isEnabled()) {
            mvccManager.onAbort(txnId);
        }

        currentTxn = null;
        autoCommit = true;
        return "Transaction " + txnId + " rolled back. " + undoRecords.size() + " operation(s) undone.";
    }

    // ======================== SHOW / SET ========================

    private String executeShow(ShowStatement stmt) {
        StringBuilder sb = new StringBuilder();
        switch (stmt.what) {
            case "TABLES":
                sb.append("Tables:\n");
                for (Schema s : catalog.getAllSchemas()) {
                    TableStats stats = catalog.getStats(s.getTableName());
                    int rows = stats != null ? stats.getRowCount() : 0;
                    sb.append(String.format("  %-20s %d row(s)  %s\n",
                            s.getTableName(), rows, s.getColumns().stream()
                                    .map(c -> c.getName() + ":" + c.getType())
                                    .collect(Collectors.joining(", ", "(", ")"))));
                }
                if (catalog.getAllSchemas().isEmpty()) sb.append("  (no tables)\n");
                break;

            case "INDEX": case "INDEXES": case "INDICES":
                sb.append("Indexes:\n");
                for (Map.Entry<String, BPlusTree> entry : indexes.entrySet()) {
                    sb.append("  ").append(entry.getValue()).append("\n");
                }
                if (indexes.isEmpty()) sb.append("  (no indexes)\n");
                break;

            case "STATS":
                sb.append("Buffer Pool: ").append(bufferPool).append("\n");
                sb.append("Disk Manager: ").append(diskManager).append("\n");
                sb.append("Transactions: ").append(txnManager).append("\n");
                sb.append("Locks: ").append(lockManager).append("\n");
                sb.append("WAL: LSN=").append(walManager.getNextLsn()).append("\n");
                sb.append("MVCC: ").append(mvccManager).append("\n");
                break;

            case "WAL": case "LOG":
                walManager.printLog();
                sb.append("(WAL printed to console)");
                break;

            default:
                sb.append("Unknown SHOW target: ").append(stmt.what);
        }
        return sb.toString();
    }

    private String executeSet(SetStatement stmt) {
        switch (stmt.variable.toLowerCase()) {
            case "mvcc":
                boolean enable = stmt.value.equalsIgnoreCase("on") || stmt.value.equals("1")
                        || stmt.value.equalsIgnoreCase("true");
                mvccManager.setEnabled(enable);
                return "MVCC " + (enable ? "enabled" : "disabled") +
                       ". Concurrency mode: " + (enable ? "MVCC (Snapshot Isolation)" : "2PL (Serializable)");
            case "autocommit":
                autoCommit = stmt.value.equalsIgnoreCase("on") || stmt.value.equals("1");
                return "Auto-commit " + (autoCommit ? "enabled" : "disabled");
            default:
                return "Unknown variable: " + stmt.variable;
        }
    }

    // ======================== GROUP BY / ORDER BY ========================

    private List<Tuple> executeGroupBy(List<Tuple> tuples, Schema schema, SelectStatement stmt) {
        if (tuples.isEmpty()) return tuples;

        // Group tuples by GROUP BY columns
        Map<String, List<Tuple>> groups = new LinkedHashMap<>();
        
        if (stmt.groupBy.isEmpty()) {
            // No GROUP BY: treat all rows as one group
            groups.put("__all__", tuples);
        } else {
            for (Tuple t : tuples) {
                StringBuilder keyBuilder = new StringBuilder();
                for (String col : stmt.groupBy) {
                    int idx = resolveColumnIndex(col, schema, null);
                    if (idx >= 0 && idx < t.getFieldCount()) {
                        keyBuilder.append(t.getField(idx)).append("|");
                    }
                }
                groups.computeIfAbsent(keyBuilder.toString(), k -> new ArrayList<>()).add(t);
            }
        }

        // Compute aggregates for each group
        List<Tuple> results = new ArrayList<>();
        for (Map.Entry<String, List<Tuple>> entry : groups.entrySet()) {
            List<Tuple> groupTuples = entry.getValue();
            Tuple firstTuple = groupTuples.get(0);
            
            List<Object> resultFields = new ArrayList<>();
            
            // Add GROUP BY column values
            for (String col : stmt.groupBy) {
                int idx = resolveColumnIndex(col, schema, null);
                if (idx >= 0 && idx < firstTuple.getFieldCount()) {
                    resultFields.add(firstTuple.getField(idx));
                }
            }

            // Compute aggregate values
            for (AggregateExpr agg : stmt.aggregates) {
                Object result = computeAggregate(agg, groupTuples, schema);
                resultFields.add(result);
            }

            results.add(new Tuple(resultFields.toArray()));
        }

        return results;
    }

    private Object computeAggregate(AggregateExpr agg, List<Tuple> tuples, Schema schema) {
        if (tuples.isEmpty()) return null;

        if (agg.function.equals("COUNT")) {
            return tuples.size();
        }

        int colIdx = agg.column.equals("*") ? 0 : resolveColumnIndex(agg.column, schema, null);
        
        switch (agg.function) {
            case "SUM": {
                double sum = 0;
                for (Tuple t : tuples) {
                    Object val = t.getField(colIdx);
                    if (val instanceof Number) sum += ((Number) val).doubleValue();
                }
                return sum;
            }
            case "AVG": {
                double sum = 0;
                int count = 0;
                for (Tuple t : tuples) {
                    Object val = t.getField(colIdx);
                    if (val instanceof Number) { sum += ((Number) val).doubleValue(); count++; }
                }
                return count > 0 ? sum / count : 0.0;
            }
            case "MIN": {
                Object min = null;
                for (Tuple t : tuples) {
                    Object val = t.getField(colIdx);
                    if (min == null || Tuple.compareValues(val, min) < 0) min = val;
                }
                return min;
            }
            case "MAX": {
                Object max = null;
                for (Tuple t : tuples) {
                    Object val = t.getField(colIdx);
                    if (max == null || Tuple.compareValues(val, max) > 0) max = val;
                }
                return max;
            }
            default: return null;
        }
    }

    private List<Tuple> executeOrderBy(List<Tuple> tuples, Schema schema, List<OrderByClause> orderBy) {
        tuples.sort((a, b) -> {
            for (OrderByClause obc : orderBy) {
                int idx = resolveColumnIndex(obc.column, schema, null);
                if (idx < 0) continue;
                Object aVal = idx < a.getFieldCount() ? a.getField(idx) : null;
                Object bVal = idx < b.getFieldCount() ? b.getField(idx) : null;
                int cmp = Tuple.compareValues(aVal, bVal);
                if (cmp != 0) return obc.ascending ? cmp : -cmp;
            }
            return 0;
        });
        return tuples;
    }

    private List<Tuple> applyDistinct(List<Tuple> tuples) {
        Set<String> seen = new LinkedHashSet<>();
        List<Tuple> results = new ArrayList<>();
        for (Tuple t : tuples) {
            String key = Arrays.toString(t.getFields());
            if (seen.add(key)) {
                results.add(t);
            }
        }
        return results;
    }

    // ======================== HELPERS ========================

    private long getActiveTxnId() {
        if (currentTxn != null) return currentTxn.getTxnId();
        // Auto-transaction for single statements
        Transaction autoTxn = txnManager.begin();
        walManager.logBegin(autoTxn.getTxnId());
        return autoTxn.getTxnId();
    }

    private Object convertValue(Object value, DataType type) {
        if (value == null) return null;
        switch (type) {
            case INT:
                if (value instanceof Integer) return value;
                return Integer.parseInt(value.toString());
            case FLOAT:
                if (value instanceof Double) return value;
                return Double.parseDouble(value.toString());
            case VARCHAR:
                return value.toString();
            default:
                return value;
        }
    }

    private int resolveColumnIndex(String columnRef, Schema schema, String defaultTable) {
        if (columnRef == null) return -1;
        
        String colName = columnRef;
        // Handle table.column references
        if (columnRef.contains(".")) {
            colName = columnRef.substring(columnRef.indexOf('.') + 1);
        }
        
        int idx = schema.getColumnIndex(colName);
        return idx;
    }

    private Schema mergeSchemas(Schema left, Schema right) {
        List<Column> merged = new ArrayList<>(left.getColumns());
        merged.addAll(right.getColumns());
        Schema result = new Schema("__joined__", merged, -1);
        return result;
    }

    private Object getWhereValue(WhereClause where) {
        if (where == null) return null;
        if (where.logicalOp != null) return getWhereValue(where.left);
        return where.rightValue;
    }

    private String getWhereOperator(WhereClause where) {
        if (where == null) return null;
        if (where.logicalOp != null) return getWhereOperator(where.left);
        return where.operator;
    }

    /**
     * Format query results as a table.
     */
    private String formatResults(List<Tuple> tuples, List<String> columns, Schema schema) {
        if (tuples.isEmpty()) {
            return "(0 rows)";
        }

        // Determine which columns to display
        List<Integer> colIndexes = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        if (columns.contains("*")) {
            for (int i = 0; i < schema.getColumnCount(); i++) {
                colIndexes.add(i);
                headers.add(schema.getColumns().get(i).getName());
            }
        } else {
            for (String col : columns) {
                int idx = resolveColumnIndex(col, schema, null);
                if (idx >= 0) {
                    colIndexes.add(idx);
                    headers.add(col.contains(".") ? col.substring(col.indexOf('.') + 1) : col);
                } else {
                    // Might be an aggregate alias
                    colIndexes.add(-1);
                    headers.add(col);
                }
            }
        }

        // Calculate column widths
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            widths[i] = headers.get(i).length();
        }
        for (Tuple t : tuples) {
            for (int i = 0; i < headers.size(); i++) {
                int idx = i < colIndexes.size() ? colIndexes.get(i) : i;
                if (idx < 0) idx = i; // aggregate result
                String val = idx < t.getFieldCount() ? String.valueOf(t.getField(idx)) : "NULL";
                widths[i] = Math.max(widths[i], val.length());
            }
        }

        // Build output
        StringBuilder sb = new StringBuilder();

        // Header
        StringBuilder separator = new StringBuilder("+");
        StringBuilder header = new StringBuilder("|");
        for (int i = 0; i < headers.size(); i++) {
            separator.append("-".repeat(widths[i] + 2)).append("+");
            header.append(String.format(" %-" + widths[i] + "s |", headers.get(i)));
        }
        sb.append(separator).append("\n");
        sb.append(header).append("\n");
        sb.append(separator).append("\n");

        // Data rows
        for (Tuple t : tuples) {
            StringBuilder row = new StringBuilder("|");
            for (int i = 0; i < headers.size(); i++) {
                int idx = i < colIndexes.size() ? colIndexes.get(i) : i;
                if (idx < 0) idx = i;
                String val = idx < t.getFieldCount() ? String.valueOf(t.getField(idx)) : "NULL";
                row.append(String.format(" %-" + widths[i] + "s |", val));
            }
            sb.append(row).append("\n");
        }

        sb.append(separator).append("\n");
        sb.append("(" + tuples.size() + " row(s))");

        return sb.toString();
    }

    // ======================== CRASH RECOVERY ========================

    /**
     * Perform crash recovery using the WAL.
     * Called at system startup to restore consistent state.
     */
    public String performRecovery() {
        WALManager.RecoveryResult result = walManager.recover();

        if (result.redoRecords.isEmpty() && result.undoRecords.isEmpty()) {
            return "No recovery needed.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== PERFORMING RECOVERY ===\n");

        // Redo committed transactions
        int redone = 0;
        for (WALManager.LogRecord rec : result.redoRecords) {
            Schema schema = catalog.getSchema(rec.getTableName());
            HeapFile hf = schema != null ? heapFiles.get(rec.getTableName().toLowerCase()) : null;
            if (hf == null) continue;

            if (rec.getType() == WALManager.LogType.INSERT && rec.getTupleData() != null) {
                Tuple tuple = new Tuple(rec.getTupleData(), rec.getTableName());
                hf.insertTuple(tuple);
                redone++;
            } else if (rec.getType() == WALManager.LogType.DELETE) {
                Tuple.RecordId rid = new Tuple.RecordId(rec.getPageId(), rec.getSlotId());
                hf.deleteTuple(rid);
                redone++;
            }
        }
        sb.append("Redo: ").append(redone).append(" operation(s) replayed.\n");

        // Undo uncommitted transactions
        int undone = 0;
        for (WALManager.LogRecord rec : result.undoRecords) {
            Schema schema = catalog.getSchema(rec.getTableName());
            HeapFile hf = schema != null ? heapFiles.get(rec.getTableName().toLowerCase()) : null;
            if (hf == null) continue;

            if (rec.getType() == WALManager.LogType.INSERT) {
                // Undo insert = delete
                Tuple.RecordId rid = new Tuple.RecordId(rec.getPageId(), rec.getSlotId());
                hf.deleteTuple(rid);
                undone++;
            } else if (rec.getType() == WALManager.LogType.DELETE && rec.getTupleData() != null) {
                // Undo delete = re-insert
                Tuple tuple = new Tuple(rec.getTupleData(), rec.getTableName());
                hf.insertTuple(tuple);
                undone++;
            }
        }
        sb.append("Undo: ").append(undone).append(" operation(s) rolled back.\n");

        // Flush recovered state
        bufferPool.flushAllPages();
        sb.append("Recovery complete. System is consistent.\n");

        return sb.toString();
    }

    // ======================== ACCESSORS ========================

    public Map<String, BPlusTree> getIndexes() { return indexes; }
    public Map<String, HeapFile> getHeapFiles() { return heapFiles; }
    public Transaction getCurrentTransaction() { return currentTxn; }
    public boolean isAutoCommit() { return autoCommit; }
    public Catalog getCatalog() { return catalog; }
    public BufferPool getBufferPool() { return bufferPool; }
    public DiskManager getDiskManager() { return diskManager; }
    public TransactionManager getTxnManager() { return txnManager; }
    public LockManager getLockManager() { return lockManager; }
    public WALManager getWalManager() { return walManager; }
    public MVCCManager getMvccManager() { return mvccManager; }
}
