package com.zcj.search;

import com.zcj.common.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to the datamart written by the Indexing Service.
 *
 * Every request opens its own connection, so the Javalin request threads never share
 * a JDBC connection. Each search runs the count and the page query inside one read
 * transaction: with WAL mode both see the same committed snapshot, so a book that is
 * being indexed at that moment is either fully visible or not visible at all.
 */
public class SearchRepository {

    /** Number of occurrences stored in a "10,250,300" positions string. */
    private static final String OCCURRENCES =
            "(LENGTH(p.positions) - LENGTH(REPLACE(p.positions, ',', '')) + 1)";

    private final String dbPath;

    public SearchRepository(String dbPath) {
        this.dbPath = dbPath;
    }

    public record BookResult(int bookId, String title, String author, String language,
                             Integer year, long hits) {
    }

    public record Page(long total, List<BookResult> results) {
    }

    public record Book(int bookId, String title, String author, String language, Integer year) {
    }

    /**
     * Books containing every term of the query that satisfy the filters, ordered by
     * the total number of occurrences of the terms (descending) and then by book_id.
     */
    public Page search(SearchQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder()
                .append("SELECT b.book_id, b.title, b.author, b.language, b.year, ")
                .append("SUM(").append(OCCURRENCES).append(") AS hits ")
                .append("FROM postings p JOIN books b ON b.book_id = p.book_id ")
                .append("WHERE p.term IN (").append(placeholders(query.terms().size())).append(")");
        List<Object> params = new ArrayList<>(query.terms());

        if (query.author() != null) {
            // SQLite's LIKE is case-insensitive for ASCII letters
            sql.append(" AND b.author LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(query.author()) + "%");
        }
        if (query.language() != null) {
            sql.append(" AND b.language = ?");
            params.add(query.language());
        }
        if (query.year() != null) {
            sql.append(" AND b.year = ?");
            params.add(query.year());
        }
        sql.append(" GROUP BY b.book_id HAVING COUNT(DISTINCT p.term) = ?");
        params.add(query.terms().size());

        String matches = sql.toString();

        try (Connection conn = Database.open(dbPath)) {
            conn.setAutoCommit(false);  // one read transaction = one consistent snapshot
            try {
                long total;
                try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM (" + matches + ")")) {
                    bind(ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0;
                    }
                }

                List<BookResult> results = new ArrayList<>();
                if (total > query.offset()) {
                    String page = matches + " ORDER BY hits DESC, b.book_id ASC LIMIT ? OFFSET ?";
                    try (PreparedStatement ps = conn.prepareStatement(page)) {
                        List<Object> pageParams = new ArrayList<>(params);
                        pageParams.add(query.limit());
                        pageParams.add(query.offset());
                        bind(ps, pageParams);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                results.add(new BookResult(rs.getInt("book_id"), rs.getString("title"),
                                        rs.getString("author"), rs.getString("language"),
                                        (Integer) rs.getObject("year"), rs.getLong("hits")));
                            }
                        }
                    }
                }
                return new Page(total, results);
            } finally {
                conn.commit();
            }
        }
    }

    public Book findBook(int bookId) throws SQLException {
        try (Connection conn = Database.open(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT book_id, title, author, language, year FROM books WHERE book_id = ?")) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Book(rs.getInt("book_id"), rs.getString("title"), rs.getString("author"),
                        rs.getString("language"), (Integer) rs.getObject("year"));
            }
        }
    }

    /** Values available for the filters, with the number of books of each one. */
    public Map<String, Object> filterValues() throws SQLException {
        Map<String, Object> filters = new LinkedHashMap<>();
        try (Connection conn = Database.open(dbPath)) {
            filters.put("languages", counts(conn, "language"));
            filters.put("years", counts(conn, "year"));
            filters.put("authors", counts(conn, "author"));
        }
        return filters;
    }

    private static List<Map<String, Object>> counts(Connection conn, String column) throws SQLException {
        List<Map<String, Object>> values = new ArrayList<>();
        String sql = "SELECT " + column + " AS value, COUNT(*) AS books FROM books "
                + "WHERE " + column + " IS NOT NULL GROUP BY " + column + " ORDER BY " + column;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", rs.getObject("value"));
                entry.put("books", rs.getLong("books"));
                values.add(entry);
            }
        }
        return values;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /** Escapes the LIKE wildcards so that an author filter is matched literally. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
