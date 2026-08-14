# KnowAgent 系统架构

本文是 KnowAgent 当前架构的唯一真相源，负责模块边界、运行链路、状态约束和基础设施职责。项目范围与里程碑见 [PLAN.md](../PLAN.md)，原 Yuxi 文件与接口迁移见 [YUXI_REFACTOR_GUIDE.md](../YUXI_REFACTOR_GUIDE.md)。

## 1. 系统组件

```mermaid
flowchart LR
    User["Vue 用户端"] --> API["knowagent-api / Spring MVC"]
    API --> PG[(PostgreSQL)]
    API --> MinIO[(MinIO)]
    API --> Outbox["事务 Outbox"]
    Outbox --> Redis[(Redis Streams)]
    Redis --> Worker["knowagent-worker"]
    Worker --> Runtime["Agent Runtime"]
    Worker --> Parser["Document Parser"]
    Runtime --> Model["Chat / Embedding / Rerank Gateway"]
    Runtime --> Tools["ToolRegistry / MCP"]
    Parser --> Model
    Worker --> Milvus[(Milvus)]
    Worker --> PG
    Runtime --> EventStream["RunEventPublisher"]
    EventStream --> Redis
    API --> EventStream
```

API 负责认证、参数校验、事务入口和 SSE 连接；Worker 负责耗时任务和 Agent Run；领域模块不依赖 API 或 Worker。

## 2. 模块依赖矩阵

| 模块 | 可以依赖 | 禁止依赖 | 主要职责 |
|---|---|---|---|
| `common` | 无 | 所有业务模块 | 通用错误、租户 ID、领域事件 |
| `security` | common | api、worker | Principal、认证授权边界 |
| `model` | common、security | knowledge、runtime | Chat、Embedding、Rerank 端口 |
| `knowledge` | common、security、model | runtime、api | 解析、分块、向量检索契约 |
| `agent-runtime` | common、security、model、knowledge | api、worker | Run 状态、编排、事件和检查点 |
| `extension` | common、security、agent-runtime | api、worker | Tools、Skills、MCP、SubAgent |
| `workspace` | common、security | api、worker | 对象存储、附件、虚拟路径 |
| `observability` | common、security | api、worker | 任务、审计、指标和评估 |
| `api` | 所有业务模块 | worker | HTTP、Spring Security、SSE、事务装配 |
| `worker` | 所有业务模块 | api | Outbox 发布、Stream 消费和后台执行 |

约束：禁止 Controller 直接调用 Mapper；禁止跨模块调用 Mapper；供应商 SDK 只能出现在基础设施适配器；自定义 SQL 必须显式校验 tenant。

## 3. 数据职责

| 组件 | 保存内容 | 不承担的职责 |
|---|---|---|
| PostgreSQL | 用户、知识库元数据、chunk、会话、消息、Request、Run、Task、Checkpoint、Outbox | 大文件和向量近邻索引 |
| Redis Streams | 任务投递、消费者组状态、短期 Run 事件和 SSE 游标 | Run 最终状态的唯一副本 |
| MinIO | 原始文档、附件、Agent 产物 | 权限事实和业务状态 |
| Milvus | embedding、chunk ID 和租户/知识库过滤字段 | chunk 正文的最终事实 |
| Neo4j | 后续阶段的图实体和关系 | 普通 RAG 可用状态 |

PostgreSQL 始终是最终事实来源。Redis、Milvus 和 Neo4j 的数据必须可以依据 PostgreSQL 重建或校验。表结构、复合租户外键、锁 SQL 和数据生命周期见 [数据库设计](database-schema.md)。

## 4. Agent 执行与事件

`AgentOrchestrator.execute` 返回 `Flux<RunEvent>`。每次执行必须产生一个开始事件、零到多个中间事件，以及一个且仅一个终态事件。

`RunEvent.eventId` 是 UUID，用于业务幂等和审计；`PublishedRunEvent.cursor` 是 Redis Stream/SSE 游标，用于排序和 `Last-Event-ID` 重连，二者不得混用。

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> RUNNING: Worker 开始执行
    PENDING --> FAILED: 初始化失败
    PENDING --> CANCELLED: 执行前取消
    RUNNING --> COMPLETED: 正常结束
    RUNNING --> FAILED: 不可恢复错误
    RUNNING --> CANCELLED: 用户取消
    RUNNING --> INTERRUPTED: 等待审批或外部输入
    INTERRUPTED --> RUNNING: resume 成功
    INTERRUPTED --> FAILED: 超时或恢复失败
    INTERRUPTED --> CANCELLED: 用户或管理员取消
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

`INTERRUPTED` 不是终态。终态不可回退；相同状态的重复写入按幂等处理；其他非法转换必须拒绝并记录审计事件。

## 5. 用户提问链路

```mermaid
sequenceDiagram
    actor User as 用户
    participant API
    participant PG as PostgreSQL
    participant Redis as Redis Streams
    participant Worker
    participant Runtime as AgentOrchestrator
    participant Model as ChatModelGateway
    participant Tools as ToolRegistry

    User->>API: POST /api/v1/agents/{id}/requests
    API->>PG: 锁定 Conversation
    API->>PG: 事务写 Message、Request、Run、Outbox
    API-->>User: 202 Accepted + requestId/runId
    API->>Redis: Outbox Publisher 发布任务
    Worker->>Redis: 消费并 ACK 前保持 pending
    Worker->>Runtime: execute(context)
    Runtime-->>Worker: Flux<RunEvent>
    Runtime->>Model: stream(ChatCommand)
    Model-->>Runtime: ModelEvent 增量
    opt 模型请求工具
        Runtime->>Tools: invoke(ToolInvocation)
        Tools-->>Runtime: ToolResult
        Runtime->>Model: ToolResultMessage
    end
    Worker->>PG: 持久化消息、Run 状态和事件索引
    Worker->>Redis: 发布 PublishedRunEvent
    API-->>User: SSE id=cursor, data=RunEvent
```

## 6. 文档入库链路

```mermaid
sequenceDiagram
    actor User as 用户
    participant API
    participant MinIO
    participant PG as PostgreSQL
    participant Redis as Redis Streams
    participant Worker
    participant Parser
    participant Chunker
    participant Embed as EmbeddingGateway
    participant Milvus

    User->>API: 上传知识库文件
    API->>MinIO: put(PutObjectCommand)
    API->>PG: 事务写 File、Task、Outbox
    API-->>User: 202 Accepted + taskId
    API->>Redis: 发布索引任务
    Worker->>Parser: parse(ParseSource)
    Parser-->>Worker: ParsedDocument
    Worker->>Chunker: split(document, policy)
    Chunker-->>Worker: ChunkDraft 列表
    Worker->>Embed: embed(text batch)
    Embed-->>Worker: vectors
    Worker->>PG: 幂等写入 chunk 与索引状态
    Worker->>Milvus: upsert(VectorChunk)
    Worker->>PG: Task/File 更新为 SUCCEEDED/READY
```

失败必须记录当前阶段、错误码和可重试性；重复任务使用 file/chunk 幂等键，不得产生重复向量。

## 7. SSE 断线恢复链路

```mermaid
sequenceDiagram
    actor User as 用户
    participant API
    participant Redis as Run Event Stream
    participant PG as PostgreSQL

    User->>API: GET /agent-runs/{id}/events + Last-Event-ID
    API->>Redis: replay(runId, lastEventId)
    alt 游标仍在保留窗口
        Redis-->>API: cursor 之后的 PublishedRunEvent
        API-->>User: 顺序补发后继续实时订阅
    else 游标已过期或 Stream 缺失
        API->>PG: 查询 Run、消息和持久化事件索引
        PG-->>API: 当前事实状态与可恢复快照
        API-->>User: snapshot/reset 事件
        API->>Redis: 从当前最新游标继续订阅
    end
```

API 必须校验 runId 所属 tenant。客户端收到 reset 后用快照替换本地状态，不能把快照重复追加为增量。

## 8. 工具消息

`ChatMessage` 是密封接口：`TextChatMessage` 表示普通文本，`AssistantToolCallMessage` 保存有序工具调用，`ToolResultMessage` 通过 `toolCallId` 关联结果。供应商适配器负责与 Spring AI 消息互转，核心模型不依赖供应商私有类型。

## 9. 对象存储隔离

`ObjectStorageGateway` 的 put/get/delete 只接受带 `TenantId` 的命令。MinIO 适配器统一生成 `<tenantId>/<objectKey>`，调用方不能提供完整物理键；数据库附件记录同时保存 tenant、object key、散列和大小。

## 10. 认证请求上下文与租户隔离

`TenantContext`(knowagent-security)用普通 `ThreadLocal` 保存当前请求的 `TenantPrincipal`(`tenantId`、`userId`、`roles`、`permissions`,均为不可变集合)。故意不使用 `InheritableThreadLocal`,避免值跨线程池(Worker 执行器、SSE 异步派发)传播而把租户 A 的身份泄漏进租户 B 的工作。

上下文 **fail closed**:`TenantContext.requireTenantId()` 在没有认证上下文时抛 `BusinessException(AUTHENTICATION_REQUIRED)`,受保护业务查询因此被拒绝,而不是默认查询全部租户。

请求链:

1. `knowagent-api` 的 `TenantContextFilter`(`OncePerRequestFilter`)从 `SecurityContextHolder` 的认证 principal 解析 `TenantPrincipal`,并在 `try/finally` 中 `set`/`clear`。
2. 过滤器注册在认证过滤器之后、Spring Security `AuthorizationFilter` 之前，保证认证 principal 已就绪，并让租户感知的授权逻辑能够读取 `TenantContext`；即使授权被拒绝，也会执行过滤器的 `finally` 清理。
3. 租户来源只能是认证 principal。客户端请求头(如 `X-Tenant-Id`)永不作为租户来源;Controller 不得手工 `set`/`clear`。
4. `finally` 清理保证:Servlet 线程复用时下个请求观察不到上个请求的租户;请求中途抛异常上下文依然被清理。

数据访问:

- `SecurityPersistenceConfiguration` 装配 `TenantLineInnerInterceptor` 在乐观锁之前,普通 MyBatis-Plus 查询/写入自动追加 `tenant_id = <context>`。
- `TenantContextTenantLineHandler` 显式忽略没有 `tenant_id` 的表:`tenants`(唯一无 tenant_id 的业务表)与 `flyway_schema_history`。
- 只有极少数认证前 Mapper 方法允许 `@InterceptorIgnore(tenantLine = "1")` 绕过,且绕过 SQL 必须自身显式携带 `tenant_id`(或属于 tenant 根表、refresh token 全局唯一 hash 的文档化例外)。该白名单由 `SecurityMapperSqlContractTest` 精确锁定,新增绕过方法会使测试失败。
- 插入规则:PO 未设置 `tenantId` 时由拦截器从上下文填充;PO 显式声明 `tenantId` 时被信任。因此应用服务必须始终用 `TenantContext.requireTenantId()` 派生租户,不能接受客户端传入的租户。
- 代码审查规则:自定义 SQL、锁查询、统计与批量更新必须显式包含 `tenant_id`;锁查询与统计只能通过显式租户条件执行。

开发者管理员初始化:

- `AdminBootstrapRunner` 按 `bootstrap.enabled` 显式开关在启动时执行;缺参、弱密码或非法 slug 直接抛异常拒绝启动,不会自动生成并打印密码。
- 整个流程在一个事务内创建初始租户、`ADMIN` 系统角色、管理员用户和 `user_roles` 绑定,按 slug / tenant+code / tenant+login 幂等,任一步失败整体回滚。
- 密码经 `PasswordHasher`(Argon2id)编码后落库,原始密码不会出现在日志或异常中。初始化运行在认证前,不存在 `TenantContext`,因此所有存在性查询复用上文认证前显式租户查询白名单,写入的 PO 显式携带 `tenantId` 被拦截器信任。

Access Token 基础设施:

- `AccessTokenIssuer`(knowagent-api/security)负责签发 JWT;`AccessTokenAuthenticationConfiguration` 用 Spring Security 官方 OAuth2 Resource Server + Jose(`NimbusJwtEncoder`/`NimbusJwtDecoder`)签发与校验,不手写 JWT 编解码器。HS256 对称密钥只从环境变量读取(`JWT_SECRET`,base64,解码后至少 32 字节);`issuer`、`audience`、有效期与密钥通过 `@ConfigurationProperties(prefix = "jwt")` 类型安全绑定,`application.yml` 不含任何真实密钥,缺参时启动即失败。
- 令牌声明契约:签名使用 HS256;必需声明 `sub`(用户 UUID)、`tenant_id`(租户 UUID)、`roles`、`permissions`、`jti`、`iat`、`exp`;校验签名、issuer、audience、过期时间与必需声明。`permissions` 声明的唯一来源是 `TenantPrincipal.permissions()`(登录/刷新时从数据库聚合的有效角色权限),`AccessTokenIssuer.issue(principal)` 直接读取该字段,principal 是 JWT permissions claim 的唯一事实来源。
- 请求链:`BearerTokenAuthenticationFilter` 经 `JwtAuthenticationProvider` 校验 token → `JwtToTenantAuthenticationConverter` 把 JWT 转换为 `JwtTenantAuthenticationToken`,principal 为 `TenantPrincipal` → `TenantContextFilter` 从 principal 建立 `TenantContext`,`finally` 清理。必需声明缺失或畸形(如 `tenant_id` 不是 UUID)时转换器抛 `InvalidBearerTokenException`,统一得到稳定 JSON 401。
- 路由规则:`/actuator/health/**`、`/api/v1/system/info`、`/api/v1/auth/login`、`/api/v1/auth/refresh`、`/api/v1/auth/logout` 匿名;其余请求要求认证。资源服务器入口点与全局 `exceptionHandling` 共用 `JsonAuthenticationEntryPoint`/`JsonAccessDeniedHandler` 输出 JSON 401/403,不保留 Spring Boot 自动生成的 in-memory 用户与开发密码。
- 接口级授权使用 Spring Security Method Security:`SecurityBootstrapConfiguration` 类级 `@EnableMethodSecurity`,`@PreAuthorize("hasAuthority('USER_READ')")` 保护管理查询。JWT 的 `permissions` 声明经转换器原样映射为 authority(因此用 `hasAuthority` 而非 `hasRole`);方法级 `AccessDeniedException` 复用 `JsonAccessDeniedHandler` 输出稳定 JSON 403。权限常量集中在 `SecurityPermissions`:`USER_READ` 决定用户读取权限;`USER_ADMIN` 本阶段只定义常量、不授予任何人、不实现写接口。
- Access Token 基础设施与 Refresh Token 轮换/登出均已实现(见下文「登录、轮换与登出接口」)。

登录与当前用户接口:

- `POST /api/v1/auth/login`(`AuthController`,匿名)把请求映射为 `LoginCommand`(租户 slug、登录名、密码、来源 IP、User-Agent),调用 `Login` 端口;`GET /api/v1/users/me`(`UserController`,需认证)从 `@AuthenticationPrincipal TenantPrincipal` 读取当前身份。
- `POST /api/v1/auth/refresh` 与 `POST /api/v1/auth/logout`(`AuthController`,匿名)分别映射为 `RefreshCommand`(原始 token + 来源 IP + User-Agent)与 `LogoutCommand`(原始 token)。刷新由 API 层 `RefreshAuthenticationService` 事务门面协调 `RefreshTokens` 与 `AccessTokenIssuer`;轮换响应与登录同构(tokenType/accessToken/refreshToken/expiresIn),复用 `LoginResponse`;登出直接调用 `RefreshTokens` 并返回 204。两个 DTO 与命令的字符串输出均隐藏原始 token。
- 登录流程(`LoginService`,**不持有事务**):标准化 slug/login 小写 → 按 slug 解析 ACTIVE 且未删除租户 → 按 tenant_id+login_name 查询未删除用户 → 状态检查 → Argon2id 密码校验 → 聚合有效角色与权限 → 调用 `LoginSuccessHandler` 提交成功写入 → 签发高熵(32 字节 Base64url)Refresh Token,仅把 SHA-256 十六进制哈希落库。所有读为自动提交;写路径各自独立事务,单个登录**最多持有一个数据库连接**,并发失败登录不会耗尽连接池。
- 失败策略:未知租户、未知用户与错误密码统一抛 `INVALID_CREDENTIALS`(401),不泄露是哪一步失败,且未知租户/用户仍执行一次预计算的 dummy Argon2 校验,并对未知租户用固定 dummy tenant ID 执行一次用户查询,使三者工作量一致(租户查询 + 用户查询 + Argon2),响应耗时不能区分账号是否存在(防计时枚举);禁用/锁定账户在密码校验前抛稳定的 `ACCOUNT_DISABLED`/`ACCOUNT_LOCKED`(403);登录失败阈值与临时锁定窗口来自 `auth.login.*` 配置(`LoginPolicies`),不在代码写死。锁定按窗口判定:`LOCKED` + 过期窗口可重试,成功登录清除计数与锁定;`LOCKED` + 空窗口视为永久锁。
- 事务边界:成功路径由 `LoginSuccessHandler`(`@Transactional`)把「带版本守卫的登录状态更新 + Refresh Token 插入」在同一事务提交,冲突抛 `CONFLICT`(409);失败计数由 `LoginFailureRecorder`(`@Transactional` 普通独立事务,因 `LoginService` 无外层事务故不再嵌套 `REQUIRES_NEW`)写入,登录方法随后抛异常不影响其提交;计数在数据库内原子递增(`login_failed_count = login_failed_count + 1`),并发错误密码不丢失计数,达到阈值即置 `LOCKED`。
- 登录运行在认证前,没有 `TenantContext`,因此所有查询/写入复用认证前显式租户白名单:自定义 SQL 自带 `tenant_id` 条件,插入的 RefreshTokenPo 显式携带 `tenantId` 被拦截器信任。
- 响应契约:登录响应只含 tokenType/accessToken/refreshToken/expiresIn,Refresh Token 原始值仅出现一次;`/users/me` 返回 userId/tenantId/tenantSlug/loginName/displayName/roles/permissions。两者都不含密码哈希、token 哈希或锁定内部字段;DTO 校验失败统一返回 JSON 400 `VALIDATION_ERROR`。
- Refresh Token 轮换与登出(`RefreshTokenService`,`refresh` 与 `logout` 均为事务方法):只把 SHA-256 哈希作为查询键(禁止明文查库或落库)。所有 refresh、重放与 logout 先锁定家族根 token(`findFamilyRootForUpdate` 按 `id = family_id` FOR UPDATE,单一串行化点),锁下按 id 重读提交的 token 并校验状态、`expires_at`、用户与租户状态。账户规则集中在 `AccountAuthenticationPolicy`:用户必须 `status == ACTIVE`,且未来的 `login_locked_until` 无论当前 status 是否仍为 ACTIVE 都视为有效临时锁;租户必须 ACTIVE 未删除。随后把 ACTIVE token 置为 CONSUMED 并插入同一 `family_id` 的子 token(`parent_token_id` 指向旧 token,root 满足 `family_id = id`,唯一部分索引 `uq_refresh_tokens_one_child` 约束单子),再按当前角色权限返回签发上下文。API 层 `RefreshAuthenticationService` 以 `@Transactional(noRollbackFor = RefreshTokenInvalidException.class)` 包住轮换、Access Token 签名和响应构造:签名或其他基础设施异常会让旧 token 消费与子 token 插入整体回滚,避免生成但未返回的凭据;CONSUMED token 重放、CAS 失败或唯一子 token 冲突则撤销家族并抛 `RefreshTokenInvalidException`,noRollbackFor 使安全撤销仍提交。子 token 插入运行在保存点(`insertChild` 的 `Propagation.NESTED`)内,PostgreSQL 唯一约束错误只回滚插入、事务仍可用于家族撤销,且仅 `uq_refresh_tokens_one_child` 映射为重放,其他唯一约束错误原样抛出。因为轮换/重放/登出共享同一家族根锁,子 token 刷新与根 token 重放/登出的并发最终收敛为家族无 ACTIVE。登出按 hash 定位 token 后锁定家族根并撤销家族内仍有效 token,重复登出幂等。轮换/登出运行在认证前,无 `TenantContext`,因此写入 SQL(消费、家族撤销)显式携带 `tenant_id` 并纳入 tenant-line 白名单。

租户内用户管理查询接口:

- `GET /api/v1/users`(分页查询)与 `GET /api/v1/users/{userId}`(详情)位于 `UserController`,均需 `USER_READ`(`@PreAuthorize`)。两者由 `UserQueryService` 支撑,租户来源只能是认证 principal:`@AuthenticationPrincipal TenantPrincipal` 取 `principal.tenantId()`,服务层不解析任何请求参数、不读取请求头。
- 分页/统计不走 MyBatis-Plus `PaginationInnerInterceptor`(代码库无此拦截器,且审查规则要求「统计只能通过显式租户条件执行」):手写 `SELECT COUNT(*)` + `LIMIT/OFFSET` 自定义 SQL,两者都显式携带 `tenant_id = #{tenantId}` 与 `deleted_at IS NULL`。这两个方法**不在**认证前绕过白名单,运行在租户插件下,插件额外注入相同的 `tenant_id` 作为 fail-closed 兜底。关键词模糊匹配用 `LOWER(login_name/display_name) LIKE LOWER(?) ESCAPE '\'`,`\`/`%`/`_` 由服务层转义;可选 status/keyword 用静态 `COALESCE(#{x},'')=''` 条件,避免动态 `<script>` 增加 JSqlParser 改写风险。
- 分页参数校验:page<1、size∉[1,100] 或 `(page-1)*size` 超出持久化层支持的 OFFSET 范围时返回 400 `VALIDATION_ERROR`;非法 status 枚举或 userId UUID 由 `ApiExceptionHandler` 的 `MethodArgumentTypeMismatchException` 处理器统一返回 400 `VALIDATION_ERROR`(此前误报 500)。
- 隔离与泄露:跨租户 `userId` 与不存在用户同样返回 404 `RESOURCE_NOT_FOUND`(不泄露资源存在性);过期 `user_roles`(expires_at 已过)在登录时聚合出空有效角色、不产生任何权限,访问受保护端点返回 403。响应 DTO(`UserItemResponse`/`UserPageResponse`)只含 userId/departmentId/loginName/displayName/email/phoneNumber/status/createdAt,结构上不可能泄露 passwordHash/loginFailedCount/loginLockedUntil 等内部字段。

## 11. 架构决策

- [ADR 0001：Maven 多模块单体](adr/0001-modular-monolith.md)
- [ADR 0002：PostgreSQL Outbox 与 Redis Streams](adr/0002-outbox-redis-streams.md)
- [ADR 0003：MyBatis-Plus](adr/0003-mybatis-plus.md)
- [ADR 0004：Spring MVC 与 Reactor Flux](adr/0004-spring-mvc.md)
- [ADR 0005：Milvus](adr/0005-milvus.md)
