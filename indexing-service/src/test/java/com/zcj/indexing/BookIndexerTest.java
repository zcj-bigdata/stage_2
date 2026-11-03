package com.zcj.indexing;

import com.zcj.common.Database;
import com.zcj.common.Datalake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

class BookIndexerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldIndexBookAndStoreMetadataAndPositions() throws Exception {
        Datalake datalake = createDatalake();

        try (Connection conn = Database.open(tempDir.resolve("test.db").toString())) {
            Database.createSchema(conn);

            BookIndexer.IndexResult result =
                    BookIndexer.indexBook(conn, datalake, 1342);

            assertEquals(1342, result.bookId());
            assertEquals(6, result.totalTokens());
            assertEquals(4, result.uniqueTerms());

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT title, author, language, year FROM books WHERE book_id = ?")) {

                ps.setInt(1, 1342);

                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Pride and Prejudice", rs.getString("title"));
                    assertEquals("Jane Austen", rs.getString("author"));
                    assertEquals("en", rs.getString("language"));
                    assertEquals(1998, rs.getInt("year"));
                }
            }

            assertEquals("0,4", getPositions(conn, "the", 1342));
            assertEquals("1,5", getPositions(conn, "house", 1342));
            assertEquals("2", getPositions(conn, "is", 1342));
            assertEquals("3", getPositions(conn, "big", 1342));
        }
    }

    @Test
    void shouldNotDuplicatePostingsWhenReindexing() throws Exception {
        Datalake datalake = createDatalake();

        try (Connection conn = Database.open(tempDir.resolve("test.db").toString())) {
            Database.createSchema(conn);

            BookIndexer.indexBook(conn, datalake, 1342);
            BookIndexer.indexBook(conn, datalake, 1342);

            assertEquals(4, count(conn, "SELECT COUNT(*) FROM postings"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM books"));
        }
    }

    @Test
    void shouldRejectInvalidBookId() throws Exception {
        Datalake datalake = createDatalake();

        try (Connection conn = Database.open(tempDir.resolve("test.db").toString())) {
            Database.createSchema(conn);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> BookIndexer.indexBook(conn, datalake, 0)
            );
        }
    }

    @Test
    void shouldNotModifyDatabaseWhenBookFilesAreMissing() throws Exception {
        Datalake datalake = new Datalake(tempDir.resolve("missing"));

        try (Connection conn = Database.open(tempDir.resolve("test.db").toString())) {
            Database.createSchema(conn);

            assertThrows(
                    java.io.IOException.class,
                    () -> BookIndexer.indexBook(conn, datalake, 1342)
            );

            assertEquals(0, count(conn, "SELECT COUNT(*) FROM books"));
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM postings"));
        }
    }

    private Datalake createDatalake() throws Exception {
        Datalake datalake = new Datalake(tempDir.resolve("datalake"));

        Path header = datalake.headerPath(1342);
        Path body = datalake.bodyPath(1342);

        Files.createDirectories(header.getParent());

        Files.writeString(header, """
                Title: Pride and Prejudice
                Author: Jane Austen
                Language: English
                Release date: June 1998
                """);

        Files.writeString(body, "the house is big the house");

        return datalake;
    }

    private String getPositions(Connection conn, String term, int bookId)
            throws Exception {

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT positions FROM postings WHERE term = ? AND book_id = ?")) {

            ps.setString(1, term);
            ps.setInt(2, bookId);

            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString("positions");
            }
        }
    }

    private int count(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
