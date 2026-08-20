package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spools a controlled {@link ParseSource} content stream into a bounded temp file so
 * parsers can do random access (PDFBox/POI need a file or bytes) without trusting the
 * declared {@code size} and without holding the caller's stream open. The caller's
 * stream is <em>always</em> closed - on success, on an exceeded limit, and on any
 * read failure - and the temp file is deleted on every exit path.
 */
final class SourceSpool {

    private SourceSpool() {
    }

    /**
     * Reads {@code source.content()} into a temp file bounded by {@code maxBytes}.
     * The content stream is closed on every path. The returned spool is the caller's
     * to delete (it is also {@link AutoCloseable}).
     */
    static SpooledFile spool(ParseSource source, long maxBytes) {
        if (source.size() == 0) {
            closeQuietly(source.content());
            throw new BusinessException(ErrorCode.EMPTY_DOCUMENT, "The document is empty.");
        }
        if (source.size() > maxBytes) {
            closeQuietly(source.content());
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "The document exceeds the maximum allowed size.");
        }
        Path temp;
        try {
            temp = Files.createTempFile("knowagent-parse-", ".part");
        } catch (IOException exception) {
            closeQuietly(source.content());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "The document could not be read.");
        }
        try (InputStream in = new LimitedInputStream(source.content(), maxBytes);
             OutputStream out = Files.newOutputStream(temp)) {
            long size = in.transferTo(out);
            if (size == 0) {
                throw new BusinessException(ErrorCode.EMPTY_DOCUMENT, "The document is empty.");
            }
            return new SpooledFile(temp, size);
        } catch (TooLarge exceeded) {
            deleteFile(temp);
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "The document exceeds the maximum allowed size.");
        } catch (BusinessException failure) {
            deleteFile(temp);
            throw failure;
        } catch (IOException exception) {
            deleteFile(temp);
            throw new BusinessException(ErrorCode.CORRUPT_DOCUMENT,
                    "The document could not be read.");
        } catch (RuntimeException failure) {
            deleteFile(temp);
            throw failure;
        }
    }

    private static void closeQuietly(InputStream content) {
        try {
            content.close();
        } catch (IOException ignored) {
            // best effort: the stream is closed on every path
        }
    }

    private static void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort: the OS temp directory cleans up eventually
        }
    }

    /** The bounded spool; delete (or close) it to release the temp file. */
    static final class SpooledFile implements AutoCloseable {
        private final Path path;
        private final long size;

        SpooledFile(Path path, long size) {
            this.path = path;
            this.size = size;
        }

        Path path() {
            return path;
        }

        long size() {
            return size;
        }

        void delete() {
            deleteFile(path);
        }

        @Override
        public void close() {
            delete();
        }
    }

    /** Fails as soon as more than {@code limit} bytes have been read, bounding the spool. */
    private static final class TooLarge extends RuntimeException {
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > limit) {
                throw new TooLarge();
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                count += read;
                if (count > limit) {
                    throw new TooLarge();
                }
            }
            return read;
        }
    }
}
