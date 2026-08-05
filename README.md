# KnowAgent

KnowAgent is a Java 21 enterprise knowledge Agent platform reconstructed from the functional boundaries of Yuxi.

## Modules

| Module | Responsibility |
|---|---|
| `knowagent-common` | Shared domain primitives, errors, tenant identifiers and events |
| `knowagent-security` | Tenant, department, user, role and authentication boundaries |
| `knowagent-model` | Chat, embedding and rerank model ports |
| `knowagent-knowledge` | Knowledge bases, parsing, chunking, vector search and citations |
| `knowagent-agent-runtime` | Agent requests, runs, checkpoints, orchestration and events |
| `knowagent-extension` | Tools, Skills, MCP and SubAgent extensions |
| `knowagent-workspace` | Object storage, attachments, artifacts and virtual paths |
| `knowagent-observability` | Tasks, audit, feedback, metrics and evaluation |
| `knowagent-api` | HTTP, security filters, validation, OpenAPI and SSE |
| `knowagent-worker` | Outbox publisher and Redis Streams consumers |

Only `knowagent-api` and `knowagent-worker` are executable Spring Boot modules. All other modules are regular JARs.

## Build

Requirements:

- Java 21
- Maven 3.9+
- Docker with Compose

```powershell
mvn clean verify
Copy-Item .env.example .env
docker compose up -d postgres redis minio etcd milvus
```

The API entry point is `com.knowagent.api.KnowAgentApiApplication`. The worker entry point is `com.knowagent.worker.KnowAgentWorkerApplication`.

## Design References

- [Project plan](../PLAN.md)
- [Yuxi refactoring guide](../YUXI_REFACTOR_GUIDE.md)
- [Module architecture](docs/architecture.md)
