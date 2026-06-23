package minidb;

import minidb.catalog.Catalog;
import minidb.storage.*;
import minidb.parser.SQLParser;
import minidb.parser.SQLParser.Statement;
import minidb.executor.QueryExecutor;
import minidb.transaction.*;
import minidb.recovery.WALManager;
import minidb.mvcc.MVCCManager;
import java.io.*;
import java.util.*;

/**
 * MiniDB - Main entry point for the MiniDB database system.
 * 
 * Provides an interactive SQL REPL (Read-Eval-Print Loop) for executing
 * SQL commands against the MiniDB engine.
 * 
 * System Architecture:
 *   ┌──────────────────────────────────────────────────┐
 *   │                SQL CLI (REPL)                    │
 *   ├──────────────────────────────────────────────────┤
 *   │              SQL Parser                          │
 *   ├──────────────────────────────────────────────────┤
 *   │        Cost-Based Query Optimizer                │
 *   ├──────────────────────────────────────────────────┤
 *   │          Query Executor (Volcano)                │
 *   ├────────────────┬─────────────────────────────────┤
 *   │ Transaction Mgr│    Recovery (WAL)               │
 *   │  (2PL + MVCC)  │                                 │
 *   ├────────────────┴─────────────────────────────────┤
 *   │           B+ Tree Index Manager                  │
 *   ├──────────────────────────────────────────────────┤
 *   │       Buffer Pool (LRU, 100 pages)               │
 *   ├──────────────────────────────────────────────────┤
 *   │       Disk Manager (Page-based Heap Files)       │
 *   └──────────────────────────────────────────────────┘
 * 
 * Usage:
 *   java minidb.MiniDB              # Interactive mode
 *   java minidb.MiniDB --demo       # Run demo with sample data
 *   java minidb.MiniDB --benchmark  # Run performance benchmarks
 *   java minidb.MiniDB --recover    # Perform crash recovery
 */
public class MiniDB {

    private static final String VERSION = "1.0.0";
    private static final String DATA_DIR = "minidb_data";
    private static final int BUFFER_POOL_SIZE = 100;

    private Catalog catalog;
    private DiskManager diskManager;
    private BufferPool bufferPool;
    private LockManager lockManager;
    private TransactionManager txnManager;
    private WALManager walManager;
    private MVCCManager mvccManager;
    private SQLParser parser;
    private QueryExecutor executor;

    public MiniDB() {
        this(DATA_DIR);
    }

    public MiniDB(String dataDir) {
        // Initialize all components
        this.diskManager = new DiskManager(dataDir);
        this.bufferPool = new BufferPool(BUFFER_POOL_SIZE, diskManager);
        this.catalog = Catalog.load(dataDir);
        this.lockManager = new LockManager();
        this.txnManager = new TransactionManager(lockManager);
        this.walManager = new WALManager(dataDir);
        this.mvccManager = new MVCCManager(txnManager);
        this.parser = new SQLParser();
        this.executor = new QueryExecutor(catalog, bufferPool, diskManager,
                txnManager, lockManager, walManager, mvccManager);
    }

    /**
     * Execute a SQL command and return the result.
     */
    public String execute(String sql) {
        try {
            Statement stmt = parser.parse(sql);
            if (stmt == null) return "";
            return executor.execute(stmt);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Start the interactive REPL.
     */
    public void startRepl() {
        Scanner scanner = new Scanner(System.in);
        printBanner();

        StringBuilder multiLine = new StringBuilder();
        boolean inMultiLine = false;

        while (true) {
            System.out.print(inMultiLine ? "    ...> " : "minidb> ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();

            // Handle special commands
            if (!inMultiLine) {
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit") || 
                    line.equalsIgnoreCase("\\q")) {
                    shutdown();
                    System.out.println("Goodbye!");
                    break;
                }
                if (line.equalsIgnoreCase("help") || line.equals("\\h")) {
                    printHelp();
                    continue;
                }
                if (line.equalsIgnoreCase("demo")) {
                    runDemo();
                    continue;
                }
                if (line.equalsIgnoreCase("benchmark")) {
                    runBenchmark();
                    continue;
                }
                if (line.equalsIgnoreCase("recover")) {
                    System.out.println(executor.performRecovery());
                    continue;
                }
                if (line.equalsIgnoreCase("clear")) {
                    // Clear screen
                    System.out.print("\033[2J\033[H");
                    System.out.flush();
                    continue;
                }
            }

            // Support multi-line SQL (until semicolon)
            multiLine.append(line).append(" ");
            if (line.endsWith(";")) {
                String sql = multiLine.toString().trim();
                if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);
                
                long start = System.nanoTime();
                String result = execute(sql);
                long elapsed = System.nanoTime() - start;
                
                System.out.println(result);
                System.out.printf("Time: %.3f ms%n%n", elapsed / 1e6);
                
                multiLine = new StringBuilder();
                inMultiLine = false;
            } else {
                inMultiLine = true;
            }
        }

        scanner.close();
    }

    /**
     * Run the built-in demo with sample data.
     */
    public void runDemo() {
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("         MiniDB DEMO - Sample Queries      ");
        System.out.println("═══════════════════════════════════════════\n");

        String[] demoQueries = {
            // Create tables
            "CREATE TABLE students (id INT, name VARCHAR, age INT, gpa FLOAT)",
            "CREATE TABLE courses (id INT, title VARCHAR, credits INT)",
            "CREATE TABLE enrollments (student_id INT, course_id INT, grade VARCHAR)",

            // Insert data into students
            "INSERT INTO students VALUES (1, 'Alice', 20, 3.8)",
            "INSERT INTO students VALUES (2, 'Bob', 22, 3.5)",
            "INSERT INTO students VALUES (3, 'Charlie', 21, 3.9)",
            "INSERT INTO students VALUES (4, 'Diana', 23, 3.2)",
            "INSERT INTO students VALUES (5, 'Eve', 20, 3.7)",

            // Insert data into courses
            "INSERT INTO courses VALUES (101, 'Database Systems', 4)",
            "INSERT INTO courses VALUES (102, 'Operating Systems', 3)",
            "INSERT INTO courses VALUES (103, 'Algorithms', 4)",

            // Insert enrollments
            "INSERT INTO enrollments VALUES (1, 101, 'A')",
            "INSERT INTO enrollments VALUES (1, 102, 'B')",
            "INSERT INTO enrollments VALUES (2, 101, 'B')",
            "INSERT INTO enrollments VALUES (3, 103, 'A')",
            "INSERT INTO enrollments VALUES (4, 101, 'C')",
            "INSERT INTO enrollments VALUES (5, 102, 'A')",

            // Create secondary index
            "CREATE INDEX idx_age ON students (age)",

            // Show tables
            "SHOW TABLES",

            // Various queries
            "SELECT * FROM students",
            "SELECT name, gpa FROM students WHERE age > 20",
            "SELECT * FROM students WHERE gpa >= 3.7",

            // Join query
            "SELECT students.name, courses.title FROM students JOIN enrollments ON students.id = enrollments.student_id JOIN courses ON enrollments.course_id = courses.id",

            // Explain plan
            "EXPLAIN SELECT * FROM students WHERE id = 3",
            "EXPLAIN SELECT * FROM students WHERE age > 20",

            // Delete
            "DELETE FROM students WHERE id = 4",
            "SELECT * FROM students",

            // Transaction demo
            "BEGIN",
            "INSERT INTO students VALUES (6, 'Frank', 24, 3.1)",
            "SELECT * FROM students",
            "ROLLBACK",
            "SELECT * FROM students",

            // Stats
            "SHOW STATS",
        };

        for (String sql : demoQueries) {
            System.out.println("minidb> " + sql + ";");
            long start = System.nanoTime();
            String result = execute(sql);
            long elapsed = System.nanoTime() - start;
            System.out.println(result);
            System.out.printf("Time: %.3f ms%n%n", elapsed / 1e6);
        }

        System.out.println("═══════════════════════════════════════════");
        System.out.println("        Demo complete! Try your own SQL.    ");
        System.out.println("═══════════════════════════════════════════\n");
    }

    /**
     * Run performance benchmarks comparing 2PL vs MVCC.
     */
    public void runBenchmark() {
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("    MiniDB BENCHMARK - 2PL vs MVCC         ");
        System.out.println("═══════════════════════════════════════════\n");

        // Setup: create a test table with data
        execute("CREATE TABLE bench (id INT, value INT, data VARCHAR)");
        
        int numRecords = 500;
        System.out.println("Inserting " + numRecords + " records...");
        long insertStart = System.nanoTime();
        for (int i = 1; i <= numRecords; i++) {
            execute("INSERT INTO bench VALUES (" + i + ", " + (i * 10) + ", 'data_" + i + "')");
        }
        long insertTime = System.nanoTime() - insertStart;
        System.out.printf("Insert time: %.2f ms (%.1f ops/sec)%n%n", 
                insertTime / 1e6, numRecords / (insertTime / 1e9));

        // Benchmark 1: Point queries under 2PL
        System.out.println("--- Benchmark 1: Point Queries ---");
        
        // 2PL mode
        execute("SET mvcc = off");
        bufferPool.resetStats();
        diskManager.resetStats();
        
        long twoPlStart = System.nanoTime();
        int queries = 100;
        for (int i = 0; i < queries; i++) {
            int id = (i % numRecords) + 1;
            execute("SELECT * FROM bench WHERE id = " + id);
        }
        long twoPlTime = System.nanoTime() - twoPlStart;
        long twoPlHits = bufferPool.getHits();
        long twoPlMisses = bufferPool.getMisses();
        System.out.printf("2PL:  %d queries in %.2f ms (%.1f qps), Buffer hit rate: %.1f%%\n",
                queries, twoPlTime / 1e6, queries / (twoPlTime / 1e9), bufferPool.getHitRate() * 100);

        // MVCC mode
        execute("SET mvcc = on");
        bufferPool.resetStats();
        diskManager.resetStats();
        
        long mvccStart = System.nanoTime();
        for (int i = 0; i < queries; i++) {
            int id = (i % numRecords) + 1;
            execute("SELECT * FROM bench WHERE id = " + id);
        }
        long mvccTime = System.nanoTime() - mvccStart;
        System.out.printf("MVCC: %d queries in %.2f ms (%.1f qps), Buffer hit rate: %.1f%%\n",
                queries, mvccTime / 1e6, queries / (mvccTime / 1e9), bufferPool.getHitRate() * 100);

        // Benchmark 2: Full table scans
        System.out.println("\n--- Benchmark 2: Full Table Scans ---");
        
        execute("SET mvcc = off");
        long scanStart2PL = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            execute("SELECT * FROM bench WHERE value > 100");
        }
        long scanTime2PL = System.nanoTime() - scanStart2PL;

        execute("SET mvcc = on");
        long scanStartMVCC = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            execute("SELECT * FROM bench WHERE value > 100");
        }
        long scanTimeMVCC = System.nanoTime() - scanStartMVCC;

        System.out.printf("2PL:  20 scans in %.2f ms\n", scanTime2PL / 1e6);
        System.out.printf("MVCC: 20 scans in %.2f ms\n", scanTimeMVCC / 1e6);

        // Benchmark 3: Mixed read-write workload
        System.out.println("\n--- Benchmark 3: Mixed Read-Write Workload ---");
        
        execute("SET mvcc = off");
        long mixedStart2PL = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            if (i % 5 == 0) {
                // Write: 20% writes
                execute("INSERT INTO bench VALUES (" + (numRecords + i + 1) + ", " + (i * 100) + ", 'new_" + i + "')");
            } else {
                // Read: 80% reads
                execute("SELECT * FROM bench WHERE id = " + ((i % numRecords) + 1));
            }
        }
        long mixedTime2PL = System.nanoTime() - mixedStart2PL;

        execute("SET mvcc = on");
        long mixedStartMVCC = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            if (i % 5 == 0) {
                execute("INSERT INTO bench VALUES (" + (numRecords + 100 + i + 1) + ", " + (i * 100) + ", 'mvcc_" + i + "')");
            } else {
                execute("SELECT * FROM bench WHERE id = " + ((i % numRecords) + 1));
            }
        }
        long mixedTimeMVCC = System.nanoTime() - mixedStartMVCC;

        System.out.printf("2PL:  50 mixed ops in %.2f ms\n", mixedTime2PL / 1e6);
        System.out.printf("MVCC: 50 mixed ops in %.2f ms\n", mixedTimeMVCC / 1e6);

        // Summary
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("               SUMMARY                     ");
        System.out.println("═══════════════════════════════════════════");
        System.out.printf("Point Queries - 2PL: %.2fms, MVCC: %.2fms (%.1fx)\n",
                twoPlTime / 1e6, mvccTime / 1e6, (double) twoPlTime / mvccTime);
        System.out.printf("Table Scans   - 2PL: %.2fms, MVCC: %.2fms (%.1fx)\n",
                scanTime2PL / 1e6, scanTimeMVCC / 1e6, (double) scanTime2PL / scanTimeMVCC);
        System.out.printf("Mixed Ops     - 2PL: %.2fms, MVCC: %.2fms (%.1fx)\n",
                mixedTime2PL / 1e6, mixedTimeMVCC / 1e6, (double) mixedTime2PL / mixedTimeMVCC);
        System.out.println();
        System.out.println("MVCC Advantage: Readers never block writers.");
        System.out.println("Under high read contention, MVCC shows better throughput.");
        System.out.println("═══════════════════════════════════════════\n");

        // Cleanup
        execute("SET mvcc = off");
        execute("DROP TABLE bench");
    }

    /**
     * Demonstrate crash recovery.
     */
    public void demoCrashRecovery() {
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("    CRASH RECOVERY DEMONSTRATION            ");
        System.out.println("═══════════════════════════════════════════\n");

        // Setup
        execute("CREATE TABLE recovery_test (id INT, name VARCHAR)");
        
        // Committed transaction (should survive crash)
        System.out.println("1. Inserting data with COMMIT (should survive crash):");
        execute("BEGIN");
        execute("INSERT INTO recovery_test VALUES (1, 'committed_data')");
        execute("COMMIT");
        
        // Uncommitted transaction (should be rolled back after crash)
        System.out.println("\n2. Inserting data WITHOUT COMMIT (simulating crash):");
        execute("BEGIN");
        execute("INSERT INTO recovery_test VALUES (2, 'uncommitted_data')");
        // Don't commit - simulating crash
        
        System.out.println("\n3. Before crash - data in table:");
        execute("SELECT * FROM recovery_test");
        
        System.out.println("\n4. Simulating crash... (WAL is on disk, data may be inconsistent)");
        System.out.println("5. Performing recovery...");
        String recoveryResult = executor.performRecovery();
        System.out.println(recoveryResult);
        
        System.out.println("6. After recovery - only committed data should remain:");
        System.out.println(execute("SELECT * FROM recovery_test"));
        
        execute("DROP TABLE recovery_test");
        System.out.println("\n═══════════════════════════════════════════\n");
    }

    /**
     * Graceful shutdown.
     */
    public void shutdown() {
        // Abort any active transaction
        if (executor.getCurrentTransaction() != null) {
            executor.execute(new SQLParser.RollbackStatement());
        }
        
        // Flush everything
        bufferPool.flushAllPages();
        diskManager.flushAll();
        catalog.save();
        
        // Save indexes
        for (var entry : executor.getIndexes().entrySet()) {
            entry.getValue().save(catalog.getDataDir() + File.separator +
                    "index_" + entry.getKey() + ".dat");
        }
        
        diskManager.close();
    }

    // ======================== UI ========================

    private void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                          ║");
        System.out.println("║   ███╗   ███╗██╗███╗   ██╗██╗██████╗ ██████╗             ║");
        System.out.println("║   ████╗ ████║██║████╗  ██║██║██╔══██╗██╔══██╗            ║");
        System.out.println("║   ██╔████╔██║██║██╔██╗ ██║██║██║  ██║██████╔╝            ║");
        System.out.println("║   ██║╚██╔╝██║██║██║╚██╗██║██║██║  ██║██╔══██╗            ║");
        System.out.println("║   ██║ ╚═╝ ██║██║██║ ╚████║██║██████╔╝██████╔╝            ║");
        System.out.println("║   ╚═╝     ╚═╝╚═╝╚═╝  ╚═══╝╚═╝╚═════╝ ╚═════╝            ║");
        System.out.println("║                                                          ║");
        System.out.println("║   MiniDB v" + VERSION + " - A Relational Database Engine          ║");
        System.out.println("║   Advanced DBMS Capstone Project                         ║");
        System.out.println("║                                                          ║");
        System.out.println("║   Features: Storage Engine | B+ Tree | SQL Parser        ║");
        System.out.println("║             Query Optimizer | 2PL + MVCC | WAL Recovery  ║");
        System.out.println("║                                                          ║");
        System.out.println("║   Type 'help' for commands, 'demo' for a demonstration   ║");
        System.out.println("║   Type 'quit' to exit                                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void printHelp() {
        System.out.println("\n=== MiniDB Commands ===");
        System.out.println("SQL Statements (end with ';'):");
        System.out.println("  CREATE TABLE name (col type, ...);     - Create a table");
        System.out.println("  DROP TABLE name;                       - Drop a table");
        System.out.println("  INSERT INTO table VALUES (v1, v2, ...);- Insert a row");
        System.out.println("  SELECT cols FROM table [WHERE ...];    - Query data");
        System.out.println("  SELECT ... JOIN table ON condition;    - Join tables");
        System.out.println("  DELETE FROM table [WHERE ...];         - Delete rows");
        System.out.println("  CREATE INDEX name ON table (col);      - Create B+ tree index");
        System.out.println("  EXPLAIN SELECT ...;                    - Show query plan");
        System.out.println("  BEGIN;                                 - Start transaction");
        System.out.println("  COMMIT;                                - Commit transaction");
        System.out.println("  ROLLBACK;                              - Rollback transaction");
        System.out.println("  SHOW TABLES;                           - List all tables");
        System.out.println("  SHOW INDEX;                            - List all indexes");
        System.out.println("  SHOW STATS;                            - System statistics");
        System.out.println("  SET mvcc = on/off;                     - Toggle MVCC mode");
        System.out.println();
        System.out.println("Special Commands:");
        System.out.println("  demo       - Run demo with sample data");
        System.out.println("  benchmark  - Run 2PL vs MVCC benchmarks");
        System.out.println("  recover    - Perform crash recovery");
        System.out.println("  clear      - Clear screen");
        System.out.println("  help       - Show this help");
        System.out.println("  quit       - Exit MiniDB");
        System.out.println();
    }

    // ======================== MAIN ========================

    public static void main(String[] args) {
        MiniDB db = new MiniDB();

        if (args.length > 0) {
            switch (args[0]) {
                case "--demo":
                    db.runDemo();
                    db.shutdown();
                    return;
                case "--benchmark":
                    db.runBenchmark();
                    db.shutdown();
                    return;
                case "--recover":
                    System.out.println(db.executor.performRecovery());
                    db.shutdown();
                    return;
                case "--crash-demo":
                    db.demoCrashRecovery();
                    db.shutdown();
                    return;
                default:
                    System.out.println("Unknown option: " + args[0]);
                    System.out.println("Usage: java minidb.MiniDB [--demo|--benchmark|--recover|--crash-demo]");
                    return;
            }
        }

        // Interactive mode
        db.startRepl();
    }

    // Accessors for benchmarking
    public QueryExecutor getExecutor() { return executor; }
    public Catalog getCatalog() { return catalog; }
    public BufferPool getBufferPool() { return bufferPool; }
    public DiskManager getDiskManager() { return diskManager; }
}
