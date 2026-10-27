package com.zcj.search;

import com.zcj.common.Config;
import com.zcj.common.Database;
import com.zcj.common.Http;
import io.javalin.Javalin;

import java.sql.Connection;

/**
 * Search Service: keyword search over the inverted index with optional
 * author, language and year filters and pagination.
 *
 * Front-end API:
 *   GET /search?q=&author=&language=&year=&offset=&limit=
 *   GET /books/{book_id}
 *   GET /search/filters
 */
public class App {

    public static void main(String[] args) throws Exception {
        int port = Config.port(Config.SEARCH_PORT);

        try (Connection conn = Database.openFromConfig()) {
            Database.createSchema(conn);
        }

        Javalin app = Http.create("search-service");

        // TODO (Search owner): implement the endpoints
        app.get("/search", Http::notImplemented);
        app.get("/search/filters", Http::notImplemented);
        app.get("/books/{book_id}", Http::notImplemented);

        app.start(port);
    }
}
