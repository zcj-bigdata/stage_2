package com.zcj.common;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a raw Project Gutenberg text into header and body and extracts the
 * metadata stored in the {@code books} table.
 */
public final class GutenbergBook {

    private static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    private static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

    private static final Pattern TITLE = Pattern.compile("^Title:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern AUTHOR = Pattern.compile("^Author:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern LANGUAGE = Pattern.compile("^Language:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern RELEASE_DATE = Pattern.compile("^Release [Dd]ate:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern YEAR = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");

    /** Gutenberg language names mapped to ISO 639-1 codes. */
    private static final Map<String, String> LANGUAGE_CODES = Map.ofEntries(
            Map.entry("english", "en"), Map.entry("spanish", "es"), Map.entry("french", "fr"),
            Map.entry("german", "de"), Map.entry("italian", "it"), Map.entry("portuguese", "pt"),
            Map.entry("dutch", "nl"), Map.entry("finnish", "fi"), Map.entry("swedish", "sv"),
            Map.entry("danish", "da"), Map.entry("norwegian", "no"), Map.entry("latin", "la"),
            Map.entry("greek", "el"), Map.entry("russian", "ru"), Map.entry("chinese", "zh"),
            Map.entry("japanese", "ja"), Map.entry("polish", "pl"), Map.entry("hungarian", "hu"));

    /** Header and body of a book. */
    public record Parts(String header, String body) {
    }

    /** Metadata of a book; fields are null when they are missing from the header. */
    public record Metadata(String title, String author, String language, Integer year) {
    }

    private GutenbergBook() {
    }

    /**
     * Splits the raw text. The whole line of the start marker is discarded
     * and so is the footer. Throws IllegalArgumentException if the markers are missing.
     */
    public static Parts split(String raw) {
        String text = raw.replace("\r\n", "\n");
        int start = text.indexOf(START_MARKER);
        int end = text.indexOf(END_MARKER);
        if (start < 0 || end < 0 || end < start) {
            throw new IllegalArgumentException("Gutenberg start/end markers not found");
        }
        int bodyStart = text.indexOf('\n', start);
        if (bodyStart < 0 || bodyStart > end) {
            bodyStart = start + START_MARKER.length();
        }
        return new Parts(text.substring(0, start).trim(), text.substring(bodyStart, end).trim());
    }

    public static Metadata parseHeader(String header) {
        String releaseDate = find(RELEASE_DATE, header);
        Integer year = null;
        if (releaseDate != null) {
            Matcher m = YEAR.matcher(releaseDate);
            if (m.find()) {
                year = Integer.parseInt(m.group(1));
            }
        }
        return new Metadata(find(TITLE, header), find(AUTHOR, header),
                languageCode(find(LANGUAGE, header)), year);
    }

    /** "English" -> "en". Unknown names are returned in lowercase. */
    public static String languageCode(String language) {
        if (language == null) {
            return null;
        }
        // Some headers list several languages ("English, French"): keep the first one
        String first = language.split("[,;]")[0].trim().toLowerCase(Locale.ROOT);
        return LANGUAGE_CODES.getOrDefault(first, first);
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
