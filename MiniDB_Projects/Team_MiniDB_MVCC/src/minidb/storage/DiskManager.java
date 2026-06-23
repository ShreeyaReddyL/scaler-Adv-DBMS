package minidb.storage;

import java.io.*;
import java.util.*;

/**
 * DiskManager - Handles page-level I/O between memory and disk.
 * 
 * Each table's data is stored in a separate heap file named "table_<id>.dat".
 * The DiskManager reads and writes fixed-size pages (4KB) to/from these files.
 * 
 * Design Decision: One file per table simplifies management and allows
 * independent growth. Pages are serialized using Java's ObjectOutputStream
 * for correctness, with padding to maintain fixed page alignment on disk.
 * 
 * File Layout:
 * [Page 0 (4KB)][Page 1 (4KB)][Page 2 (4KB)]...
 */
public class DiskManager {

    private String dataDir;
    private Map<Integer, RandomAccessFile> openFiles; // tableId -> file handle
    private Map<Integer, Integer> pageCount;          // tableId -> number of pages
    
    // Statistics
    private long totalReads = 0;
    private long totalWrites = 0;

    public DiskManager(String dataDir) {
        this.dataDir = dataDir;
        this.openFiles = new HashMap<>();
        this.pageCount = new HashMap<>();
        
        // Create data directory if it doesn't exist
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Get the file path for a table's heap file.
     */
    private String getFilePath(int tableId) {
        return dataDir + File.separator + "table_" + tableId + ".dat";
    }

    /**
     * Get or open the RandomAccessFile for a table.
     */
    private RandomAccessFile getFile(int tableId) throws IOException {
        if (!openFiles.containsKey(tableId)) {
            String path = getFilePath(tableId);
            RandomAccessFile raf = new RandomAccessFile(path, "rw");
            openFiles.put(tableId, raf);
            // Calculate existing page count
            int pages = (int) (raf.length() / Page.PAGE_SIZE);
            pageCount.put(tableId, pages);
        }
        return openFiles.get(tableId);
    }

    /**
     * Read a page from disk.
     * 
     * @param tableId The table's ID
     * @param pageId The page number within the table's file
     * @return The deserialized Page object
     */
    public Page readPage(int tableId, int pageId) {
        try {
            RandomAccessFile raf = getFile(tableId);
            long offset = (long) pageId * Page.PAGE_SIZE;
            
            if (offset >= raf.length()) {
                // Page doesn't exist on disk yet, return new empty page
                return new Page(pageId, tableId);
            }
            
            byte[] data = new byte[Page.PAGE_SIZE];
            raf.seek(offset);
            raf.readFully(data);
            totalReads++;
            
            Page page = Page.fromBytes(data);
            page.setDirty(false);
            return page;
        } catch (Exception e) {
            // If deserialization fails, return empty page
            return new Page(pageId, tableId);
        }
    }

    /**
     * Write a page to disk.
     * 
     * @param tableId The table's ID
     * @param pageId The page number
     * @param page The page to write
     */
    public void writePage(int tableId, int pageId, Page page) {
        try {
            RandomAccessFile raf = getFile(tableId);
            long offset = (long) pageId * Page.PAGE_SIZE;
            
            byte[] data = page.toBytes();
            raf.seek(offset);
            raf.write(data);
            raf.getFD().sync(); // Force write to disk (durability)
            totalWrites++;
            
            page.setDirty(false);
            
            // Update page count
            int currentCount = pageCount.getOrDefault(tableId, 0);
            if (pageId >= currentCount) {
                pageCount.put(tableId, pageId + 1);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write page " + pageId + " for table " + tableId, e);
        }
    }

    /**
     * Allocate a new page for a table.
     * 
     * @return The new page's ID
     */
    public int allocatePage(int tableId) {
        int newPageId = pageCount.getOrDefault(tableId, 0);
        pageCount.put(tableId, newPageId + 1);
        
        // Write an empty page to reserve the space
        Page emptyPage = new Page(newPageId, tableId);
        writePage(tableId, newPageId, emptyPage);
        
        return newPageId;
    }

    /**
     * Get the number of pages for a table.
     */
    public int getPageCount(int tableId) {
        try {
            getFile(tableId); // Ensure file is opened and count is initialized
        } catch (IOException e) {
            return 0;
        }
        return pageCount.getOrDefault(tableId, 0);
    }

    /**
     * Delete a table's data file.
     */
    public void deleteTable(int tableId) {
        try {
            if (openFiles.containsKey(tableId)) {
                openFiles.get(tableId).close();
                openFiles.remove(tableId);
            }
            File f = new File(getFilePath(tableId));
            f.delete();
            pageCount.remove(tableId);
        } catch (IOException e) {
            System.err.println("Warning: Could not delete table file: " + e.getMessage());
        }
    }

    /**
     * Flush all open files to disk.
     */
    public void flushAll() {
        for (Map.Entry<Integer, RandomAccessFile> entry : openFiles.entrySet()) {
            try {
                entry.getValue().getFD().sync();
            } catch (IOException e) {
                System.err.println("Warning: Could not sync file for table " + entry.getKey());
            }
        }
    }

    /**
     * Close all open files.
     */
    public void close() {
        for (RandomAccessFile raf : openFiles.values()) {
            try {
                raf.close();
            } catch (IOException e) {
                // Ignore on shutdown
            }
        }
        openFiles.clear();
    }

    // ======================== STATISTICS ========================

    public long getTotalReads() { return totalReads; }
    public long getTotalWrites() { return totalWrites; }
    
    public void resetStats() {
        totalReads = 0;
        totalWrites = 0;
    }

    @Override
    public String toString() {
        return String.format("DiskManager[dir=%s, openFiles=%d, reads=%d, writes=%d]",
                dataDir, openFiles.size(), totalReads, totalWrites);
    }
}
