package com.knowagent.worker.stream;

/** Poison message rejected before a Worker tenant context is established. */
public final class InvalidEventEnvelopeException extends RuntimeException {
    public InvalidEventEnvelopeException(String message) {
        super(message);
    }
}
