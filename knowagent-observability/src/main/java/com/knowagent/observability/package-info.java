/**
 * Tasks, audit, feedback, metrics and evaluation contracts.
 *
 * <p>This module owns the durable foundations for asynchronous work: the task
 * lifecycle ({@code task}), the transactional outbox ({@code outbox}) and the
 * idempotent inbox ({@code inbox}), together with the application ports and
 * services other modules depend on ({@code application}). PostgreSQL is the source
 * of truth for task and event state; Redis, Milvus and MinIO are always rebuildable
 * from it.
 */
package com.knowagent.observability;
