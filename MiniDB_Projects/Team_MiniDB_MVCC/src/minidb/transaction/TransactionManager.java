package minidb.transaction;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TransactionManager - Manages transaction lifecycle for MiniDB.
 * 
 * Coordinates BEGIN, COMMIT, and ROLLBACK operations.
 * Works with LockManager (2PL) for concurrency control
 * and WALManager for durability/recovery.
 * 
 * Transaction States:
 *   ACTIVE → COMMITTED (success path)
 *   ACTIVE → ABORTED   (rollback or deadlock)
 * 
 * Design Decision: Each transaction gets a monotonically increasing ID.
 * This simplifies MVCC visibility checks (higher ID = newer transaction).
 */
public class TransactionManager {

    // ======================== TRANSACTION CLASS ========================

    public enum TxnStatus {
        ACTIVE,
        COMMITTED,
        ABORTED
    }

    /**
     * Represents an active or completed transaction.
     */
    public static class Transaction {
        private long txnId;
        private TxnStatus status;
        private long startTimestamp;
        private long commitTimestamp;
        private List<UndoRecord> undoLog; // For rollback

        public Transaction(long txnId) {
            this.txnId = txnId;
            this.status = TxnStatus.ACTIVE;
            this.startTimestamp = System.nanoTime();
            this.commitTimestamp = 0;
            this.undoLog = new ArrayList<>();
        }

        public long getTxnId() { return txnId; }
        public TxnStatus getStatus() { return status; }
        public long getStartTimestamp() { return startTimestamp; }
        public long getCommitTimestamp() { return commitTimestamp; }
        public List<UndoRecord> getUndoLog() { return undoLog; }

        public void setStatus(TxnStatus status) { this.status = status; }
        public void setCommitTimestamp(long ts) { this.commitTimestamp = ts; }

        public void addUndoRecord(UndoRecord record) {
            undoLog.add(record);
        }

        @Override
        public String toString() {
            return String.format("Transaction[id=%d, status=%s]", txnId, status);
        }
    }

    /**
     * Undo record for transaction rollback.
     * Stores the inverse operation needed to undo a change.
     */
    public static class UndoRecord {
        public enum UndoType { INSERT, DELETE }
        
        public UndoType type;
        public String tableName;
        public Object[] tupleData;   // The tuple data
        public int pageId;
        public int slotId;
        public Object primaryKeyValue; // For index cleanup

        public UndoRecord(UndoType type, String tableName, Object[] tupleData,
                          int pageId, int slotId, Object primaryKeyValue) {
            this.type = type;
            this.tableName = tableName;
            this.tupleData = tupleData;
            this.pageId = pageId;
            this.slotId = slotId;
            this.primaryKeyValue = primaryKeyValue;
        }
    }

    // ======================== MANAGER STATE ========================

    private final AtomicLong nextTxnId;
    private final Map<Long, Transaction> activeTransactions;
    private final Map<Long, Transaction> completedTransactions;
    private final LockManager lockManager;
    
    // Commit timestamp counter (used by MVCC)
    private final AtomicLong globalTimestamp;

    public TransactionManager(LockManager lockManager) {
        this.nextTxnId = new AtomicLong(1);
        this.activeTransactions = new LinkedHashMap<>();
        this.completedTransactions = new LinkedHashMap<>();
        this.lockManager = lockManager;
        this.globalTimestamp = new AtomicLong(1);
    }

    // ======================== TRANSACTION LIFECYCLE ========================

    /**
     * Begin a new transaction.
     * 
     * @return The new Transaction object
     */
    public synchronized Transaction begin() {
        long txnId = nextTxnId.getAndIncrement();
        Transaction txn = new Transaction(txnId);
        activeTransactions.put(txnId, txn);
        return txn;
    }

    /**
     * Commit a transaction.
     * Releases all locks (shrinking phase of 2PL).
     * 
     * @param txnId The transaction ID to commit
     */
    public synchronized void commit(long txnId) {
        Transaction txn = activeTransactions.get(txnId);
        if (txn == null) {
            throw new RuntimeException("Transaction " + txnId + " not found or already completed");
        }

        txn.setStatus(TxnStatus.COMMITTED);
        txn.setCommitTimestamp(globalTimestamp.getAndIncrement());
        
        // Release all locks (strict 2PL shrinking phase)
        lockManager.releaseAll(txnId);
        
        // Move from active to completed
        activeTransactions.remove(txnId);
        completedTransactions.put(txnId, txn);
        
        // Keep only recent completed transactions to avoid memory leak
        if (completedTransactions.size() > 1000) {
            Iterator<Long> it = completedTransactions.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
    }

    /**
     * Abort (rollback) a transaction.
     * The actual undo of changes is handled by the caller (QueryExecutor)
     * using the undo log records.
     * 
     * @param txnId The transaction ID to abort
     * @return List of UndoRecords for the caller to process
     */
    public synchronized List<UndoRecord> abort(long txnId) {
        Transaction txn = activeTransactions.get(txnId);
        if (txn == null) {
            // Already completed or doesn't exist
            return Collections.emptyList();
        }

        txn.setStatus(TxnStatus.ABORTED);
        
        // Get undo records in reverse order (LIFO)
        List<UndoRecord> undoRecords = new ArrayList<>(txn.getUndoLog());
        Collections.reverse(undoRecords);
        
        // Release all locks
        lockManager.releaseAll(txnId);
        
        // Move from active to completed
        activeTransactions.remove(txnId);
        completedTransactions.put(txnId, txn);
        
        return undoRecords;
    }

    // ======================== QUERY METHODS ========================

    /**
     * Get a transaction by ID.
     */
    public Transaction getTransaction(long txnId) {
        Transaction txn = activeTransactions.get(txnId);
        if (txn != null) return txn;
        return completedTransactions.get(txnId);
    }

    /**
     * Check if a transaction is active.
     */
    public boolean isActive(long txnId) {
        Transaction txn = activeTransactions.get(txnId);
        return txn != null && txn.getStatus() == TxnStatus.ACTIVE;
    }

    /**
     * Check if a transaction is committed.
     */
    public boolean isCommitted(long txnId) {
        Transaction txn = completedTransactions.get(txnId);
        return txn != null && txn.getStatus() == TxnStatus.COMMITTED;
    }

    /**
     * Get all active transaction IDs.
     */
    public Set<Long> getActiveTransactionIds() {
        return new HashSet<>(activeTransactions.keySet());
    }

    /**
     * Get the current global timestamp.
     */
    public long getCurrentTimestamp() {
        return globalTimestamp.get();
    }

    /**
     * Get the lock manager.
     */
    public LockManager getLockManager() {
        return lockManager;
    }

    /**
     * Abort all active transactions (used during shutdown/recovery).
     */
    public void abortAll() {
        List<Long> txnIds = new ArrayList<>(activeTransactions.keySet());
        for (long txnId : txnIds) {
            try {
                abort(txnId);
            } catch (Exception e) {
                // Best effort during shutdown
            }
        }
    }

    @Override
    public String toString() {
        return String.format("TransactionManager[active=%d, completed=%d, nextId=%d]",
                activeTransactions.size(), completedTransactions.size(), nextTxnId.get());
    }
}
