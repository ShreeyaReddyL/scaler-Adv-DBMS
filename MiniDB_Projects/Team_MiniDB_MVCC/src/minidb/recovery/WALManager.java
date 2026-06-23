package minidb.recovery;

import minidb.storage.Tuple;
import java.io.*;
import java.util.*;

/**
 * WALManager - Write-Ahead Logging for crash recovery.
 * 
 * Implements the WAL protocol: log records are forced to disk BEFORE
 * the corresponding data page changes are written. This ensures that
 * committed transactions can be recovered after a crash.
 * 
 * ARIES-style Recovery Process:
 * 1. Analysis: Scan log to determine active transactions at crash
 * 2. Redo: Replay all logged operations to restore state
 * 3. Undo: Roll back uncommitted transactions
 * 
 * Log Record Types:
 * - BEGIN: Transaction started
 * - INSERT: Tuple inserted (stores new tuple for redo)
 * - DELETE: Tuple deleted (stores old tuple for undo)
 * - COMMIT: Transaction committed (durability guarantee)
 * - ABORT: Transaction aborted
 * - CHECKPOINT: Consistent snapshot marker
 * 
 * Design Decision: Sequential append-only log file for simplicity.
 * Each record is self-describing with type, txn ID, and data.
 */
public class WALManager {

    // ======================== LOG RECORD ========================

    public enum LogType {
        BEGIN, INSERT, DELETE, COMMIT, ABORT, CHECKPOINT, UPDATE
    }

    /**
     * A single log record in the WAL.
     */
    public static class LogRecord implements Serializable {
        private static final long serialVersionUID = 1L;

        private long lsn;         // Log Sequence Number
        private LogType type;
        private long txnId;
        private String tableName;
        
        // For INSERT/DELETE/UPDATE
        private Object[] tupleData;    // The tuple fields
        private Object[] oldTupleData; // Previous version (for undo)
        private int pageId;
        private int slotId;
        
        // For CHECKPOINT
        private Set<Long> activeTxns;

        public LogRecord(long lsn, LogType type, long txnId) {
            this.lsn = lsn;
            this.type = type;
            this.txnId = txnId;
        }

        // Getters
        public long getLsn() { return lsn; }
        public LogType getType() { return type; }
        public long getTxnId() { return txnId; }
        public String getTableName() { return tableName; }
        public Object[] getTupleData() { return tupleData; }
        public Object[] getOldTupleData() { return oldTupleData; }
        public int getPageId() { return pageId; }
        public int getSlotId() { return slotId; }
        public Set<Long> getActiveTxns() { return activeTxns; }

        // Setters
        public void setTableName(String tableName) { this.tableName = tableName; }
        public void setTupleData(Object[] data) { this.tupleData = data; }
        public void setOldTupleData(Object[] data) { this.oldTupleData = data; }
        public void setPageId(int pageId) { this.pageId = pageId; }
        public void setSlotId(int slotId) { this.slotId = slotId; }
        public void setActiveTxns(Set<Long> txns) { this.activeTxns = txns; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("LSN=%d [%s] TXN=%d", lsn, type, txnId));
            if (tableName != null) sb.append(" table=").append(tableName);
            if (pageId >= 0 && type != LogType.BEGIN && type != LogType.COMMIT && type != LogType.ABORT) {
                sb.append(String.format(" page=%d slot=%d", pageId, slotId));
            }
            if (tupleData != null) sb.append(" data=").append(Arrays.toString(tupleData));
            return sb.toString();
        }
    }

    // ======================== WAL STATE ========================

    private String walFilePath;
    private long nextLsn;
    private List<LogRecord> logBuffer;  // In-memory buffer before flush
    private boolean enabled;

    public WALManager(String dataDir) {
        this.walFilePath = dataDir + File.separator + "wal.log";
        this.nextLsn = 1;
        this.logBuffer = new ArrayList<>();
        this.enabled = true;
        
        // Load existing LSN counter
        loadLsnCounter(dataDir);
    }

    // ======================== LOGGING OPERATIONS ========================

    /**
     * Log a BEGIN record.
     */
    public synchronized long logBegin(long txnId) {
        LogRecord rec = new LogRecord(nextLsn++, LogType.BEGIN, txnId);
        logBuffer.add(rec);
        return rec.getLsn();
    }

    /**
     * Log an INSERT record.
     * Stores the new tuple data for redo during recovery.
     */
    public synchronized long logInsert(long txnId, String tableName, 
                                        Object[] tupleData, int pageId, int slotId) {
        LogRecord rec = new LogRecord(nextLsn++, LogType.INSERT, txnId);
        rec.setTableName(tableName);
        rec.setTupleData(tupleData != null ? Arrays.copyOf(tupleData, tupleData.length) : null);
        rec.setPageId(pageId);
        rec.setSlotId(slotId);
        logBuffer.add(rec);
        return rec.getLsn();
    }

    /**
     * Log a DELETE record.
     * Stores the old tuple data for undo during recovery.
     */
    public synchronized long logDelete(long txnId, String tableName,
                                        Object[] tupleData, int pageId, int slotId) {
        LogRecord rec = new LogRecord(nextLsn++, LogType.DELETE, txnId);
        rec.setTableName(tableName);
        rec.setTupleData(tupleData != null ? Arrays.copyOf(tupleData, tupleData.length) : null);
        rec.setPageId(pageId);
        rec.setSlotId(slotId);
        logBuffer.add(rec);
        return rec.getLsn();
    }

    /**
     * Log a COMMIT record and force to disk.
     * This is the durability guarantee: once the commit record is on disk,
     * the transaction is considered committed even if the system crashes.
     */
    public synchronized long logCommit(long txnId) {
        LogRecord rec = new LogRecord(nextLsn++, LogType.COMMIT, txnId);
        logBuffer.add(rec);
        flushLog(); // Force commit record to disk
        return rec.getLsn();
    }

    /**
     * Log an ABORT record.
     */
    public synchronized long logAbort(long txnId) {
        LogRecord rec = new LogRecord(nextLsn++, LogType.ABORT, txnId);
        logBuffer.add(rec);
        flushLog();
        return rec.getLsn();
    }

    /**
     * Log a CHECKPOINT record.
     * Records the set of active transactions at this point.
     */
    public synchronized long logCheckpoint(Set<Long> activeTxns) {
        LogRecord rec = new LogRecord(nextLsn++, LogType.CHECKPOINT, 0);
        rec.setActiveTxns(new HashSet<>(activeTxns));
        logBuffer.add(rec);
        flushLog();
        return rec.getLsn();
    }

    // ======================== FLUSH ========================

    /**
     * Force all buffered log records to disk.
     * Uses append mode to add to existing log file.
     */
    public synchronized void flushLog() {
        if (!enabled || logBuffer.isEmpty()) return;
        
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(walFilePath, true))) {
            for (LogRecord rec : logBuffer) {
                oos.writeObject(rec);
            }
            oos.flush();
        } catch (IOException e) {
            // For append mode with ObjectOutputStream, we need special handling
            // Use a simpler approach: write all records to file
            writeAllRecords();
        }
        
        logBuffer.clear();
    }

    /**
     * Write all log records (from scratch) to file.
     * Fallback when append mode has issues with ObjectOutputStream.
     */
    private void writeAllRecords() {
        List<LogRecord> allRecords = readAllRecords();
        allRecords.addAll(logBuffer);
        
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(walFilePath))) {
            oos.writeInt(allRecords.size());
            for (LogRecord rec : allRecords) {
                oos.writeObject(rec);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not write WAL: " + e.getMessage());
        }
    }

    // ======================== RECOVERY ========================

    /**
     * Perform crash recovery using the WAL.
     * 
     * ARIES-style recovery in 3 phases:
     * 1. Analysis: Determine which transactions were active at crash
     * 2. Redo: Replay all logged operations
     * 3. Undo: Roll back uncommitted transactions
     * 
     * @return RecoveryResult containing redo and undo information
     */
    public RecoveryResult recover() {
        List<LogRecord> records = readAllRecords();
        if (records.isEmpty()) {
            return new RecoveryResult(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptySet(), Collections.emptySet());
        }

        System.out.println("=== WAL RECOVERY START ===");
        System.out.println("Total log records: " + records.size());

        // Phase 1: ANALYSIS
        // Determine committed and uncommitted transactions
        Set<Long> committedTxns = new HashSet<>();
        Set<Long> abortedTxns = new HashSet<>();
        Set<Long> allTxns = new HashSet<>();

        for (LogRecord rec : records) {
            if (rec.getTxnId() > 0) allTxns.add(rec.getTxnId());
            if (rec.getType() == LogType.COMMIT) committedTxns.add(rec.getTxnId());
            if (rec.getType() == LogType.ABORT) abortedTxns.add(rec.getTxnId());
        }

        Set<Long> uncommittedTxns = new HashSet<>(allTxns);
        uncommittedTxns.removeAll(committedTxns);
        uncommittedTxns.removeAll(abortedTxns);

        System.out.println("Committed transactions: " + committedTxns);
        System.out.println("Uncommitted transactions (need undo): " + uncommittedTxns);

        // Phase 2: REDO
        // Replay operations from committed transactions
        List<LogRecord> redoRecords = new ArrayList<>();
        for (LogRecord rec : records) {
            if (committedTxns.contains(rec.getTxnId()) &&
                (rec.getType() == LogType.INSERT || rec.getType() == LogType.DELETE)) {
                redoRecords.add(rec);
            }
        }
        System.out.println("Redo records: " + redoRecords.size());

        // Phase 3: UNDO
        // Collect operations from uncommitted transactions (in reverse order)
        List<LogRecord> undoRecords = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            LogRecord rec = records.get(i);
            if (uncommittedTxns.contains(rec.getTxnId()) &&
                (rec.getType() == LogType.INSERT || rec.getType() == LogType.DELETE)) {
                undoRecords.add(rec);
            }
        }
        System.out.println("Undo records: " + undoRecords.size());
        System.out.println("=== WAL RECOVERY END ===");

        return new RecoveryResult(redoRecords, undoRecords, committedTxns, uncommittedTxns);
    }

    /**
     * Read all log records from the WAL file.
     */
    private List<LogRecord> readAllRecords() {
        List<LogRecord> records = new ArrayList<>();
        File f = new File(walFilePath);
        if (!f.exists() || f.length() == 0) return records;

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            int count = ois.readInt();
            for (int i = 0; i < count; i++) {
                records.add((LogRecord) ois.readObject());
            }
        } catch (Exception e) {
            // Log may be corrupted at the end (crash during write)
            // Return what we could read
        }
        return records;
    }

    /**
     * Load the LSN counter from a previously written log.
     */
    private void loadLsnCounter(String dataDir) {
        List<LogRecord> records = readAllRecords();
        if (!records.isEmpty()) {
            nextLsn = records.get(records.size() - 1).getLsn() + 1;
        }
    }

    /**
     * Clear the WAL (after successful checkpoint + recovery).
     */
    public void clearLog() {
        logBuffer.clear();
        try {
            new FileOutputStream(walFilePath).close(); // Truncate file
        } catch (IOException e) {
            // Ignore
        }
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public long getNextLsn() { return nextLsn; }

    /**
     * Get all log records currently in the buffer (for display).
     */
    public List<LogRecord> getBufferedRecords() {
        return new ArrayList<>(logBuffer);
    }

    /**
     * Print the entire WAL for debugging.
     */
    public void printLog() {
        List<LogRecord> records = readAllRecords();
        records.addAll(logBuffer);
        
        System.out.println("=== WAL Contents (" + records.size() + " records) ===");
        for (LogRecord rec : records) {
            System.out.println("  " + rec);
        }
    }

    // ======================== RECOVERY RESULT ========================

    /**
     * Contains the results of WAL recovery analysis.
     */
    public static class RecoveryResult {
        public final List<LogRecord> redoRecords;
        public final List<LogRecord> undoRecords;
        public final Set<Long> committedTxns;
        public final Set<Long> uncommittedTxns;

        public RecoveryResult(List<LogRecord> redoRecords, List<LogRecord> undoRecords,
                              Set<Long> committedTxns, Set<Long> uncommittedTxns) {
            this.redoRecords = redoRecords;
            this.undoRecords = undoRecords;
            this.committedTxns = committedTxns;
            this.uncommittedTxns = uncommittedTxns;
        }
    }
}
