package minidb.storage;

import java.util.*;

/**
 * BufferPool - In-memory page cache with LRU eviction policy.
 * 
 * The buffer pool sits between the query executor and the disk manager,
 * caching frequently accessed pages in memory to reduce disk I/O.
 * 
 * Key concepts demonstrated:
 * - Page pinning: prevents eviction while page is in use
 * - Dirty page tracking: knows which pages need to be written back
 * - LRU eviction: when pool is full, evicts the least recently used unpinned page
 * 
 * Design Decision: LRU was chosen over CLOCK or LRU-K for simplicity.
 * The pool uses a LinkedHashMap (access-ordered) to naturally maintain LRU order.
 * 
 * ┌─────────────────────────────────┐
 * │     Buffer Pool (N pages)       │
 * │  ┌────┐ ┌────┐ ┌────┐ ┌────┐  │
 * │  │ P1 │ │ P2 │ │ P3 │ │ P4 │  │
 * │  │pin1│ │pin0│ │pin2│ │pin0│  │
 * │  └────┘ └────┘ └────┘ └────┘  │
 * │  MRU ◄────────────────► LRU   │
 * └─────────────────────────────────┘
 *          ▲           │
 *          │ getPage   │ evict (flush if dirty)
 *          │           ▼
 * ┌─────────────────────────────────┐
 * │         DiskManager             │
 * └─────────────────────────────────┘
 */
public class BufferPool {

    /**
     * Unique key for a page in the buffer pool.
     */
    private static class PageKey {
        int tableId;
        int pageId;

        PageKey(int tableId, int pageId) {
            this.tableId = tableId;
            this.pageId = pageId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PageKey)) return false;
            PageKey k = (PageKey) o;
            return tableId == k.tableId && pageId == k.pageId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(tableId, pageId);
        }
    }

    private final int capacity;          // Maximum number of pages in pool
    private final DiskManager diskManager;
    private final LinkedHashMap<PageKey, Page> pages; // Access-ordered for LRU

    // Statistics
    private long hits = 0;
    private long misses = 0;
    private long evictions = 0;

    /**
     * Create a buffer pool with the specified capacity.
     * 
     * @param capacity Maximum number of pages to cache
     * @param diskManager The disk manager for reading/writing pages
     */
    public BufferPool(int capacity, DiskManager diskManager) {
        this.capacity = capacity;
        this.diskManager = diskManager;
        // Access-ordered LinkedHashMap for LRU behavior
        this.pages = new LinkedHashMap<>(capacity, 0.75f, true);
    }

    /**
     * Fetch a page from the buffer pool.
     * If the page is not cached, read it from disk and add to pool.
     * The page is automatically pinned (pin count incremented).
     * 
     * @param tableId Table ID
     * @param pageId Page number
     * @return The requested page, pinned in the buffer pool
     */
    public synchronized Page getPage(int tableId, int pageId) {
        PageKey key = new PageKey(tableId, pageId);
        
        Page page = pages.get(key);
        if (page != null) {
            // Cache hit
            hits++;
            page.pin();
            return page;
        }

        // Cache miss - read from disk
        misses++;
        page = diskManager.readPage(tableId, pageId);

        // Evict if necessary
        if (pages.size() >= capacity) {
            evictPage();
        }

        // Add to pool
        page.pin();
        pages.put(key, page);
        return page;
    }

    /**
     * Unpin a page in the buffer pool.
     * A page can only be evicted when its pin count reaches 0.
     */
    public synchronized void unpinPage(int tableId, int pageId, boolean isDirty) {
        PageKey key = new PageKey(tableId, pageId);
        Page page = pages.get(key);
        if (page != null) {
            page.unpin();
            if (isDirty) {
                page.setDirty(true);
            }
        }
    }

    /**
     * Flush a specific dirty page to disk.
     */
    public synchronized void flushPage(int tableId, int pageId) {
        PageKey key = new PageKey(tableId, pageId);
        Page page = pages.get(key);
        if (page != null && page.isDirty()) {
            diskManager.writePage(tableId, pageId, page);
            page.setDirty(false);
        }
    }

    /**
     * Flush all dirty pages to disk.
     */
    public synchronized void flushAllPages() {
        for (Map.Entry<PageKey, Page> entry : pages.entrySet()) {
            Page page = entry.getValue();
            if (page.isDirty()) {
                diskManager.writePage(entry.getKey().tableId, entry.getKey().pageId, page);
                page.setDirty(false);
            }
        }
    }

    /**
     * Evict the least recently used unpinned page.
     * If the page is dirty, flush it to disk first.
     * 
     * LRU Policy: The LinkedHashMap iteration order gives us pages
     * from least recently accessed to most recently accessed.
     */
    private void evictPage() {
        PageKey victimKey = null;

        // Find the least recently used unpinned page
        for (Map.Entry<PageKey, Page> entry : pages.entrySet()) {
            if (entry.getValue().getPinCount() == 0) {
                victimKey = entry.getKey();
                break; // First unpinned page in LRU order
            }
        }

        if (victimKey == null) {
            throw new RuntimeException("Buffer pool full: all pages are pinned! " +
                    "Increase buffer pool size or unpin pages after use.");
        }

        Page victim = pages.get(victimKey);

        // Write dirty page to disk before eviction
        if (victim.isDirty()) {
            diskManager.writePage(victimKey.tableId, victimKey.pageId, victim);
        }

        pages.remove(victimKey);
        evictions++;
    }

    /**
     * Remove all pages for a specific table (e.g., when dropping a table).
     */
    public synchronized void evictTable(int tableId) {
        List<PageKey> toRemove = new ArrayList<>();
        for (Map.Entry<PageKey, Page> entry : pages.entrySet()) {
            if (entry.getKey().tableId == tableId) {
                if (entry.getValue().isDirty()) {
                    diskManager.writePage(tableId, entry.getKey().pageId, entry.getValue());
                }
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(pages::remove);
    }

    /**
     * Check if a page is in the buffer pool.
     */
    public synchronized boolean containsPage(int tableId, int pageId) {
        return pages.containsKey(new PageKey(tableId, pageId));
    }

    // ======================== STATISTICS ========================

    public int getSize() { return pages.size(); }
    public int getCapacity() { return capacity; }
    public long getHits() { return hits; }
    public long getMisses() { return misses; }
    public long getEvictions() { return evictions; }
    
    public double getHitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public void resetStats() {
        hits = 0;
        misses = 0;
        evictions = 0;
    }

    @Override
    public String toString() {
        return String.format("BufferPool[size=%d/%d, hitRate=%.1f%%, hits=%d, misses=%d, evictions=%d]",
                pages.size(), capacity, getHitRate() * 100, hits, misses, evictions);
    }
}
