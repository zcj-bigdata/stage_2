package com.zcj.ingestion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zcj.common.Datalake;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation of the internal Ingestion API contract (docs/api/openapi.yaml):
 * starts the service on a free port, reading the books from sample_data/.
 */
class IngestionApiTest {

    private static Javalin app;
    private static String baseUrl;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startService() throws Exception {
        Datalake datalake = new Datalake(Files.createTempDirectory("ingestion-api-test").resolve("datalake"));
        app = App.start(0, datalake, new SampleDataSource(Path.of("..", "sample_data")), 16);
        baseUrl = "http://localhost:" + app.port();
    }

    @AfterAll
    static void stopService() {
        app.stop();
    }

    private record Response(int status, JsonObject body) {
    }

    private static Response send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> r = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), JsonParser.parseString(r.body()).getAsJsonObject());
    }

    @Test
    void ingestThenStatusThenList() throws Exception {
        Response first = send("POST", "/ingest/1342");
        assertEquals(200, first.status());
        assertEquals(1342, first.body().get("book_id").getAsInt());
        assertEquals("downloaded", first.body().get("status").getAsString());
        assertTrue(first.body().get("path").getAsString().endsWith("ranges/001000-001999/1342"));

        Response again = send("POST", "/ingest/1342");
        assertEquals(200, again.status());
        assertEquals("already_available", again.body().get("status").getAsString());

        Response status = send("GET", "/ingest/status/1342");
        assertEquals(200, status.status());
        assertEquals("available", status.body().get("status").getAsString());

        Response list = send("GET", "/ingest/list");
        assertEquals(200, list.status());
        assertTrue(list.body().get("count").getAsInt() >= 1);
        assertTrue(list.body().getAsJsonArray("books").toString().contains("1342"));
    }

    @Test
    void bookNotIngestedIsNotAvailable() throws Exception {
        Response r = send("GET", "/ingest/status/5");
        assertEquals(200, r.status());
        assertEquals(5, r.body().get("book_id").getAsInt());
        assertEquals("not_available", r.body().get("status").getAsString());
    }

    @Test
    void unknownBookReturns404() throws Exception {
        Response r = send("POST", "/ingest/99999999");
        assertEquals(404, r.status());
        assertEquals("not_found", r.body().get("error").getAsString());
    }

    @Test
    void invalidBookIdReturns400() throws Exception {
        Response notInteger = send("POST", "/ingest/abc");
        assertEquals(400, notInteger.status());
        assertEquals("invalid_parameter", notInteger.body().get("error").getAsString());
        assertEquals("book_id must be an integer", notInteger.body().get("message").getAsString());

        Response negative = send("GET", "/ingest/status/-3");
        assertEquals(400, negative.status());
        assertEquals("book_id must be positive", negative.body().get("message").getAsString());
    }
}
