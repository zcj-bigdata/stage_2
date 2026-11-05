package com.zcj.control;

import com.zcj.common.Config;
import com.zcj.common.Http;
import io.javalin.Javalin;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control Module: orchestrates the pipeline. For each new book it calls
 * Ingestion (POST /ingest/{id}), and once the book is available it calls
 * Indexing (POST /index/update/{id}), recording the progress in control files.
 *
 * The workflow runs in the background as soon as the process starts.
 * Operational endpoints (consumer: operators):
 *   GET /status           health check
 *   GET /control/status   {"downloaded", "indexed", "failed", "pending"}
 *   GET /control/metrics  coordination cost measured since start-up
 *
 * Configuration: see {@link ControlSettings}; PORT defaults to 7000.
 */
public class App {

    public static void main(String[] args) throws Exception {
        ControlSettings settings = ControlSettings.fromConfig();
        start(Config.port(Config.CONTROL_PORT), settings, true);
    }

    /** Starts the HTTP server and, if {@code runLoop}, the orchestration loop. */
    public static Javalin start(int port, ControlSettings settings, boolean runLoop) throws Exception {
        ControlState state = new ControlState(Path.of(settings.controlPath()));
        ControlMetrics metrics = new ControlMetrics();
        ServiceClient client = new ServiceClient();
        Orchestrator orchestrator = new Orchestrator(settings, state, client, metrics);
        ControlLoop loop = new ControlLoop(settings, orchestrator, state, client);

        Javalin app = Http.create("control-module");

        app.get("/control/status", ctx -> {
            ControlState.Snapshot s = state.snapshot();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("downloaded", s.downloaded());
            body.put("indexed", s.indexed());
            body.put("failed", s.failed());
            body.put("pending", s.pending());
            Http.json(ctx, body);
        });

        app.get("/control/metrics", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("running", loop.isRunning());
            body.put("workers", settings.workers());
            body.putAll(metrics.toMap());
            Http.json(ctx, body);
        });

        app.start(port);
        System.out.printf("[CONTROL] Listening on port %d, control files in %s, Ingestion at %s, Indexing at %s%n",
                app.port(), Path.of(settings.controlPath()).toAbsolutePath().normalize(),
                settings.ingestionUrl(), settings.indexingUrl());

        if (runLoop) {
            loop.start();
            Runtime.getRuntime().addShutdownHook(new Thread(loop::stop));
        }
        return app;
    }
}
