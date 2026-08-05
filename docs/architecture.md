# KnowAgent Module Architecture

## Dependency Direction

```text
common
  ↑
security
  ↑
model ───────┐
  ↑          │
knowledge    │
  ↑          │
agent-runtime│
  ↑          │
extension    │
             │
workspace ───┤
observability┤
             ↓
       api / worker
```

The diagram is conceptual. Maven dependencies are kept one-way:

- Domain modules never depend on `knowagent-api` or `knowagent-worker`.
- A module never calls another module's Mapper directly.
- Framework and SDK types remain inside adapters.
- PostgreSQL is the source of truth for business state.
- Redis Streams carry jobs and short-lived run events.
- API and worker share the same domain modules but have separate process lifecycles.

## First Architecture Slice

The initial scaffold contains stable ports and status types only. Controllers, mappers, adapters and database migrations are added by feature slice so that empty abstractions do not grow ahead of real behavior.
