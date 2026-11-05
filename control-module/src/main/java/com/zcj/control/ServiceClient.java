package com.zcj.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Small HTTP client used by the Control Module to call the internal APIs.
 * A timeout or a connection error is thrown as an IOException
 * ({@link java.net.http.HttpTimeoutException} for timeouts); every HTTP answer,
 * including 4xx and 5xx, is returned as a {@link Reply}.
 */
public class ServiceClient {

    /** Status code and JSON body of an answer. The body is empty if it was not a JSON object. */
    public record Reply(int status, JsonObject body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Field of the body as text, or null. */
        public String field(String name) {
            JsonElement value = body.get(name);
            return value == null || value.isJsonNull() ? null : value.getAsString();
        }

        /** "409 rebuild_in_progress: ..." for the logs and the failed_books file. */
        public String describe() {
            String error = field("error");
            String message = field("message");
            StringBuilder text = new StringBuilder(String.valueOf(status));
            if (error != null) {
                text.append(' ').append(error);
            }
            if (message != null) {
                text.append(": ").append(message);
            }
            return text.toString();
        }
    }

    private final HttpClient client;

    public ServiceClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Reply post(String url, Duration timeout) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    public Reply get(String url, Duration timeout) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build());
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), parse(response.body()));
    }

    private static JsonObject parse(String text) {
        try {
            JsonElement element = JsonParser.parseString(text == null ? "" : text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return new JsonObject();
        }
    }
}
