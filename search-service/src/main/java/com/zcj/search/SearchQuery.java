package com.zcj.search;

import com.zcj.common.Tokenizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Validated parameters of {@code GET /search}.
 *
 * <ul>
 *   <li>{@code q} (required): tokenized with the shared tokenizer; a book matches when it
 *       contains every term (AND).</li>
 *   <li>{@code author}: case-insensitive substring of the author.</li>
 *   <li>{@code language}: exact ISO 639-1 code, e.g. {@code en}.</li>
 *   <li>{@code year}: exact year of the Gutenberg release date.</li>
 *   <li>{@code offset} (default 0, minimum 0) and {@code limit} (default 20, 1 to 100).</li>
 * </ul>
 *
 * Invalid values throw IllegalArgumentException, which the service turns into
 * {@code 400 invalid_parameter}.
 */
public record SearchQuery(String q, List<String> terms, String author, String language,
                          Integer year, int offset, int limit) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_TERMS = 10;
    public static final int MAX_OFFSET = 100_000;

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

    /**
     * Builds the query from the raw parameters. {@code param} returns the value of
     * a query parameter or null when it is missing.
     */
    public static SearchQuery parse(Function<String, String> param) {
        String q = trimToNull(param.apply("q"));
        if (q == null) {
            throw new IllegalArgumentException("q is required");
        }
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(Tokenizer.tokenize(q)));
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("q must contain at least one word made of letters a-z");
        }
        if (terms.size() > MAX_TERMS) {
            throw new IllegalArgumentException("q can contain at most " + MAX_TERMS + " different words");
        }

        String author = trimToNull(param.apply("author"));

        String language = trimToNull(param.apply("language"));
        if (language != null) {
            language = language.toLowerCase(Locale.ROOT);
            if (!LANGUAGE.matcher(language).matches()) {
                throw new IllegalArgumentException("language must be an ISO 639-1 code such as 'en'");
            }
        }

        Integer year = parseInt(param.apply("year"), "year", 0, 9999);
        Integer offset = parseInt(param.apply("offset"), "offset", 0, MAX_OFFSET);
        Integer limit = parseInt(param.apply("limit"), "limit", 1, MAX_LIMIT);

        return new SearchQuery(q, terms, author, language, year,
                offset == null ? 0 : offset, limit == null ? DEFAULT_LIMIT : limit);
    }

    /** Filters that were actually requested, in the order of the API. */
    public Map<String, Object> filters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (author != null) {
            filters.put("author", author);
        }
        if (language != null) {
            filters.put("language", language);
        }
        if (year != null) {
            filters.put("year", year);
        }
        return filters;
    }

    private static Integer parseInt(String raw, String name, int min, int max) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
