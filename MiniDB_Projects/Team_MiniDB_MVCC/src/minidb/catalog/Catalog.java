package minidb.catalog;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Catalog - System catalog for MiniDB.
 * 
 * Manages metadata for all tables: schemas, column definitions, data types,
 * and table-level statistics used by the cost-based optimizer.
 * 
 * Design Decision: In-memory catalog backed by file serialization.
 * Real databases use dedicated catalog tables, but for MiniDB we use
 * Java serialization for simplicity while demonstrating the concept.
 */
public class Catalog implements Serializable {
    private static final long serialVersionUID = 1L;

    // ======================== DATA TYPES ========================
    
    public enum DataType implements Serializable {
        INT,       // 4 bytes - Java int
        FLOAT,     // 8 bytes - Java double
        VARCHAR;   // Variable-length string
        
        public static DataType fromString(String s) {
            switch (s.toUpperCase()) {
                case "INT": case "INTEGER": return INT;
                case "FLOAT": case "DOUBLE": case "REAL": return FLOAT;
                case "VARCHAR": case "STRING": case "TEXT": return VARCHAR;
                default: throw new RuntimeException("Unknown data type: " + s);
            }
        }
    }

    // ======================== COLUMN ========================
    
    public static class Column implements Serializable {
        private static final long serialVersionUID = 1L;
        private String name;
        private DataType type;
        private int maxLength; // For VARCHAR

        public Column(String name, DataType type) {
            this.name = name;
            this.type = type;
            this.maxLength = 255;
        }

        public Column(String name, DataType type, int maxLength) {
            this.name = name;
            this.type = type;
            this.maxLength = maxLength;
        }

        public String getName() { return name; }
        public DataType getType() { return type; }
        public int getMaxLength() { return maxLength; }

        @Override
        public String toString() {
            return name + " " + type + (type == DataType.VARCHAR ? "(" + maxLength + ")" : "");
        }
    }

    // ======================== SCHEMA ========================
    
    public static class Schema implements Serializable {
        private static final long serialVersionUID = 1L;
        private String tableName;
        private List<Column> columns;
        private String primaryKey;  // Column name of the primary key
        private int tableId;

        public Schema(String tableName, List<Column> columns, int tableId) {
            this.tableName = tableName;
            this.columns = new ArrayList<>(columns);
            this.tableId = tableId;
            // Default primary key is the first column
            this.primaryKey = columns.isEmpty() ? null : columns.get(0).getName();
        }

        public String getTableName() { return tableName; }
        public List<Column> getColumns() { return columns; }
        public int getTableId() { return tableId; }
        public String getPrimaryKey() { return primaryKey; }
        public void setPrimaryKey(String pk) { this.primaryKey = pk; }

        public int getColumnIndex(String colName) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).getName().equalsIgnoreCase(colName)) {
                    return i;
                }
            }
            return -1;
        }

        public Column getColumn(String colName) {
            int idx = getColumnIndex(colName);
            return idx >= 0 ? columns.get(idx) : null;
        }

        public int getColumnCount() { return columns.size(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Table: ").append(tableName).append(" (id=").append(tableId).append(")\n");
            sb.append("Columns:\n");
            for (Column col : columns) {
                sb.append("  ").append(col).append(col.getName().equals(primaryKey) ? " [PK]" : "").append("\n");
            }
            return sb.toString();
        }
    }

    // ======================== TABLE STATISTICS ========================
    
    /**
     * TableStats - Statistics for the cost-based query optimizer.
     * Tracks row counts and distinct value counts per column.
     */
    public static class TableStats implements Serializable {
        private static final long serialVersionUID = 1L;
        private int rowCount;
        private Map<String, Integer> distinctValues; // column name -> distinct count
        private Map<String, Object> minValues;       // column name -> min value
        private Map<String, Object> maxValues;       // column name -> max value

        public TableStats() {
            this.rowCount = 0;
            this.distinctValues = new HashMap<>();
            this.minValues = new HashMap<>();
            this.maxValues = new HashMap<>();
        }

        public int getRowCount() { return rowCount; }
        public void setRowCount(int count) { this.rowCount = count; }
        public void incrementRowCount() { this.rowCount++; }
        public void decrementRowCount() { if (rowCount > 0) rowCount--; }

        public int getDistinctValues(String column) {
            return distinctValues.getOrDefault(column, 1);
        }

        public void setDistinctValues(String column, int count) {
            distinctValues.put(column, count);
        }

        public void setMinValue(String column, Object val) { minValues.put(column, val); }
        public void setMaxValue(String column, Object val) { maxValues.put(column, val); }
        public Object getMinValue(String column) { return minValues.get(column); }
        public Object getMaxValue(String column) { return maxValues.get(column); }
    }

    // ======================== CATALOG ========================

    private Map<String, Schema> tables;       // tableName -> Schema
    private Map<String, TableStats> stats;    // tableName -> TableStats
    private Map<String, String> indexes;      // indexName -> tableName
    private Map<String, String> indexColumns; // indexName -> columnName
    private AtomicInteger nextTableId;
    private String dataDir;

    public Catalog(String dataDir) {
        this.tables = new LinkedHashMap<>();
        this.stats = new HashMap<>();
        this.indexes = new HashMap<>();
        this.indexColumns = new HashMap<>();
        this.nextTableId = new AtomicInteger(1);
        this.dataDir = dataDir;
    }

    /**
     * Create a new table in the catalog.
     */
    public Schema createTable(String tableName, List<Column> columns) {
        if (tables.containsKey(tableName.toLowerCase())) {
            throw new RuntimeException("Table already exists: " + tableName);
        }
        int id = nextTableId.getAndIncrement();
        Schema schema = new Schema(tableName.toLowerCase(), columns, id);
        tables.put(tableName.toLowerCase(), schema);
        stats.put(tableName.toLowerCase(), new TableStats());
        return schema;
    }

    /**
     * Drop a table from the catalog.
     */
    public void dropTable(String tableName) {
        tables.remove(tableName.toLowerCase());
        stats.remove(tableName.toLowerCase());
        // Remove associated indexes
        indexes.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(tableName));
    }

    /**
     * Register an index in the catalog.
     */
    public void createIndex(String indexName, String tableName, String columnName) {
        indexes.put(indexName.toLowerCase(), tableName.toLowerCase());
        indexColumns.put(indexName.toLowerCase(), columnName.toLowerCase());
    }

    /**
     * Get index name for a table+column, if one exists.
     */
    public String getIndexForColumn(String tableName, String columnName) {
        for (Map.Entry<String, String> entry : indexes.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(tableName) &&
                indexColumns.get(entry.getKey()).equalsIgnoreCase(columnName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public Schema getSchema(String tableName) {
        return tables.get(tableName.toLowerCase());
    }

    public TableStats getStats(String tableName) {
        return stats.get(tableName.toLowerCase());
    }

    public boolean tableExists(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    public Collection<Schema> getAllSchemas() {
        return tables.values();
    }

    public Map<String, String> getIndexes() { return indexes; }
    public Map<String, String> getIndexColumns() { return indexColumns; }
    public String getDataDir() { return dataDir; }

    /**
     * Persist catalog to disk.
     */
    public void save() {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(dataDir + File.separator + "catalog.dat"))) {
            oos.writeObject(this);
        } catch (IOException e) {
            System.err.println("Warning: Could not save catalog: " + e.getMessage());
        }
    }

    /**
     * Load catalog from disk.
     */
    public static Catalog load(String dataDir) {
        File f = new File(dataDir + File.separator + "catalog.dat");
        if (!f.exists()) {
            return new Catalog(dataDir);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            Catalog cat = (Catalog) ois.readObject();
            cat.dataDir = dataDir;
            return cat;
        } catch (Exception e) {
            System.err.println("Warning: Could not load catalog, creating new: " + e.getMessage());
            return new Catalog(dataDir);
        }
    }
}
