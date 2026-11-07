package com.zcj.ingestion;

import com.zcj.common.Datalake;
import com.zcj.common.GutenbergBook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

/**
 * Stores books in the range-based datalake shared with the Indexing Service.
 *
 * <h2>When is a book "available"?</h2>
 * Each file is first written to a temporary name ({@code 1342.body.txt.<uuid>.tmp}),
 * flushed to disk and then renamed atomically to its final name. The body is renamed
 * first and the header last, so the header acts as the completion marker:
 * <ul>
 *   <li>a reader (the Indexing Service) never sees a half-written file, and</li>
 *   <li>if both final files exist, the book is complete.</li>
 * </ul>
 * After the rename the book id is appended to {@code <datalake>/ingested_books.txt}
 * (the persistent record) and only then the book is reported as {@code available}.
 *
 * <h2>Concurrency</h2>
 * Requests for different books run in parallel (one Javalin thread each). Requests
 * for the same book are serialized with a per-book lock, so a book is downloaded only
 * once and the second caller gets {@code already_available}. The lock only protects
 * the threads of this process: two replicas could download the same book at the same
 * time, but thanks to the atomic renames the datalake still ends with a single, complete
 * copy (the last rename wins and both copies are identical).
 *
 * <h2>Recovery</h2>
 * On start-up the service deletes leftover {@code .tmp} files (downloads interrupted by
 * a crash) and records any complete book that was renamed but not recorded yet.
 */
public class IngestionService {

    /** Persistent record of the ingested books, one id per line, inside the datalake root. */
    public static final String REGISTRY_FILE = "ingested_books.txt";

    public static final String DOWNLOADED = "downloaded";
    public static final String ALREADY_AVAILABLE = "already_available";

    private static final String TMP_SUFFIX = ".tmp";
    private static final String HEADER_SUFFIX = ".header.txt";

    /** Response of {@code POST /ingest/{book_id}}: {"book_id", "status", "path"}. */
    public record Result(int bookId, String status, String path) {
    }

    private final Datalake datalake;
    private final BookSource source;
    private final Path registry;

    private final Set<Integer> available = new ConcurrentSkipListSet<>();
    private final ConcurrentHashMap<Integer, Object> bookLocks = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    public IngestionService(Datalake datalake, BookSource source) throws IOException {
        this.datalake = datalake;
        this.source = source;
        this.registry = datalake.root().resolve(REGISTRY_FILE);
        Files.createDirectories(datalake.root());
        recover();
    }

    /**
     * Downloads the book and stores it, or returns {@code already_available} without
     * downloading it again.
     */
    public Result ingest(int bookId) throws IngestionException, IOException {
        if (isAvailable(bookId)) {
            return result(bookId, ALREADY_AVAILABLE);
        }
        Object lock = bookLocks.computeIfAbsent(bookId, id -> new Object());
        synchronized (lock) {
            // Another request may have finished this book while we were waiting
            if (isAvailable(bookId)) {
                return result(bookId, ALREADY_AVAILABLE);
            }

            String raw = source.fetch(bookId);
            GutenbergBook.Parts parts;
            try {
                parts = GutenbergBook.split(raw);
            } catch (IllegalArgumentException e) {
                throw new IngestionException(IngestionException.Kind.INVALID_BOOK,
                        "Gutenberg start/end markers not found in book " + bookId);
            }

            Files.createDirectories(datalake.rangeDir(bookId));
            writeAtomically(datalake.bodyPath(bookId), parts.body());
            writeAtomically(datalake.headerPath(bookId), parts.header());   // header last = complete
            markAvailable(bookId);
            return result(bookId, DOWNLOADED);
        }
    }

    /**
     * True when the ingestion of the book has completed. Books written by another
     * replica sharing the same datalake are detected through their files.
     */
    public boolean isAvailable(int bookId) throws IOException {
        if (available.contains(bookId)) {
            return true;
        }
        if (isComplete(bookId)) {
            markAvailable(bookId);
            return true;
        }
        return false;
    }

    /** Ids of every available book, in ascending order. */
    public List<Integer> list() throws IOException {
        // Rescanning the range folders also picks up books stored by other replicas;
        // with the range layout this is a few folders, not one folder per book.
        for (int bookId : scanCompleteBooks()) {
            if (!available.contains(bookId)) {
                markAvailable(bookId);
            }
        }
        return new ArrayList<>(available);
    }

    /** Folder of the book as returned to the caller, e.g. {@code datalake/ranges/001000-001999/1342}. */
    public String pathOf(int bookId) {
        return datalake.rangeDir(bookId).resolve(String.valueOf(bookId)).toString().replace('\\', '/');
    }

    public BookSource source() {
        return source;
    }

    // ------------------------------------------------------------------ internals

    private Result result(int bookId, String status) {
        return new Result(bookId, status, pathOf(bookId));
    }

    private boolean isComplete(int bookId) {
        return Files.isRegularFile(datalake.headerPath(bookId)) && Files.isRegularFile(datalake.bodyPath(bookId));
    }

    private void markAvailable(int bookId) throws IOException {
        synchronized (registryLock) {
            if (available.contains(bookId)) {
                return;
            }
            appendLine(registry, String.valueOf(bookId));
            available.add(bookId);
        }
    }

    private void recover() throws IOException {
        Path ranges = datalake.root().resolve("ranges");
        int removedTmp = 0;
        if (Files.isDirectory(ranges)) {
            try (Stream<Path> files = Files.walk(ranges)) {
                for (Path tmp : files.filter(p -> p.getFileName().toString().endsWith(TMP_SUFFIX)).toList()) {
                    Files.deleteIfExists(tmp);
                    removedTmp++;
                }
            }
        }

        // Books in the record whose files are still there
        Set<Integer> recorded = new TreeSet<>();
        if (Files.isRegularFile(registry)) {
            for (String line : Files.readAllLines(registry, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    int bookId = Integer.parseInt(value);
                    if (isComplete(bookId)) {
                        recorded.add(bookId);
                    }
                } catch (NumberFormatException ignored) {
                    // A line cut by a crash in the middle of an append
                }
            }
            terminateLastLine(registry);
        }
        available.addAll(recorded);

        // Complete books renamed just before a crash, before they were recorded
        int recovered = 0;
        for (int bookId : scanCompleteBooks()) {
            if (!available.contains(bookId)) {
                markAvailable(bookId);
                recovered++;
            }
        }

        System.out.printf("[INGESTION] %d books available in %s (%d recovered, %d temporary files removed)%n",
                available.size(), datalake.root(), recovered, removedTmp);
    }

    /** Books whose header and body are both present in the datalake. */
    private Set<Integer> scanCompleteBooks() throws IOException {
        Set<Integer> ids = new TreeSet<>();
        Path ranges = datalake.root().resolve("ranges");
        if (!Files.isDirectory(ranges)) {
            return ids;
        }
        try (Stream<Path> files = Files.walk(ranges, 2)) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(HEADER_SUFFIX))
                    .forEach(name -> {
                        try {
                            int bookId = Integer.parseInt(name.substring(0, name.length() - HEADER_SUFFIX.length()));
                            if (isComplete(bookId)) {
                                ids.add(bookId);
                            }
                        } catch (NumberFormatException ignored) {
                            // Not a book file
                        }
                    });
        }
        return ids;
    }

    /** Writes to a temporary file, forces it to disk and renames it to the final name. */
    static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + TMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * If a crash cut the last append ("13" instead of "1342\n"), ends that line so the
     * next append does not glue a new id to it ("1384").
     */
    static void terminateLastLine(Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.read(last, size - 1);
            if (last.get(0) != '\n') {
                channel.write(ByteBuffer.wrap(new byte[]{'\n'}), size);
                channel.force(true);
            }
        }
    }

    /** Appends one line and forces it to disk before returning. */
    static void appendLine(Path file, String line) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }
}
