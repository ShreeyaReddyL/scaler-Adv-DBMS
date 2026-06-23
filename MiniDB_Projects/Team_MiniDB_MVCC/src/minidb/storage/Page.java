package minidb.storage;

import java.io.*;
import java.util.*;

/**
 * Page - Fixed-size (4KB) page with slotted storage format.
 * 
 * Page Layout:
 * ┌──────────────────────────────────────┐
 * │ Header: pageId(4) | numTuples(4)    │
 * │         nextPageId(4) | flags(4)    │
 * ├──────────────────────────────────────┤
 * │ Tuple slots (serialized tuples)     │
 * │ [tuple0][tuple1]...[tupleN]         │
 * │                                     │
 * │ (deleted slots marked with flag)    │
 * └──────────────────────────────────────┘
 * 
 * Design Decision: We store tuples as serialized Java objects within
 * the page rather than raw byte manipulation. This simplifies the
 * implementation while still demonstrating the page-based storage concept.
 * Each page has a fixed capacity determined by PAGE_SIZE.
 */
public class Page implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int PAGE_SIZE = 4096; // 4KB pages
    public static final int MAX_TUPLES_PER_PAGE = 100; // Practical limit

    private int pageId;
    private int tableId;
    private int nextPageId;       // For linked list of pages (-1 = last)
    private boolean dirty;        // Modified since last disk write
    private int pinCount;         // Buffer pool pin count

    // Tuple storage
    private List<Tuple> tuples;
    private boolean[] slotUsed;   // Track which slots are occupied
    private int numTuples;        // Number of active tuples
    private int usedBytes;        // Approximate bytes used

    public Page(int pageId, int tableId) {
        this.pageId = pageId;
        this.tableId = tableId;
        this.nextPageId = -1;
        this.dirty = false;
        this.pinCount = 0;
        this.tuples = new ArrayList<>(MAX_TUPLES_PER_PAGE);
        this.slotUsed = new boolean[MAX_TUPLES_PER_PAGE];
        this.numTuples = 0;
        this.usedBytes = 16; // Header size
    }

    // ======================== TUPLE OPERATIONS ========================

    /**
     * Insert a tuple into this page.
     * @return slot index where the tuple was inserted, or -1 if page is full.
     */
    public int insertTuple(Tuple tuple) {
        // Estimate tuple size
        byte[] serialized = tuple.serialize();
        int tupleSize = serialized.length;

        // Check if page has space
        if (usedBytes + tupleSize > PAGE_SIZE || numTuples >= MAX_TUPLES_PER_PAGE) {
            return -1; // Page full
        }

        // Find first free slot
        int slotId = -1;
        for (int i = 0; i < slotUsed.length; i++) {
            if (!slotUsed[i]) {
                slotId = i;
                break;
            }
        }

        if (slotId == -1) return -1;

        // Insert tuple
        while (tuples.size() <= slotId) {
            tuples.add(null);
        }
        tuples.set(slotId, tuple);
        slotUsed[slotId] = true;
        tuple.setRecordId(pageId, slotId);
        numTuples++;
        usedBytes += tupleSize;
        dirty = true;

        return slotId;
    }

    /**
     * Delete a tuple from this page by slot index.
     */
    public boolean deleteTuple(int slotId) {
        if (slotId < 0 || slotId >= slotUsed.length || !slotUsed[slotId]) {
            return false;
        }

        Tuple t = tuples.get(slotId);
        if (t != null) {
            usedBytes -= t.serialize().length;
        }

        tuples.set(slotId, null);
        slotUsed[slotId] = false;
        numTuples--;
        dirty = true;
        return true;
    }

    /**
     * Get a tuple by slot index.
     */
    public Tuple getTuple(int slotId) {
        if (slotId < 0 || slotId >= tuples.size() || !slotUsed[slotId]) {
            return null;
        }
        return tuples.get(slotId);
    }

    /**
     * Get all active (non-deleted) tuples in this page.
     */
    public List<Tuple> getAllTuples() {
        List<Tuple> result = new ArrayList<>();
        for (int i = 0; i < tuples.size(); i++) {
            if (slotUsed[i] && tuples.get(i) != null) {
                result.add(tuples.get(i));
            }
        }
        return result;
    }

    /**
     * Update a tuple in place (for MVCC version management).
     */
    public boolean updateTuple(int slotId, Tuple newTuple) {
        if (slotId < 0 || slotId >= slotUsed.length || !slotUsed[slotId]) {
            return false;
        }
        tuples.set(slotId, newTuple);
        newTuple.setRecordId(pageId, slotId);
        dirty = true;
        return true;
    }

    // ======================== PAGE METADATA ========================

    public int getPageId() { return pageId; }
    public int getTableId() { return tableId; }
    public int getNextPageId() { return nextPageId; }
    public void setNextPageId(int nextPageId) { this.nextPageId = nextPageId; dirty = true; }
    public int getNumTuples() { return numTuples; }
    public boolean isFull() { return usedBytes >= PAGE_SIZE * 0.9 || numTuples >= MAX_TUPLES_PER_PAGE; }
    public boolean isEmpty() { return numTuples == 0; }
    public int getFreeSpace() { return PAGE_SIZE - usedBytes; }

    // ======================== BUFFER POOL SUPPORT ========================

    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }
    public int getPinCount() { return pinCount; }
    public void pin() { pinCount++; }
    public void unpin() { if (pinCount > 0) pinCount--; }

    // ======================== SERIALIZATION ========================

    /**
     * Serialize entire page to bytes for disk storage.
     */
    public byte[] toBytes() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(PAGE_SIZE);
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(this);
            oos.flush();
            
            byte[] data = baos.toByteArray();
            // Pad to PAGE_SIZE
            byte[] padded = new byte[PAGE_SIZE];
            System.arraycopy(data, 0, padded, 0, Math.min(data.length, PAGE_SIZE));
            return padded;
        } catch (IOException e) {
            throw new RuntimeException("Page serialization error", e);
        }
    }

    /**
     * Deserialize page from bytes.
     */
    public static Page fromBytes(byte[] data) {
        try {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
            return (Page) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("Page deserialization error", e);
        }
    }

    @Override
    public String toString() {
        return String.format("Page[id=%d, table=%d, tuples=%d, free=%d bytes]",
                pageId, tableId, numTuples, getFreeSpace());
    }
}
