package minidb.storage;

import minidb.catalog.Catalog.Schema;
import minidb.catalog.Catalog.DataType;
import java.io.*;
import java.util.*;

/**
 * Tuple - Represents a single row in a database table.
 * 
 * Each tuple contains:
 * - An array of field values (typed as Object)
 * - A reference to its schema for type information
 * - A RecordId (pageId, slotId) indicating physical location
 * - MVCC metadata (xmin, xmax) for version visibility
 * 
 * Design Decision: Using Object[] for field values provides flexibility
 * with Java's type system. In production databases, tuples are typically
 * stored as raw bytes, but this approach is clearer for demonstration.
 */
public class Tuple implements Serializable {
    private static final long serialVersionUID = 1L;

    // ======================== RECORD ID ========================
    
    /**
     * RecordId uniquely identifies a tuple's physical location.
     * Composed of the page number and slot number within that page.
     */
    public static class RecordId implements Serializable {
        private static final long serialVersionUID = 1L;
        private int pageId;
        private int slotId;

        public RecordId(int pageId, int slotId) {
            this.pageId = pageId;
            this.slotId = slotId;
        }

        public int getPageId() { return pageId; }
        public int getSlotId() { return slotId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RecordId)) return false;
            RecordId r = (RecordId) o;
            return pageId == r.pageId && slotId == r.slotId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pageId, slotId);
        }

        @Override
        public String toString() {
            return "(" + pageId + "," + slotId + ")";
        }
    }

    // ======================== TUPLE FIELDS ========================

    private Object[] fields;       // Field values
    private RecordId recordId;     // Physical location
    private String tableName;      // Table this tuple belongs to

    // MVCC metadata (Extension Track B)
    private long xmin;    // Transaction ID that created this version
    private long xmax;    // Transaction ID that deleted this version (0 = alive)
    private boolean deleted; // Logical deletion flag

    public Tuple(Object[] fields) {
        this.fields = Arrays.copyOf(fields, fields.length);
        this.xmin = 0;
        this.xmax = 0;
        this.deleted = false;
    }

    public Tuple(Object[] fields, String tableName) {
        this(fields);
        this.tableName = tableName;
    }

    /**
     * Create a deep copy of this tuple.
     */
    public Tuple copy() {
        Tuple t = new Tuple(fields);
        t.recordId = this.recordId;
        t.tableName = this.tableName;
        t.xmin = this.xmin;
        t.xmax = this.xmax;
        t.deleted = this.deleted;
        return t;
    }

    // ======================== FIELD ACCESS ========================

    public Object getField(int index) {
        if (index < 0 || index >= fields.length) {
            throw new IndexOutOfBoundsException("Field index " + index + " out of range [0," + fields.length + ")");
        }
        return fields[index];
    }

    public void setField(int index, Object value) {
        fields[index] = value;
    }

    public int getInt(int index) {
        Object val = getField(index);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof String) return Integer.parseInt((String) val);
        throw new ClassCastException("Field " + index + " is not an INT: " + val);
    }

    public double getFloat(int index) {
        Object val = getField(index);
        if (val instanceof Double) return (Double) val;
        if (val instanceof Integer) return ((Integer) val).doubleValue();
        if (val instanceof String) return Double.parseDouble((String) val);
        throw new ClassCastException("Field " + index + " is not a FLOAT: " + val);
    }

    public String getString(int index) {
        Object val = getField(index);
        return val == null ? null : val.toString();
    }

    public int getFieldCount() { return fields.length; }
    public Object[] getFields() { return fields; }

    // ======================== RECORD ID ========================

    public RecordId getRecordId() { return recordId; }
    public void setRecordId(RecordId rid) { this.recordId = rid; }
    public void setRecordId(int pageId, int slotId) {
        this.recordId = new RecordId(pageId, slotId);
    }

    // ======================== TABLE NAME ========================

    public String getTableName() { return tableName; }
    public void setTableName(String name) { this.tableName = name; }

    // ======================== MVCC METADATA ========================

    public long getXmin() { return xmin; }
    public void setXmin(long xmin) { this.xmin = xmin; }
    public long getXmax() { return xmax; }
    public void setXmax(long xmax) { this.xmax = xmax; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    // ======================== SERIALIZATION ========================

    /**
     * Serialize tuple to bytes for page storage.
     * Format: [fieldCount(4)][field1Type(1)][field1Data]...[fieldNType(1)][fieldNData]
     *         [xmin(8)][xmax(8)][deleted(1)]
     */
    public byte[] serialize() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            
            dos.writeInt(fields.length);
            for (Object field : fields) {
                if (field == null) {
                    dos.writeByte(0); // NULL
                } else if (field instanceof Integer) {
                    dos.writeByte(1); // INT
                    dos.writeInt((Integer) field);
                } else if (field instanceof Double) {
                    dos.writeByte(2); // FLOAT
                    dos.writeDouble((Double) field);
                } else {
                    dos.writeByte(3); // VARCHAR
                    String s = field.toString();
                    dos.writeInt(s.length());
                    dos.writeBytes(s);
                }
            }
            // MVCC metadata
            dos.writeLong(xmin);
            dos.writeLong(xmax);
            dos.writeBoolean(deleted);
            
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Serialization error", e);
        }
    }

    /**
     * Deserialize tuple from bytes.
     */
    public static Tuple deserialize(byte[] data) {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            int fieldCount = dis.readInt();
            Object[] fields = new Object[fieldCount];
            
            for (int i = 0; i < fieldCount; i++) {
                byte type = dis.readByte();
                switch (type) {
                    case 0: fields[i] = null; break;
                    case 1: fields[i] = dis.readInt(); break;
                    case 2: fields[i] = dis.readDouble(); break;
                    case 3:
                        int len = dis.readInt();
                        byte[] strBytes = new byte[len];
                        dis.readFully(strBytes);
                        fields[i] = new String(strBytes);
                        break;
                }
            }
            
            Tuple t = new Tuple(fields);
            t.xmin = dis.readLong();
            t.xmax = dis.readLong();
            t.deleted = dis.readBoolean();
            return t;
        } catch (IOException e) {
            throw new RuntimeException("Deserialization error", e);
        }
    }

    // ======================== COMPARISON ========================

    /**
     * Compare a specific field value against another value.
     * Used by WHERE clause evaluation.
     */
    @SuppressWarnings("unchecked")
    public static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        // Normalize types for comparison
        if (a instanceof Integer && b instanceof Double) {
            a = ((Integer) a).doubleValue();
        } else if (a instanceof Double && b instanceof Integer) {
            b = ((Integer) b).doubleValue();
        } else if (a instanceof Integer && b instanceof String) {
            try { b = Integer.parseInt((String) b); } catch (NumberFormatException e) { /* keep as string */ }
        } else if (a instanceof String && b instanceof Integer) {
            try { a = Integer.parseInt((String) a); } catch (NumberFormatException e) { /* keep as string */ }
        } else if (a instanceof Double && b instanceof String) {
            try { b = Double.parseDouble((String) b); } catch (NumberFormatException e) { /* keep as string */ }
        } else if (a instanceof String && b instanceof Double) {
            try { a = Double.parseDouble((String) a); } catch (NumberFormatException e) { /* keep as string */ }
        }

        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }

    // ======================== DISPLAY ========================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(", ");
            if (fields[i] instanceof String) {
                sb.append("'").append(fields[i]).append("'");
            } else {
                sb.append(fields[i]);
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
