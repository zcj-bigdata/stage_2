package com.zcj.control;

import com.zcj.common.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of the Control Module (environment variables, defaults in brackets):
 *
 * <pre>
 *   INGESTION_URL            Ingestion Service              [http://localhost:7001]
 *   INDEXING_URL             Indexing Service               [http://localhost:7002]
 *   CONTROL_PATH             folder of the control files    [control]
 *   CONTROL_BOOKS            ids to process: "11,84,1342", "sample" (the 5 sample books)
 *                            or empty for random ids from 1 to CONTROL_TOTAL_BOOKS   []
 *   CONTROL_TOTAL_BOOKS      highest random id              [70000]
 *   CONTROL_MAX_BOOKS        stop after this many books are indexed in this run;
 *                            0 = no limit                    [10]
 *   CONTROL_WORKERS          worker threads, each processes a different book  [1]
 *   CONTROL_INTERVAL_MS      pause after a step with nothing to do or a transient
 *                            failure                         [1000]
 *   CONTROL_MAX_ATTEMPTS     attempts of one call before giving up for now  [3]
 *   CONTROL_BACKOFF_MS       wait before the 2nd attempt; doubled every time [1000]
 *   INGEST_TIMEOUT_SECONDS   timeout of POST /ingest/{id}    [60]
 *   INDEX_TIMEOUT_SECONDS    timeout of POST /index/update/{id} [120]
 * </pre>
 */
public record ControlSettings(
        String ingestionUrl,
        String indexingUrl,
        String controlPath,
        List<Integer> bookIds,
        int totalBooks,
        int maxBooks,
        int workers,
        Duration interval,
        int maxAttempts,
        Duration backoff,
        Duration ingestTimeout,
        Duration indexTimeout,
        Duration statusTimeout) {

    public static final List<Integer> SAMPLE_BOOKS = List.of(11, 84, 98, 1342, 1661);

    public static ControlSettings fromConfig() {
        return new ControlSettings(
                trimSlash(Config.ingestionUrl()),
                trimSlash(Config.indexingUrl()),
                Config.get("CONTROL_PATH", "control"),
                parseBookIds(Config.get("CONTROL_BOOKS", "")),
                positive("CONTROL_TOTAL_BOOKS", Config.getInt("CONTROL_TOTAL_BOOKS", 70000)),
                Math.max(0, Config.getInt("CONTROL_MAX_BOOKS", 10)),
                positive("CONTROL_WORKERS", Config.getInt("CONTROL_WORKERS", 1)),
                Duration.ofMillis(Math.max(0, Config.getInt("CONTROL_INTERVAL_MS", 1000))),
                positive("CONTROL_MAX_ATTEMPTS", Config.getInt("CONTROL_MAX_ATTEMPTS", 3)),
                Duration.ofMillis(Math.max(0, Config.getInt("CONTROL_BACKOFF_MS", 1000))),
                Duration.ofSeconds(positive("INGEST_TIMEOUT_SECONDS", Config.getInt("INGEST_TIMEOUT_SECONDS", 60))),
                Duration.ofSeconds(positive("INDEX_TIMEOUT_SECONDS", Config.getInt("INDEX_TIMEOUT_SECONDS", 120))),
                Duration.ofSeconds(10));
    }

    /** Empty list = random ids. */
    static List<Integer> parseBookIds(String value) {
        List<Integer> ids = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        if (value.trim().equalsIgnoreCase("sample")) {
            return new ArrayList<>(SAMPLE_BOOKS);
        }
        for (String part : value.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                int id = Integer.parseInt(part.trim());
                if (id <= 0) {
                    throw new NumberFormatException();
                }
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CONTROL_BOOKS must be a list of positive integers: " + value);
            }
        }
        return ids;
    }

    private static int positive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
