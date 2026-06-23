package minidb.storage;

import minidb.catalog.Catalog;
import minidb.catalog.Catalog.Schema;
import java.util.*;

/**
 * HeapFile - Manages page-based heap storage for a single table.
 * 
 * A heap file is an unordered collection of pages, each containing tuples.
 * New tuples are inserted into the first page with available space.
 * Pages are linked together (nextPageId) for sequential scanning.
 * 
 * Design Decision: Heap files provide the simplest storage organization.
 * They support efficient inserts (append) but require full scans for
 * lookups without an index. The B+ tree index provides fast lookups.
 * 
 * Operations:
 * - insertTuple: Add a new tuple to the heap
 * - deleteTuple: Remove a tuple by RecordId
 * - getTuple: Fetch a specific tuple by RecordId
 * - scan: Full table scan returning all tuples
 */
public class HeapFile {

    private int tableId;
    private String tableName;
    private BufferPool bufferPool;
    private DiskManager diskManager;

    public HeapFile(int tableId, String tableName, BufferPool bufferPool, DiskManager diskManager) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.bufferPool = bufferPool;
        this.diskManager = diskManager;
    }

    /**
     * Insert a tuple into the heap file.
     * Scans existing pages for free space, allocates a new page if needed.
     * 
     * @param tuple The tuple to insert
     * @return The RecordId of the inserted tuple
     */
    public Tuple.RecordId insertTuple(Tuple tuple) {
        tuple.setTableName(tableName);
        int numPages = diskManager.getPageCount(tableId);

        // Try to find a page with free space
        for (int i = 0; i < numPages; i++) {
            Page page = bufferPool.getPage(tableId, i);
            try {
                int slotId = page.insertTuple(tuple);
                if (slotId >= 0) {
                    bufferPool.unpinPage(tableId, i, true);
                    return new Tuple.RecordId(i, slotId);
                }
            } finally {
                bufferPool.unpinPage(tableId, i, page.isDirty());
            }
        }

        // All pages full - allocate a new page
        int newPageId = diskManager.allocatePage(tableId);
        Page newPage = bufferPool.getPage(tableId, newPageId);
        int slotId = newPage.insertTuple(tuple);
        bufferPool.unpinPage(tableId, newPageId, true);

        if (slotId < 0) {
            throw new RuntimeException("Failed to insert tuple into new page");
        }

        return new Tuple.RecordId(newPageId, slotId);
    }

    /**
     * Delete a tuple by its RecordId.
     * 
     * @param rid The RecordId of the tuple to delete
     * @return The deleted tuple, or null if not found
     */
    public Tuple deleteTuple(Tuple.RecordId rid) {
        Page page = bufferPool.getPage(tableId, rid.getPageId());
        try {
            Tuple tuple = page.getTuple(rid.getSlotId());
            if (tuple != null) {
                page.deleteTuple(rid.getSlotId());
                return tuple;
            }
            return null;
        } finally {
            bufferPool.unpinPage(tableId, rid.getPageId(), page.isDirty());
        }
    }

    /**
     * Get a specific tuple by RecordId.
     */
    public Tuple getTuple(Tuple.RecordId rid) {
        Page page = bufferPool.getPage(tableId, rid.getPageId());
        try {
            return page.getTuple(rid.getSlotId());
        } finally {
            bufferPool.unpinPage(tableId, rid.getPageId(), false);
        }
    }

    /**
     * Perform a full table scan, returning all tuples.
     * This iterates through every page and every slot.
     */
    public List<Tuple> scan() {
        List<Tuple> results = new ArrayList<>();
        int numPages = diskManager.getPageCount(tableId);

        for (int i = 0; i < numPages; i++) {
            Page page = bufferPool.getPage(tableId, i);
            try {
                results.addAll(page.getAllTuples());
            } finally {
                bufferPool.unpinPage(tableId, i, false);
            }
        }

        return results;
    }

    /**
     * Scan with a predicate filter (pushdown).
     * Evaluates the predicate on each tuple during the scan to avoid
     * materializing all tuples in memory.
     */
    public List<Tuple> scanWithFilter(TupleFilter filter) {
        List<Tuple> results = new ArrayList<>();
        int numPages = diskManager.getPageCount(tableId);

        for (int i = 0; i < numPages; i++) {
            Page page = bufferPool.getPage(tableId, i);
            try {
                for (Tuple t : page.getAllTuples()) {
                    if (!t.isDeleted() && filter.accept(t)) {
                        results.add(t);
                    }
                }
            } finally {
                bufferPool.unpinPage(tableId, i, false);
            }
        }

        return results;
    }

    /**
     * Get the total number of tuples in this heap file.
     */
    public int getTupleCount() {
        int count = 0;
        int numPages = diskManager.getPageCount(tableId);
        for (int i = 0; i < numPages; i++) {
            Page page = bufferPool.getPage(tableId, i);
            try {
                count += page.getNumTuples();
            } finally {
                bufferPool.unpinPage(tableId, i, false);
            }
        }
        return count;
    }

    /**
     * Get number of pages in this heap file.
     */
    public int getPageCount() {
        return diskManager.getPageCount(tableId);
    }

    /**
     * Flush all pages of this heap file to disk.
     */
    public void flush() {
        int numPages = diskManager.getPageCount(tableId);
        for (int i = 0; i < numPages; i++) {
            bufferPool.flushPage(tableId, i);
        }
    }

    public int getTableId() { return tableId; }
    public String getTableName() { return tableName; }

    /**
     * Functional interface for tuple filtering during scans.
     */
    public interface TupleFilter {
        boolean accept(Tuple tuple);
    }
}
