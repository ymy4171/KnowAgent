package com.knowagent.observability.application.service;

/**
 * Inbound port for asynchronous work: writes the caller's business record, the task
 * and the outbox event in one Spring transaction so a crash can never leave a task
 * without its event or an event without its task.
 *
 * <p>Only this port and the other application services in this package are the
 * surface {@code knowagent-knowledge} (and similar modules) may call for
 * asynchronous work; it never reaches for a persistence mapper.
 */
public interface TaskSubmission {

    /**
     * Creates a PENDING task and a PENDING outbox event in the caller's transaction
     * and returns both ids.
     */
    TaskSubmissionResult submit(SubmitTaskCommand command);
}
