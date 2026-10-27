package com.zcj.common;

/**
 * Configuration shared by all the services. Every value can be overridden with an
 * environment variable, so the deployment can be reproduced on any machine.
 *
 * <pre>
 *   PORT            port of the service           (default: depends on the service)
 *   DATALAKE_PATH   root folder of the datalake   (default: datalake)
 *   DB_PATH         SQLite datamart file          (default: datamarts/search.db)
 *   INGESTION_URL   base URL of Ingestion         (default: http://localhost:7001)
 *   INDEXING_URL    base URL of Indexing          (default: http://localhost:7002)
 *   SEARCH_URL      base URL of Search            (default: http://localhost:7003)
 * </pre>
 */
public final class Config {

    public static final int CONTROL_PORT = 7000;
    public static final int INGESTION_PORT = 7001;
    public static final int INDEXING_PORT = 7002;
    public static final int SEARCH_PORT = 7003;

    private Config() {
    }

    public static String get(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public static int getInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Environment variable " + name + " must be an integer: " + value);
        }
    }

    public static int port(int defaultPort) {
        return getInt("PORT", defaultPort);
    }

    public static String datalakePath() {
        return get("DATALAKE_PATH", "datalake");
    }

    public static String dbPath() {
        return get("DB_PATH", "datamarts/search.db");
    }

    public static String ingestionUrl() {
        return get("INGESTION_URL", "http://localhost:" + INGESTION_PORT);
    }

    public static String indexingUrl() {
        return get("INDEXING_URL", "http://localhost:" + INDEXING_PORT);
    }

    public static String searchUrl() {
        return get("SEARCH_URL", "http://localhost:" + SEARCH_PORT);
    }
}
