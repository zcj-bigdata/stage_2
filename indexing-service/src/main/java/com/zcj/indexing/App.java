package com.zcj.indexing;

import com.zcj.common.ApiError;
import com.zcj.common.Config;
import com.zcj.common.Database;
import com.zcj.common.Datalake;
import com.zcj.common.Http;
import io.javalin.Javalin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class App {

    public static void main(String[] args) throws Exception {
        int port = Config.port(Config.INDEXING_PORT);

        try (Connection conn = Database.openFromConfig()) {
            Database.createSchema(conn);
        }

        Javalin app = Http.create("indexing-service");

        app.post("/index/update/{book_id}", ctx -> {
            int bookId = Integer.parseInt(ctx.pathParam("book_id"));

            if (bookId <= 0) {
                ctx.status(400);
                Http.json(ctx, ApiError.invalidParameter(
                        "book_id must be positive"
                ));
                return;
            }

            Datalake datalake = Datalake.fromConfig();

            if (!Files.isRegularFile(datalake.headerPath(bookId))
                    || !Files.isRegularFile(datalake.bodyPath(bookId))) {
                ctx.status(404);
                Http.json(ctx, ApiError.notFound(
                        "Book files not found: " + bookId
                ));
                return;
            }

            try (Connection conn = Database.openFromConfig()) {
                BookIndexer.IndexResult result =
                        BookIndexer.indexBook(conn, datalake, bookId);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "indexed");
                response.put("book_id", result.bookId());
                response.put("total_tokens", result.totalTokens());
                response.put("unique_terms", result.uniqueTerms());

                Http.json(ctx, response);
            }
        });

        app.post("/index/rebuild", ctx -> {
            Datalake datalake = Datalake.fromConfig();
            Path rangesDir = datalake.root().resolve("ranges");

            if (!Files.isDirectory(rangesDir)) {
                ctx.status(500);
                Http.json(ctx, ApiError.internal(
                        "Datalake ranges directory not found: " + rangesDir
                ));
                return;
            }

            Set<Integer> bookIds = new TreeSet<>();

            try (var paths = Files.walk(rangesDir)) {
                paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".header.txt"))
                        .forEach(name -> {
                            String id = name.substring(
                                    0, name.length() - ".header.txt".length()
                            );

                            try {
                                int bookId = Integer.parseInt(id);
                                if (bookId > 0) {
                                    bookIds.add(bookId);
                                }
                            } catch (NumberFormatException ignored) {
                                // Ignore files that do not have a numeric book ID.
                            }
                        });
            }

            int totalTokens = 0;
            int totalPostings = 0;

            try (Connection conn = Database.openFromConfig()) {
                for (int bookId : bookIds) {
                    if (!Files.isRegularFile(datalake.bodyPath(bookId))) {
                        throw new IOException(
                                "Body file not found for book " + bookId
                        );
                    }

                    BookIndexer.IndexResult result =
                            BookIndexer.indexBook(conn, datalake, bookId);

                    totalTokens += result.totalTokens();
                    totalPostings += result.uniqueTerms();
                }

                // Remove records for books no longer present in the datalake.
                removeBooksNotInDatalake(conn, bookIds);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "rebuilt");
            response.put("indexed_books", bookIds.size());
            response.put("total_tokens", totalTokens);
            response.put("total_postings", totalPostings);

            Http.json(ctx, response);
        });

        app.get("/index/status", ctx -> {
            try (Connection conn = Database.openFromConfig()) {
                int indexedBooks = count(conn,
                        "SELECT COUNT(*) FROM books");

                int totalTerms = count(conn,
                        "SELECT COUNT(DISTINCT term) FROM postings");

                int totalPostings = count(conn,
                        "SELECT COUNT(*) FROM postings");

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "ok");
                response.put("indexed_books", indexedBooks);
                response.put("total_terms", totalTerms);
                response.put("total_postings", totalPostings);

                Http.json(ctx, response);
            }
        });

        app.start(port);
    }

    private static int count(Connection conn, String sql)
            throws SQLException {

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            rs.next();
            return rs.getInt(1);
        }
    }

    private static void removeBooksNotInDatalake(
            Connection conn,
            Set<Integer> bookIds
    ) throws SQLException {

        boolean previousAutoCommit = conn.getAutoCommit();

        try {
            conn.setAutoCommit(false);

            if (bookIds.isEmpty()) {
                try (PreparedStatement postings =
                             conn.prepareStatement("DELETE FROM postings")) {
                    postings.executeUpdate();
                }

                try (PreparedStatement books =
                             conn.prepareStatement("DELETE FROM books")) {
                    books.executeUpdate();
                }
            } else {
                String placeholders = String.join(
                        ",", java.util.Collections.nCopies(bookIds.size(), "?")
                );

                String sql = "DELETE FROM books WHERE book_id NOT IN ("
                        + placeholders + ")";

                try (PreparedStatement books = conn.prepareStatement(sql)) {
                    int index = 1;
                    for (int bookId : bookIds) {
                        books.setInt(index++, bookId);
                    }
                    books.executeUpdate();
                }

                try (PreparedStatement postings = conn.prepareStatement("""
                        DELETE FROM postings
                        WHERE book_id NOT IN (SELECT book_id FROM books)
                        """)) {
                    postings.executeUpdate();
                }
            }

            conn.commit();

        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;

        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }
}
