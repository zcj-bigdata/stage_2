package com.zcj.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite datamart shared by Indexing (writer) and Search (reader).
 *
 * <pre>
 *   books(book_id, title, author, language, year, path)
 *   postings(term, book_id, positions)     positions = "10,250,300"
 * </pre>
 *
 * WAL mode lets Search read the last committed state while Indexing writes:
 * a book becomes searchable only when the transaction that indexes it commits.
 */
public final class Database {

    private Database() {
    }

    public static Connection open(String dbPath) throws SQLException {
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new SQLException("Cannot create the folder of " + dbPath, e);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    public static Connection openFromConfig() throws SQLException {
        return open(Config.dbPath());
    }

    /** Creates the tables if they do not exist. Safe to call from every service. */
    public static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS books (
                        book_id  INTEGER PRIMARY KEY,
                        title    TEXT,
                        author   TEXT,
                        language TEXT,
                        year     INTEGER,
                        path     TEXT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS postings (
                        term      TEXT    NOT NULL,
                        book_id   INTEGER NOT NULL,
                        positions TEXT    NOT NULL,
                        PRIMARY KEY (term, book_id)
                    ) WITHOUT ROWID""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_books_language ON books(language)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_books_year ON books(year)");
        }
    }
}
