# KnowAgent Java Agent 项目计划

## 1. 项目定位

KnowAgent 是面向 Agent 应用开发岗位的企业知识库 Agent 平台，支持显式多租户、文档解析分块、Milvus 向量检索、RAG 问答、Agent 运行编排、工具调用、SSE 事件流和任务恢复。

项目参考 Yuxi `main@c4c6eb7` 的完整功能边界，但不兼容其 HTTP API 和数据库结构。前 12 周完成可演示主链，后续阶段继续补齐图谱、评估、SubAgent、Skills、沙盒和高级恢复能力。

完整的原项目文件地图、端点对照、原实现分析和 Java 重写方法见 [YUXI_REFACTOR_GUIDE.md](YUXI_REFACTOR_GUIDE.md)。

## 2. 已确定的技术方案

| 类别 | 选择 |
|---|---|
| Java | Java 21 |
| Web 框架 | Spring Boot 3.5.9、Spring MVC、SseEmitter |
| AI 框架 | Spring AI 1.1.8 |
| 安全 | Spring Security、JWT、RBAC、显式 tenant |
| 数据访问 | MyBatis-Plus、自定义 SQL、Flyway |
| 业务数据库 | PostgreSQL 16 |
| 消息与事件 | Redis 7、Redis Streams、事务 Outbox |
| 文件存储 | MinIO |
| 向量数据库 | Milvus 2.5.6 |
| 图数据库 | Neo4j 5.26，12 周后接入 |
| 文档解析 | Tika/PDFBox/POI + 外部 MinerU/PaddleX |
| 前端 | 保留 Vue 3、Vite、Pinia，逐步适配 `/api/v1` |
| 部署 | Docker Compose 优先 |
| 测试 | JUnit 5、Testcontainers、WireMock、Playwright |

## 3. 工程结构

项目采用 Maven 多模块单体，API 与 Worker 独立启动。模块依赖、组件职责、数据边界、状态机和核心时序以 [系统架构文档](docs/architecture.md) 为唯一真相源；关系模型、约束和事务 SQL 以 [数据库设计文档](docs/database-schema.md) 为准。本计划不重复维护接口签名、依赖矩阵和字段清单。

Java 根包固定为 `com.knowagent`。领域模块不得依赖 Controller，不得跨模块直接调用 Mapper；跨域协作使用应用服务、端口接口或领域事件。

## 4. 12 周必须完成

- 租户、部门、用户、JWT、角色权限和管理员初始化。
- 模型供应商配置、加密存储、Chat/Embedding/Rerank 网关。
- 知识库 CRUD、MinIO 文件上传、文档解析、分块和任务状态。
- Milvus 向量入库、租户过滤检索、rerank 和引用来源。
- Agent 配置、会话、消息、Request/Run 状态机。
- PostgreSQL Outbox、Redis Streams worker、失败恢复和幂等消费。
- Request SSE、Run SSE、`Last-Event-ID` 回放和取消。
- ToolRegistry、基础知识库 Skill、一个 MCP 工具和审批骨架。
- Vue 聊天、知识库、Agent 配置、任务状态和历史页面。
- Docker Compose、测试、README、架构图和演示脚本。

## 5. 后续全量能力

- 知识图谱抽取、Neo4j 子图检索、思维导图。
- RAG 数据集、检索指标和 Agent 轨迹评估。
- SubAgent、多级事件流和预算限制。
- 完整 Skills 安装、依赖工具门控和版本管理。
- 沙盒 provisioner、线程文件系统和可交付产物。
- OIDC、CLI 浏览器授权、API Key 和用户模拟。
- approval resume、steer、复杂 checkpoint 和请求恢复。
- Dashboard、Langfuse、审计和完整运营指标。

## 6. 核心状态与接口

Request、Run、Task 的完整状态转换和恢复约束见 [系统架构文档](docs/architecture.md)。其中 `INTERRUPTED` 不是终态，只允许恢复为 `RUNNING`，或转为 `FAILED/CANCELLED`。

关键接口统一使用 `/api/v1`：

- `POST /api/v1/knowledge-bases/{id}/files`
- `POST /api/v1/knowledge-bases/{id}/retrieval`
- `POST /api/v1/agents/{id}/requests`
- `GET /api/v1/agent-requests/{id}/events`
- `GET /api/v1/agent-runs/{id}/events`
- `POST /api/v1/agent-runs/{id}:cancel`
- `POST /api/v1/agent-runs/{id}:resume`

数据库事务同时写入 Message、Request、Run 和 Outbox。提交后由 publisher 投递 Redis Stream，Worker 幂等执行；PostgreSQL 始终是最终状态来源。消息序号分配、Token 家族、Outbox 抢占和 Chunk 删除流程见 [数据库设计文档](docs/database-schema.md)。

## 7. 数据模型

核心表：

- `tenants`、`departments`、`users`、`roles`、`user_roles`、`refresh_tokens`、`api_keys`
- `model_providers`
- `knowledge_bases`、`knowledge_files`、`knowledge_chunks`
- `agents`、`agent_knowledge_bases`、`conversations`、`messages`、`message_tool_calls`、`message_citations`、`message_feedback`
- `agent_run_requests`、`agent_runs`、`agent_run_events`、`agent_checkpoints`
- `tasks`、`outbox_events`、`inbox_events`、`audit_logs`
- `skills`、`agent_skills`、`agent_tool_grants`、`mcp_servers`、`agent_mcp_servers`

除租户根表外，所有业务表带 `tenant_id`。Milvus 向量元数据至少包含 `tenant_id`、`knowledge_base_id`、`file_id`、`chunk_id`。

## 8. 实施里程碑

### 第 1～2 周：工程与安全

- 创建 Maven 模块和依赖规则。
- 建立 Flyway 迁移、租户认证和模型供应商。
- 启动 PostgreSQL、Redis、MinIO、Milvus。

验收：可以登录；租户数据隔离；模型连通性可检测；Compose 基础服务健康。

### 第 3～5 周：知识库 RAG

- 完成文件上传、解析、分块、Embedding、Milvus 和检索引用。
- 使用 worker 执行任务并支持幂等重试。

验收：上传文档后可检索；失败任务可重试；重复任务不产生重复 chunk。

### 第 6～8 周：Agent 运行时

- 完成 Agent、会话、Request/Run、Outbox、Redis Streams 和双 SSE。
- 实现 FIFO、取消、事件回放和历史保存。

验收：Agent 可基于知识库流式回答；断线后恢复；并发请求按线程串行。

### 第 9～10 周：扩展能力

- 完成 ToolRegistry、基础 Skills、MCP client 和审批骨架。

验收：工具按用户和 Agent 权限开放；超时和失败形成结构化事件。

### 第 11～12 周：演示交付

- 适配 Vue 页面，补充测试、文档、架构图和演示脚本。

验收：Docker Compose 一键启动；完成知识上传、Agent 问答、工具调用、任务恢复的 10 分钟演示。

## 9. 测试要求

可执行验收清单见 [TEST_PLAN.md](TEST_PLAN.md)。

- 单元测试覆盖状态机、分块、权限、工具策略和错误映射。
- Testcontainers 覆盖 PostgreSQL 约束、租户隔离和事务并发。
- Redis 测试覆盖 Outbox 发布、重复消费、pending reclaim 和事件回放。
- MinIO/Milvus 集成测试覆盖上传、索引、过滤、删除和引用。
- WireMock 覆盖模型流式协议、限流、超时和供应商错误。
- Playwright 覆盖登录、上传、问答、重连、取消和历史恢复。

## 10. 面试讲解

重点说明：

- Spring AI 与自建 Agent 状态机的职责边界。
- PostgreSQL、Redis Streams、MinIO、Milvus 和 Neo4j 的数据职责。
- Request 与 Run 分离、双 SSE 和断线回放。
- 事务 Outbox 如何解决数据库提交与消息投递的一致性问题。
- tenant 如何贯穿 SQL、Redis、MinIO 和 Milvus。
- 为什么保留外部 OCR 服务，以及如何让解析器可替换。
- 如何通过 Tools、Skills、MCP 和 SubAgent 逐步扩展运行时能力。

简历描述：

> 基于 Spring Boot + Spring AI 构建多租户企业知识库 Agent 平台，实现文档解析分块、Milvus 向量检索、RAG 引用问答、Agent Request/Run 状态机、Redis Streams 异步执行和可回放 SSE。采用 PostgreSQL 事务 Outbox 保证任务投递一致性，通过运行时 ToolRegistry 集成 Skills 与 MCP，并使用 Docker Compose 和 Testcontainers 完成部署与验证。
