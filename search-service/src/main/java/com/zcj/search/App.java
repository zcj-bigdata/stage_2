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
 *
 * Configuration (environment variables):
 *   PORT            default 7003
 *   DB_PATH         default datamarts/search.db
 *   SEARCH_THREADS  maximum number of request threads (default 200, Javalin's default)
 *   CORS_ORIGIN     origin allowed for a browser front-end (default: any origin)
 */
public class App {

    public static void main(String[] args) throws Exception {
        start(Config.port(Config.SEARCH_PORT), Config.dbPath(), Config.getInt("SEARCH_THREADS", 200));
    }

    public static Javalin start(int port, String dbPath, int maxThreads) throws Exception {
        // The tables are created here too so that the service starts before the first indexing
        try (Connection conn = Database.open(dbPath)) {
            Database.createSchema(conn);
        }

        SearchController controller = new SearchController(new SearchRepository(dbPath));
        String corsOrigin = Config.get("CORS_ORIGIN", "*");

        Javalin app = Http.create("search-service", config -> {
            config.jetty.threadPool = new org.eclipse.jetty.util.thread.QueuedThreadPool(maxThreads, Math.min(8, maxThreads));
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                if ("*".equals(corsOrigin)) {
                    rule.anyHost();
                } else {
                    rule.allowHost(corsOrigin);
                }
            }));
        });

        app.get("/search", controller::search);
        app.get("/search/filters", controller::filters);
        app.get("/books/{book_id}", controller::book);

        return app.start(port);
    }
}
