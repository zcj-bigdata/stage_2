package com.zcj.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommonTest {

    @Test
    void tokenizerKeepsOnlyLowercaseLetters() {
        assertEquals(List.of("mr", "darcy", "s", "house"), Tokenizer.tokenize("Mr. DARCY's house!"));
        assertEquals(List.of(), Tokenizer.tokenize("123 ... ---"));
    }

    @Test
    void splitDiscardsTheStartMarkerLine() {
        String raw = "Title: X\r\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\r\nHello world\r\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK X ***\r\nfooter";
        GutenbergBook.Parts parts = GutenbergBook.split(raw);
        assertEquals("Title: X", parts.header());
        assertEquals("Hello world", parts.body());
    }

    @Test
    void splitRejectsTextsWithoutMarkers() {
        assertThrows(IllegalArgumentException.class, () -> GutenbergBook.split("no markers"));
    }

    @Test
    void parsesMetadataOfASampleBook() throws Exception {
        Path sample = Path.of("..", "sample_data", "pg1342.txt");
        GutenbergBook.Parts parts = GutenbergBook.split(Files.readString(sample));
        GutenbergBook.Metadata meta = GutenbergBook.parseHeader(parts.header());

        assertEquals("Pride and Prejudice", meta.title());
        assertEquals("Jane Austen", meta.author());
        assertEquals("en", meta.language());
        assertEquals(1998, meta.year());
        // Same tokens as in Stage 1: darcy appears 432 times in the body
        assertEquals(432, Tokenizer.tokenize(parts.body()).stream().filter("darcy"::equals).count());
    }

    @Test
    void rangeLayoutGroupsBooksByThousands() {
        Datalake datalake = new Datalake(Path.of("datalake"));
        assertEquals(Path.of("datalake", "ranges", "001000-001999", "1342.body.txt"), datalake.bodyPath(1342));
        assertEquals(Path.of("datalake", "ranges", "000000-000999", "84.header.txt"), datalake.headerPath(84));
    }
}
