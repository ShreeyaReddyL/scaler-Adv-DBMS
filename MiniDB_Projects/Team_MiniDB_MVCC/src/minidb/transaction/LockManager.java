package minidb.transaction;

import java.util.*;
import java.util.concurrent.*;

/**
 * LockManager - Implements Strict Two-Phase Locking (2PL) for MiniDB.
 * 
 * Manages shared (S) and exclusive (X) lock acquisition and release.
 * Uses a wait-for graph for deadlock detection.
 * 
 * Lock Compatibility Matrix:
 *          | S Lock | X Lock |
 * S Lock   |  YES   |  NO    |
 * X Lock   |  NO    |  NO    |
 * 
 * Strict 2PL Protocol:
 * - Growing phase: Transaction acquires locks as needed
 * - Shrinking phase: All locks released only at commit/abort
 * - No lock is released before the transaction ends
 * 
 * Design Decision: Row-level locking using (tableName, key) pairs.
 * This provides better concurrency than table-level locks while being
 * simpler to implement than page-level or predicate locks.
 * 
 * Deadlock Detection: Wait-for graph cycle detection.
 * When a transaction must wait, we check if granting the wait would
 * create a cycle (deadlock). If so, the requesting transaction is aborted.
 */
public class LockManager {

    // ======================== LOCK TYPES ========================

    public enum LockType {
        SHARED,     // Read lock - multiple transactions can hold simultaneously
        EXCLUSIVE   // Write lock - only one transaction can hold
    }

    /**
     * Represents a lock held on a resource.
     */
    private static class Lock {
        LockType type;
        Set<Long> holders; // Transaction IDs holding this lock

        Lock(LockType type, long txnId) {
            this.type = type;
            this.holders = ConcurrentHashMap.newKeySet();
            this.holders.add(txnId);
        }
    }

    /**
     * Represents a transaction waiting for a lock.
     */
    private static class LockRequest {
        long txnId;
        LockType type;
        String resource;

        LockRequest(long txnId, LockType type, String resource) {
            this.txnId = txnId;
            this.type = type;
            this.resource = resource;
        }
    }

    // ======================== STATE ========================

    // resource -> Lock (current lock on that resource)
    private final Map<String, Lock> lockTable;
    
    // txnId -> set of resources locked by that transaction
    private final Map<Long, Set<String>> txnLocks;
    
    // Wait-for graph: txnId -> set of txnIds it's waiting for
    private final Map<Long, Set<Long>> waitForGraph;
    
    // Queue of pending lock requests per resource
    private final Map<String, Queue<LockRequest>> waitQueues;

    // Lock timeout in milliseconds
    private static final long LOCK_TIMEOUT_MS = 5000;

    public LockManager() {
        this.lockTable = new ConcurrentHashMap<>();
        this.txnLocks = new ConcurrentHashMap<>();
        this.waitForGraph = new ConcurrentHashMap<>();
        this.waitQueues = new ConcurrentHashMap<>();
    }

    // ======================== LOCK OPERATIONS ========================

    /**
     * Acquire a shared (read) lock on a resource.
     * Blocks if an exclusive lock is held by another transaction.
     * 
     * @param txnId Transaction requesting the lock
     * @param resource Resource identifier (e.g., "table:key")
     * @throws RuntimeException if deadlock is detected
     */
    public synchronized boolean acquireShared(long txnId, String resource) {
        return acquireLock(txnId, resource, LockType.SHARED);
    }

    /**
     * Acquire an exclusive (write) lock on a resource.
     * Blocks if any lock (shared or exclusive) is held by another transaction.
     * 
     * @param txnId Transaction requesting the lock
     * @param resource Resource identifier
     * @throws RuntimeException if deadlock is detected
     */
    public synchronized boolean acquireExclusive(long txnId, String resource) {
        return acquireLock(txnId, resource, LockType.EXCLUSIVE);
    }

    /**
     * Core lock acquisition logic.
     */
    private boolean acquireLock(long txnId, String resource, LockType requestedType) {
        // Initialize transaction's lock set
        txnLocks.computeIfAbsent(txnId, k -> ConcurrentHashMap.newKeySet());

        Lock currentLock = lockTable.get(resource);

        // Case 1: No existing lock on this resource
        if (currentLock == null) {
            lockTable.put(resource, new Lock(requestedType, txnId));
            txnLocks.get(txnId).add(resource);
            return true;
        }

        // Case 2: Transaction already holds this lock
        if (currentLock.holders.contains(txnId)) {
            // Lock upgrade: S → X
            if (currentLock.type == LockType.SHARED && requestedType == LockType.EXCLUSIVE) {
                if (currentLock.holders.size() == 1) {
                    // Only holder — upgrade directly
                    currentLock.type = LockType.EXCLUSIVE;
                    return true;
                } else {
                    // Others hold shared locks — check for deadlock
                    if (wouldDeadlock(txnId, currentLock.holders)) {
                        throw new RuntimeException("DEADLOCK detected: Transaction " + txnId +
                                " cannot upgrade lock on " + resource);
                    }
                    // Wait for other shared locks to release
                    return waitForLock(txnId, resource, requestedType, currentLock);
                }
            }
            return true; // Already have compatible or same lock
        }

        // Case 3: Compatible lock request (shared + shared)
        if (currentLock.type == LockType.SHARED && requestedType == LockType.SHARED) {
            currentLock.holders.add(txnId);
            txnLocks.get(txnId).add(resource);
            return true;
        }

        // Case 4: Incompatible lock — need to wait
        // Check for deadlock first
        if (wouldDeadlock(txnId, currentLock.holders)) {
            throw new RuntimeException("DEADLOCK detected: Transaction " + txnId +
                    " blocked by " + currentLock.holders + " on " + resource);
        }

        return waitForLock(txnId, resource, requestedType, currentLock);
    }

    /**
     * Wait for a lock to become available.
     * Uses a simple retry with timeout approach.
     */
    private boolean waitForLock(long txnId, String resource, LockType type, Lock currentLock) {
        // Add to wait-for graph
        waitForGraph.computeIfAbsent(txnId, k -> ConcurrentHashMap.newKeySet());
        waitForGraph.get(txnId).addAll(currentLock.holders);

        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < LOCK_TIMEOUT_MS) {
            Lock lock = lockTable.get(resource);
            
            if (lock == null) {
                // Lock was released
                lockTable.put(resource, new Lock(type, txnId));
                txnLocks.get(txnId).add(resource);
                waitForGraph.remove(txnId);
                return true;
            }
            
            if (type == LockType.SHARED && lock.type == LockType.SHARED) {
                lock.holders.add(txnId);
                txnLocks.get(txnId).add(resource);
                waitForGraph.remove(txnId);
                return true;
            }
            
            if (lock.holders.isEmpty() || 
                (lock.holders.size() == 1 && lock.holders.contains(txnId))) {
                lock.type = type;
                lock.holders.add(txnId);
                txnLocks.get(txnId).add(resource);
                waitForGraph.remove(txnId);
                return true;
            }

            try {
                Thread.sleep(10); // Brief wait before retry
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Timeout — abort to prevent potential deadlock
        waitForGraph.remove(txnId);
        throw new RuntimeException("Lock timeout: Transaction " + txnId + 
                " could not acquire " + type + " lock on " + resource);
    }

    /**
     * Release all locks held by a transaction (at commit/abort).
     * This is the "shrinking phase" of strict 2PL.
     */
    public synchronized void releaseAll(long txnId) {
        Set<String> resources = txnLocks.remove(txnId);
        if (resources == null) return;

        for (String resource : resources) {
            Lock lock = lockTable.get(resource);
            if (lock != null) {
                lock.holders.remove(txnId);
                if (lock.holders.isEmpty()) {
                    lockTable.remove(resource);
                }
            }
        }

        // Clean up wait-for graph
        waitForGraph.remove(txnId);
        for (Set<Long> waiters : waitForGraph.values()) {
            waiters.remove(txnId);
        }
    }

    // ======================== DEADLOCK DETECTION ========================

    /**
     * Check if granting a lock would create a deadlock.
     * Uses DFS to detect cycles in the wait-for graph.
     * 
     * @param txnId The requesting transaction
     * @param blockers The transactions currently holding the lock
     * @return true if a deadlock would occur
     */
    private boolean wouldDeadlock(long txnId, Set<Long> blockers) {
        // Would txnId waiting for blockers create a cycle?
        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new LinkedList<>(blockers);
        
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (current == txnId) return true; // Cycle found!
            if (visited.contains(current)) continue;
            visited.add(current);
            
            Set<Long> currentWaitsFor = waitForGraph.get(current);
            if (currentWaitsFor != null) {
                queue.addAll(currentWaitsFor);
            }
        }
        
        return false;
    }

    // ======================== QUERY METHODS ========================

    /**
     * Get all locks held by a transaction.
     */
    public Set<String> getLocksForTransaction(long txnId) {
        return txnLocks.getOrDefault(txnId, Collections.emptySet());
    }

    /**
     * Get the lock on a specific resource.
     */
    public String getLockInfo(String resource) {
        Lock lock = lockTable.get(resource);
        if (lock == null) return "UNLOCKED";
        return lock.type + " held by " + lock.holders;
    }

    /**
     * Get total number of active locks.
     */
    public int getActiveLockCount() {
        return lockTable.size();
    }

    /**
     * Create a resource key for table-level locking.
     */
    public static String tableResource(String tableName) {
        return "TABLE:" + tableName;
    }

    /**
     * Create a resource key for row-level locking.
     */
    public static String rowResource(String tableName, Object key) {
        return "ROW:" + tableName + ":" + key;
    }

    @Override
    public String toString() {
        return String.format("LockManager[activeLocks=%d, activeTransactions=%d]",
                lockTable.size(), txnLocks.size());
    }
}
