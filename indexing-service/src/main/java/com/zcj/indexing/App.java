package com.zcj.indexing;

import com.zcj.common.ApiError;
import com.zcj.common.Config;
import com.zcj.common.Database;
import com.zcj.common.Datalake;
import com.zcj.common.Http;
import io.javalin.Javalin;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

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
    private static final AtomicBoolean REBUILDING = new AtomicBoolean(false);
    private static volatile String LAST_UPDATE = null;
    private static final ReentrantLock OPERATION_LOCK = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        int port = Config.port(Config.INDEXING_PORT);

        try (Connection conn = Database.openFromConfig()) {
            Database.createSchema(conn);
        }

        Javalin app = Http.create("indexing-service");

        app.post("/index/update/{book_id}", ctx -> {

    if (REBUILDING.get()) {
        ctx.status(409);
        Http.json(ctx, new ApiError(
                "rebuild_in_progress",
                "A rebuild is already running"
        ));
        return;
    }

    OPERATION_LOCK.lock();

    try {
        if (REBUILDING.get()) {
            ctx.status(409);
            Http.json(ctx, new ApiError(
                    "rebuild_in_progress",
                    "A rebuild is already running"
            ));
            return;
        }

        int bookId;

        try {
            bookId = Integer.parseInt(ctx.pathParam("book_id"));
        } catch (NumberFormatException e) {
            ctx.status(400);
            Http.json(ctx, ApiError.invalidParameter(
                    "book_id must be an integer"
            ));
            return;
        }

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

            LAST_UPDATE = Instant.now().toString();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("book_id", result.bookId());
            response.put("index", "updated");
            response.put("total_tokens", result.totalTokens());
            response.put("unique_terms", result.uniqueTerms());

            Http.json(ctx, response);
        }

    } finally {
        OPERATION_LOCK.unlock();
    }
});

app.post("/index/rebuild", ctx -> {

    if (!REBUILDING.compareAndSet(false, true)) {
        ctx.status(409);
        Http.json(ctx, new ApiError(
                "rebuild_in_progress",
                "A rebuild is already running"
        ));
        return;
    }

    OPERATION_LOCK.lock();

    try {
        long startedAt = System.nanoTime();

        Datalake datalake = Datalake.fromConfig();
        Path rangesDir = datalake.root().resolve("ranges");

        if (!Files.isDirectory(rangesDir)) {
            ctx.status(404);
            Http.json(ctx, ApiError.notFound(
                    "Datalake ranges directory not found"
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
                            bookIds.add(Integer.parseInt(id));
                        } catch (NumberFormatException ignored) {
                            // Ignore files that do not have a numeric book ID.
                        }
                    });
        }

        int totalTokens = 0;
        int totalPostings = 0;

        try (Connection conn = Database.openFromConfig()) {
            boolean previousAutoCommit = conn.getAutoCommit();

            try {
                conn.setAutoCommit(false);

                for (int bookId : bookIds) {
                    BookIndexer.IndexResult result =
                            BookIndexer.indexBookInTransaction(
                                    conn, datalake, bookId
                            );

                    totalTokens += result.totalTokens();
                    totalPostings += result.uniqueTerms();
                }

                removeBooksNotInDatalake(conn, bookIds);
                conn.commit();

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

        LAST_UPDATE = Instant.now().toString();

        double elapsedSeconds =
                (System.nanoTime() - startedAt) / 1_000_000_000.0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("books_processed", bookIds.size());
        response.put("elapsed_time", String.format(
                java.util.Locale.ROOT, "%.1fs", elapsedSeconds
        ));
        response.put("total_tokens", totalTokens);
        response.put("total_postings", totalPostings);

        Http.json(ctx, response);

    } finally {
        OPERATION_LOCK.unlock();
        REBUILDING.set(false);
    }
});


        app.get("/index/status", ctx -> {
    try (Connection conn = Database.openFromConfig()) {
        int indexedBooks = count(conn, "SELECT COUNT(*) FROM books");
        int totalTerms = count(conn, "SELECT COUNT(DISTINCT term) FROM postings");
        int totalPostings = count(conn, "SELECT COUNT(*) FROM postings");

        Path dbPath = Path.of(Config.dbPath());
        long bytes = Files.exists(dbPath) ? Files.size(dbPath) : 0L;

        double indexSizeMb = Math.round(
                (bytes / (1024.0 * 1024.0)) * 10.0
        ) / 10.0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("books_indexed", indexedBooks);
        response.put("last_update", LAST_UPDATE);
        response.put("index_size_mb", indexSizeMb);

        // Additional statistics for compatibility.
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

    /**
     * Removes books and postings that are no longer present in the datalake.
     * This method participates in the caller's transaction.
     */
    private static void removeBooksNotInDatalake(
            Connection conn,
            Set<Integer> bookIds
    ) throws SQLException {

        List<Integer> staleBookIds = new ArrayList<>();

        try (PreparedStatement select =
                     conn.prepareStatement("SELECT book_id FROM books");
             ResultSet rs = select.executeQuery()) {

            while (rs.next()) {
                int bookId = rs.getInt("book_id");

                if (!bookIds.contains(bookId)) {
                    staleBookIds.add(bookId);
                }
            }
        }

        try (PreparedStatement deletePostings =
                     conn.prepareStatement(
                             "DELETE FROM postings WHERE book_id = ?");
             PreparedStatement deleteBooks =
                     conn.prepareStatement(
                             "DELETE FROM books WHERE book_id = ?")) {

            for (int bookId : staleBookIds) {
                deletePostings.setInt(1, bookId);
                deletePostings.addBatch();

                deleteBooks.setInt(1, bookId);
                deleteBooks.addBatch();
            }

            deletePostings.executeBatch();
            deleteBooks.executeBatch();
        }
    }
}
