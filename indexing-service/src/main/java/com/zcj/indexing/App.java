package com.zcj.indexing;

import com.zcj.common.Config;
import com.zcj.common.Database;
import com.zcj.common.Http;
import io.javalin.Javalin;

import java.sql.Connection;

/**
 * Indexing Service: reads books from the datalake, extracts their metadata and
 * updates the inverted index in the SQLite datamart.
 *
 * Internal API (consumer: Control Module):
 *   POST /index/update/{book_id}   index one book (idempotent)
 *   POST /index/rebuild            rebuild metadata and index from the whole datalake
 *   GET  /index/status             indexing statistics
 */
public class App {

    public static void main(String[] args) throws Exception {
        int port = Config.port(Config.INDEXING_PORT);

        // Make sure the shared tables exist before accepting requests
        try (Connection conn = Database.openFromConfig()) {
            Database.createSchema(conn);
        }

        Javalin app = Http.create("indexing-service");

        // TODO (Indexing owner): implement the endpoints
        app.post("/index/update/{book_id}", Http::notImplemented);
        app.post("/index/rebuild", Http::notImplemented);
        app.get("/index/status", Http::notImplemented);

        app.start(port);
    }
}
