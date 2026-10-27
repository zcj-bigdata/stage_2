package com.zcj.ingestion;

import com.zcj.common.Config;
import com.zcj.common.Http;
import io.javalin.Javalin;

/**
 * Ingestion Service: downloads books from Project Gutenberg, splits them into
 * header and body and stores them in the datalake (range-based layout).
 *
 * Internal API (consumer: Control Module):
 *   POST /ingest/{book_id}         download and store a book
 *   GET  /ingest/status/{book_id}  whether the book is available in the datalake
 *   GET  /ingest/list              books stored in the datalake
 */
public class App {

    public static void main(String[] args) {
        int port = Config.port(Config.INGESTION_PORT);

        Javalin app = Http.create("ingestion-service");

        // TODO (Ingestion owner): implement the endpoints
        app.post("/ingest/{book_id}", Http::notImplemented);
        app.get("/ingest/status/{book_id}", Http::notImplemented);
        app.get("/ingest/list", Http::notImplemented);

        app.start(port);
    }
}
