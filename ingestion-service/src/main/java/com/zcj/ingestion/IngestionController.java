package com.zcj.ingestion;

import com.zcj.common.ApiError;
import com.zcj.common.Http;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP handlers of the internal Ingestion API (consumer: Control Module).
 * Contract: docs/api/openapi.yaml, tag "Internal: Ingestion".
 */
public class IngestionController {

    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    /** POST /ingest/{book_id} → 200 {"book_id", "status", "path"} | 400 | 404 | 422 | 502 | 500 */
    public void ingest(Context ctx) throws Exception {
        int bookId = bookId(ctx);
        long start = System.nanoTime();
        try {
            IngestionService.Result result = service.ingest(bookId);
            if (IngestionService.DOWNLOADED.equals(result.status())) {
                System.out.printf("[INGESTION] Book %d downloaded in %d ms%n",
                        bookId, (System.nanoTime() - start) / 1_000_000);
            }
            Http.json(ctx, result);
        } catch (IngestionException e) {
            System.out.printf("[INGESTION] Book %d failed (%s): %s%n", bookId, e.kind().code(), e.getMessage());
            ctx.status(e.kind().httpStatus());
            Http.json(ctx, new ApiError(e.kind().code(), e.getMessage()));
        }
    }

    /** GET /ingest/status/{book_id} → 200 {"book_id", "status": "available" | "not_available"} */
    public void status(Context ctx) throws Exception {
        int bookId = bookId(ctx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("book_id", bookId);
        body.put("status", service.isAvailable(bookId) ? "available" : "not_available");
        Http.json(ctx, body);
    }

    /** GET /ingest/list → 200 {"count", "books"} */
    public void list(Context ctx) throws Exception {
        List<Integer> books = service.list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", books.size());
        body.put("books", books);
        Http.json(ctx, body);
    }

    /**
     * Reads the {book_id} path parameter. An IllegalArgumentException becomes
     * 400 invalid_parameter in the common error handler.
     */
    static int bookId(Context ctx) {
        return parseBookId(ctx.pathParam("book_id"));
    }

    static int parseBookId(String value) {
        int bookId;
        try {
            bookId = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("book_id must be an integer");
        }
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be positive");
        }
        return bookId;
    }
}
