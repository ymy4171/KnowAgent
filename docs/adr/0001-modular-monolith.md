# ADR 0001: Maven Multi-Module Modular Monolith

Status: Accepted

KnowAgent uses one repository and one PostgreSQL database, with API and worker as separately executable processes. Business capabilities are isolated in Maven modules and communicate through application services or ports.

This keeps transactions and local development straightforward while making ownership and dependency direction visible in interviews and code review.
