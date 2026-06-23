# MiniDB — A Relational Database Engine from Scratch

> **Advanced DBMS Capstone Project** | Extension Track B: MVCC Concurrency

## Team Information

| Field | Details |
|-------|---------|
| **Team Name** | _TODO: Add your team name_ |
| **Member 1** | _Name, Email, Roll Number_ |
| **Member 2** | _Name, Email, Roll Number_ |

---

## 1. Project Overview

### Problem Statement
Build a functioning relational database engine (MiniDB) that integrates storage, indexing, query processing, transaction management, and recovery — demonstrating understanding of database internals.

### Goals
- Build a working database engine from foundational components in Java
- Integrate page-based storage, B+ tree indexing, SQL parsing, cost-based optimization, and transaction management
- Implement Write-Ahead Logging (WAL) for crash recovery
- **Extension Track B**: Replace 2PL with MVCC for higher read throughput under contention

### Chosen Extension Track
**Track B — Concurrency (MVCC)**
- Multi-Version Concurrency Control with Snapshot Isolation
- Readers never block writers, and writers never block readers
- Version chains per tuple key with visibility rules based on transaction timestamps

---

## 2. System Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    SQL Interface (CLI/REPL)               │
├──────────────────────────────────────────────────────────┤
│        SQL Parser (Lexer → Tokens → Recursive Descent)    │
├──────────────────────────────────────────────────────────┤
│          Cost-Based Query Optimizer                       │
│   (Selectivity estimation, Access path, Join ordering)    │
├──────────────────────────────────────────────────────────┤
│              Query Executor (Volcano Model)               │
│  (SeqScan, IndexScan, NestedLoopJoin, Filter, Project)    │
├─────────────────┬────────────────────────────────────────┤
│  Transaction    │       Recovery (WAL)                    │
│  Manager        │  (ARIES: Analysis → Redo → Undo)       │
│  (2PL + MVCC)   │                                        │
├─────────────────┴────────────────────────────────────────┤
│              B+ Tree Index Manager                        │
│   (Search, Insert, Delete, Range Scan, Leaf Linking)      │
├──────────────────────────────────────────────────────────┤
│         Buffer Pool (LRU Eviction, Pin/Unpin)             │
├──────────────────────────────────────────────────────────┤
│      Disk Manager (Page-based Heap Files, 4KB Pages)      │
└──────────────────────────────────────────────────────────┘
```

### Major Modules
| Module | Files | Description |
|--------|-------|-------------|
| Storage Engine | `Page.java`, `DiskManager.java`, `BufferPool.java`, `HeapFile.java`, `Tuple.java` | Page-based heap storage with LRU buffer pool |
| Catalog | `Catalog.java` | System metadata: schemas, columns, types, statistics |
| Indexing | `BPlusTree.java` | B+ tree with search, insert, delete, range scans |
| SQL Parser | `SQLParser.java` | Recursive descent parser with lexer and AST |
| Query Optimizer | `QueryOptimizer.java` | Cost-based optimizer with selectivity estimation |
| Query Executor | `QueryExecutor.java` | Volcano-model executor for all SQL operations |
| Transactions | `TransactionManager.java`, `LockManager.java` | Strict 2PL with deadlock detection |
| Recovery | `WALManager.java` | Write-Ahead Logging with ARIES-style recovery |
| MVCC | `MVCCManager.java` | Multi-version concurrency control (Extension B) |

### Data Flow
```
SQL Query → Lexer → Parser → AST → Optimizer → Execution Plan → Executor
                                                                    │
                                    ┌───────────────────────────────┘
                                    ▼
                        Transaction Manager (Lock/MVCC)
                                    │
                        ┌───────────┴────────────┐
                        ▼                        ▼
                   B+ Tree Index            Heap File
                        │                        │
                        └───────────┬────────────┘
                                    ▼
                              Buffer Pool
                                    │
                                    ▼
                             Disk Manager → wal.log / table_N.dat
```

---

## 3. Storage Layer

### Page Format
- **Page size**: 4096 bytes (4KB) — standard for modern databases
- **Layout**: Slotted page format with tuple storage
- Each page tracks: pageId, tableId, numTuples, free space, nextPageId
- Pages are the unit of I/O between disk and memory

### Heap Files
- One heap file per table (`table_<id>.dat`)
- Pages linked together for sequential scanning
- New tuples inserted into first page with free space
- Deleted slots marked and reusable

### Buffer Pool
- **Capacity**: 100 pages (configurable)
- **Eviction policy**: LRU (Least Recently Used) via access-ordered `LinkedHashMap`
- **Pin/Unpin protocol**: Prevents eviction of in-use pages
- **Dirty page tracking**: Dirty pages flushed to disk on eviction or commit

**Design Trade-off**: LRU chosen over CLOCK or LRU-K for simplicity. LRU provides good cache behavior for typical workloads. A real system would use CLOCK for O(1) eviction.

---

## 4. Indexing

### B+ Tree Design
- **Order**: 4 (max keys per node) — chosen for clear demonstration of splits
- **In production**: Order would be `(page_size - header) / (key_size + pointer_size)`
- All data pointers reside in leaf nodes
- Internal nodes store only separator keys and child pointers

### Node Structure
```
Internal Node: [key1 | key2 | key3]
               /    |     |    \
         child0  child1 child2 child3

Leaf Node: [key1→RID1 | key2→RID2 | key3→RID3] → next_leaf
```

### Operations
| Operation | Complexity | Notes |
|-----------|-----------|-------|
| Search | O(log n) | Traverse from root to leaf |
| Insert | O(log n) amortized | May trigger splits up the tree |
| Delete | O(log n) | Simple removal from leaf |
| Range Scan | O(log n + k) | Linked list traversal at leaf level |

### Search Path
1. Start at root node
2. Binary search within node to find correct child pointer
3. Follow pointer to child node
4. Repeat until leaf node reached
5. Linear scan within leaf for target key

---

## 5. Query Execution

### Parser
- **Type**: Hand-written recursive descent parser
- **Tokenizer**: Classifies input into keywords, identifiers, literals, operators
- **AST Nodes**: CreateTable, Insert, Select, Delete, CreateIndex, Begin, Commit, Rollback

### Supported SQL
```sql
CREATE TABLE name (col1 INT, col2 VARCHAR, col3 FLOAT);
INSERT INTO table VALUES (v1, 'v2', v3);
SELECT cols FROM table WHERE condition;
SELECT t1.col, t2.col FROM t1 JOIN t2 ON t1.id = t2.fk WHERE ...;
DELETE FROM table WHERE condition;
CREATE INDEX name ON table (column);
BEGIN; COMMIT; ROLLBACK;
EXPLAIN SELECT ...;
```

### Query Plan Generation
The optimizer chooses between Table Scan and Index Scan based on cost estimates, then the executor creates an appropriate pipeline.

### Operator Execution (Volcano Model)
Each operator (Scan, Filter, Join, Project) produces tuples that flow upward to the next operator. This iterator model enables lazy evaluation and pipelining.

---

## 6. Optimizer

### Cost Estimation
| Access Method | Cost Formula |
|---------------|-------------|
| Table Scan | `⌈row_count / tuples_per_page⌉` |
| Index Scan | `tree_height + ⌈selectivity × row_count / tuples_per_page⌉` |
| Nested Loop Join | `|outer_pages| + |outer_pages| × |inner_pages|` |

### Selectivity Estimation
| Predicate | Selectivity |
|-----------|------------|
| `col = val` | `1 / distinct_values(col)` |
| `col != val` | `1 - 1/distinct_values(col)` |
| `col < val` | `(val - min) / (max - min)` |
| `col > val` | `1 - (val - min) / (max - min)` |
| `A AND B` | `sel(A) × sel(B)` (independence assumption) |
| `A OR B` | `sel(A) + sel(B) - sel(A)×sel(B)` |

### Join Ordering
Greedy algorithm: start with the smallest table, then join the cheapest next table at each step. O(N²) complexity, produces good results for 2-4 table joins.

---

## 7. Transactions & Concurrency

### Locking Strategy (2PL - Default)
- **Protocol**: Strict Two-Phase Locking
- **Lock types**: Shared (S) for reads, Exclusive (X) for writes
- **Lock granularity**: Row-level using `(tableName, primaryKey)` pairs
- **Growing phase**: Acquire locks as needed during execution
- **Shrinking phase**: All locks released at COMMIT/ABORT

### Lock Compatibility Matrix
|  | S | X |
|--|---|---|
| **S** | ✅ | ❌ |
| **X** | ❌ | ❌ |

### Isolation Guarantee
- **Serializable**: Strict 2PL guarantees serializability
- All schedules produced are conflict-serializable

### Deadlock Handling
- **Detection**: Wait-for graph with cycle detection (BFS)
- **Prevention**: Lock timeout (5 seconds)
- **Resolution**: Abort the requesting transaction

---

## 8. Recovery

### WAL Design
- **Protocol**: Write-Ahead Logging — log records forced to disk before data changes
- **Durability guarantee**: Once COMMIT record is on disk, transaction survives crashes
- **Log file**: Append-only sequential file (`wal.log`)

### Log Records
| Type | Content | Purpose |
|------|---------|---------|
| BEGIN | txnId | Transaction started |
| INSERT | txnId, table, tupleData, pageId, slotId | Redo information |
| DELETE | txnId, table, tupleData, pageId, slotId | Undo information |
| COMMIT | txnId | Durability marker |
| ABORT | txnId | Transaction aborted |
| CHECKPOINT | activeTxns | Consistent snapshot |

### Crash Recovery Procedure (ARIES-style)
1. **Analysis Phase**: Scan log to identify committed and active transactions at crash
2. **Redo Phase**: Replay all operations from committed transactions
3. **Undo Phase**: Roll back all operations from uncommitted transactions

---

## 9. Extension Track B: MVCC

### Motivation
Under 2PL, **readers block writers and writers block readers**. This reduces throughput for read-heavy workloads. MVCC eliminates this bottleneck by allowing readers to see consistent snapshots without acquiring locks.

### Design
- Each tuple has version metadata: `xmin` (creating txn), `xmax` (deleting txn)
- On INSERT: create a new version with `xmin = current_txn`
- On DELETE: set `xmax = current_txn` on the visible version
- On SELECT: traverse version chain to find version visible to snapshot

### Visibility Rules
A version is visible to transaction T if:
1. `xmin` is committed AND was committed before T's snapshot
2. `xmax` is either 0 (alive), or `xmax` is not yet committed, or `xmax` was committed after T's snapshot

### Results

| Benchmark | 2PL | MVCC | Speedup |
|-----------|-----|------|---------|
| Point Queries (100) | 38.31 ms | 17.41 ms | **2.2x** |
| Table Scans (20) | 94.71 ms | 49.90 ms | **1.9x** |
| Mixed Read-Write (50) | 28.07 ms | 23.24 ms | **1.2x** |

**Key insight**: MVCC provides significantly better read throughput because readers never need to acquire locks. The advantage is most pronounced under read-heavy workloads.

---

## 10. Benchmarks

### Experimental Setup
- **Hardware**: Local machine (results will vary)
- **Dataset**: 500 records in test table
- **Workload**: Point queries, full table scans, mixed read-write (80/20)
- **Comparison**: 2PL (Strict Two-Phase Locking) vs MVCC (Snapshot Isolation)

### Results
See Section 9 for detailed benchmark comparison.

### Analysis
- **Insert throughput**: ~1000 ops/sec (dominated by WAL disk sync)
- **Buffer hit rate**: 100% after warm-up (all data fits in buffer pool)
- **MVCC overhead**: Minimal — version chains add ~15% memory overhead but eliminate lock contention
- **2PL bottleneck**: Lock manager synchronization adds overhead even without contention

---

## 11. Limitations

### Missing Features
- UPDATE statement (can be implemented as DELETE + INSERT)
- Multi-column indexes / composite keys
- Hash join, sort-merge join (only nested-loop join implemented)
- Predicate locking for phantom prevention
- Online index building

### Scalability Limits
- Buffer pool is in-memory — limited by JVM heap
- B+ tree indexes are serialized entirely to/from disk (not page-based)
- WAL grows unboundedly without log truncation after checkpoint
- MVCC version chains not garbage collected aggressively

### Future Improvements
- Page-based B+ tree nodes stored on disk
- Hash join for equi-joins on large tables
- Background MVCC garbage collection thread
- Log-structured merge tree (LSM) storage option (Track C)
- Query result caching

---

## 12. How to Run

### Prerequisites
- **Java 11+** (JDK, not just JRE)
- No external dependencies — pure Java implementation

### Build Steps
```bash
# Compile all Java sources
javac -d out -sourcepath src src/minidb/catalog/Catalog.java src/minidb/storage/*.java src/minidb/index/BPlusTree.java src/minidb/parser/SQLParser.java src/minidb/transaction/*.java src/minidb/recovery/WALManager.java src/minidb/mvcc/MVCCManager.java src/minidb/optimizer/QueryOptimizer.java src/minidb/executor/QueryExecutor.java src/minidb/MiniDB.java

# Or use the build script (Windows)
compile.bat
```

### Running
```bash
# Interactive SQL REPL
java -cp out minidb.MiniDB

# Run demo with sample data
java -cp out minidb.MiniDB --demo

# Run 2PL vs MVCC benchmark
java -cp out minidb.MiniDB --benchmark

# Perform crash recovery
java -cp out minidb.MiniDB --recover

# Crash recovery demonstration
java -cp out minidb.MiniDB --crash-demo
```

### Example Commands
```sql
-- Create a table
CREATE TABLE employees (id INT, name VARCHAR, salary FLOAT, dept VARCHAR);

-- Insert data
INSERT INTO employees VALUES (1, 'Alice', 75000.0, 'Engineering');
INSERT INTO employees VALUES (2, 'Bob', 65000.0, 'Marketing');

-- Query with WHERE
SELECT name, salary FROM employees WHERE salary > 70000;

-- Create index and use EXPLAIN
CREATE INDEX idx_salary ON employees (salary);
EXPLAIN SELECT * FROM employees WHERE salary > 70000;

-- Join tables
SELECT e.name, d.dept_name FROM employees e JOIN departments d ON e.dept = d.id;

-- Transaction with rollback
BEGIN;
INSERT INTO employees VALUES (3, 'Charlie', 80000.0, 'Engineering');
ROLLBACK;  -- Charlie's insert is undone

-- Enable MVCC mode
SET mvcc = on;

-- Show system stats
SHOW STATS;
SHOW TABLES;
SHOW INDEX;
```

---

## Project Structure
```
MiniDB/
├── src/minidb/
│   ├── MiniDB.java              # Entry point, CLI REPL
│   ├── catalog/
│   │   └── Catalog.java         # Schema, Column, DataType, TableStats
│   ├── storage/
│   │   ├── Tuple.java           # Row representation with MVCC metadata
│   │   ├── Page.java            # 4KB slotted page
│   │   ├── DiskManager.java     # Page-level disk I/O
│   │   ├── BufferPool.java      # LRU buffer pool
│   │   └── HeapFile.java        # Heap file organization
│   ├── index/
│   │   └── BPlusTree.java       # B+ tree index
│   ├── parser/
│   │   └── SQLParser.java       # Lexer + Parser + AST
│   ├── optimizer/
│   │   └── QueryOptimizer.java  # Cost-based optimizer
│   ├── executor/
│   │   └── QueryExecutor.java   # Query execution engine
│   ├── transaction/
│   │   ├── LockManager.java     # Strict 2PL with deadlock detection
│   │   └── TransactionManager.java  # Transaction lifecycle
│   ├── recovery/
│   │   └── WALManager.java      # Write-ahead logging + recovery
│   └── mvcc/
│       └── MVCCManager.java     # MVCC (Extension Track B)
├── compile.bat                  # Build script
├── run.bat                      # Run script
└── README.md                    # This file
```
