# KnowAgent

KnowAgent 是一个基于 Java 21 构建的企业知识库 Agent 平台，参考 Yuxi 的功能边界进行重构。

项目目标是实现知识库管理、文档解析与分块、向量检索、RAG 问答、Agent 编排、SSE 流式响应和异步任务处理，并形成一个适合学习、演示和面试讲解的完整 Java Agent 项目。

## 模块说明

| 模块 | 职责 |
|---|---|
| `knowagent-common` | 通用领域类型、错误定义、租户标识和领域事件 |
| `knowagent-security` | 租户、部门、用户、角色、认证和授权边界 |
| `knowagent-model` | 对话、Embedding 和 Rerank 模型调用端口 |
| `knowagent-knowledge` | 知识库、文档解析、文本分块、向量检索和引用来源 |
| `knowagent-agent-runtime` | Agent 请求、运行状态、检查点、编排和运行事件 |
| `knowagent-extension` | Tools、Skills、MCP 和 SubAgent 扩展能力 |
| `knowagent-workspace` | 对象存储、附件、运行产物和虚拟路径 |
| `knowagent-observability` | 任务、审计、反馈、指标和评估 |
| `knowagent-api` | HTTP API、安全过滤器、参数校验、OpenAPI 和 SSE |
| `knowagent-worker` | Outbox 发布器和 Redis Streams 消费者 |

只有 `knowagent-api` 和 `knowagent-worker` 是可独立运行的 Spring Boot 模块，其他模块均为普通 JAR 模块。

## 环境要求

- Java 21
- Maven 3.9 或更高版本
- Docker Desktop 和 Docker Compose

## 构建与启动

```powershell
mvn clean verify
Copy-Item .env.example .env
docker compose up -d postgres redis minio etcd milvus
```

API 服务启动类：

```text
com.knowagent.api.KnowAgentApiApplication
```

Worker 服务启动类：

```text
com.knowagent.worker.KnowAgentWorkerApplication
```

本地开发时，可以只启动当前功能需要的基础服务。例如仅运行 API 骨架时，先启动 PostgreSQL：

```powershell
docker compose up -d postgres
```

## 当前阶段

项目目前已经完成多模块工程、核心领域端口、API/Worker 启动入口、Flyway 基线和 Docker Compose 基础设施。后续优先实现：

1. 用户、角色、租户和 JWT 鉴权
2. 知识库 CRUD 与文件上传
3. 文档解析、分块、Embedding 和向量检索
4. Agent 配置、RAG 问答和 SSE 流式输出
5. Outbox、Redis Streams、任务恢复和测试

## 设计文档

- [项目计划](./PLAN.md)
- [Yuxi Java 重构指南](./YUXI_REFACTOR_GUIDE.md)
- [系统架构说明](./docs/architecture.md)
- [可执行测试计划](./TEST_PLAN.md)
- [架构决策记录](./docs/adr/)
