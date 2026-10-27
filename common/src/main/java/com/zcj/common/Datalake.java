package com.zcj.common;

import java.nio.file.Path;

/**
 * Range-based datalake layout selected in Stage 1:
 *
 * <pre>
 *   datalake/ranges/001000-001999/1342.header.txt
 *   datalake/ranges/001000-001999/1342.body.txt
 * </pre>
 */
public final class Datalake {

    public static final int BUCKET_SIZE = 1000;

    private final Path root;

    public Datalake(Path root) {
        this.root = root;
    }

    public static Datalake fromConfig() {
        return new Datalake(Path.of(Config.datalakePath()));
    }

    public Path root() {
        return root;
    }

    public Path rangeDir(int bookId) {
        int start = (bookId / BUCKET_SIZE) * BUCKET_SIZE;
        return root.resolve("ranges").resolve(String.format("%06d-%06d", start, start + BUCKET_SIZE - 1));
    }

    public Path headerPath(int bookId) {
        return rangeDir(bookId).resolve(bookId + ".header.txt");
    }

    public Path bodyPath(int bookId) {
        return rangeDir(bookId).resolve(bookId + ".body.txt");
    }
}
