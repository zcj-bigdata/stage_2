package com.zcj.common;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Creates the Javalin server of a service with the conventions shared by all of
 * them: JSON responses, a {@code GET /status} health endpoint and the common
 * error format of {@link ApiError}.
 */
public final class Http {

    private Http() {
    }

    public static Javalin create(String serviceName) {
        return create(serviceName, config -> {
        });
    }

    /**
     * Same as {@link #create(String)}, with extra service-specific configuration
     * (thread pool size, CORS...).
     */
    public static Javalin create(String serviceName, Consumer<JavalinConfig> extraConfig) {
        Instant startedAt = Instant.now();

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            config.showJavalinBanner = false;
            extraConfig.accept(config);
        });

        app.get("/status", ctx -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("service", serviceName);
            status.put("status", "running");
            status.put("started_at", startedAt.toString());
            json(ctx, status);
        });

        app.exception(IllegalArgumentException.class, (e, ctx) ->
                error(ctx, HttpStatus.BAD_REQUEST, ApiError.invalidParameter(e.getMessage())));
        // Javalin's own errors (unknown route, wrong method...) also use the common format
        app.exception(HttpResponseException.class, (e, ctx) -> {
            HttpStatus status = HttpStatus.forStatus(e.getStatus());
            String code = status == HttpStatus.NOT_FOUND ? "not_found"
                    : status.getMessage().toLowerCase().replace(' ', '_');
            error(ctx, status, new ApiError(code, e.getMessage()));
        });
        app.exception(Exception.class, (e, ctx) ->
                error(ctx, HttpStatus.INTERNAL_SERVER_ERROR, ApiError.internal(e.getMessage())));

        return app;
    }

    public static void json(Context ctx, Object body) {
        ctx.contentType("application/json").result(Json.toJson(body));
    }

    public static void error(Context ctx, HttpStatus status, ApiError error) {
        ctx.status(status);
        json(ctx, error);
    }

    /** Placeholder used by the endpoints that are not implemented yet. */
    public static void notImplemented(Context ctx) {
        error(ctx, HttpStatus.NOT_IMPLEMENTED,
                ApiError.notImplemented(ctx.method() + " " + ctx.path() + " is not implemented yet"));
    }
}
