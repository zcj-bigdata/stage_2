package com.zcj.indexing;

import com.zcj.common.Datalake;
import com.zcj.common.GutenbergBook;
import com.zcj.common.Tokenizer;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class BookIndexer {

    private BookIndexer() {
    }

    public record IndexResult(
            int bookId,
            int totalTokens,
            int uniqueTerms
    ) {
    }

    /**
     * Indexes one book using its own transaction.
     */
    public static IndexResult indexBook(
            Connection conn,
            Datalake datalake,
            int bookId
    ) throws IOException, SQLException {

        boolean previousAutoCommit = conn.getAutoCommit();

        try {
            conn.setAutoCommit(false);

            IndexResult result =
                    indexBookInTransaction(conn, datalake, bookId);

            conn.commit();
            return result;

        } catch (SQLException | IOException | RuntimeException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;

        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Indexes one book without committing or rolling back.
     * The caller is responsible for managing the transaction.
     */
    static IndexResult indexBookInTransaction(
            Connection conn,
            Datalake datalake,
            int bookId
    ) throws IOException, SQLException {

        if (bookId <= 0) {
            throw new IllegalArgumentException(
                    "book_id must be positive"
            );
        }

        var headerPath = datalake.headerPath(bookId);
        var bodyPath = datalake.bodyPath(bookId);

        if (!Files.isRegularFile(headerPath)) {
            throw new IOException("Book header not found: " + bookId);
        }

        if (!Files.isRegularFile(bodyPath)) {
            throw new IOException("Book body not found: " + bookId);
        }

        String header = Files.readString(headerPath);
        String body = Files.readString(bodyPath);

        GutenbergBook.Metadata metadata =
                GutenbergBook.parseHeader(header);

        List<String> tokens = Tokenizer.tokenize(body);

        Map<String, StringJoiner> invertedIndex =
                new LinkedHashMap<>();

        for (int position = 0; position < tokens.size(); position++) {
            String term = tokens.get(position);

            invertedIndex
                    .computeIfAbsent(term, key -> new StringJoiner(","))
                    .add(String.valueOf(position));
        }

        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM postings WHERE book_id = ?")) {

            delete.setInt(1, bookId);
            delete.executeUpdate();
        }

        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO books (
                    book_id, title, author, language, year, path
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(book_id) DO UPDATE SET
                    title = excluded.title,
                    author = excluded.author,
                    language = excluded.language,
                    year = excluded.year,
                    path = excluded.path
                """)) {

            insert.setInt(1, bookId);
            insert.setString(2, metadata.title());
            insert.setString(3, metadata.author());
            insert.setString(4, metadata.language());

            if (metadata.year() == null) {
                insert.setNull(5, java.sql.Types.INTEGER);
            } else {
                insert.setInt(5, metadata.year());
            }

            insert.setString(6, bodyPath.toString());
            insert.executeUpdate();
        }

        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO postings (term, book_id, positions)
                VALUES (?, ?, ?)
                """)) {

            for (Map.Entry<String, StringJoiner> entry
                    : invertedIndex.entrySet()) {

                insert.setString(1, entry.getKey());
                insert.setInt(2, bookId);
                insert.setString(3, entry.getValue().toString());
                insert.addBatch();
            }

            insert.executeBatch();
        }

        return new IndexResult(
                bookId,
                tokens.size(),
                invertedIndex.size()
        );
    }
}
