package com.zcj.control;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent workflow state of the Control Module, kept in control files as in Stage 1:
 *
 * <pre>
 *   control/downloaded_books.txt   one id per line: ingestion confirmed (200)
 *   control/indexed_books.txt      one id per line: indexing confirmed (200), searchable
 *   control/failed_books.txt       "id&lt;TAB&gt;reason": permanent errors, never retried
 * </pre>
 *
 * A line is appended (and forced to disk) only after the corresponding service has
 * answered 200, so after a crash the state can only be behind the services, never
 * ahead of them. Repeating a step is safe because ingestion and indexing are idempotent.
 *
 * Books currently being processed are "claimed" in memory, so several worker threads
 * never work on the same book. Claims are not persisted: after a crash, an interrupted
 * book is simply processed again. Only one Control Module process may use a control
 * folder; the claims do not coordinate separate processes.
 */
public class ControlState {

    public static final String DOWNLOADED_FILE = "downloaded_books.txt";
    public static final String INDEXED_FILE = "indexed_books.txt";
    public static final String FAILED_FILE = "failed_books.txt";

    /** Counters returned by {@code GET /control/status}. */
    public record Snapshot(int downloaded, int indexed, int failed, int pending) {
    }

    private final Path downloadedFile;
    private final Path indexedFile;
    private final Path failedFile;

    private final Set<Integer> downloaded = new LinkedHashSet<>();
    private final Set<Integer> indexed = new HashSet<>();
    private final Map<Integer, String> failed = new LinkedHashMap<>();
    /** Downloaded, not indexed and not failed, in download order. Kept apart so that
     *  finding the next pending book does not depend on the number of processed books. */
    private final Set<Integer> pending = new LinkedHashSet<>();
    private final Set<Integer> inProgress = new HashSet<>();

    public ControlState(Path folder) throws IOException {
        Files.createDirectories(folder);
        this.downloadedFile = folder.resolve(DOWNLOADED_FILE);
        this.indexedFile = folder.resolve(INDEXED_FILE);
        this.failedFile = folder.resolve(FAILED_FILE);
        load();
    }

    // ------------------------------------------------------------------ claims

    /**
     * Claims a book that is not known yet (never downloaded, never failed) and not
     * being processed. Returns false if the book must be skipped.
     */
    public synchronized boolean claimNew(int bookId) {
        if (downloaded.contains(bookId) || failed.containsKey(bookId) || inProgress.contains(bookId)) {
            return false;
        }
        inProgress.add(bookId);
        return true;
    }

    /**
     * Claims the oldest book that is downloaded but not indexed yet (and not failed),
     * or returns null if there is none.
     */
    public synchronized Integer claimPending() {
        for (int bookId : pending) {
            if (!inProgress.contains(bookId)) {
                inProgress.add(bookId);
                return bookId;
            }
        }
        return null;
    }

    /**
     * Claims a specific book that is not indexed, not failed and not being processed,
     * whether it is downloaded already or not.
     */
    public synchronized boolean claimUnfinished(int bookId) {
        if (indexed.contains(bookId) || failed.containsKey(bookId) || inProgress.contains(bookId)) {
            return false;
        }
        inProgress.add(bookId);
        return true;
    }

    public synchronized void release(int bookId) {
        inProgress.remove(bookId);
    }

    // ------------------------------------------------------------------ transitions

    public synchronized void markDownloaded(int bookId) throws IOException {
        if (downloaded.add(bookId)) {
            appendLine(downloadedFile, String.valueOf(bookId));
            if (!indexed.contains(bookId) && !failed.containsKey(bookId)) {
                pending.add(bookId);
            }
        }
    }

    public synchronized void markIndexed(int bookId) throws IOException {
        markDownloaded(bookId);   // an indexed book was necessarily downloaded
        if (indexed.add(bookId)) {
            appendLine(indexedFile, String.valueOf(bookId));
        }
        pending.remove(bookId);
    }

    public synchronized void markFailed(int bookId, String reason) throws IOException {
        if (!failed.containsKey(bookId)) {
            String clean = reason == null ? "" : reason.replaceAll("[\\t\\r\\n]+", " ").trim();
            failed.put(bookId, clean);
            appendLine(failedFile, bookId + "\t" + clean);
        }
        pending.remove(bookId);
    }

    // ------------------------------------------------------------------ queries

    public synchronized boolean isDownloaded(int bookId) {
        return downloaded.contains(bookId);
    }

    public synchronized boolean isIndexed(int bookId) {
        return indexed.contains(bookId);
    }

    public synchronized boolean isFailed(int bookId) {
        return failed.containsKey(bookId);
    }

    public synchronized boolean isKnown(int bookId) {
        return downloaded.contains(bookId) || failed.containsKey(bookId);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(downloaded.size(), indexed.size(), failed.size(), pending.size());
    }

    // ------------------------------------------------------------------ files

    private void load() throws IOException {
        for (String line : readLines(downloadedFile)) {
            Integer id = parseId(line);
            if (id != null) {
                downloaded.add(id);
            }
        }
        for (String line : readLines(indexedFile)) {
            Integer id = parseId(line);
            if (id != null) {
                indexed.add(id);
                downloaded.add(id);
            }
        }
        for (String line : readLines(failedFile)) {
            int tab = line.indexOf('\t');
            Integer id = parseId(tab < 0 ? line : line.substring(0, tab));
            if (id != null) {
                failed.put(id, tab < 0 ? "" : line.substring(tab + 1));
            }
        }
        for (int bookId : downloaded) {
            if (!indexed.contains(bookId) && !failed.containsKey(bookId)) {
                pending.add(bookId);
            }
        }
    }

    private static Set<String> readLines(Path file) throws IOException {
        Set<String> lines = new LinkedHashSet<>();
        if (Files.isRegularFile(file)) {
            lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            terminateLastLine(file);
        }
        return lines;
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

    /** Null for empty lines or a line cut by a crash in the middle of an append. */
    private static Integer parseId(String value) {
        try {
            int id = Integer.parseInt(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void appendLine(Path file, String line) throws IOException {
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
