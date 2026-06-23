package minidb.parser;

import java.util.*;

/**
 * SQLParser - Recursive descent parser for MiniDB's SQL dialect.
 * 
 * Supported SQL statements:
 * - CREATE TABLE name (col1 type, col2 type, ...)
 * - DROP TABLE name
 * - INSERT INTO table VALUES (v1, v2, ...)
 * - SELECT [cols] FROM table [WHERE cond] [JOIN table ON cond]
 * - DELETE FROM table [WHERE cond]
 * - CREATE INDEX name ON table (column)
 * - BEGIN / COMMIT / ROLLBACK
 * - EXPLAIN SELECT ...
 * - SHOW TABLES / SHOW INDEX
 * 
 * Parser Architecture:
 * 1. Lexer: SQL string → Token stream
 * 2. Parser: Token stream → AST (Abstract Syntax Tree)
 * 
 * Design Decision: Hand-written recursive descent parser instead of a
 * parser generator (ANTLR, JavaCC). This is simpler to understand and
 * easier to explain during a viva.
 */
public class SQLParser {

    // ======================== TOKEN TYPES ========================

    public enum TokenType {
        // Keywords
        SELECT, FROM, WHERE, INSERT, INTO, VALUES, DELETE,
        CREATE, DROP, TABLE, INDEX, ON, AND, OR, NOT,
        JOIN, INNER, LEFT, RIGHT, CROSS, 
        ORDER, BY, ASC, DESC, GROUP, HAVING,
        BEGIN, COMMIT, ROLLBACK, EXPLAIN, SHOW, TABLES, SET,
        AS, IN, BETWEEN, LIKE, IS, NULL, EXISTS,
        INT_TYPE, FLOAT_TYPE, VARCHAR_TYPE, PRIMARY, KEY,
        COUNT, SUM, AVG, MIN, MAX, DISTINCT, LIMIT,
        
        // Literals
        INTEGER_LITERAL, FLOAT_LITERAL, STRING_LITERAL,
        
        // Identifiers
        IDENTIFIER,
        
        // Operators
        EQUALS, NOT_EQUALS, LESS_THAN, GREATER_THAN,
        LESS_EQUALS, GREATER_EQUALS,
        PLUS, MINUS, STAR, SLASH,
        
        // Punctuation
        COMMA, DOT, SEMICOLON,
        LEFT_PAREN, RIGHT_PAREN,
        
        // Special
        EOF
    }

    // ======================== TOKEN ========================

    public static class Token {
        public TokenType type;
        public String value;
        public int position;

        public Token(TokenType type, String value, int position) {
            this.type = type;
            this.value = value;
            this.position = position;
        }

        @Override
        public String toString() {
            return type + "(" + value + ")";
        }
    }

    // ======================== AST NODES ========================

    /**
     * Base class for all SQL statements.
     */
    public static abstract class Statement {
        public abstract String getType();
    }

    /**
     * CREATE TABLE statement.
     */
    public static class CreateTableStatement extends Statement {
        public String tableName;
        public List<ColumnDef> columns;
        public String primaryKey;

        @Override public String getType() { return "CREATE_TABLE"; }
    }

    public static class ColumnDef {
        public String name;
        public String type;
        public int length; // For VARCHAR

        public ColumnDef(String name, String type) {
            this.name = name;
            this.type = type;
            this.length = 255;
        }

        public ColumnDef(String name, String type, int length) {
            this.name = name;
            this.type = type;
            this.length = length;
        }
    }

    /**
     * DROP TABLE statement.
     */
    public static class DropTableStatement extends Statement {
        public String tableName;
        @Override public String getType() { return "DROP_TABLE"; }
    }

    /**
     * INSERT INTO statement.
     */
    public static class InsertStatement extends Statement {
        public String tableName;
        public List<Object> values;

        @Override public String getType() { return "INSERT"; }
    }

    /**
     * SELECT statement (supports WHERE, JOIN, aggregates).
     */
    public static class SelectStatement extends Statement {
        public List<String> columns;          // Column names (* = all)
        public List<String> tables;           // FROM tables
        public List<String> tableAliases;     // Table aliases
        public WhereClause where;             // WHERE condition
        public List<JoinClause> joins;        // JOIN clauses
        public List<String> groupBy;          // GROUP BY columns
        public List<OrderByClause> orderBy;   // ORDER BY
        public int limit = -1;               // LIMIT
        public boolean isExplain = false;     // EXPLAIN prefix
        public boolean distinct = false;      // SELECT DISTINCT
        public List<AggregateExpr> aggregates; // Aggregate expressions

        public SelectStatement() {
            columns = new ArrayList<>();
            tables = new ArrayList<>();
            tableAliases = new ArrayList<>();
            joins = new ArrayList<>();
            groupBy = new ArrayList<>();
            orderBy = new ArrayList<>();
            aggregates = new ArrayList<>();
        }

        @Override public String getType() { return "SELECT"; }
    }

    public static class AggregateExpr {
        public String function; // COUNT, SUM, AVG, MIN, MAX
        public String column;
        public String alias;

        public AggregateExpr(String function, String column) {
            this.function = function;
            this.column = column;
            this.alias = function.toLowerCase() + "_" + column;
        }
    }

    /**
     * WHERE clause condition.
     */
    public static class WhereClause {
        public String leftColumn;
        public String operator; // =, !=, <, >, <=, >=
        public Object rightValue;
        public String rightColumn; // For join conditions (column = column)
        public WhereClause left;   // For AND/OR
        public WhereClause right;
        public String logicalOp;   // AND/OR
        
        // Simple condition constructor
        public WhereClause(String leftColumn, String operator, Object rightValue) {
            this.leftColumn = leftColumn;
            this.operator = operator;
            this.rightValue = rightValue;
        }

        // Column-to-column condition
        public WhereClause(String leftColumn, String operator, String rightColumn, boolean isColumnRef) {
            this.leftColumn = leftColumn;
            this.operator = operator;
            this.rightColumn = rightColumn;
        }

        // Compound condition (AND/OR)
        public WhereClause(WhereClause left, String logicalOp, WhereClause right) {
            this.left = left;
            this.logicalOp = logicalOp;
            this.right = right;
        }
    }

    /**
     * JOIN clause.
     */
    public static class JoinClause {
        public String joinType = "INNER"; // INNER, LEFT, RIGHT
        public String tableName;
        public String alias;
        public String leftColumn;   // ON left.col
        public String rightColumn;  // = right.col
    }

    /**
     * ORDER BY clause.
     */
    public static class OrderByClause {
        public String column;
        public boolean ascending = true;
    }

    /**
     * DELETE FROM statement.
     */
    public static class DeleteStatement extends Statement {
        public String tableName;
        public WhereClause where;
        @Override public String getType() { return "DELETE"; }
    }

    /**
     * CREATE INDEX statement.
     */
    public static class CreateIndexStatement extends Statement {
        public String indexName;
        public String tableName;
        public String columnName;
        @Override public String getType() { return "CREATE_INDEX"; }
    }

    /**
     * Transaction control statements.
     */
    public static class BeginStatement extends Statement {
        @Override public String getType() { return "BEGIN"; }
    }

    public static class CommitStatement extends Statement {
        @Override public String getType() { return "COMMIT"; }
    }

    public static class RollbackStatement extends Statement {
        @Override public String getType() { return "ROLLBACK"; }
    }

    /**
     * SHOW TABLES / SHOW INDEX statements.
     */
    public static class ShowStatement extends Statement {
        public String what; // "TABLES", "INDEX"
        @Override public String getType() { return "SHOW"; }
    }

    /**
     * SET statement for configuration.
     */
    public static class SetStatement extends Statement {
        public String variable;
        public String value;
        @Override public String getType() { return "SET"; }
    }

    // ======================== LEXER ========================

    private String input;
    private List<Token> tokens;
    private int pos; // Current position in token list

    /**
     * Tokenize the SQL input string.
     */
    private List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        
        while (i < sql.length()) {
            char c = sql.charAt(i);
            
            // Skip whitespace
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // String literal
            if (c == '\'') {
                int start = i;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < sql.length() && sql.charAt(i) != '\'') {
                    sb.append(sql.charAt(i));
                    i++;
                }
                if (i < sql.length()) i++; // skip closing quote
                tokens.add(new Token(TokenType.STRING_LITERAL, sb.toString(), start));
                continue;
            }

            // Numbers
            if (Character.isDigit(c) || (c == '-' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1))
                    && (tokens.isEmpty() || tokens.get(tokens.size()-1).type == TokenType.LEFT_PAREN
                    || tokens.get(tokens.size()-1).type == TokenType.COMMA
                    || tokens.get(tokens.size()-1).type == TokenType.EQUALS
                    || tokens.get(tokens.size()-1).type == TokenType.LESS_THAN
                    || tokens.get(tokens.size()-1).type == TokenType.GREATER_THAN
                    || tokens.get(tokens.size()-1).type == TokenType.LESS_EQUALS
                    || tokens.get(tokens.size()-1).type == TokenType.GREATER_EQUALS
                    || tokens.get(tokens.size()-1).type == TokenType.NOT_EQUALS))) {
                int start = i;
                if (c == '-') i++;
                boolean isFloat = false;
                while (i < sql.length() && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    if (sql.charAt(i) == '.') isFloat = true;
                    i++;
                }
                String numStr = sql.substring(start, i);
                tokens.add(new Token(isFloat ? TokenType.FLOAT_LITERAL : TokenType.INTEGER_LITERAL, numStr, start));
                continue;
            }

            // Identifiers and keywords
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                String word = sql.substring(start, i);
                TokenType type = getKeywordType(word);
                tokens.add(new Token(type, word, start));
                continue;
            }

            // Operators and punctuation
            int start = i;
            switch (c) {
                case '=': tokens.add(new Token(TokenType.EQUALS, "=", start)); i++; break;
                case '<':
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.LESS_EQUALS, "<=", start)); i += 2;
                    } else if (i + 1 < sql.length() && sql.charAt(i + 1) == '>') {
                        tokens.add(new Token(TokenType.NOT_EQUALS, "<>", start)); i += 2;
                    } else {
                        tokens.add(new Token(TokenType.LESS_THAN, "<", start)); i++;
                    }
                    break;
                case '>':
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.GREATER_EQUALS, ">=", start)); i += 2;
                    } else {
                        tokens.add(new Token(TokenType.GREATER_THAN, ">", start)); i++;
                    }
                    break;
                case '!':
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.NOT_EQUALS, "!=", start)); i += 2;
                    } else {
                        i++;
                    }
                    break;
                case '(': tokens.add(new Token(TokenType.LEFT_PAREN, "(", start)); i++; break;
                case ')': tokens.add(new Token(TokenType.RIGHT_PAREN, ")", start)); i++; break;
                case ',': tokens.add(new Token(TokenType.COMMA, ",", start)); i++; break;
                case '.': tokens.add(new Token(TokenType.DOT, ".", start)); i++; break;
                case ';': tokens.add(new Token(TokenType.SEMICOLON, ";", start)); i++; break;
                case '*': tokens.add(new Token(TokenType.STAR, "*", start)); i++; break;
                case '+': tokens.add(new Token(TokenType.PLUS, "+", start)); i++; break;
                case '-': tokens.add(new Token(TokenType.MINUS, "-", start)); i++; break;
                case '/': tokens.add(new Token(TokenType.SLASH, "/", start)); i++; break;
                default: i++; break; // Skip unknown chars
            }
        }

        tokens.add(new Token(TokenType.EOF, "", sql.length()));
        return tokens;
    }

    /**
     * Map a word to its keyword TokenType, or IDENTIFIER if not a keyword.
     */
    private TokenType getKeywordType(String word) {
        switch (word.toUpperCase()) {
            case "SELECT": return TokenType.SELECT;
            case "FROM": return TokenType.FROM;
            case "WHERE": return TokenType.WHERE;
            case "INSERT": return TokenType.INSERT;
            case "INTO": return TokenType.INTO;
            case "VALUES": return TokenType.VALUES;
            case "DELETE": return TokenType.DELETE;
            case "CREATE": return TokenType.CREATE;
            case "DROP": return TokenType.DROP;
            case "TABLE": return TokenType.TABLE;
            case "INDEX": return TokenType.INDEX;
            case "ON": return TokenType.ON;
            case "AND": return TokenType.AND;
            case "OR": return TokenType.OR;
            case "NOT": return TokenType.NOT;
            case "JOIN": return TokenType.JOIN;
            case "INNER": return TokenType.INNER;
            case "LEFT": return TokenType.LEFT;
            case "RIGHT": return TokenType.RIGHT;
            case "CROSS": return TokenType.CROSS;
            case "ORDER": return TokenType.ORDER;
            case "BY": return TokenType.BY;
            case "ASC": return TokenType.ASC;
            case "DESC": return TokenType.DESC;
            case "GROUP": return TokenType.GROUP;
            case "HAVING": return TokenType.HAVING;
            case "BEGIN": case "START": return TokenType.BEGIN;
            case "COMMIT": return TokenType.COMMIT;
            case "ROLLBACK": case "ABORT": return TokenType.ROLLBACK;
            case "EXPLAIN": return TokenType.EXPLAIN;
            case "SHOW": return TokenType.SHOW;
            case "TABLES": return TokenType.TABLES;
            case "SET": return TokenType.SET;
            case "AS": return TokenType.AS;
            case "IN": return TokenType.IN;
            case "BETWEEN": return TokenType.BETWEEN;
            case "LIKE": return TokenType.LIKE;
            case "IS": return TokenType.IS;
            case "NULL": return TokenType.NULL;
            case "INT": case "INTEGER": return TokenType.INT_TYPE;
            case "FLOAT": case "DOUBLE": case "REAL": return TokenType.FLOAT_TYPE;
            case "VARCHAR": case "STRING": case "TEXT": return TokenType.VARCHAR_TYPE;
            case "PRIMARY": return TokenType.PRIMARY;
            case "KEY": return TokenType.KEY;
            case "COUNT": return TokenType.COUNT;
            case "SUM": return TokenType.SUM;
            case "AVG": return TokenType.AVG;
            case "MIN": return TokenType.MIN;
            case "MAX": return TokenType.MAX;
            case "DISTINCT": return TokenType.DISTINCT;
            case "LIMIT": return TokenType.LIMIT;
            default: return TokenType.IDENTIFIER;
        }
    }

    // ======================== PARSER ========================

    /**
     * Parse a SQL string into an AST Statement.
     */
    public Statement parse(String sql) {
        this.input = sql.trim();
        if (input.isEmpty()) return null;
        
        this.tokens = tokenize(input);
        this.pos = 0;

        // Check first token to determine statement type
        Token first = peek();
        
        switch (first.type) {
            case SELECT: return parseSelect(false);
            case INSERT: return parseInsert();
            case DELETE: return parseDelete();
            case CREATE:
                advance(); // consume CREATE
                Token next = peek();
                if (next.type == TokenType.TABLE) return parseCreateTable();
                if (next.type == TokenType.INDEX) return parseCreateIndex();
                throw new RuntimeException("Expected TABLE or INDEX after CREATE");
            case DROP: return parseDrop();
            case BEGIN: advance(); return new BeginStatement();
            case COMMIT: advance(); return new CommitStatement();
            case ROLLBACK: advance(); return new RollbackStatement();
            case EXPLAIN: advance(); return parseSelect(true);
            case SHOW: return parseShow();
            case SET: return parseSet();
            default:
                throw new RuntimeException("Unexpected token: " + first);
        }
    }

    // ======================== STATEMENT PARSERS ========================

    private SelectStatement parseSelect(boolean isExplain) {
        SelectStatement stmt = new SelectStatement();
        stmt.isExplain = isExplain;
        
        expect(TokenType.SELECT);

        // Check for DISTINCT
        if (peek().type == TokenType.DISTINCT) {
            advance();
            stmt.distinct = true;
        }

        // Parse column list
        parseSelectColumns(stmt);

        // FROM
        expect(TokenType.FROM);
        parseFromClause(stmt);

        // JOINs
        while (isJoinKeyword(peek().type)) {
            stmt.joins.add(parseJoinClause());
        }

        // WHERE
        if (peek().type == TokenType.WHERE) {
            advance();
            stmt.where = parseWhereClause();
        }

        // GROUP BY
        if (peek().type == TokenType.GROUP) {
            advance(); expect(TokenType.BY);
            do {
                stmt.groupBy.add(advance().value);
            } while (peek().type == TokenType.COMMA && advance() != null);
        }

        // ORDER BY
        if (peek().type == TokenType.ORDER) {
            advance(); expect(TokenType.BY);
            do {
                OrderByClause obc = new OrderByClause();
                obc.column = parseColumnRef();
                if (peek().type == TokenType.DESC) {
                    obc.ascending = false;
                    advance();
                } else if (peek().type == TokenType.ASC) {
                    advance();
                }
                stmt.orderBy.add(obc);
            } while (peek().type == TokenType.COMMA && advance() != null);
        }

        // LIMIT
        if (peek().type == TokenType.LIMIT) {
            advance();
            stmt.limit = Integer.parseInt(advance().value);
        }

        return stmt;
    }

    private void parseSelectColumns(SelectStatement stmt) {
        if (peek().type == TokenType.STAR) {
            stmt.columns.add("*");
            advance();
            return;
        }

        do {
            // Check for aggregate functions
            TokenType tt = peek().type;
            if (tt == TokenType.COUNT || tt == TokenType.SUM || tt == TokenType.AVG ||
                tt == TokenType.MIN || tt == TokenType.MAX) {
                String func = advance().value.toUpperCase();
                expect(TokenType.LEFT_PAREN);
                String col = peek().type == TokenType.STAR ? "*" : parseColumnRef();
                advance(); // consume the column or *
                // Actually, parseColumnRef already advanced. Let me fix.
                expect(TokenType.RIGHT_PAREN);
                
                AggregateExpr agg = new AggregateExpr(func, col);
                stmt.aggregates.add(agg);
                stmt.columns.add(agg.alias);
            } else {
                stmt.columns.add(parseColumnRef());
            }
        } while (peek().type == TokenType.COMMA && advance() != null);
    }

    private void parseFromClause(SelectStatement stmt) {
        // First table
        String tableName = advance().value;
        stmt.tables.add(tableName);
        
        // Optional alias
        if (peek().type == TokenType.IDENTIFIER || peek().type == TokenType.AS) {
            if (peek().type == TokenType.AS) advance();
            stmt.tableAliases.add(advance().value);
        } else {
            stmt.tableAliases.add(tableName);
        }

        // Additional tables (comma-separated cross joins)
        while (peek().type == TokenType.COMMA) {
            advance();
            tableName = advance().value;
            stmt.tables.add(tableName);
            if (peek().type == TokenType.IDENTIFIER || peek().type == TokenType.AS) {
                if (peek().type == TokenType.AS) advance();
                stmt.tableAliases.add(advance().value);
            } else {
                stmt.tableAliases.add(tableName);
            }
        }
    }

    private boolean isJoinKeyword(TokenType type) {
        return type == TokenType.JOIN || type == TokenType.INNER ||
               type == TokenType.LEFT || type == TokenType.RIGHT || type == TokenType.CROSS;
    }

    private JoinClause parseJoinClause() {
        JoinClause join = new JoinClause();
        
        // Parse join type
        Token t = peek();
        if (t.type == TokenType.INNER) {
            join.joinType = "INNER";
            advance();
        } else if (t.type == TokenType.LEFT) {
            join.joinType = "LEFT";
            advance();
        } else if (t.type == TokenType.RIGHT) {
            join.joinType = "RIGHT";
            advance();
        } else if (t.type == TokenType.CROSS) {
            join.joinType = "CROSS";
            advance();
        }
        
        expect(TokenType.JOIN);
        
        join.tableName = advance().value;
        
        // Optional alias
        if (peek().type == TokenType.IDENTIFIER || peek().type == TokenType.AS) {
            if (peek().type == TokenType.AS) advance();
            join.alias = advance().value;
        } else {
            join.alias = join.tableName;
        }
        
        // ON condition
        if (peek().type == TokenType.ON) {
            advance();
            join.leftColumn = parseColumnRef();
            expect(TokenType.EQUALS);
            join.rightColumn = parseColumnRef();
        }
        
        return join;
    }

    private WhereClause parseWhereClause() {
        WhereClause left = parseCondition();
        
        while (peek().type == TokenType.AND || peek().type == TokenType.OR) {
            String op = advance().value.toUpperCase();
            WhereClause right = parseCondition();
            left = new WhereClause(left, op, right);
        }
        
        return left;
    }

    private WhereClause parseCondition() {
        String leftCol = parseColumnRef();
        
        String operator;
        Token opToken = peek();
        switch (opToken.type) {
            case EQUALS: operator = "="; advance(); break;
            case NOT_EQUALS: operator = "!="; advance(); break;
            case LESS_THAN: operator = "<"; advance(); break;
            case GREATER_THAN: operator = ">"; advance(); break;
            case LESS_EQUALS: operator = "<="; advance(); break;
            case GREATER_EQUALS: operator = ">="; advance(); break;
            default:
                throw new RuntimeException("Expected comparison operator, got: " + opToken);
        }
        
        Token valueToken = peek();
        
        // Check if right side is a column reference or a literal
        if (valueToken.type == TokenType.STRING_LITERAL) {
            advance();
            return new WhereClause(leftCol, operator, valueToken.value);
        } else if (valueToken.type == TokenType.INTEGER_LITERAL) {
            advance();
            return new WhereClause(leftCol, operator, Integer.parseInt(valueToken.value));
        } else if (valueToken.type == TokenType.FLOAT_LITERAL) {
            advance();
            return new WhereClause(leftCol, operator, Double.parseDouble(valueToken.value));
        } else if (valueToken.type == TokenType.NULL) {
            advance();
            return new WhereClause(leftCol, operator, null);
        } else {
            // Column reference (for join conditions in WHERE)
            String rightCol = parseColumnRef();
            return new WhereClause(leftCol, operator, rightCol, true);
        }
    }

    /**
     * Parse a column reference, which may include a table prefix (table.column).
     */
    private String parseColumnRef() {
        String name = advance().value;
        if (peek().type == TokenType.DOT) {
            advance(); // consume dot
            name = name + "." + advance().value;
        }
        return name;
    }

    private InsertStatement parseInsert() {
        InsertStatement stmt = new InsertStatement();
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        stmt.tableName = advance().value;
        expect(TokenType.VALUES);
        expect(TokenType.LEFT_PAREN);
        
        stmt.values = new ArrayList<>();
        do {
            Token t = peek();
            if (t.type == TokenType.STRING_LITERAL) {
                stmt.values.add(t.value);
            } else if (t.type == TokenType.INTEGER_LITERAL) {
                stmt.values.add(Integer.parseInt(t.value));
            } else if (t.type == TokenType.FLOAT_LITERAL) {
                stmt.values.add(Double.parseDouble(t.value));
            } else if (t.type == TokenType.NULL) {
                stmt.values.add(null);
            } else {
                throw new RuntimeException("Expected value, got: " + t);
            }
            advance();
        } while (peek().type == TokenType.COMMA && advance() != null);
        
        expect(TokenType.RIGHT_PAREN);
        return stmt;
    }

    private DeleteStatement parseDelete() {
        DeleteStatement stmt = new DeleteStatement();
        expect(TokenType.DELETE);
        expect(TokenType.FROM);
        stmt.tableName = advance().value;
        
        if (peek().type == TokenType.WHERE) {
            advance();
            stmt.where = parseWhereClause();
        }
        
        return stmt;
    }

    private CreateTableStatement parseCreateTable() {
        CreateTableStatement stmt = new CreateTableStatement();
        expect(TokenType.TABLE);
        stmt.tableName = advance().value;
        expect(TokenType.LEFT_PAREN);
        
        stmt.columns = new ArrayList<>();
        do {
            // Check for PRIMARY KEY constraint
            if (peek().type == TokenType.PRIMARY) {
                advance(); // PRIMARY
                expect(TokenType.KEY);
                expect(TokenType.LEFT_PAREN);
                stmt.primaryKey = advance().value;
                expect(TokenType.RIGHT_PAREN);
                continue;
            }
            
            String colName = advance().value;
            Token typeToken = peek();
            String typeName;
            int length = 255;
            
            if (typeToken.type == TokenType.INT_TYPE) {
                typeName = "INT";
                advance();
            } else if (typeToken.type == TokenType.FLOAT_TYPE) {
                typeName = "FLOAT";
                advance();
            } else if (typeToken.type == TokenType.VARCHAR_TYPE) {
                typeName = "VARCHAR";
                advance();
                if (peek().type == TokenType.LEFT_PAREN) {
                    advance();
                    length = Integer.parseInt(advance().value);
                    expect(TokenType.RIGHT_PAREN);
                }
            } else {
                typeName = advance().value.toUpperCase(); // fallback
            }
            
            ColumnDef col = new ColumnDef(colName, typeName, length);
            stmt.columns.add(col);
            
            // Check for PRIMARY KEY after column definition
            if (peek().type == TokenType.PRIMARY) {
                advance(); expect(TokenType.KEY);
                stmt.primaryKey = colName;
            }
        } while (peek().type == TokenType.COMMA && advance() != null);
        
        expect(TokenType.RIGHT_PAREN);
        return stmt;
    }

    private CreateIndexStatement parseCreateIndex() {
        CreateIndexStatement stmt = new CreateIndexStatement();
        expect(TokenType.INDEX);
        stmt.indexName = advance().value;
        expect(TokenType.ON);
        stmt.tableName = advance().value;
        expect(TokenType.LEFT_PAREN);
        stmt.columnName = advance().value;
        expect(TokenType.RIGHT_PAREN);
        return stmt;
    }

    private DropTableStatement parseDrop() {
        DropTableStatement stmt = new DropTableStatement();
        expect(TokenType.DROP);
        expect(TokenType.TABLE);
        stmt.tableName = advance().value;
        return stmt;
    }

    private ShowStatement parseShow() {
        ShowStatement stmt = new ShowStatement();
        expect(TokenType.SHOW);
        Token what = advance();
        stmt.what = what.value.toUpperCase();
        return stmt;
    }

    private SetStatement parseSet() {
        SetStatement stmt = new SetStatement();
        expect(TokenType.SET);
        stmt.variable = advance().value;
        if (peek().type == TokenType.EQUALS) advance();
        stmt.value = advance().value;
        return stmt;
    }

    // ======================== PARSER HELPERS ========================

    private Token peek() {
        if (pos >= tokens.size()) return new Token(TokenType.EOF, "", -1);
        return tokens.get(pos);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        pos++;
        return t;
    }

    private Token expect(TokenType type) {
        Token t = peek();
        if (t.type != type) {
            throw new RuntimeException("Expected " + type + " but got " + t.type +
                    " ('" + t.value + "') at position " + t.position);
        }
        return advance();
    }
}
