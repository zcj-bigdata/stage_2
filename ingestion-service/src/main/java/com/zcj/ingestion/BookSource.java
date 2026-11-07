package com.zcj.ingestion;

/**
 * Where the raw text of a book comes from: Project Gutenberg in a normal run,
 * or the {@code sample_data/} folder to test the pipeline without internet.
 */
public interface BookSource {

    /**
     * Returns the complete raw text of the book (header, body and footer).
     *
     * @throws IngestionException NOT_FOUND if the book does not exist,
     *                            UPSTREAM if the source could not be reached
     */
    String fetch(int bookId) throws IngestionException;

    /** Short description shown in the logs, e.g. "https://www.gutenberg.org". */
    String describe();
}
