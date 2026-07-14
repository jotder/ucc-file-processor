package com.gamma.acquire;

import java.io.IOException;

/**
 * Thrown by a {@link CollectorConnector} when discovery, retrieval, or finalization fails.
 *
 * <p>Extends {@link IOException} so it flows transparently through the engine's existing
 * {@code throws IOException} contracts (e.g. {@link com.gamma.inspector.CollectorProcessor#collectCandidates})
 * — callers that already handle I/O failures need no change.
 */
public class AcquisitionException extends IOException {

    public AcquisitionException(String message) {
        super(message);
    }

    public AcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
