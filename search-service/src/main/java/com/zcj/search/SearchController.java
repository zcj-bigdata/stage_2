package com.zcj.search;

import com.zcj.common.ApiError;
import com.zcj.common.Http;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP handlers of the front-end API. */
public class SearchController {

    private final SearchRepository repository;

    public SearchController(SearchRepository repository) {
        this.repository = repository;
    }

    /** GET /search?q=&author=&language=&year=&offset=&limit= */
    public void search(Context ctx) throws Exception {
        SearchQuery query = SearchQuery.parse(ctx::queryParam);
        SearchRepository.Page page = repository.search(query);

        List<Map<String, Object>> results = page.results().stream().map(r -> {
            Map<String, Object> item = book(r.bookId(), r.title(), r.author(), r.language(), r.year());
            item.put("hits", r.hits());
            return item;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.q());
        body.put("terms", query.terms());
        body.put("filters", query.filters());
        body.put("total", page.total());
        body.put("offset", query.offset());
        body.put("limit", query.limit());
        body.put("results", results);
        Http.json(ctx, body);
    }

    /** GET /books/{book_id} */
    public void book(Context ctx) throws Exception {
        int bookId;
        try {
            bookId = Integer.parseInt(ctx.pathParam("book_id"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("book_id must be an integer");
        }
        if (bookId < 1) {
            throw new IllegalArgumentException("book_id must be positive");
        }
        SearchRepository.Book book = repository.findBook(bookId);
        if (book == null) {
            Http.error(ctx, HttpStatus.NOT_FOUND, ApiError.notFound("Book " + bookId + " is not indexed"));
            return;
        }
        Http.json(ctx, book(book.bookId(), book.title(), book.author(), book.language(), book.year()));
    }

    /** GET /search/filters */
    public void filters(Context ctx) throws Exception {
        Http.json(ctx, repository.filterValues());
    }

    private static Map<String, Object> book(int bookId, String title, String author, String language, Integer year) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("book_id", bookId);
        item.put("title", title);
        item.put("author", author);
        item.put("language", language);
        item.put("year", year);
        return item;
    }
}
