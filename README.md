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

### 开发者管理员初始化（可选）

首次启动需要开发环境管理员账号时，可以用环境变量显式开启一次幂等的初始化流程。它会创建初始租户、`ADMIN` 系统角色和管理员用户，并建立 `user_roles` 绑定：

| 环境变量 | 说明 |
|---|---|
| `BOOTSTRAP_ENABLED` | `true` 时执行初始化；缺省为 `false`（不执行）。 |
| `BOOTSTRAP_TENANT_SLUG` | 初始租户 slug，仅小写字母数字与连字符，如 `acme`。 |
| `BOOTSTRAP_TENANT_NAME` | 租户显示名，缺省取 slug。 |
| `BOOTSTRAP_ADMIN_LOGIN` | 管理员登录名，如 `admin@acme.test`。 |
| `BOOTSTRAP_ADMIN_DISPLAY_NAME` | 管理员显示名，缺省取登录名。 |
| `BOOTSTRAP_ADMIN_PASSWORD` | 管理员密码，至少 12 个字符。 |

```powershell
$env:BOOTSTRAP_ENABLED = "true"
$env:BOOTSTRAP_TENANT_SLUG = "acme"
$env:BOOTSTRAP_ADMIN_LOGIN = "admin@acme.test"
$env:BOOTSTRAP_ADMIN_PASSWORD = "local-dev-admin-password-123"
mvn spring-boot:run -pl knowagent-api
```

安全与幂等约定：

- 仅当 `BOOTSTRAP_ENABLED=true` 时才执行；生产环境缺失或不安全配置时直接拒绝启动，不会自动生成并打印密码。
- 流程整体在一个事务内，任何一步失败全部回滚；重复启动不会产生重复数据（按 slug / tenant+code / tenant+login 幂等）。
- 密码只以 Argon2id 哈希落库，原始密码不会出现在日志或异常信息中。
- 不要实现公开注册接口：管理员只能由本流程或后续受控的管理接口创建。
- `.env.example` 只保留占位说明，禁止提交真实密码。

## 当前阶段

项目目前已经完成多模块工程、核心领域端口、API/Worker 启动入口、Flyway V1-V11 共 31 张业务表和 Docker Compose 基础设施。后续优先实现：

1. 用户、角色、租户和 JWT 鉴权
2. 知识库 CRUD 与文件上传
3. 文档解析、分块、Embedding 和向量检索
4. Agent 配置、RAG 问答和 SSE 流式输出
5. Outbox、Redis Streams、任务恢复和测试

## 设计文档

- [项目计划](./PLAN.md)
- [认证阶段开发提示词](./DEVELOPMENT_PROMPTS.md)
- [Yuxi Java 重构指南](./YUXI_REFACTOR_GUIDE.md)
- [系统架构说明](./docs/architecture.md)
- [可执行测试计划](./TEST_PLAN.md)
- [架构决策记录](./docs/adr/)
