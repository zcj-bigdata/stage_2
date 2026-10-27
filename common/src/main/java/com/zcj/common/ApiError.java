package com.zcj.common;

/**
 * Error body returned by every service:
 * {@code {"error": "invalid_parameter", "message": "limit must be between 1 and 100"}}
 */
public record ApiError(String error, String message) {

    public static ApiError invalidParameter(String message) {
        return new ApiError("invalid_parameter", message);
    }

    public static ApiError notFound(String message) {
        return new ApiError("not_found", message);
    }

    public static ApiError internal(String message) {
        return new ApiError("internal_error", message);
    }

    public static ApiError notImplemented(String message) {
        return new ApiError("not_implemented", message);
    }
}
