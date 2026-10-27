package com.zcj.control;

import com.zcj.common.Config;
import com.zcj.common.Http;
import io.javalin.Javalin;

/**
 * Control Module: orchestrates the pipeline. For each new book it calls
 * Ingestion (POST /ingest/{id}), waits until the book is available, calls
 * Indexing (POST /index/update/{id}) and records the progress persistently.
 *
 * It only exposes a status endpoint; the workflow runs in the background.
 */
public class App {

    public static void main(String[] args) {
        int port = Config.port(Config.CONTROL_PORT);

        Javalin app = Http.create("control-module");

        // TODO (Control owner): workflow state endpoint and orchestration loop
        app.get("/control/status", Http::notImplemented);

        app.start(port);

        System.out.println("Ingestion at " + Config.ingestionUrl() + ", Indexing at " + Config.indexingUrl());
    }
}
