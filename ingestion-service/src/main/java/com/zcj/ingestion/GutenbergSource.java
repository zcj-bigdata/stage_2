package com.zcj.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Downloads {@code <base>/cache/epub/{id}/pg{id}.txt} from Project Gutenberg.
 *
 * The request timeout (30 s by default) is shorter than the 60 s the Control Module
 * waits for {@code POST /ingest/{id}}, so a slow download is reported as a 502
 * instead of leaving the caller without an answer.
 */
public class GutenbergSource implements BookSource {

    private static final String USER_AGENT = "ZCJ-search-engine/1.0 (ULPGC Big Data course project)";

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient client;

    public GutenbergSource(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String url(int bookId) {
        return baseUrl + "/cache/epub/" + bookId + "/pg" + bookId + ".txt";
    }

    @Override
    public String fetch(int bookId) throws IngestionException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(bookId)))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IngestionException(IngestionException.Kind.UPSTREAM,
                    "Could not download book " + bookId + " from Project Gutenberg: " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestionException(IngestionException.Kind.UPSTREAM,
                    "Download of book " + bookId + " was interrupted", e);
        }

        int status = response.statusCode();
        if (status == 404) {
            throw new IngestionException(IngestionException.Kind.NOT_FOUND,
                    "Book " + bookId + " does not exist in Project Gutenberg");
        }
        if (status != 200) {
            throw new IngestionException(IngestionException.Kind.UPSTREAM,
                    "Project Gutenberg answered HTTP " + status + " for book " + bookId);
        }
        return response.body();
    }

    @Override
    public String describe() {
        return baseUrl;
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
