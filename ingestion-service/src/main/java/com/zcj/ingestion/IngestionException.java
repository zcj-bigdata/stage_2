package com.zcj.ingestion;

/**
 * Error of an ingestion. The kind decides the HTTP status and whether the
 * caller may retry (see docs/api/openapi.yaml):
 *
 * <pre>
 *   NOT_FOUND    404 not_found       the book does not exist         permanent
 *   INVALID_BOOK 422 invalid_book    no Gutenberg start/end markers  permanent
 *   UPSTREAM     502 upstream_error  Gutenberg could not be reached  transient, retry later
 * </pre>
 */
public class IngestionException extends Exception {

    public enum Kind {
        NOT_FOUND(404, "not_found"),
        INVALID_BOOK(422, "invalid_book"),
        UPSTREAM(502, "upstream_error");

        private final int httpStatus;
        private final String code;

        Kind(int httpStatus, String code) {
            this.httpStatus = httpStatus;
            this.code = code;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String code() {
            return code;
        }
    }

    private final Kind kind;

    public IngestionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public IngestionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
