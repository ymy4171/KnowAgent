/**
 * Outbound ports for the observability module: durable task state, transactional
 * outbox events and idempotent inbox receipts.
 *
 * <p>These are the only surface another module (for example {@code knowagent-knowledge})
 * may depend on when it needs asynchronous work: it calls the application services
 * and ports here, never the persistence mappers below them.
 */
package com.knowagent.observability.application.port.out;
