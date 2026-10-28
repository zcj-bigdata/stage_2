package com.zcj.search;

import com.zcj.common.Config;
import com.zcj.common.Database;
import com.zcj.common.GutenbergBook;
import com.zcj.common.Tokenizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Development tool: fills the datamart with the books of {@code sample_data/} using the
 * shared schema, so that the Search Service can be developed and tested without the
 * Indexing Service. The real pipeline uses the Indexing Service instead.
 *
 * <pre>
 *   java -cp search-service/target/search-service-1.0.0.jar com.zcj.search.SampleDataSeeder [sample_data]
 * </pre>
 *
 * Each book is written in its own transaction with INSERT OR REPLACE, so running the
 * tool several times does not create duplicates.
 */
public class SampleDataSeeder {

    public static void main(String[] args) throws Exception {
        Path sampleDir = Path.of(args.length > 0 ? args[0] : "sample_data");
        int books = seed(sampleDir, Config.dbPath());
        System.out.println("Seeded " + books + " books into " + Config.dbPath());
    }

    /** Indexes every pg&lt;id&gt;.txt file of the folder. Returns the number of books. */
    public static int seed(Path sampleDir, String dbPath) throws Exception {
        List<Path> files;
        try (Stream<Path> stream = Files.list(sampleDir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("pg\\d+\\.txt")).sorted().toList();
        }

        try (Connection conn = Database.open(dbPath)) {
            Database.createSchema(conn);
            for (Path file : files) {
                String name = file.getFileName().toString();
                int bookId = Integer.parseInt(name.substring(2, name.length() - 4));
                index(conn, bookId, Files.readString(file), file.toString());
            }
        }
        return files.size();
    }

    private static void index(Connection conn, int bookId, String raw, String path) throws Exception {
        GutenbergBook.Parts parts = GutenbergBook.split(raw);
        GutenbergBook.Metadata meta = GutenbergBook.parseHeader(parts.header());

        Map<String, List<Integer>> postings = new HashMap<>();
        List<String> tokens = Tokenizer.tokenize(parts.body());
        for (int i = 0; i < tokens.size(); i++) {
            postings.computeIfAbsent(tokens.get(i), k -> new ArrayList<>()).add(i);
        }

        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO books(book_id, title, author, language, year, path) VALUES (?,?,?,?,?,?)")) {
                ps.setInt(1, bookId);
                ps.setString(2, meta.title());
                ps.setString(3, meta.author());
                ps.setString(4, meta.language());
                ps.setObject(5, meta.year());
                ps.setString(6, path);
                ps.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM postings WHERE book_id = ?")) {
                del.setInt(1, bookId);
                del.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO postings(term, book_id, positions) VALUES (?,?,?)")) {
                for (Map.Entry<String, List<Integer>> e : postings.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setInt(2, bookId);
                    ps.setString(3, join(e.getValue()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static String join(List<Integer> positions) {
        StringBuilder sb = new StringBuilder();
        for (int p : positions) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
