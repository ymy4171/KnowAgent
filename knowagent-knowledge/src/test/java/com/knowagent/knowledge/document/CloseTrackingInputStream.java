package com.knowagent.knowledge.document;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Records that {@link #close()} was called, so tests can assert the parser closes the
 * source stream it is handed - on success and on every error path. */
final class CloseTrackingInputStream extends FilterInputStream {

    private boolean closed;

    CloseTrackingInputStream(InputStream in) {
        super(in);
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        super.close();
    }
}
