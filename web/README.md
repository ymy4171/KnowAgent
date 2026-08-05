# Web Migration Placeholder

The Vue 3 client will be migrated from `../Yuxi/web` after the first API contracts are implemented.

Planned boundaries:

- `src/apis`: all `/api/v1` calls and SSE connections
- `src/stores`: authenticated user, Agent and conversation state
- `src/composables`: request queue and Run event streams
- `src/views`: page-level workflows
- `src/components`: reusable domain UI

The original Yuxi frontend remains untouched and is the behavior reference.
