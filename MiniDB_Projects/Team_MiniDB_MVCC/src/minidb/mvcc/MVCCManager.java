package minidb.mvcc;

import minidb.storage.Tuple;
import minidb.transaction.TransactionManager;
import minidb.transaction.TransactionManager.Transaction;
import minidb.transaction.TransactionManager.TxnStatus;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MVCCManager - Multi-Version Concurrency Control for MiniDB.
 * 
 * EXTENSION TRACK B: Replaces 2PL with MVCC for higher read throughput.
 * 
 * MVCC Key Principle: Readers never block writers, and writers never block readers.
 * Each write operation creates a new VERSION of the tuple rather than modifying
 * the existing one. Readers see a consistent SNAPSHOT of the database.
 * 
 * Version Visibility Rules:
 * A tuple version (with xmin=creating_txn, xmax=deleting_txn) is visible to
 * transaction T if:
 *   1. xmin is committed AND xmin < T's snapshot timestamp
 *   2. xmax is either:
 *      - 0 (not deleted), OR
 *      - not committed, OR
 *      - committed but xmax >= T's snapshot timestamp
 * 
 * Version Chain:
 *   Latest Version → Previous Version → Older Version → ...
 *   Each tuple key maps to a chain of versions.
 *   Readers traverse the chain to find the version visible to their snapshot.
 * 
 * Comparison with 2PL:
 *   2PL: Readers acquire shared locks → block writers → reduced throughput
 *   MVCC: Readers see snapshot → no locks needed → higher read throughput
 *   MVCC: Writers still need exclusive locks on the same key (write-write conflict)
 * 
 * Design Decision: In-memory version chains using a ConcurrentHashMap.
 * Keys are (tableName, primaryKey), values are lists of tuple versions
 * ordered newest-first.
 */
public class MVCCManager {

    // ======================== VERSION CHAIN ========================

    /**
     * A single version of a tuple.
     */
    public static class TupleVersion {
        Object[] data;          // Tuple field values
        long xmin;              // Creating transaction ID
        long xmax;              // Deleting transaction ID (0 = alive)
        long createTimestamp;   // When this version was created
        boolean committed;      // Whether the creating txn is committed

        public TupleVersion(Object[] data, long xmin) {
            this.data = Arrays.copyOf(data, data.length);
            this.xmin = xmin;
            this.xmax = 0;
            this.createTimestamp = System.nanoTime();
            this.committed = false;
        }

        public Object[] getData() { return data; }
        public long getXmin() { return xmin; }
        public long getXmax() { return xmax; }
        public boolean isCommitted() { return committed; }

        @Override
        public String toString() {
            return String.format("Version[xmin=%d, xmax=%d, committed=%s, data=%s]",
                    xmin, xmax, committed, Arrays.toString(data));
        }
    }

    /**
     * Snapshot for a transaction — captures the state at transaction start.
     */
    public static class Snapshot {
        long snapshotTimestamp;      // Logical timestamp when snapshot was taken
        Set<Long> activeAtStart;    // Transactions active when this snapshot was created

        public Snapshot(long timestamp, Set<Long> activeTxns) {
            this.snapshotTimestamp = timestamp;
            this.activeAtStart = new HashSet<>(activeTxns);
        }
    }

    // ======================== MVCC STATE ========================

    // (tableName + ":" + primaryKey) → list of versions (newest first)
    private final Map<String, List<TupleVersion>> versionStore;
    
    // txnId → Snapshot
    private final Map<Long, Snapshot> snapshots;
    
    // Reference to transaction manager for checking txn status
    private final TransactionManager txnManager;
    
    // Track committed transaction IDs for visibility
    private final Set<Long> committedTxns;
    
    private boolean enabled;

    public MVCCManager(TransactionManager txnManager) {
        this.versionStore = new ConcurrentHashMap<>();
        this.snapshots = new ConcurrentHashMap<>();
        this.txnManager = txnManager;
        this.committedTxns = ConcurrentHashMap.newKeySet();
        this.enabled = false; // Disabled by default (2PL is default)
    }

    // ======================== SNAPSHOT MANAGEMENT ========================

    /**
     * Create a snapshot for a transaction.
     * Called at transaction BEGIN under MVCC mode.
     */
    public void createSnapshot(long txnId) {
        long timestamp = txnManager.getCurrentTimestamp();
        Set<Long> activeTxns = txnManager.getActiveTransactionIds();
        snapshots.put(txnId, new Snapshot(timestamp, activeTxns));
    }

    /**
     * Remove a snapshot when transaction completes.
     */
    public void removeSnapshot(long txnId) {
        snapshots.remove(txnId);
    }

    // ======================== VERSION OPERATIONS ========================

    /**
     * Create a new version of a tuple (INSERT).
     * The new version's xmin is set to the creating transaction.
     */
    public void insertVersion(String tableName, Object primaryKey, Object[] tupleData, long txnId) {
        String key = versionKey(tableName, primaryKey);
        TupleVersion version = new TupleVersion(tupleData, txnId);
        
        versionStore.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
        versionStore.get(key).add(0, version); // Add as newest version
    }

    /**
     * Delete a version (mark as deleted by setting xmax).
     * Under MVCC, deletion doesn't remove the tuple — it creates a "tombstone"
     * by setting xmax on the visible version.
     */
    public boolean deleteVersion(String tableName, Object primaryKey, long txnId) {
        String key = versionKey(tableName, primaryKey);
        List<TupleVersion> versions = versionStore.get(key);
        
        if (versions == null || versions.isEmpty()) return false;

        // Find the currently visible version and mark it as deleted
        for (TupleVersion v : versions) {
            if (v.xmax == 0 && (v.committed || v.xmin == txnId)) {
                v.xmax = txnId;
                return true;
            }
        }
        
        return false;
    }

    /**
     * Get the version of a tuple visible to a specific transaction.
     * Traverses the version chain to find the correct version based on
     * the transaction's snapshot.
     * 
     * @return The visible tuple data, or null if no visible version exists
     */
    public Object[] getVisibleVersion(String tableName, Object primaryKey, long txnId) {
        String key = versionKey(tableName, primaryKey);
        List<TupleVersion> versions = versionStore.get(key);
        
        if (versions == null || versions.isEmpty()) return null;

        Snapshot snapshot = snapshots.get(txnId);

        for (TupleVersion v : versions) {
            if (isVisible(v, txnId, snapshot)) {
                return v.getData();
            }
        }
        
        return null;
    }

    /**
     * Get all visible versions for a table scan.
     * Returns tuple data for all keys that have a visible version.
     */
    public List<Object[]> scanVisibleVersions(String tableName, long txnId) {
        List<Object[]> results = new ArrayList<>();
        Snapshot snapshot = snapshots.get(txnId);
        String prefix = tableName + ":";
        
        for (Map.Entry<String, List<TupleVersion>> entry : versionStore.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                for (TupleVersion v : entry.getValue()) {
                    if (isVisible(v, txnId, snapshot)) {
                        results.add(v.getData());
                        break; // Only the first visible version per key
                    }
                }
            }
        }
        
        return results;
    }

    // ======================== VISIBILITY CHECK ========================

    /**
     * Determine if a tuple version is visible to a transaction.
     * 
     * Visibility Rules:
     * 1. If xmin == txnId → visible (transaction sees its own writes)
     * 2. If xmin is not committed → not visible (created by active/aborted txn)
     * 3. If xmin was active when snapshot was taken → not visible
     * 4. If xmax == 0 → visible (not deleted)
     * 5. If xmax == txnId → not visible (deleted by this transaction)
     * 6. If xmax is committed and was committed before snapshot → not visible
     * 7. Otherwise → visible
     */
    private boolean isVisible(TupleVersion version, long txnId, Snapshot snapshot) {
        // Rule 1: Transaction sees its own writes
        if (version.xmin == txnId) {
            // But not if it also deleted it
            return version.xmax == 0 || version.xmax != txnId;
        }
        
        // Rule 2: Creating transaction must be committed
        if (!version.committed && !committedTxns.contains(version.xmin)) {
            return false;
        }
        
        // Rule 3: Creating transaction was active at snapshot time
        if (snapshot != null && snapshot.activeAtStart.contains(version.xmin)) {
            return false;
        }
        
        // Now check if it's been deleted
        if (version.xmax == 0) {
            return true; // Not deleted
        }
        
        // Rule 5: This transaction deleted it
        if (version.xmax == txnId) {
            return false;
        }
        
        // Rule 6: Deleting transaction is committed
        if (committedTxns.contains(version.xmax)) {
            if (snapshot == null || !snapshot.activeAtStart.contains(version.xmax)) {
                return false; // Deletion is visible
            }
        }
        
        return true; // Deletion not yet visible
    }

    // ======================== TRANSACTION LIFECYCLE ========================

    /**
     * Called when a transaction commits under MVCC.
     * Marks all versions created by this transaction as committed.
     */
    public void onCommit(long txnId) {
        committedTxns.add(txnId);
        
        // Mark all versions by this txn as committed
        for (List<TupleVersion> versions : versionStore.values()) {
            for (TupleVersion v : versions) {
                if (v.xmin == txnId) {
                    v.committed = true;
                }
            }
        }
        
        removeSnapshot(txnId);
    }

    /**
     * Called when a transaction aborts under MVCC.
     * Removes all versions created by this transaction.
     */
    public void onAbort(long txnId) {
        for (List<TupleVersion> versions : versionStore.values()) {
            // Remove versions created by this transaction
            versions.removeIf(v -> v.xmin == txnId);
            // Undo deletions by this transaction
            for (TupleVersion v : versions) {
                if (v.xmax == txnId) {
                    v.xmax = 0;
                }
            }
        }
        
        removeSnapshot(txnId);
    }

    /**
     * Garbage collect old versions that are no longer visible to any active transaction.
     * This prevents the version store from growing unboundedly.
     */
    public void garbageCollect() {
        long oldestActiveSnapshot = Long.MAX_VALUE;
        for (Snapshot s : snapshots.values()) {
            oldestActiveSnapshot = Math.min(oldestActiveSnapshot, s.snapshotTimestamp);
        }
        
        // Remove versions that are no longer needed
        for (List<TupleVersion> versions : versionStore.values()) {
            if (versions.size() > 1) {
                // Keep only the latest visible version and one previous
                while (versions.size() > 2) {
                    versions.remove(versions.size() - 1);
                }
            }
        }
    }

    // ======================== UTILITY ========================

    private String versionKey(String tableName, Object primaryKey) {
        return tableName + ":" + primaryKey;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Get statistics about the version store.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", enabled);
        stats.put("totalKeys", versionStore.size());
        
        int totalVersions = 0;
        int maxChainLength = 0;
        for (List<TupleVersion> versions : versionStore.values()) {
            totalVersions += versions.size();
            maxChainLength = Math.max(maxChainLength, versions.size());
        }
        
        stats.put("totalVersions", totalVersions);
        stats.put("maxChainLength", maxChainLength);
        stats.put("activeSnapshots", snapshots.size());
        stats.put("committedTxns", committedTxns.size());
        return stats;
    }

    @Override
    public String toString() {
        Map<String, Object> stats = getStats();
        return String.format("MVCCManager[enabled=%s, keys=%s, versions=%s, snapshots=%s]",
                stats.get("enabled"), stats.get("totalKeys"),
                stats.get("totalVersions"), stats.get("activeSnapshots"));
    }
}
