# ADR 0002: PostgreSQL Outbox and Redis Streams

Status: Accepted

Business transactions write state and an Outbox event atomically to PostgreSQL. A publisher forwards unsent events to Redis Streams. Workers consume with consumer groups and execute jobs idempotently.

PostgreSQL remains the final source of truth. Redis contains delivery state and short-lived event history, not the only copy of a Run's final status.
