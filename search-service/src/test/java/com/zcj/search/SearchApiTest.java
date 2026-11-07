package com.zcj.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation of the front-end API contract: starts the Search Service on a free port
 * over a datamart seeded with the five sample books and checks the HTTP responses.
 */
class SearchApiTest {

    private static Javalin app;
    private static String baseUrl;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startService() throws Exception {
        Path db = Files.createTempDirectory("search-test").resolve("search.db");
        SampleDataSeeder.seed(Path.of("..", "sample_data"), db.toString());
        app = App.start(0, db.toString(), 16);
        baseUrl = "http://localhost:" + app.port();
    }

    @AfterAll
    static void stopService() {
        app.stop();
    }

    private record Response(int status, JsonObject body) {
    }

    private static Response get(String pathAndQuery) throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), JsonParser.parseString(r.body()).getAsJsonObject());
    }

    private static List<Integer> ids(JsonObject body) {
        List<Integer> ids = new ArrayList<>();
        for (var e : body.getAsJsonArray("results")) {
            ids.add(e.getAsJsonObject().get("book_id").getAsInt());
        }
        return ids;
    }

    @Test
    void keywordSearch() throws Exception {
        Response r = get("/search?q=darcy");
        assertEquals(200, r.status());
        assertEquals(1, r.body().get("total").getAsInt());
        JsonObject book = r.body().getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals(1342, book.get("book_id").getAsInt());
        assertEquals("Pride and Prejudice", book.get("title").getAsString());
        assertEquals("Jane Austen", book.get("author").getAsString());
        assertEquals("en", book.get("language").getAsString());
        assertEquals(1998, book.get("year").getAsInt());
        assertEquals(432, book.get("hits").getAsInt());  // same count as in Stage 1
    }

    @Test
    void severalWordsAreCombinedWithAnd() throws Exception {
        Response r = get("/search?q=monster%20creature");
        assertEquals(200, r.status());
        assertEquals(List.of(84, 98), ids(r.body()));  // ordered by number of hits
    }

    @Test
    void singleFilters() throws Exception {
        assertEquals(List.of(1342), ids(get("/search?q=love&author=austen").body()));
        assertEquals(5, get("/search?q=the&language=en").body().get("total").getAsInt());
        assertEquals(List.of(1342), ids(get("/search?q=the&year=1998").body()));
    }

    @Test
    void combinedFilters() throws Exception {
        Response r = get("/search?q=the&author=doyle&language=en&year=1999");
        assertEquals(List.of(1661), ids(r.body()));
        JsonObject filters = r.body().getAsJsonObject("filters");
        assertEquals("doyle", filters.get("author").getAsString());
        assertEquals("en", filters.get("language").getAsString());
        assertEquals(1999, filters.get("year").getAsInt());
    }

    @Test
    void validQueryWithoutMatchesReturnsEmptyResults() throws Exception {
        Response r = get("/search?q=zzzzqx");
        assertEquals(200, r.status());
        assertEquals(0, r.body().get("total").getAsInt());
        assertEquals(0, r.body().getAsJsonArray("results").size());

        Response filtered = get("/search?q=darcy&language=fr");
        assertEquals(200, filtered.status());
        assertEquals(0, filtered.body().get("total").getAsInt());
    }

    @Test
    void pagination() throws Exception {
        Response all = get("/search?q=the&limit=100");
        assertEquals(5, all.body().get("total").getAsInt());
        List<Integer> order = ids(all.body());

        Response page1 = get("/search?q=the&offset=0&limit=2");
        Response page2 = get("/search?q=the&offset=2&limit=2");
        Response page3 = get("/search?q=the&offset=4&limit=2");
        Response beyond = get("/search?q=the&offset=10&limit=2");

        assertEquals(order.subList(0, 2), ids(page1.body()));
        assertEquals(order.subList(2, 4), ids(page2.body()));
        assertEquals(order.subList(4, 5), ids(page3.body()));
        assertEquals(List.of(), ids(beyond.body()));
        assertEquals(5, beyond.body().get("total").getAsInt());  // total ignores the page
        assertEquals(2, page1.body().get("limit").getAsInt());
        assertEquals(20, get("/search?q=the").body().get("limit").getAsInt());  // default
    }

    @Test
    void invalidParametersReturn400() throws Exception {
        String[] invalid = {
                "/search",                      // q missing
                "/search?q=",                   // q empty
                "/search?q=123",                // no letters
                "/search?q=the&limit=0",
                "/search?q=the&limit=101",
                "/search?q=the&limit=abc",
                "/search?q=the&offset=-1",
                "/search?q=the&year=nineteen",
                "/search?q=the&language=english",
        };
        for (String path : invalid) {
            Response r = get(path);
            assertEquals(400, r.status(), path);
            assertEquals("invalid_parameter", r.body().get("error").getAsString(), path);
            assertTrue(r.body().has("message"), path);
        }
    }

    @Test
    void bookDetails() throws Exception {
        Response r = get("/books/84");
        assertEquals(200, r.status());
        assertEquals("Frankenstein; or, the modern prometheus", r.body().get("title").getAsString());

        Response missing = get("/books/999999");
        assertEquals(404, missing.status());
        assertEquals("not_found", missing.body().get("error").getAsString());

        assertEquals(400, get("/books/abc").status());
    }

    @Test
    void filterValues() throws Exception {
        Response r = get("/search/filters");
        assertEquals(200, r.status());
        JsonArray languages = r.body().getAsJsonArray("languages");
        assertEquals(1, languages.size());
        assertEquals("en", languages.get(0).getAsJsonObject().get("value").getAsString());
        assertEquals(5, languages.get(0).getAsJsonObject().get("books").getAsInt());
        assertEquals(5, r.body().getAsJsonArray("authors").size());
    }
}
