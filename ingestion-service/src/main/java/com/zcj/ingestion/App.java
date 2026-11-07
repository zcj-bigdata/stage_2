package com.zcj.ingestion;

import com.zcj.common.Config;
import com.zcj.common.Datalake;
import com.zcj.common.Http;
import io.javalin.Javalin;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Ingestion Service: downloads books from Project Gutenberg, splits them into
 * header and body and stores them in the datalake (range-based layout).
 *
 * Internal API (consumer: Control Module):
 *   POST /ingest/{book_id}         download and store a book
 *   GET  /ingest/status/{book_id}  whether the book is available in the datalake
 *   GET  /ingest/list              books stored in the datalake
 *
 * Configuration (environment variables):
 *   PORT                       default 7001
 *   DATALAKE_PATH              default datalake
 *   INGESTION_THREADS          maximum number of request threads (default 200)
 *   SAMPLE_DATA                folder with pg{id}.txt files; if set, books are read from it
 *                              instead of being downloaded (offline tests)
 *   GUTENBERG_URL              default https://www.gutenberg.org
 *   GUTENBERG_TIMEOUT_SECONDS  timeout of one download (default 30)
 */
public class App {

    public static void main(String[] args) throws Exception {
        start(Config.port(Config.INGESTION_PORT), Datalake.fromConfig(), sourceFromConfig(),
                Config.getInt("INGESTION_THREADS", 200));
    }

    public static BookSource sourceFromConfig() {
        String sampleData = Config.get("SAMPLE_DATA", "");
        if (!sampleData.isEmpty()) {
            return new SampleDataSource(Path.of(sampleData));
        }
        return new GutenbergSource(Config.get("GUTENBERG_URL", "https://www.gutenberg.org"),
                Duration.ofSeconds(Config.getInt("GUTENBERG_TIMEOUT_SECONDS", 30)));
    }

    public static Javalin start(int port, Datalake datalake, BookSource source, int maxThreads) throws Exception {
        IngestionController controller = new IngestionController(new IngestionService(datalake, source));

        Javalin app = Http.create("ingestion-service", config ->
                config.jetty.threadPool = new org.eclipse.jetty.util.thread.QueuedThreadPool(
                        maxThreads, Math.min(8, maxThreads)));

        app.post("/ingest/{book_id}", controller::ingest);
        app.get("/ingest/status/{book_id}", controller::status);
        app.get("/ingest/list", controller::list);

        app.start(port);
        System.out.printf("[INGESTION] Listening on port %d, datalake %s, source %s, max %d threads%n",
                app.port(), datalake.root().toAbsolutePath().normalize(), source.describe(), maxThreads);
        return app;
    }
}
