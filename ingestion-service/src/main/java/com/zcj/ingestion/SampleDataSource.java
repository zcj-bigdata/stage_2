package com.zcj.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code pg{id}.txt} from a local folder (normally {@code sample_data/}),
 * so the pipeline can be tested offline with the same files as Stage 1.
 * Enabled with the environment variable {@code SAMPLE_DATA=sample_data}.
 */
public class SampleDataSource implements BookSource {

    private final Path folder;

    public SampleDataSource(Path folder) {
        this.folder = folder;
    }

    @Override
    public String fetch(int bookId) throws IngestionException {
        Path file = folder.resolve("pg" + bookId + ".txt");
        if (!Files.isRegularFile(file)) {
            throw new IngestionException(IngestionException.Kind.NOT_FOUND,
                    "Book " + bookId + " is not in the sample data folder " + folder);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IngestionException(IngestionException.Kind.UPSTREAM,
                    "Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String describe() {
        return "sample data folder " + folder.toAbsolutePath().normalize();
    }
}
