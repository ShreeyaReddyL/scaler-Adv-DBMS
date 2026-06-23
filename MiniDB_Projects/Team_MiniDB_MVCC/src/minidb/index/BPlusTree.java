package minidb.index;

import minidb.storage.Tuple;
import java.io.*;
import java.util.*;

/**
 * BPlusTree - B+ Tree index implementation for MiniDB.
 * 
 * Provides O(log n) search, insert, and delete operations for indexed columns.
 * Used as the primary key index and optionally for secondary indexes.
 * 
 * B+ Tree Properties:
 * - All data pointers are in leaf nodes
 * - Leaf nodes are linked for efficient range scans
 * - Internal nodes only store keys and child pointers
 * - Tree stays balanced (all leaves at same depth)
 * 
 * Design Decision: Order (max keys per node) is set to 4 for clear demonstration
 * of splits and merges. In production, order would be chosen to fill a disk page.
 * 
 *                    [30|60]                    ← Internal Node
 *                   /   |   \
 *          [10|20]    [40|50]    [70|80]        ← Internal Nodes
 *          / | \      / | \      / | \
 *    [5,10] [20] [30,40] [50] [60,70] [80,90]  ← Leaf Nodes (linked →)
 * 
 * Key → RecordId mapping allows finding the physical location of any indexed tuple.
 */
public class BPlusTree implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int DEFAULT_ORDER = 4; // Max keys per node

    // ======================== NODE CLASSES ========================

    /**
     * Abstract base class for B+ tree nodes.
     */
    private abstract static class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        List<Comparable> keys;
        
        Node() {
            this.keys = new ArrayList<>();
        }
        
        abstract boolean isLeaf();
        int keyCount() { return keys.size(); }
    }

    /**
     * Internal (non-leaf) node containing keys and child pointers.
     * If a node has n keys, it has n+1 children.
     */
    private static class InternalNode extends Node implements Serializable {
        private static final long serialVersionUID = 1L;
        List<Node> children;
        
        InternalNode() {
            super();
            this.children = new ArrayList<>();
        }
        
        @Override
        boolean isLeaf() { return false; }
    }

    /**
     * Leaf node containing keys and associated RecordIds.
     * Leaf nodes are linked together for range scan support.
     */
    private static class LeafNode extends Node implements Serializable {
        private static final long serialVersionUID = 1L;
        List<Tuple.RecordId> values;  // Parallel to keys
        LeafNode next;                // Next leaf (for range scans)
        
        LeafNode() {
            super();
            this.values = new ArrayList<>();
            this.next = null;
        }
        
        @Override
        boolean isLeaf() { return true; }
    }

    // ======================== TREE STATE ========================

    private Node root;
    private int order;        // Maximum keys per node
    private int size;         // Total number of entries
    private String indexName;
    private String tableName;
    private String columnName;

    public BPlusTree(String indexName, String tableName, String columnName) {
        this(indexName, tableName, columnName, DEFAULT_ORDER);
    }

    public BPlusTree(String indexName, String tableName, String columnName, int order) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.columnName = columnName;
        this.order = order;
        this.root = new LeafNode();
        this.size = 0;
    }

    // ======================== SEARCH ========================

    /**
     * Search for a key in the B+ tree.
     * 
     * @param key The key to search for
     * @return The RecordId associated with the key, or null if not found
     * 
     * Time Complexity: O(log n) where n is the number of entries
     */
    @SuppressWarnings("unchecked")
    public Tuple.RecordId search(Comparable key) {
        LeafNode leaf = findLeaf(key);
        for (int i = 0; i < leaf.keys.size(); i++) {
            if (leaf.keys.get(i).compareTo(key) == 0) {
                return leaf.values.get(i);
            }
        }
        return null; // Key not found
    }

    /**
     * Range search: find all entries with keys in [low, high].
     * Leverages leaf-level linked list for efficient scanning.
     */
    @SuppressWarnings("unchecked")
    public List<Tuple.RecordId> rangeSearch(Comparable low, Comparable high) {
        List<Tuple.RecordId> results = new ArrayList<>();
        LeafNode leaf = findLeaf(low);

        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                Comparable k = leaf.keys.get(i);
                if (k.compareTo(low) >= 0 && k.compareTo(high) <= 0) {
                    results.add(leaf.values.get(i));
                } else if (k.compareTo(high) > 0) {
                    return results; // Past the range
                }
            }
            leaf = leaf.next;
        }

        return results;
    }

    /**
     * Find the leaf node where a key should reside.
     */
    @SuppressWarnings("unchecked")
    private LeafNode findLeaf(Comparable key) {
        Node node = root;
        while (!node.isLeaf()) {
            InternalNode internal = (InternalNode) node;
            int i = 0;
            while (i < internal.keys.size() && key.compareTo(internal.keys.get(i)) >= 0) {
                i++;
            }
            node = internal.children.get(i);
        }
        return (LeafNode) node;
    }

    // ======================== INSERT ========================

    /**
     * Insert a key-RecordId pair into the B+ tree.
     * Handles node splitting when a node becomes full.
     * 
     * @param key The index key
     * @param rid The RecordId pointing to the tuple's location
     * 
     * Time Complexity: O(log n) amortized
     */
    @SuppressWarnings("unchecked")
    public void insert(Comparable key, Tuple.RecordId rid) {
        // Handle duplicate keys: update existing entry
        LeafNode existingLeaf = findLeaf(key);
        for (int i = 0; i < existingLeaf.keys.size(); i++) {
            if (existingLeaf.keys.get(i).compareTo(key) == 0) {
                existingLeaf.values.set(i, rid);
                return; // Updated existing entry
            }
        }

        // Insert into tree (may cause splits)
        Object[] result = insertRecursive(root, key, rid);
        
        if (result != null) {
            // Root was split — create new root
            InternalNode newRoot = new InternalNode();
            newRoot.keys.add((Comparable) result[0]);
            newRoot.children.add(root);
            newRoot.children.add((Node) result[1]);
            root = newRoot;
        }

        size++;
    }

    /**
     * Recursive insert with split propagation.
     * 
     * @return null if no split occurred, or [promotedKey, newNode] if split
     */
    @SuppressWarnings("unchecked")
    private Object[] insertRecursive(Node node, Comparable key, Tuple.RecordId rid) {
        if (node.isLeaf()) {
            return insertIntoLeaf((LeafNode) node, key, rid);
        } else {
            return insertIntoInternal((InternalNode) node, key, rid);
        }
    }

    @SuppressWarnings("unchecked")
    private Object[] insertIntoLeaf(LeafNode leaf, Comparable key, Tuple.RecordId rid) {
        // Find insertion position (maintain sorted order)
        int pos = 0;
        while (pos < leaf.keys.size() && leaf.keys.get(pos).compareTo(key) < 0) {
            pos++;
        }

        leaf.keys.add(pos, key);
        leaf.values.add(pos, rid);

        // Check if split is needed
        if (leaf.keys.size() > order) {
            return splitLeaf(leaf);
        }

        return null; // No split needed
    }

    @SuppressWarnings("unchecked")
    private Object[] insertIntoInternal(InternalNode node, Comparable key, Tuple.RecordId rid) {
        // Find child to recurse into
        int i = 0;
        while (i < node.keys.size() && key.compareTo(node.keys.get(i)) >= 0) {
            i++;
        }

        Object[] result = insertRecursive(node.children.get(i), key, rid);

        if (result != null) {
            // Child was split — insert promoted key
            Comparable promotedKey = (Comparable) result[0];
            Node newChild = (Node) result[1];

            int pos = 0;
            while (pos < node.keys.size() && node.keys.get(pos).compareTo(promotedKey) < 0) {
                pos++;
            }

            node.keys.add(pos, promotedKey);
            node.children.add(pos + 1, newChild);

            // Check if this node needs splitting too
            if (node.keys.size() > order) {
                return splitInternal(node);
            }
        }

        return null;
    }

    /**
     * Split a leaf node into two halves.
     * The middle key is promoted to the parent.
     */
    private Object[] splitLeaf(LeafNode leaf) {
        int mid = leaf.keys.size() / 2;
        LeafNode newLeaf = new LeafNode();

        // Move upper half to new leaf
        newLeaf.keys.addAll(leaf.keys.subList(mid, leaf.keys.size()));
        newLeaf.values.addAll(leaf.values.subList(mid, leaf.values.size()));

        // Truncate original leaf
        leaf.keys.subList(mid, leaf.keys.size()).clear();
        leaf.values.subList(mid, leaf.values.size()).clear();

        // Maintain leaf linked list
        newLeaf.next = leaf.next;
        leaf.next = newLeaf;

        // Promote first key of new leaf
        return new Object[]{newLeaf.keys.get(0), newLeaf};
    }

    /**
     * Split an internal node.
     */
    private Object[] splitInternal(InternalNode node) {
        int mid = node.keys.size() / 2;
        Comparable promotedKey = node.keys.get(mid);

        InternalNode newNode = new InternalNode();

        // Move upper half to new node
        newNode.keys.addAll(node.keys.subList(mid + 1, node.keys.size()));
        newNode.children.addAll(node.children.subList(mid + 1, node.children.size()));

        // Truncate original node
        node.keys.subList(mid, node.keys.size()).clear();
        node.children.subList(mid + 1, node.children.size()).clear();

        return new Object[]{promotedKey, newNode};
    }

    // ======================== DELETE ========================

    /**
     * Delete a key from the B+ tree.
     * 
     * @param key The key to delete
     * @return true if the key was found and deleted
     */
    @SuppressWarnings("unchecked")
    public boolean delete(Comparable key) {
        LeafNode leaf = findLeaf(key);
        
        for (int i = 0; i < leaf.keys.size(); i++) {
            if (leaf.keys.get(i).compareTo(key) == 0) {
                leaf.keys.remove(i);
                leaf.values.remove(i);
                size--;
                return true;
            }
        }
        
        return false; // Key not found
    }

    // ======================== UTILITY ========================

    /**
     * Get all entries in the index (full index scan).
     */
    public List<Map.Entry<Comparable, Tuple.RecordId>> getAllEntries() {
        List<Map.Entry<Comparable, Tuple.RecordId>> entries = new ArrayList<>();
        
        // Find leftmost leaf
        Node node = root;
        while (!node.isLeaf()) {
            node = ((InternalNode) node).children.get(0);
        }
        
        // Traverse leaf linked list
        LeafNode leaf = (LeafNode) node;
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                entries.add(new AbstractMap.SimpleEntry<>(leaf.keys.get(i), leaf.values.get(i)));
            }
            leaf = leaf.next;
        }
        
        return entries;
    }

    /**
     * Check if a key exists in the index.
     */
    public boolean contains(Comparable key) {
        return search(key) != null;
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public String getIndexName() { return indexName; }
    public String getTableName() { return tableName; }
    public String getColumnName() { return columnName; }
    public int getOrder() { return order; }

    /**
     * Get the height of the tree.
     */
    public int getHeight() {
        int height = 1;
        Node node = root;
        while (!node.isLeaf()) {
            height++;
            node = ((InternalNode) node).children.get(0);
        }
        return height;
    }

    /**
     * Print tree structure for debugging.
     */
    public void printTree() {
        System.out.println("B+ Tree: " + indexName + " (order=" + order + ", size=" + size + ", height=" + getHeight() + ")");
        printNode(root, 0);
    }

    private void printNode(Node node, int level) {
        String indent = "  ".repeat(level);
        if (node.isLeaf()) {
            LeafNode leaf = (LeafNode) node;
            System.out.print(indent + "Leaf: ");
            for (int i = 0; i < leaf.keys.size(); i++) {
                System.out.print(leaf.keys.get(i) + "→" + leaf.values.get(i) + " ");
            }
            System.out.println();
        } else {
            InternalNode internal = (InternalNode) node;
            System.out.println(indent + "Internal: " + internal.keys);
            for (Node child : internal.children) {
                printNode(child, level + 1);
            }
        }
    }

    // ======================== PERSISTENCE ========================

    /**
     * Save B+ tree to file.
     */
    public void save(String filePath) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath))) {
            oos.writeObject(this);
        } catch (IOException e) {
            System.err.println("Warning: Could not save index: " + e.getMessage());
        }
    }

    /**
     * Load B+ tree from file.
     */
    public static BPlusTree load(String filePath) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath))) {
            return (BPlusTree) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format("BPlusTree[%s on %s(%s), order=%d, size=%d, height=%d]",
                indexName, tableName, columnName, order, size, getHeight());
    }
}
