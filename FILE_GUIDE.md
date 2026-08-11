# KnowAgent 文件职责指南

本文覆盖 `KnowAgent` 当前全部手写文件。`target/` 和 `.m2/` 属于构建产物或本地依赖缓存，不纳入说明。

当前工程处于架构骨架阶段：多数 Java 文件是领域数据类型或端口接口，用来约束模块边界；它们不是完整业务实现。后续实现类应放在对应模块的 `application`、`infrastructure` 或具体业务包中，不要把供应商 SDK 直接写进这些核心接口。

## 1. 根目录与构建配置

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.dockerignore` | 控制 Docker 构建上下文，排除 Git、本地缓存和编译产物。 | 新增不需要打进镜像的目录时同步更新。 |
| `.editorconfig` | 统一编码、缩进、换行和文件尾规则。 | IDE 应启用 EditorConfig，减少无意义格式差异。 |
| `.env.example` | 罗列 Compose 和应用需要的环境变量示例，不保存真实密钥。 | 本地复制为 `.env` 后填写模型、数据库和对象存储配置。 |
| `.gitignore` | 排除 IDE 文件、密钥文件、日志、缓存和构建产物。 | 新增本地运行产物时补充规则。 |
| `.mvn/maven.config` | 让所有 Maven 命令自动使用项目内 `settings.xml`。 | 保证不同机器使用一致的仓库配置入口。 |
| `.mvn/settings.xml` | 将 Maven 本地仓库放到项目 `.m2/repository`，绕开机器级错误配置。 | 可按团队环境增加镜像，但不要写认证凭据。 |
| `pom.xml` | 父 POM；声明 10 个模块、Java 21、依赖版本、测试与 Enforcer 规则。 | 所有跨模块版本在这里集中管理。 |
| `docker-compose.yml` | 编排 API、Worker、PostgreSQL、Redis、MinIO、Milvus、etcd 和可选 Neo4j。 | 后续增加健康检查、初始化脚本和生产配置覆盖。 |
| `README.md` | 项目入口，说明模块、构建、运行方式和当前完成度。 | 每完成一个可演示阶段同步更新。 |
| `PLAN.md` | 定义 12 周交付范围、开发阶段和验收入口。 | 只维护范围和里程碑，架构细节统一链接到架构文档。 |
| `DEVELOPMENT_PROMPTS.md` | 提供认证、授权与租户里程碑的分阶段可执行提示词。 | 按顺序一次执行一个提示词，完成验收后再进入下一阶段。 |
| `YUXI_REFACTOR_GUIDE.md` | 记录 Yuxi 功能、旧代码位置、迁移去向和 Java 重写策略。 | 迁移功能时用于追溯原实现，不作为新系统接口的唯一真相源。 |
| `TEST_PLAN.md` | 按能力域整理可勾选的单元、集成、并发、安全和端到端测试。 | 每完成一个实现切片同步勾选，并保留 CI 证据。 |
| `FILE_GUIDE.md` | 当前文件职责索引。 | 新增、移动或删除手写文件时同步维护。 |

## 2. Docker 与架构文档

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `docker/api.Dockerfile` | 分阶段构建 API 可执行 JAR，并以精简 JRE 镜像运行。 | 增加非 root 用户、探针和 JVM 生产参数。 |
| `docker/worker.Dockerfile` | 分阶段构建并运行异步 Worker。 | 与 API 独立扩缩容，配置不同资源限制。 |
| `docs/architecture.md` | 架构唯一真相源，包含组件图、模块依赖、状态机和三条核心时序。 | 架构或运行模型变化时优先更新，再由计划和指南引用。 |
| `docs/database-schema.md` | 数据库唯一说明，记录 31 张表、租户复合外键、消息事务、Token 家族、Outbox 和 Chunk 生命周期。 | 写 Mapper、锁查询或新迁移前先核对；结构变化只通过新的 Flyway 版本推进。 |
| `docs/adr/0001-modular-monolith.md` | 记录先采用模块化单体而非微服务的决策。 | 架构拆分时新增 ADR，不直接改写历史结论。 |
| `docs/adr/0002-outbox-redis-streams.md` | 记录 PostgreSQL Outbox + Redis Streams 的任务投递决策。 | 实现后补充失败恢复和一致性验证结果。 |
| `docs/adr/0003-mybatis-plus.md` | 记录选择 MyBatis-Plus 而非 JPA/Hibernate 的原因与代价。 | 数据访问策略变化时新增替代 ADR。 |
| `docs/adr/0004-spring-mvc.md` | 记录阻塞式持久化栈下选择 Spring MVC，并在内部保留 Reactor 流的决策。 | 若未来切换全响应式数据栈，重新评审该决策。 |
| `docs/adr/0005-milvus.md` | 记录选择 Milvus 而非 pgvector 或 Elasticsearch 的向量检索决策。 | 以检索规模和运维数据作为复审依据。 |

## 3. `knowagent-common`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-common/pom.xml` | 最底层共享模块，无业务模块反向依赖。 | 只放稳定、通用且无基础设施依赖的类型。 |
| `knowagent-common/src/main/java/com/knowagent/common/package-info.java` | 声明 common 包的边界和用途。 | 保持为模块级说明。 |
| `.../common/error/ErrorCode.java` | 统一错误码接口。 | 各模块定义自己的错误码枚举并实现它。 |
| `.../common/error/BusinessException.java` | 携带错误码的业务异常基类。 | API 层统一转换为标准错误响应。 |
| `.../common/event/DomainEvent.java` | 领域事件最小契约，提供事件 ID 和发生时间。 | Outbox 事件和模块间事件实现该接口。 |
| `.../common/tenant/TenantId.java` | 强类型租户 ID，避免在核心逻辑中裸用字符串或 UUID。 | 所有租户数据、命令和查询都显式携带它。 |

## 4. `knowagent-security`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-security/pom.xml` | 安全模块依赖 common，并为模块内持久化适配引入 MyBatis-Plus、Jackson 与 PostgreSQL JDBC。 | 后续加入 JWT、Spring Security 时继续保持 HTTP 依赖不进入该模块。 |
| `.../security/package-info.java` | 声明认证、授权和租户上下文模块边界。 | 保持为包级架构说明。 |
| `.../security/principal/TenantPrincipal.java` | 表示已认证用户、租户、角色和权限集合。 | JWT/OIDC/API Key 认证成功后构造，并注入请求上下文。 |
| `.../security/domain/package-info.java` | 声明身份认证领域层不依赖持久化对象。 | 新增安全领域行为时保持基础设施类型在边界外。 |
| `.../security/domain/tenant/Tenant.java`、`TenantStatus.java` | 表示租户身份、不可变 JSON settings 及 ACTIVE/SUSPENDED/DISABLED 状态。 | 登录前按 slug 解析租户，状态名保持与数据库 CHECK 一致。 |
| `.../security/domain/user/User.java`、`UserStatus.java` | 表示本地用户、密码散列、锁定信息和乐观锁版本，并在字符串输出中隐藏密码散列。 | 后续登录应用服务校验状态、密码和失败计数。 |
| `.../security/domain/role/Role.java`、`RoleStatus.java` | 表示租户角色及不可变权限集合。 | 登录和 RBAC 应用服务加载有效角色后聚合权限。 |
| `.../security/domain/role/UserRole.java` | 表示用户角色绑定及有效期。 | 管理员初始化和授权写入使用，过期判断不依赖 HTTP 层。 |
| `.../security/domain/token/RefreshToken.java`、`RefreshTokenStatus.java` | 表示 Refresh Token 家族、所有权、生命周期和版本；字符串输出不包含 token_hash。 | 后续轮换服务必须在事务内锁定并校验租户与用户关系。 |
| `.../security/application/port/out/package-info.java` | 声明安全应用层访问数据库的输出端口边界。 | 应用服务只依赖端口，禁止暴露 Mapper。 |
| `.../security/application/port/out/TenantRepository.java` | 提供 ACTIVE、未删除租户的 slug 查询端口。 | 登录前租户解析调用。 |
| `.../security/application/port/out/UserRepository.java` | 提供显式 tenantId + loginName 的未删除用户查询端口。 | 登录前调用，不依赖尚未建立的 TenantContext。 |
| `.../security/application/port/out/RoleRepository.java` | 提供显式租户和用户的当前有效角色查询端口。 | 登录和鉴权阶段聚合角色与 permissions。 |
| `.../security/application/port/out/RefreshTokenStore.java` | 提供全局唯一 token_hash 普通查询和事务内 `FOR UPDATE` 查询端口。 | 调用方必须在锁定事务中校验返回记录的 tenantId/userId。 |
| `.../security/application/port/out/UserRoleStore.java` | 提供用户角色绑定写入端口。 | 管理员初始化和授权应用服务通过该端口写入，禁止直接调用 Mapper。 |
| `.../security/infrastructure/persistence/package-info.java` | 声明安全模块 MyBatis-Plus 持久化适配边界。 | 基础设施实现只向应用层暴露端口。 |
| `.../persistence/entity/TenantPo.java`、`UserPo.java`、`RolePo.java`、`UserRolePo.java`、`RefreshTokenPo.java` | 映射五张认证主链表的 UUID、枚举、timestamptz、jsonb、inet 和 version 字段；主键使用应用输入模式。 | 仅供 Mapper 和转换器使用，禁止直接返回 Controller。 |
| `.../persistence/typehandler/JsonNodeJsonbTypeHandler.java` | 使用 Jackson 和 PostgreSQL `PGobject` 映射通用 JSONB。 | 租户 settings 等 JSON 对象字段复用。 |
| `.../persistence/typehandler/PermissionSetJsonbTypeHandler.java` | 校验 JSONB 字符串数组并映射不可变 `Set<String>`。 | 禁止手工拼接 permissions JSON。 |
| `.../persistence/typehandler/PostgresInetTypeHandler.java` | 在 PostgreSQL inet 与 Java `InetAddress` 间转换。 | Refresh Token 签发来源 IP 持久化复用。 |
| `.../persistence/typehandler/PostgresUuidTypeHandler.java` | 为 MyBatis-Plus 自动 ResultMap 显式映射 PostgreSQL UUID。 | 所有应用预生成 UUID 的持久化对象复用。 |
| `.../persistence/converter/IdentityPersistenceConverter.java` | 将五类持久化对象转换为领域模型，并把损坏数据转换为稳定内部错误。 | 写入端口增加时在同一边界补充反向转换。 |
| `.../persistence/mapper/TenantMapper.java` | 查询 ACTIVE、未删除租户，并整体忽略 tenant-line 插件。 | `tenants` 没有 tenant_id，禁止被租户插件改写。 |
| `.../persistence/mapper/UserMapper.java` | 用显式 tenant_id + login_name 查询未删除用户。 | 认证前查询方法绕过 tenant-line，但 SQL 自身保持租户条件。 |
| `.../persistence/mapper/RoleMapper.java` | 显式联结 users、user_roles、roles 并过滤禁用、删除和过期授权。 | 自定义 SQL 必须继续对每个租户表保留 tenant_id 条件。 |
| `.../persistence/mapper/UserRoleMapper.java` | 提供用户角色绑定的 MyBatis-Plus 基础映射。 | 后续初始化/授权写服务通过应用端口使用，不跨层暴露。 |
| `.../persistence/mapper/RefreshTokenMapper.java` | 按全局唯一 token_hash 查询，并提供 `FOR UPDATE` 锁查询。 | 这是认证前无 tenant 上下文的受控例外，返回后仍校验所有权。 |
| `.../persistence/repository/MyBatisTenantRepository.java`、`MyBatisUserRepository.java`、`MyBatisRoleRepository.java`、`MyBatisRefreshTokenStore.java` | 将查询输出端口适配到 Mapper，并返回领域模型；实现类保持可被 Spring 类代理。 | Controller 和跨模块调用方不得绕过这些端口。 |
| `.../persistence/repository/MyBatisUserRoleStore.java` | 将用户角色写入端口适配到 UserRoleMapper。 | 后续初始化和授权应用服务只依赖 UserRoleStore。 |
| `.../persistence/config/SecurityPersistenceConfiguration.java` | 只扫描安全持久化 Mapper，并注册 MyBatis-Plus 乐观锁插件。 | 后续租户上下文阶段在同一拦截器链加入 tenant-line。 |
| `.../persistence/converter/IdentityPersistenceConverterTest.java` | 验证五类转换、不可变 permissions、时间和敏感字段字符串输出。 | 领域或表字段变化时同步维护。 |
| `.../persistence/typehandler/PersistenceTypeHandlerTest.java` | 验证 JSONB permissions 校验和 inet IPv4/IPv6 映射。 | TypeHandler 变化时保持非法输入覆盖。 |
| `.../persistence/mapper/SecurityMapperSqlContractTest.java` | 固定认证前 SQL 的租户条件、Tenant 根表例外及 Refresh Token 锁语义。 | 新增自定义安全 SQL 时加入显式租户审查断言。 |

## 5. `knowagent-model`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-model/pom.xml` | 模型模块依赖定义，引入 Spring AI 模型抽象。 | 添加具体供应商适配器时保持核心端口不变。 |
| `.../model/package-info.java` | 声明模型网关模块边界。 | 防止 Agent 直接依赖供应商 SDK。 |
| `.../model/chat/ChatRole.java` | 定义 system、user、assistant、tool 等消息角色。 | 做供应商消息格式映射。 |
| `.../model/chat/ChatMessage.java` | 密封消息接口，统一文本、工具调用和工具结果消息。 | ChatCommand 和供应商适配器只依赖该抽象。 |
| `.../model/chat/TextChatMessage.java` | SYSTEM、USER、ASSISTANT 的普通文本消息。 | Prompt 和历史文本消息使用，禁止 TOOL 角色。 |
| `.../model/chat/ToolCall.java` | 工具调用 ID、工具名和 JSON 参数。 | 关联模型调用与后续工具结果。 |
| `.../model/chat/AssistantToolCallMessage.java` | 助手发起的一组有序工具调用。 | 支持单次模型响应包含多个 Tool Call。 |
| `.../model/chat/ToolResultMessage.java` | 与 toolCallId 关联的工具执行结果。 | 作为 TOOL 消息继续模型循环。 |
| `.../model/chat/ChatMessageTest.java` | 验证消息角色约束、工具关联和多调用顺序。 | 消息模型扩展时同步维护。 |
| `.../model/chat/ModelOptions.java` | 标准化 temperature、maxTokens 等生成参数。 | 后续可增加 stop、topP，但避免暴露供应商私有字段。 |
| `.../model/chat/ChatCommand.java` | 一次模型调用命令，包含模型、消息和生成选项。 | Agent Runtime 构造，模型适配器消费。 |
| `.../model/chat/ModelEvent.java` | 统一流式模型事件：文本增量、工具调用、用量和完成。 | 映射到 RunEvent，再通过 SSE 输出。 |
| `.../model/chat/ChatModelGateway.java` | 大模型调用端口，返回 Reactor `Flux<ModelEvent>`。 | Spring AI 适配器实现供应商路由、超时和重试。 |
| `.../model/embedding/EmbeddingGateway.java` | 文本向量化端口。 | 文档索引和查询向量生成共同调用。 |
| `.../model/rerank/RankedDocument.java` | 重排后的文档及分数数据类型。 | RAG 检索结果二次排序后返回。 |
| `.../model/rerank/RerankGateway.java` | Rerank 模型调用端口。 | 实现供应商适配和无 Rerank 时的降级策略。 |

## 6. `knowagent-knowledge`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-knowledge/pom.xml` | 知识库模块依赖定义，依赖 security、model 和 common。 | 后续加入解析器、数据库和 Milvus 适配实现。 |
| `.../knowledge/package-info.java` | 声明知识入库与检索模块边界。 | 保持业务能力说明。 |
| `.../knowledge/document/ParseSource.java` | 描述待解析文件的来源、名称、类型和输入流。 | MinIO 下载后传给解析器。 |
| `.../knowledge/document/ParsedSection.java` | 表示解析后的章节及页码等元数据。 | 保留引用定位信息，供分块继承。 |
| `.../knowledge/document/ParsedDocument.java` | 文档解析结果，聚合多个章节。 | 作为解析到分块之间的标准格式。 |
| `.../knowledge/document/DocumentParser.java` | 文档解析端口。 | Tika/PDFBox/POI 或外部 MinerU 适配器按 MIME 类型实现。 |
| `.../knowledge/chunk/ChunkPolicy.java` | 分块策略参数，如块大小和重叠长度。 | 知识库可保存独立策略，并在任务执行时读取。 |
| `.../knowledge/chunk/ChunkDraft.java` | 尚未持久化的文本块和元数据。 | 生成 embedding 后转成数据库记录与 VectorChunk。 |
| `.../knowledge/chunk/Chunker.java` | 文本分块端口。 | 实现递归字符、Token 或标题感知分块。 |
| `.../knowledge/vector/VectorChunk.java` | 写入向量库的 chunk、向量和租户过滤元数据。 | Milvus 适配器将它映射为 collection entity。 |
| `.../knowledge/vector/VectorQuery.java` | 向量检索请求，包含租户、知识库、查询向量和 topK。 | 所有检索必须通过其过滤条件实现租户隔离。 |
| `.../knowledge/vector/VectorHit.java` | 向量命中结果，包含 chunk 标识、文本、分数和元数据。 | RAG 上下文和引用来源使用。 |
| `.../knowledge/vector/VectorStoreGateway.java` | 向量写入、删除和检索端口。 | 由 Milvus SDK/Spring AI VectorStore 适配器实现。 |

## 7. `knowagent-agent-runtime`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-agent-runtime/pom.xml` | Agent 运行时模块依赖定义。 | 运行时只依赖领域端口，不直接访问 Web Controller。 |
| `.../agent/package-info.java` | 声明 Agent 编排、状态机和事件边界。 | 保持模块级说明。 |
| `.../agent/run/AgentRequestStatus.java` | 请求排队阶段状态：排队、分发、取消、拒绝、失败。 | 请求表状态机和 API 返回值共用。 |
| `.../agent/run/AgentRunStatus.java` | 执行阶段状态及合法转换规则；`INTERRUPTED` 可恢复或失败/取消。 | Run 表、Worker 和 SSE 状态保持一致，所有状态更新先校验转换。 |
| `.../agent/run/AgentRunContext.java` | 一次运行所需的租户、用户、Agent、会话、请求、Run 和问题。 | Worker 加载数据库配置后构造。 |

| `.../agent/run/AgentOrchestrator.java` | 返回 `Flux<RunEvent>` 的流式编排端口。 | Worker 按顺序消费、持久化和发布运行事件。 |
| `.../agent/checkpoint/AgentCheckpoint.java` | 可恢复执行检查点的数据类型。 | 保存步骤、状态和恢复所需载荷。 |
| `.../agent/checkpoint/CheckpointStore.java` | 检查点保存与读取端口。 | PostgreSQL 或 Redis 实现，中断/恢复流程调用。 |
| `.../agent/event/RunEvent.java` | 实现 DomainEvent 的运行事件，使用 UUID 作为业务事件 ID。 | 覆盖模型增量、工具、审批和终态。 |
| `.../agent/event/PublishedRunEvent.java` | 组合运行事件和 Redis/SSE 字符串游标。 | 将领域事件身份与 Last-Event-ID 分离。 |
| `.../agent/event/RunEventPublisher.java` | 响应式发布和回放 PublishedRunEvent 的端口。 | Redis Streams 适配器返回游标，SSE 使用游标重连。 |
| `.../agent/job/JobEnvelope.java` | 异步任务消息信封，携带任务 ID、类型、租户和载荷。 | Redis Stream 消息使用，支持幂等键。 |
| `.../agent/job/JobDispatcher.java` | 异步任务投递端口。 | 事务提交后由 Outbox 发布器调用。 |
| `.../agent/run/AgentStatusTest.java` | 验证 Request/Run 终态标志、INTERRUPTED 恢复和终态不可逆。 | 状态机扩展时同步维护。 |
| `.../agent/run/AgentOrchestratorContractTest.java` | 验证事件顺序和订阅取消传播。 | 流式编排实现必须满足该契约。 |
| `.../agent/event/RunEventTest.java` | 验证领域事件 UUID、聚合 ID、不可变元数据和 SSE 游标。 | 事件存储实现前的契约基线。 |

## 8. `knowagent-extension`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-extension/pom.xml` | 扩展模块依赖定义。 | 后续承载 Tool、Skill 和 MCP 适配。 |
| `.../extension/package-info.java` | 声明扩展系统边界。 | 保持统一扩展入口说明。 |
| `.../extension/tool/ToolScope.java` | 定义工具授权范围。 | 按系统、租户、Agent 或 Run 做授权判断。 |
| `.../extension/tool/ToolDefinition.java` | 工具名称、描述、参数 Schema 和权限要求。 | 注册本地工具或映射 MCP 工具描述。 |
| `.../extension/tool/ToolInvocation.java` | 一次工具调用请求及上下文。 | Agent Runtime 从模型 tool call 转换而来。 |
| `.../extension/tool/ToolResult.java` | 工具执行结果及错误信息。 | 转为 tool 消息继续模型循环，并保存审计。 |
| `.../extension/tool/ToolRegistry.java` | 工具注册、查询和执行端口。 | 实现按 Run 授权、超时、隔离和动态加载。 |

## 9. `knowagent-workspace`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-workspace/pom.xml` | 工作区与对象存储模块依赖定义。 | 后续加入 MinIO SDK 适配。 |
| `.../workspace/package-info.java` | 声明虚拟工作区和文件存储边界。 | 保持路径安全规则说明。 |
| `.../workspace/path/VirtualPath.java` | 规范化虚拟路径并拒绝 `..` 路径穿越。 | 所有 Agent 文件访问先转换为该类型。 |
| `.../workspace/storage/ObjectKey.java` | 对象存储键值对象。 | 统一 tenant/workspace/file 的键命名。 |
| `.../workspace/storage/PutObjectCommand.java` | 带租户的对象上传命令。 | API 上传与内部产物保存共同使用。 |
| `.../workspace/storage/GetObjectCommand.java` | 带租户的对象读取命令。 | 禁止仅凭 ObjectKey 跨租户读取。 |
| `.../workspace/storage/DeleteObjectCommand.java` | 带租户的对象删除命令。 | 禁止仅凭 ObjectKey 跨租户删除。 |
| `.../workspace/storage/StoredObject.java` | 包含租户、对象键、类型、大小和散列的存储结果。 | 数据库附件记录引用该结果。 |
| `.../workspace/storage/ObjectStorageGateway.java` | 只接受租户命令的上传、读取和删除端口。 | MinIO 适配器统一生成 tenantId/objectKey 物理键。 |
| `.../workspace/path/VirtualPathTest.java` | 验证路径规范化和穿越攻击拦截。 | 增加 Windows 分隔符、空路径和编码边界测试。 |
| `.../workspace/storage/ObjectStorageCommandTest.java` | 验证所有存储操作必须携带租户并校验上传元数据。 | MinIO 适配器测试继续覆盖物理键隔离。 |

## 10. `knowagent-observability`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-observability/pom.xml` | 任务、审计、指标和评估模块依赖定义。 | 后续加入 Micrometer、追踪和评估实现。 |
| `.../observability/package-info.java` | 声明可观测与评估能力边界。 | 保持模块级说明。 |
| `.../observability/task/TaskStatus.java` | 使用显式终态标志的后台任务状态。 | 与任务表、前端任务列表和重试逻辑共用。 |
| `.../observability/task/TaskStatusTest.java` | 验证任务状态的终态标志。 | 新增状态时防止遗漏终态语义。 |

## 11. `knowagent-api`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-api/pom.xml` | 聚合全部业务模块并引入 Spring MVC、Security、数据库、Redis、Flyway；`docker-it` Profile 使用 Failsafe 运行 Testcontainers。 | Controller、SseEmitter、请求 DTO、认证过滤器和基础设施 Bean 在此装配；数据库迁移集成测试用 `mvn -Pdocker-it verify` 执行。 |
| `.../api/KnowAgentApiApplication.java` | HTTP API 进程的 Spring Boot 启动入口；Mapper 扫描由各业务模块配置负责。 | 保持薄启动类，避免全根包扫描把应用端口误注册为 Mapper。 |
| `.../api/config/SecurityBootstrapConfiguration.java` | 基于 Servlet `SecurityFilterChain` 的开发期安全占位配置，放行健康检查和系统信息接口。 | 替换为 JWT、RBAC、租户上下文和统一未认证响应。 |
| `.../api/system/SystemInfoController.java` | 提供 `/api/v1/system/info`，用于验证 API 已启动。 | 可增加公开版本信息，不能暴露密钥和内部配置。 |
| `.../resources/application.yml` | API 端口、数据源、Redis、Flyway、Actuator 和日志配置。 | 通过环境变量覆盖，不在文件中写生产密码。 |
| `.../resources/db/migration/V1__baseline.sql` | 已发布的 Flyway 空基线，用于固定迁移起点。 | 保持内容不变，禁止修改已执行迁移的校验和。 |
| `.../resources/db/migration/V2__identity_core.sql` 至 `V11__mcp.sql` | 按身份、权限、凭据、模型、知识库、聊天、运行时、异步任务、Skills 和 MCP 创建 31 张 MVP 表。 | 只允许新增更高版本迁移；字段、约束和锁语义以 `docs/database-schema.md` 为准。 |
| `.../api/database/FlywaySchemaIT.java` | 在 PostgreSQL 16 Testcontainer 中验证迁移、租户约束、Token 家族、Run 并发、Outbox 抢占和事务回滚。 | Docker 可用时通过 `docker-it` Profile 运行；默认构建不启动容器。 |
| `.../api/database/SecurityPersistenceIT.java` | 使用 PostgreSQL 16 和真实 Mapper 验证认证表映射、有效角色、跨租户隔离、乐观锁、Refresh Token 行锁，并启动真实 Spring Boot 上下文验证生产装配。 | Docker 可用时通过 `docker-it` Profile 运行；失败或跳过时不得勾选测试计划。 |

## 12. `knowagent-worker`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-worker/pom.xml` | 聚合后台处理所需模块和 Redis、数据库运行依赖。 | 加入 Stream 消费者、解析、索引和 Run 执行实现。 |
| `.../worker/KnowAgentWorkerApplication.java` | 非 Web Worker 的 Spring Boot 启动入口；Mapper 扫描由各业务模块配置负责。 | 启动消费者组、任务处理器和恢复扫描器，避免全根包误扫描端口接口。 |
| `.../worker/resources/application.yml` | Worker 数据源、Redis、进程类型和日志配置。 | 增加并发数、消费者名、重试和模型超时配置。 |

## 13. `web`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `web/README.md` | 说明 Vue 3 前端暂时只建立目录边界。 | API 契约稳定后初始化 Vite、TypeScript 和组件库。 |
| `web/src/apis/.gitkeep` | 保留 API 请求层空目录。 | 放认证、知识库、Agent、会话和任务请求客户端。 |
| `web/src/components/.gitkeep` | 保留通用组件目录。 | 放消息、引用、上传器和状态展示组件。 |
| `web/src/composables/.gitkeep` | 保留 Vue Composable 目录。 | 放 SSE、分页、上传和会话状态复用逻辑。 |
| `web/src/router/.gitkeep` | 保留路由目录。 | 配置登录、知识库、Agent、聊天和管理页面路由。 |
| `web/src/stores/.gitkeep` | 保留 Pinia 状态目录。 | 放用户、模型配置、会话和任务状态。 |
| `web/src/views/.gitkeep` | 保留页面目录。 | 实现登录、知识库管理、Agent 配置、聊天和任务页面。 |

## 14. 阅读顺序

建议先读 `pom.xml` 和 `docs/architecture.md` 理解模块边界，再读 common 中的基础类型、model/knowledge 的网关接口、agent-runtime 的状态和事件，最后查看 api/worker 如何装配。真正开始编码时，第一条实现链建议是：安全表迁移 -> JWT -> 租户上下文 -> 知识库 CRUD，而不是先实现 Agent 编排。
