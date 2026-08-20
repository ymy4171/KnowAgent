package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;

/**
 * A cooperative parse deadline: parsers call {@link #checkTime()} at page/paragraph
 * boundaries and fail with the stable {@link ErrorCode#DOCUMENT_TIMEOUT} once the
 * configured timeout has elapsed. The timeout is checked at loop boundaries rather than
 * preemptively, so it bounds runaway loops without spawning a thread per parse.
 */
final class ParseBudget {

    private final long deadlineNanos;

    ParseBudget(ParseProperties properties) {
        this.deadlineNanos = System.nanoTime() + properties.timeout().toNanos();
    }

    void checkTime() {
        if (System.nanoTime() > deadlineNanos) {
            throw new BusinessException(ErrorCode.DOCUMENT_TIMEOUT,
                    "Parsing the document timed out.");
        }
    }
}
