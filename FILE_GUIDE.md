# KnowAgent 文件职责指南

本文覆盖 `KnowAgent` 当前全部手写文件。`target/` 和 `.m2/` 属于构建产物或本地依赖缓存，不纳入说明。

当前工程处于架构骨架阶段：多数 Java 文件是领域数据类型或端口接口，用来约束模块边界；它们不是完整业务实现。后续实现类应放在对应模块的 `application`、`infrastructure` 或具体业务包中，不要把供应商 SDK 直接写进这些核心接口。

## 1. 根目录与构建配置

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.dockerignore` | 控制 Docker 构建上下文，排除 Git、本地缓存和编译产物。 | 新增不需要打进镜像的目录时同步更新。 |
| `.editorconfig` | 统一编码、缩进、换行和文件尾规则。 | IDE 应启用 EditorConfig，减少无意义格式差异。 |
| `.env.example` | 罗列 Compose 和应用需要的环境变量示例，不保存真实密钥；包含 MinIO 客户端和本地解析 `PARSE_*` 安全默认值。 | 本地复制为 `.env` 后填写模型、数据库和对象存储配置，解析预算按部署规模调整。 |
| `.gitignore` | 排除 IDE 文件、密钥文件、日志、缓存和构建产物。 | 新增本地运行产物时补充规则。 |
| `.mvn/maven.config` | 让所有 Maven 命令自动使用项目内 `settings.xml`。 | 保证不同机器使用一致的仓库配置入口。 |
| `.mvn/settings.xml` | 将 Maven 本地仓库放到项目 `.m2/repository`，绕开机器级错误配置。 | 可按团队环境增加镜像，但不要写认证凭据。 |
| `pom.xml` | 父 POM；声明 10 个模块、Java 21、依赖版本、测试与 Enforcer 规则。 | 所有跨模块版本在这里集中管理。 |
| `docker-compose.yml` | 编排 API、Worker、PostgreSQL、Redis、MinIO、Milvus、etcd 和可选 Neo4j；`x-app-environment` 向 API/Worker 统一透传 MinIO 与 `PARSE_*` 解析预算。 | 后续增加健康检查、初始化脚本和生产配置覆盖。 |
| `README.md` | 项目入口，说明模块、构建、运行方式和当前完成度。 | 每完成一个可演示阶段同步更新。 |
| `PLAN.md` | 定义 12 周交付范围、开发阶段和验收入口。 | 只维护范围和里程碑，架构细节统一链接到架构文档。 |
| `DEVELOPMENT_PROMPTS.md` | 提供认证、授权与租户里程碑的分阶段可执行提示词。 | 按顺序一次执行一个提示词，完成验收后再进入下一阶段。 |
| `KNOWLEDGE_DEVELOPMENT_PROMPTS.md` | 提供模型供应商、知识库 CRUD、MinIO、异步入库、Embedding、Milvus、检索与删除的第 9～20 号开发提示词。 | 认证里程碑完成后按顺序执行；每条提示词都独立实现、测试和验收。 |
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
| `knowagent-security/pom.xml` | 安全模块依赖 common，并为模块内持久化适配引入 MyBatis-Plus、Jackson 与 PostgreSQL JDBC，另加 `mybatis-plus-jsqlparser-4.9` 提供 TenantLineInnerInterceptor；密码散列只引入 `spring-security-crypto`（HTTP 依赖不进本模块）与 Bouncy Castle `bcprov-jdk18on`（Spring Boot BOM 不托管版本，固定 1.80）。 | 后续加入 JWT 或认证应用服务时继续复用同一 PasswordEncoder。 |
| `.../security/package-info.java` | 声明认证、授权和租户上下文模块边界。 | 保持为包级架构说明。 |
| `.../security/principal/TenantPrincipal.java` | 表示已认证用户、租户、角色和权限集合。 | JWT/OIDC/API Key 认证成功后构造，并注入请求上下文。 |
| `.../security/context/TenantContext.java` | 请求级租户上下文，用普通 ThreadLocal 保存 TenantPrincipal；`requireTenantId()` 在无上下文时 fail closed 抛出 AUTHENTICATION_REQUIRED。 | 只允许 API 过滤器 set/clear，Controller 和跨模块调用方不得直接操作；Worker 后续从任务信封填充。 |
| `.../security/domain/package-info.java` | 声明身份认证领域层不依赖持久化对象。 | 新增安全领域行为时保持基础设施类型在边界外。 |
| `.../security/domain/tenant/Tenant.java`、`TenantStatus.java` | 表示租户身份、不可变 JSON settings 及 ACTIVE/SUSPENDED/DISABLED 状态。 | 登录前按 slug 解析租户，状态名保持与数据库 CHECK 一致。 |
| `.../security/domain/user/User.java`、`UserStatus.java` | 表示本地用户、密码散列、锁定信息和乐观锁版本，并在字符串输出中隐藏密码散列。 | 登录应用服务校验状态、密码和失败计数；用户管理查询返回该领域对象。 |
| `.../security/domain/user/UserPage.java` | 表示租户内用户分页结果（不可变用户列表 + 非负总数）。 | 用户管理查询端口返回它，禁止直接返回持久化对象。 |
| `.../security/domain/role/Role.java`、`RoleStatus.java` | 表示租户角色及不可变权限集合。 | 登录和 RBAC 应用服务加载有效角色后聚合权限。 |
| `.../security/domain/role/UserRole.java` | 表示用户角色绑定及有效期。 | 管理员初始化和授权写入使用，过期判断不依赖 HTTP 层。 |
| `.../security/domain/role/SecurityPermissions.java` | 集中定义后续管理接口需要的稳定权限码（TENANT_/DEPARTMENT_/USER_/ROLE_/MODEL_PROVIDER_/KNOWLEDGE_BASE_/KNOWLEDGE_FILE_ 读写、TASK_READ、AUDIT_READ），并提供 `ADMIN_ROLE_PERMISSIONS`（已含 KNOWLEDGE_BASE_READ/WRITE、KNOWLEDGE_FILE_READ/WRITE、TASK_READ）；`USER_ADMIN` 本阶段只定义常量、不加入 `ADMIN_ROLE_PERMISSIONS`、不授予任何人。 | 任何地方都从它引用权限码，禁止散落魔法字符串。 |
| `.../security/domain/token/RefreshToken.java`、`RefreshTokenStatus.java` | 表示 Refresh Token 家族、所有权、生命周期和版本；字符串输出不包含 token_hash。 | 轮换/重放/登出在事务内锁定家族根 token（`id = family_id` FOR UPDATE）后重读校验状态、过期与租户/用户关系。 |
| `.../security/application/port/out/package-info.java` | 声明安全应用层访问数据库的输出端口边界。 | 应用服务只依赖端口，禁止暴露 Mapper。 |
| `.../security/application/port/out/TenantRepository.java` | 提供 ACTIVE、未删除租户的 slug 与 ID 查询端口（`findActiveBySlug`/`findActiveById`）。 | 登录前租户解析、/users/me 租户校验调用。 |
| `.../security/application/port/out/UserRepository.java` | 提供显式 tenantId + loginName 的未删除用户查询端口，以及显式租户的用户 ID 查询、带版本守卫的登录状态更新、数据库原子失败计数递增和租户内用户分页（`findById`/`updateLoginState`/`recordLoginFailure`/`search`，`search` 接收服务层预先转义的 LIKE pattern 与可空状态，返回 `UserPage`）。 | 登录前调用，不依赖尚未建立的 TenantContext；`updateLoginState` 返回 false 表示并发冲突，`recordLoginFailure` 返回受影响行数；`search` 的分页/统计 SQL 显式携带 tenant_id。 |
| `.../security/application/port/out/RoleRepository.java` | 提供显式租户和用户的当前有效角色查询端口。 | 登录和鉴权阶段聚合角色与 permissions。 |
| `.../security/application/port/out/RefreshTokenStore.java` | 提供全局唯一 token_hash 普通查询、按 id+tenant 重读（`findById`）、家族根锁（`findFamilyRootForUpdate`，按 `id = family_id` FOR UPDATE 串行化整个家族）、插入（`insert`）与保存点子插入（`insertChild`，仅子 token 冲突回滚插入）、CAS 消费（`consume`，按 ACTIVE 守卫返回布尔）与家族撤销（`revokeFamily`，撤销家族内仍 ACTIVE 的 token，显式携带 tenant_id）端口；插入只接收只含哈希的领域模型。 | 登录签发 token 时插入；轮换在锁定事务中消费旧 token 并插入子 token，重放/登出时锁定家族根后撤销整个家族。 |
| `.../security/application/port/out/UserRoleStore.java` | 提供用户角色绑定写入端口。 | 管理员初始化和授权应用服务通过该端口写入，禁止直接调用 Mapper。 |
| `.../security/application/port/out/PasswordHasher.java` | 密码散列输出端口：`encode` 与 `matches`。 | 只接受原始密码并返回散列，禁止把明文传入持久化层。 |
| `.../security/application/port/out/AdminBootstrapRepository.java` | 开发管理员初始化的持久化边界：租户/角色/用户幂等查询，以及用户角色绑定的原子确保操作。 | 仅被 AdminBootstrapService 使用；认证前查询和绑定 UPSERT 均显式携带 tenant_id。 |
| `.../security/application/service/AdminBootstrap.java` | 开发管理员初始化入端口：`initialize(AdminBootstrapRequest)`。 | 启动 Runner 调用，不暴露任何 HTTP 端点。 |
| `.../security/application/service/AdminBootstrapRequest.java` | 校验并规范化初始化参数：slug/login 小写、缺省名回退、密码至少 12 字符、拒绝空值。 | 参数来自环境变量，校验失败即拒绝启动，不自动生成密码。 |
| `.../security/application/service/AdminBootstrapService.java` | 幂等创建租户、`ADMIN` 系统角色、管理员用户和 `user_roles` 绑定，整体 `@Transactional`；密码经 PasswordHasher（Argon2id）编码后落库，UUID 全部在 Java 预生成。 | 绑定通过原子 UPSERT 创建或恢复过期记录；重复启动不产生重复数据，任一步失败全部回滚。 |
| `.../security/application/service/Login.java`、`LoginCommand.java`、`LoginResult.java` | 登录入端口、不可变命令（含来源 IP 与 User-Agent，字符串输出隐藏密码）与结果（principal、permissions、一次性 refresh token 原始值、过期时间，字符串输出隐藏 token）。 | Controller 只依赖 Login 端口，不接触 Mapper；Command 由 API 层从 HTTP 请求构造。 |
| `.../security/application/service/LoginPolicies.java` | 不可变登录策略：最大失败次数、临时锁定窗口、Refresh Token 有效期，构造时校验全为正数。 | 由 API 层的 `auth.login.*` 配置属性映射，禁止在代码里写死阈值。 |
| `.../security/application/service/AccountAuthenticationPolicy.java` | 登录与刷新共用的账户状态规则：未来 `login_locked_until` 无论 status 是否仍为 ACTIVE 都视为临时锁；登录允许 LOCKED + 过期窗口用密码恢复，刷新只允许无有效锁窗口的 ACTIVE 用户。 | 状态或锁定语义变化时只在此处调整，避免登录与刷新判断漂移。 |
| `.../security/application/service/LoginService.java` | **不持有事务**的登录主流程：标准化 slug/login → 解析 ACTIVE 租户 → 按租户查询用户 → 状态检查 → Argon2id 校验 → 聚合有效角色与权限 → 委托 `LoginSuccessHandler` 提交成功写入。所有读为自动提交、写各自独立事务，单个登录最多持有一个数据库连接（并发失败登录不会耗尽连接池）；未知租户/用户/密码统一 INVALID_CREDENTIALS，且未知账号也执行一次预计算 dummy Argon2 校验、未知租户用固定 dummy tenant ID 跑一次用户查询，使三者工作量一致（防计时枚举）；禁用/锁定返回稳定错误码，锁定按窗口判定（LOCKED+过期窗口可重试、LOCKED+空窗口视为永久锁）。 | 不负责签名 Access Token（由 API 层 AccessTokenIssuer 完成）；Refresh Token 轮换与登出由 `RefreshTokenService` 承担（见下）。 |
| `.../security/application/service/LoginSuccessHandler.java` | 登录成功写入的独立事务服务（`@Transactional`）：带版本守卫的登录状态更新（返回 false 抛 CONFLICT）+ Refresh Token 插入（只存 SHA-256 哈希）在同一事务提交，并生成高熵（32 字节 Base64url）Refresh Token 原始值。 | 因 `LoginService` 无外层事务，该事务独立提交，与失败路径互不干扰；轮换与单次使用由 `RefreshTokenService` 在锁定事务中实现。 |
| `.../security/application/service/LoginFailureRecorder.java` | 在普通独立事务（`@Transactional`，因 `LoginService` 无外层事务故不再嵌套 `REQUIRES_NEW`）里把失败计数原子递增并在达到阈值时置 LOCKED + 临时锁定窗口（经 `UserRepository.recordLoginFailure` 走数据库侧 `login_failed_count + 1`）。 | 解决登录方法抛异常导致失败计数随事务回滚的问题；数据库原子递增保证并发错误密码不丢失计数、无法绕过锁定阈值；单登录单连接，不再有池耗尽风险。 |
| `.../security/application/service/CurrentUser.java`、`CurrentUserService.java` | `/users/me` 的应用服务：按已认证 principal 加载 ACTIVE 租户、未删除用户与有效角色，聚合角色码和权限；缺失即抛 RESOURCE_NOT_FOUND。 | 供 UserController 读取当前用户身份，禁止直接返回持久化对象。 |
| `.../security/application/service/UserQueryService.java` | 租户内用户查询应用服务：`pageUsers` 校验分页参数（page<1、size∉[1,100] 或计算后的 OFFSET 超出持久化层支持范围 → VALIDATION_ERROR）、trim/空白化 keyword、按 `\ → \\, % → \%, _ → \_` 转义并包裹 `%...%` 生成 LIKE pattern 后委托 `search`；`userDetail` 按租户+ID 查用户，空 → RESOURCE_NOT_FOUND。租户一律由调用方（来自 principal）传入，服务层不解析任何请求参数。 | 供 UserController 查询，禁止直接返回持久化对象；keyword 转义在服务层完成，避免 SQL 注入。 |
| `.../security/application/service/RefreshTokens.java` | 轮换与登出入端口：`refresh(RefreshCommand)` 返回 `LoginResult`（与登录同构），`logout(LogoutCommand)` 无返回值。 | Controller 只依赖该端口，不接触 Mapper。 |
| `.../security/application/service/RefreshCommand.java`、`LogoutCommand.java` | 不可变命令：`RefreshCommand` 携带原始 token、来源 IP 与 User-Agent，`LogoutCommand` 携带原始 token；字符串输出均隐藏原始 token。 | 由 API 层从 HTTP 请求构造，原始 token 只被哈希后用于查询。 |
| `.../security/application/service/RefreshTokenInvalidException.java` | 轮换拒绝的稳定业务异常（INVALID_CREDENTIALS→401），消息不泄露家族/会话信息；`refresh` 声明为 `noRollbackFor` 使重放撤销仍提交。 | 未知/过期/撤销/已消费 token 与唯一子 token 冲突统一抛它。 |
| `.../security/application/service/RefreshTokenHashes.java` | 包内工具：SHA-256 十六进制哈希、32 字节 Base64url 原始 token 生成、User-Agent 截断。 | 登录与轮换共用，禁止在其他地方拼接或记录原始 token。 |
| `.../security/application/service/RefreshTokenService.java` | 轮换与登出主流程（`refresh` 与 `logout` 均为事务方法）：先锁定家族根 token（`findFamilyRootForUpdate`，`id = family_id` FOR UPDATE）→ 锁下按 id 重读校验状态/过期/共享账户策略/租户 → CAS 消费（失败按并发撤销家族）→ 保存点插入同家族子 token（`insertChild`，仅 `uq_refresh_tokens_one_child` 冲突转重放，其他唯一约束原样抛出）→ 聚合角色权限返回 LoginResult；CONSUMED 重放/CAS 失败/子插入冲突都撤销家族并抛稳定异常；`logout` 按 hash 定位家族根并撤销仍有效 token（幂等）。 | 运行在认证前无 TenantContext，写入 SQL 显式携带 tenant_id 并纳入 tenant-line 白名单；API 层事务门面继续覆盖 JWT 签名。 |
| `.../security/infrastructure/persistence/package-info.java` | 声明安全模块 MyBatis-Plus 持久化适配边界。 | 基础设施实现只向应用层暴露端口。 |
| `.../persistence/entity/TenantPo.java`、`UserPo.java`、`RolePo.java`、`UserRolePo.java`、`RefreshTokenPo.java` | 映射五张认证主链表的 UUID、枚举、timestamptz、jsonb、inet 和 version 字段；主键使用应用输入模式。 | 仅供 Mapper 和转换器使用，禁止直接返回 Controller。 |
| `.../persistence/typehandler/JsonNodeJsonbTypeHandler.java` | 使用 Jackson 和 PostgreSQL `PGobject` 映射通用 JSONB。 | 租户 settings 等 JSON 对象字段复用。 |
| `.../persistence/typehandler/PermissionSetJsonbTypeHandler.java` | 校验 JSONB 字符串数组并映射不可变 `Set<String>`。 | 禁止手工拼接 permissions JSON。 |
| `.../persistence/typehandler/PostgresInetTypeHandler.java` | 在 PostgreSQL inet 与 Java `InetAddress` 间转换。 | Refresh Token 签发来源 IP 持久化复用。 |
| `.../persistence/typehandler/PostgresUuidTypeHandler.java` | 为 MyBatis-Plus 自动 ResultMap 显式映射 PostgreSQL UUID。 | 所有应用预生成 UUID 的持久化对象复用。 |
| `.../persistence/converter/IdentityPersistenceConverter.java` | 将五类持久化对象转换为领域模型，并把损坏数据转换为稳定内部错误；`toPersistence` 支持 Tenant/User/Role/UserRole 反向转换。 | 写入端口增加时在同一边界补充反向转换。 |
| `.../persistence/mapper/TenantMapper.java` | 查询 ACTIVE、未删除租户，并整体忽略 tenant-line 插件；`selectBySlug` 供初始化幂等查询。 | `tenants` 没有 tenant_id，禁止被租户插件改写。 |
| `.../persistence/mapper/UserMapper.java` | 用显式 tenant_id + login_name 查询未删除用户（`selectByTenantAndLoginName`），显式租户的用户 ID 查询（`selectByIdAndTenant`），带版本守卫的登录状态更新（`updateLoginState`：仅更新登录状态字段并 `version=version+1`，`WHERE ... AND version=#{version}`），数据库原子失败计数递增（`recordLoginFailure`：`login_failed_count + 1`，达到阈值置 LOCKED + 窗口），以及租户内用户分页与统计（`selectUserPage`：`LIMIT/OFFSET` + 可选 status/keyword 的 `COALESCE` 静态条件 + `LIKE ... ESCAPE`；`countUsers`：同 WHERE 的 `SELECT COUNT(*)`，两者均显式携带 tenant_id）。 | 认证前查询/写入方法绕过 tenant-line，但 SQL 自身保持租户条件；`updateLoginState` 返回影响行数判断并发冲突，`recordLoginFailure` 靠数据库原子递增保证并发计数不丢失；`selectUserPage`/`countUsers` **不加** `@InterceptorIgnore`，留在租户插件下并显式携带 tenant_id。 |
| `.../persistence/mapper/RoleMapper.java` | 显式联结 users、user_roles、roles 并过滤禁用、删除和过期授权；`selectByTenantAndCode` 供初始化幂等查询。 | 自定义 SQL 必须继续对每个租户表保留 tenant_id 条件。 |
| `.../persistence/mapper/UserRoleMapper.java` | 提供用户角色绑定的 MyBatis-Plus 基础映射，以及 `ensureEffectiveAssignment` 原子 UPSERT：缺失时插入、过期时恢复、有效时保持不变。 | SQL 显式携带 tenant_id，并依赖 `uq_user_roles_assignment` 防止重复绑定；后续服务通过应用端口使用。 |
| `.../persistence/mapper/RefreshTokenMapper.java` | 按全局唯一 token_hash 查询（文档化例外），按 id+tenant 重读（`selectByIdAndTenant`），家族根锁（`selectFamilyRootForUpdate`：`WHERE tenant_id=? AND id=#{familyId} ... FOR UPDATE`，`id = family_id` 串行化整个家族），CAS 消费（`consumeActive`：`WHERE tenant_id=? AND id=? AND status='ACTIVE'`，更新 status/consumed_at/version）与家族撤销（`revokeActiveFamily`：`WHERE tenant_id=? AND family_id=? AND status='ACTIVE'`，更新 status/revoked_at/revoke_reason/version），均显式携带 tenant_id。 | 认证前无 tenant 上下文的受控例外，绕过 SQL 自身保持租户条件；返回后仍校验所有权。 |
| `.../persistence/repository/MyBatisTenantRepository.java`、`MyBatisUserRepository.java`、`MyBatisRoleRepository.java`、`MyBatisRefreshTokenStore.java` | 将查询与写入输出端口适配到 Mapper，并返回领域模型；`MyBatisRefreshTokenStore` 的 `insertChild` 用 `Propagation.NESTED` 保存点执行插入（唯一子 token 冲突只回滚插入、外层事务仍可撤销家族），`consume` 以 `consumeActive` 受影响行数为真值（CAS），`revokeFamily` 委托 `revokeActiveFamily`；`MyBatisUserRepository.search` 先 `countUsers` 再 `selectUserPage` 并映射 `UserPage`；实现类保持可被 Spring 类代理。 | Controller 和跨模块调用方不得绕过这些端口。 |
| `.../persistence/repository/MyBatisUserRoleStore.java` | 将用户角色写入端口适配到 UserRoleMapper。 | 后续初始化和授权应用服务只依赖 UserRoleStore。 |
| `.../persistence/repository/MyBatisAdminBootstrapRepository.java` | 将初始化持久化端口适配到四个 Mapper；用户角色绑定调用原子 UPSERT，其他写入失败抛稳定内部错误。 | 仅供 AdminBootstrapService 使用，认证前不依赖 TenantContext，租户范围由显式 tenant_id 保证。 |
| `.../infrastructure/crypto/SpringSecurityArgon2PasswordHasher.java` | 用 Spring Security 的 Argon2PasswordEncoder（Argon2id）实现 PasswordHasher 端口。 | 生产与初始化共用，成本参数用框架默认值。 |
| `.../persistence/config/SecurityPersistenceConfiguration.java` | 只扫描安全持久化 Mapper，并在拦截器链中先装配 TenantLineInnerInterceptor、再装配乐观锁插件。 | 新增内层拦截器时保持 tenant-line 在最前。 |
| `.../persistence/config/TenantContextTenantLineHandler.java` | 租户拦截器 handler：从 TenantContext.requireTenantId() 取值（fail closed），显式忽略无 tenant_id 的 tenants 与 flyway_schema_history 表。 | 新增无 tenant_id 的基础设施表时在此补充忽略项。 |
| `.../persistence/converter/IdentityPersistenceConverterTest.java` | 验证五类转换、不可变 permissions、时间和敏感字段字符串输出。 | 领域或表字段变化时同步维护。 |
| `.../persistence/typehandler/PersistenceTypeHandlerTest.java` | 验证 JSONB permissions 校验和 inet IPv4/IPv6 映射。 | TypeHandler 变化时保持非法输入覆盖。 |
| `.../persistence/mapper/SecurityMapperSqlContractTest.java` | 固定认证前 SQL 的租户条件、Tenant 根表例外及 Refresh Token 锁语义，并精确锁定允许绕过 tenant-line 的 Mapper 方法白名单（含初始化的三个存在性查询）；正向断言 `selectUserPage`/`countUsers` 显式携带 tenant_id、deleted_at IS NULL、LIMIT/OFFSET 且**未被** `@InterceptorIgnore` 标注。 | 新增自定义安全 SQL 时加入显式租户审查断言；任何新增绕过方法都会使白名单测试失败。 |
| `.../context/TenantContextTest.java` | 验证同线程先后请求租户不残留、缺上下文 fail closed、clear 幂等。 | 上下文行为变化时同步维护。 |
| `.../persistence/config/TenantContextTenantLineHandlerTest.java` | 验证 handler 从上下文取值、无上下文抛 AUTHENTICATION_REQUIRED、根表忽略与租户表不忽略。 | 忽略表清单变化时同步维护。 |
| `.../application/service/AdminBootstrapServiceTest.java` | 用内存仓储验证首次初始化、重复运行幂等、已有租户/角色/用户兼容性、过期绑定原地恢复，且持久化串不含原始密码。 | 内存仓储模拟绑定唯一约束；真实 PostgreSQL UPSERT 与事务回滚由容器级 IT 覆盖。 |
| `.../application/service/UserQueryServiceTest.java` | 用记录型 fake 仓储验证分页参数与 tenant/pattern/status 严格透传、空白 keyword 转 null、`buildLikePattern` 对 `\`/`%`/`_`/中文的转义、非法分页及 OFFSET 溢出 → VALIDATION_ERROR、详情命中与未知/跨租户 → RESOURCE_NOT_FOUND，以及服务层不解析任何请求参数。 | 分页或过滤语义变化时同步维护。 |

## 5. `knowagent-model`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-model/pom.xml` | 模型模块依赖定义：引入 Spring AI 模型抽象（`spring-ai-openai` 提供 OpenAI-compatible 协议客户端，版本由父 POM 的 spring-ai-bom 管理）、micrometer-core（非敏感调用指标）与 WireMock 3.9.1（测试）；MyBatis-Plus starter、Jackson、PostgreSQL JDBC 用于供应商配置持久化与 JSONB 类型处理器。租户行级与乐观锁拦截器复用 security 模块全局 `mybatisPlusInterceptor` Bean，本模块不重复定义。依赖管理把 Jetty BOM 钉在 11.0.20（WireMock 3.9.1 内嵌服务器基于 Jetty 11，父 POM 的 Spring Boot BOM 会把 jetty-server 提升到 12 导致混代启动失败）。 | 添加具体供应商适配器时保持核心端口不变。 |
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
| `.../model/embedding/EmbeddingGateway.java` | 文本向量化端口：`EmbeddingResult embed(EmbeddingRequest)`，按请求租户解析模型供应商、批量调用并返回顺序一致的向量。 | 文档索引和查询向量生成共同调用。 |
| `.../model/embedding/EmbeddingRequest.java` | 值对象：tenantId、providerId、model、可选 expectedDimensions、texts；compact 构造器校验非空，`toString()` 不输出文本。 | 索引/查询构造该请求，不暴露供应商 DTO。 |
| `.../model/embedding/EmbeddingResult.java` | 值对象：vectors（顺序一致、非空、有限）、dimensions、model、batchCount、estimatedTokens；compact 构造器集中校验。 | 索引链路消费，直接与 Milvus 维度比对。 |
| `.../model/embedding/BatchPlanner.java` | 批规划器：同时遵守最大文本条数、估算 token 总量（char-run-v1）与请求体大小上限；请求体按 JSON 转义后的 UTF-8 字节估算，并计入模型名等可变开销；空/空白输入直接拒绝，单个文本超限无法成批时拒绝；批次顺序保持输入顺序。 | 供应商请求体限制变化时只改配置。 |
| `.../model/embedding/CharRunTokenEstimator.java` | 确定性 token 估算（char-run-v1，与 knowledge 模块 DeterministicTokenCounter 共享规则），无 LLM 参与。 | BatchPlanner 唯一估算实现，禁止随机。 |
| `.../model/embedding/BatchPlannerTest.java`、`.../model/embedding/CharRunTokenEstimatorTest.java` | 验证批规划三限制同时生效、空/空白/超限拒绝、顺序保持，以及 char-run token 估算确定性。 | 估算规则或限制语义变化时同步。 |
| `.../model/infrastructure/embedding/OpenAiCompatibleEmbeddingGateway.java` | OpenAI-compatible 适配器（提示词 15 主实现）：每调用按当前租户解析 ModelProvider 并校验适配器/启用/EMBEDDING 能力/模型目录（跨租户或缺失 → RESOURCE_NOT_FOUND，其余 → MODEL_CONFIGURATION_ERROR）；BatchPlanner 分批；`OpenAiApi`+`OpenAiEmbeddingModel` 只做协议；受 totalTimeout 约束的有界重试（只重试 429/明确 5xx/网络暂态，4xx 配置错误不重试）；错误映射稳定 MODEL_AUTH_FAILED/MODEL_RATE_LIMITED/MODEL_TIMEOUT/MODEL_BAD_RESPONSE/MODEL_SERVICE_ERROR/MODEL_CONFIGURATION_ERROR；向量按数量/顺序/维度/有限性校验；API Key 与自定义 Header 只在客户端构建边界解密。 | 后续 Chat/Rerank 适配器沿用同一失败分类与指标约定。 |
| `.../model/infrastructure/embedding/EmbeddingModelClientCache.java` | 模型客户端 LRU 缓存，键 = (tenantId, providerId, configVersion)，`maxClientCacheSize` 有界；configVersion 更新后旧客户端不再被复用。 | 缓存键必须至少含 tenantId+providerId+configVersion。 |
| `.../model/infrastructure/embedding/EmbeddingMetrics.java` | 非敏感调用指标：providerId/model/outcome/耗时/批次数/估算 token 总数；无 MeterRegistry 时为 no-op，绝不记录 chunk 原文或向量数组。 | Worker 链路直接复用同一指标名。 |
| `.../model/infrastructure/embedding/ModelCallException.java`、`OpenAiResponseErrorHandler.java` | 供应商 HTTP 错误分类（AUTH/RATE_LIMITED/TRANSIENT_SERVICE/TIMEOUT/CLIENT_CONFIG）与 RestClient 自定义错误处理器；错误处理器不读取供应商正文，避免敏感正文进入异常。 | 其它供应商适配器复用同一分类。 |
| `.../model/infrastructure/embedding/config/EmbeddingProperties.java`、`EmbeddingGatewayConfiguration.java` | 绑定 `knowagent.model.embedding.*`（连接/读取/总超时、重试预算、批限、缓存大小），装配唯一 `EmbeddingGateway` Bean；MeterRegistry 可选。 | API 与 Worker 共用同一装配与配置前缀。 |
| `.../model/infrastructure/embedding/OpenAiCompatibleEmbeddingGatewayTest.java` | WireMock 契约测试（22 例）：多批拼接顺序、429 后重试、401/403 与永久传输错误不重试、读取超时、单次及跨批 totalTimeout、响应数量错误、维度错误、NaN/Infinity、configVersion 缓存失效、跨租户 404、日志/异常不含密钥/Header/原文。 | 供应商行为变化时以真实响应体回归。 |
| `.../model/infrastructure/embedding/EmbeddingModelClientCacheTest.java`、`.../model/infrastructure/embedding/EmbeddingGatewayContextTest.java`、`.../model/infrastructure/embedding/EmbeddingTestSupport.java` | 客户端缓存失效与键语义、Spring 上下文只装配一个 EmbeddingGateway Bean（无 MeterRegistry 也能启动）、测试夹具（默认供应商、`gateway()` 与 WireMock 请求断言工具）。 | 新增适配器测试时复用夹具。 |
| `.../model/rerank/RankedDocument.java` | 重排后的文档及分数数据类型。 | RAG 检索结果二次排序后返回。 |
| `.../model/rerank/RerankGateway.java` | Rerank 模型调用端口。 | 实现供应商适配和无 Rerank 时的降级策略。 |
| `.../model/provider/AdapterType.java`、`ModelCapability.java`、`EnabledModel.java`、`HealthStatus.java` | 供应商配置值对象：适配器类型（仅 OPENAI_COMPATIBLE）、能力（CHAT/EMBEDDING/RERANK）、启用模型条目、健康状态（UNKNOWN/HEALTHY/UNHEALTHY，与 DB CHECK 一致）。 | 新增适配器类型或能力时在此扩展枚举。 |
| `.../model/provider/ModelProvider.java` | 供应商聚合：密文只以 `EncryptedSecret` 信封存在，`provider_key` 正则校验与规范化方法集中在此，`toString()` 不含任何密文或 keyVersion。 | 供应商 CRUD 与后续模型调用共用的领域事实。 |
| `.../model/provider/ModelProviderPage.java`、`ModelProviderHealthCheck.java` | 分页结果与健康检查结果；健康检查 `checked` 恒为 false，不伪造 HEALTHY。 | 后续接入真实适配器探活时替换。 |
| `.../model/crypto/SecretCipher.java`、`EncryptedSecret.java`、`SecretEnvelope.java`、`SecretCipherException.java` | 加密端口与信封值对象：`aesgcm.v{n}.{base64url(nonce)}.{base64url(ciphertext)}`，`EncryptedSecret.toString()` 隐藏密文。 | 领域/应用层只依赖端口，不接触 JDK 加密类型。 |
| `.../model/crypto/AesGcmSecretCipher.java` | AES-256-GCM 实现：随机 12 字节 nonce、128 位 tag、严格校验 32 字节 AES key、按 keyVersion 查密钥；缺密钥时 `encrypt` 拒绝。 | 主密钥由模型模块共享配置从环境变量装配。 |
| `.../model/infrastructure/crypto/config/ModelProviderSecretProperties.java`、`ModelProviderCryptoConfiguration.java` | 绑定 `model-provider.secret-key`，校验 base64 解码后恰好 32 字节并装配共享 `SecretCipher`；配置对象输出固定脱敏。 | API 与 Worker 均扫描该配置，必须使用同一个 `MODEL_PROVIDER_SECRET_KEY`。 |
| `.../model/application/port/out/ModelProviderRepository.java`、`ModelProviderReferenceChecker.java` | 供应商持久化端口与「被活动知识库引用」检查端口；`findByIdForKeyShare` 以 `FOR KEY SHARE` 锁定活动供应商（供知识库创建/更新引用前串行化删除）。 | 引用检查端口由 knowledge 模块实现，model 模块不直接查询知识库表。 |
| `.../model/application/service/ModelProviderService.java`、`CreateModelProviderCommand.java`、`UpdateModelProviderCommand.java` | 应用服务：provider_key 规范化/唯一、URL/JSON/Header/模型目录校验、密钥加密、更新三态语义、唯一约束竞争与乐观锁冲突 409；删除在事务内先锁供应商行再查引用。命令 `toString()` 隐藏明文 secret/headers。 | Controller 只依赖该服务；知识库写入须遵守配套引用锁协议。 |
| `.../model/infrastructure/persistence/entity/ModelProviderPo.java` | `model_providers` 持久化对象（UUID、枚举、JSONB、`@Version`）。 | 仅供 Mapper/Converter 使用。 |
| `.../model/infrastructure/persistence/mapper/ModelProviderMapper.java` | 供应商 CRUD/分页/统计/行锁（`FOR UPDATE` 删除锁 + `FOR KEY SHARE` 引用锁）/更新/软删 Mapper，自定义 SQL 全部显式 tenant_id 且不加 `@InterceptorIgnore`。 | 新增自定义 SQL 时保持显式 tenant_id，并为状态写入保留 version 守卫。 |
| `.../model/infrastructure/persistence/converter/ModelProviderPersistenceConverter.java` | PO ↔ 领域转换；密文保持不透明，不在此解密或输出。 | 表字段变化时同步维护。 |
| `.../model/infrastructure/persistence/repository/MyBatisModelProviderRepository.java` | 把供应商持久化端口适配到 Mapper，包含租户内 `FOR UPDATE` 行锁读取。 | Controller/跨模块不得绕过端口。 |
| `.../model/infrastructure/persistence/typehandler/CapabilitySetJsonbTypeHandler.java`、`EnabledModelsJsonbTypeHandler.java` | capabilities/enabled_models 的 JSONB 结构化映射（拒绝未知值）；public_config 复用 security 的 `JsonNodeJsonbTypeHandler`。 | 字段结构变化时同步。 |
| `.../model/infrastructure/persistence/config/ModelPersistenceConfiguration.java` | `@MapperScan` 扫描模型模块 Mapper；不定义自己的 `MybatisPlusInterceptor`。 | 与 security 的全局拦截器共享租户隔离与乐观锁。 |
| `.../model/crypto/AesGcmSecretCipherTest.java`、`.../model/infrastructure/crypto/config/ModelProviderSecretPropertiesTest.java` | 验证密文随机性/篡改检测、AES-256 key 与 active version 校验，以及配置输出不泄露主密钥。 | 加密算法、信封格式或配置方式变化时同步。 |
| `.../model/application/service/ModelProviderServiceTest.java` | 验证 key 规范化、URL/JSON/Header/模型目录校验、密文存储、secret/header 三态更新、唯一键竞争映射 409、引用删除、跨租户和分页规则。 | 服务规则变化时同步。 |
| `.../model/infrastructure/persistence/typehandler/ModelProviderTypeHandlerTest.java` | JSONB capabilities/enabled_models 往返与非法值拒绝。 | TypeHandler 变化时同步。 |
| `.../model/infrastructure/persistence/mapper/ModelProviderMapperSqlContractTest.java` | 锁定供应商 Mapper 每条自定义 SQL 显式 tenant_id，验证活动行条件、version 守卫和 `SELECT ... FOR UPDATE`，且无 `@InterceptorIgnore`。 | 新增供应商 SQL 时在此补断言。 |

## 6. `knowagent-knowledge`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-knowledge/pom.xml` | 知识库模块依赖定义：依赖 common/security/model，并为文件上传新增 observability（仅用其应用端口，禁止碰 Mapper）与 workspace（对象存储边界）依赖；`org.apache.tika:tika-core:3.1.0`（Spring Boot 不托管、固定版本）做基于内容的类型嗅探；`org.apache.pdfbox:pdfbox:3.0.5` 与 `org.apache.poi:poi-ooxml:5.4.1`（均 Spring Boot 不托管、显式固定版本）做本地文档解析；PDFBox 传递的原生 `commons-logging` 被精确排除，统一使用 Spring Boot `spring-jcl`，避免双日志桥接。 | 后续加入 Milvus 适配实现；升级解析库时复核版本与日志桥接。 |
| `.../knowledge/package-info.java` | 声明知识入库与检索模块边界。 | 保持业务能力说明。 |
| `.../knowledge/knowledgebase/KnowledgeBase.java` | 知识库领域聚合：slug 规范化与 V6 正则校验、名称/描述/元数据边界、embedding/rerank 供应商成对配置约束、分块与检索配置、版本号、完整字段 `toString()`（不含配置与审计细节）。 | 文件上传、解析和检索任务的唯一租户内知识库事实来源。 |
| `.../knowledge/knowledgebase/KnowledgeBaseStatus.java` | 知识库状态机：ACTIVE/DISABLED/DELETING/DELETED，`canTransitionTo` 集中定义合法转换（仅 ACTIVE↔DISABLED 可正向切换，删除只走软删到 DELETED）。 | 状态转换只能通过该枚举判定，禁止在服务里散落分支。 |
| `.../knowledge/knowledgebase/KnowledgeType.java` | 知识库类型：LOCAL / EXTERNAL（与 V6 CHECK 一致）。 | 上传与检索按类型区分行为。 |
| `.../knowledge/knowledgebase/RetrievalConfig.java` | 检索配置值对象：`topK`（1..100）、`scoreThreshold`（0..1）、`rerankEnabled`，compact 构造器集中校验，默认值工厂。 | 检索任务执行时读取；JSONB 持久化。 |
| `.../knowledge/knowledgebase/KnowledgeBasePage.java` | 知识库分页结果（不可变列表 + 非负总数）。 | 列表端口返回它，禁止直接返回持久化对象。 |
| `.../knowledge/chunk/ChunkPolicy.java` | 分块策略值对象：RECURSIVE / MARKDOWN_HEADING / TOKEN_WINDOW 三种策略枚举 + `maxTokens`/`overlapTokens`（**单位恒为 token**），compact 构造器集中校验（maxTokens>0、overlapTokens>=0、overlapTokens<maxTokens），`defaults()` 为 RECURSIVE 800/100。 | 知识库保存独立策略，解析任务按此分块。 |
| `.../knowledge/application/port/out/KnowledgeBaseRepository.java` | 知识库持久化端口：`findById`、`findByIdForKeyShare`（文件写入引用锁）、`findByIdForUpdate`（删除锁）、`findActiveBySlug`、`save`、版本守卫 `updateConfig`、版本守卫软删 `softDelete`、`page`。 | 应用服务只依赖该端口，禁止暴露 Mapper。 |
| `.../knowledge/application/port/out/KnowledgeFileReferenceChecker.java` | 「知识库是否仍有未删除文件」检查端口。 | 删除知识库前的存在性守卫。 |
| `.../knowledge/application/service/CreateKnowledgeBaseCommand.java`、`UpdateKnowledgeBaseCommand.java` | 创建/更新不可变命令：携带 tenantId/actorId 与各可写字段；更新语义 null=保留。 | 由 API 层从 HTTP 请求构造，tenantId 只来自 principal。 |
| `.../knowledge/application/service/KnowledgeBaseService.java` | 知识库 CRUD 应用服务：slug 规范化+唯一（创建与更新的唯一约束竞争都映射 409）、创建默认值、供应商解析（创建/更新均为 `@Transactional`，经 `findByIdForKeyShare` 锁定供应商——租户内存在、enabled、声明 EMBEDDING/RERANK 能力、provider/model 成对，`enabled_models` 非空时要求「模型名+能力」完全匹配、空目录允许任意模型名——跨租户或已软删供应商 404、其余校验 400）、分页参数校验、LIKE pattern 转义、集中状态转换、乐观锁冲突 409、删除守卫（有活动文件 409）与软删。 | Controller 只依赖该服务；不接触 Mapper；供应商 `FOR KEY SHARE` 锁与删除侧 `FOR UPDATE` 串行化，防止「验证后插入引用已删供应商」的 TOCTOU。 |
| `.../knowledge/infrastructure/persistence/entity/KnowledgeBasePo.java` | `knowledge_bases` 持久化对象：UUID、枚举、JSONB（TypeHandler）、`@Version` 乐观锁、应用输入主键。 | 仅供 Mapper/Converter 使用。 |
| `.../knowledge/infrastructure/persistence/converter/KnowledgeBasePersistenceConverter.java` | PO ↔ 领域转换；损坏数据转稳定内部错误。 | 表字段变化时同步维护。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeBaseMapper.java` | 知识库 CRUD/分页/统计/`FOR KEY SHARE` 引用锁/`FOR UPDATE` 删除锁/版本守卫更新/版本守卫软删 Mapper，自定义 SQL 全部显式 tenant_id + `deleted_at IS NULL`，不加 `@InterceptorIgnore`。 | 新增自定义 SQL 时保持显式 tenant_id。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeFileReferenceMapper.java` | 按 tenant_id + knowledge_base_id 统计未删除文件（`countActiveFiles`）。 | 删除知识库前守卫 SQL。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeModelProviderReferenceMapper.java` | 在知识库模块内按 tenant_id 统计活动知识库对模型供应商的引用（embedding/rerank 两字段）。 | 知识库创建/更新时由本模块拥有相关 SQL。 |
| `.../knowledge/infrastructure/persistence/repository/MyBatisKnowledgeBaseRepository.java`、`MyBatisKnowledgeFileReferenceChecker.java` | 把知识库/文件引用输出端口适配到 Mapper，包含租户内 `FOR KEY SHARE` 文件引用锁、`FOR UPDATE` 删除锁与版本守卫返回受影响行数。 | Controller/跨模块不得绕过端口。 |
| `.../knowledge/infrastructure/persistence/repository/KnowledgeModelProviderReferenceChecker.java`、`.../config/KnowledgePersistenceConfiguration.java` | 实现 model 模块的引用检查端口并扫描 knowledge Mapper，避免 model 反向查询知识库表。 | 文件上传写知识库时沿用同一 `FOR KEY SHARE` 供应商锁定协议。 |
| `.../knowledge/infrastructure/persistence/typehandler/ChunkPolicyJsonbTypeHandler.java`、`RetrievalConfigJsonbTypeHandler.java` | chunk_policy / retrieval_config 的 JSONB 结构化映射（拒绝非法值）。 | 字段结构变化时同步。 |
| `.../knowledge/chunk/ChunkDraft.java` | 确定性分块的输出草稿：chunkIndex/content/contentHash（SHA-256 64 位小写 hex）/tokenCount/字符与 Token offset/pageNumber/sectionPath/metadata；compact 构造器集中校验（hash 匹配 content、tokenCount 匹配 token offset 差、字符 offset 范围等于内容长度、page 1-based、防御性拷贝）。 | 由 ChunkWriteService 转成 KnowledgeChunk 落库，不直接持久化。 |
| `.../knowledge/chunk/Chunker.java` | 文本分块端口：`split(ParsedDocument, ChunkPolicy)` 返回有序 ChunkDraft。 | 供应商 tokenizer 接入后由新实现满足同一契约。 |
| `.../knowledge/vector/VectorChunk.java` | 写入向量库的 chunk、向量、租户过滤元数据与 embeddingModelSpec（非空、≤255，构造时校验）；Milvus entity id 必须等于 chunkId。 | 由 Worker 索引任务构造；Milvus 适配器映射为 collection entity。 |
| `.../knowledge/vector/VectorQuery.java` | 向量检索请求：tenantId、knowledgeBaseId、查询向量、topK（1..16384）、minimumScore（[-1,1]）与可选 fileIds（构造时校验）。 | 所有检索必须通过其过滤条件实现租户隔离。 |
| `.../knowledge/vector/VectorHit.java` | 向量命中结果：chunkId/fileId/score；content 由应用层按 tenant + chunk ids 从 PostgreSQL 回查后填充，Milvus 适配器恒返回 null。 | RAG 上下文和引用来源使用。 |
| `.../knowledge/vector/VectorStoreGateway.java` | 向量写入、删除和检索端口；契约固定：写/查/删按 tenant+kb（+file）作用域、Milvus entity id == PostgreSQL chunk UUID、检索只返回 id/score/必要标量、删除缺失文件幂等成功。 | 由 Milvus 适配器实现（提示词 16 已落地），SDK 类型不跨该边界。 |
| `.../knowledge/knowledgebase/KnowledgeBaseTest.java` | 领域测试：供应商成对、slug 规范化边界、名称/描述/元数据校验、metadata 深拷贝、完整状态转换矩阵、toString 不含配置/审计。 | 领域规则变化时同步。 |
| `.../knowledge/application/service/KnowledgeBaseServiceTest.java` | 用假仓储（可注入重复键/乐观锁冲突）验证创建默认值、slug 重复 409 + 竞争、供应商校验、状态转换、乐观锁不覆盖、删除守卫/slug 复用、列表租户过滤与 pattern 转义。 | 服务规则变化时同步。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeBaseMapperSqlContractTest.java` | 锁定知识库 Mapper 每条自定义 SQL 显式 tenant_id、`deleted_at IS NULL`、分页 LIMIT/OFFSET、版本守卫，且无 `@InterceptorIgnore`；单独断言文件引用锁使用 `FOR KEY SHARE`，删除锁使用 `FOR UPDATE`。 | 新增知识库 SQL 时在此补断言。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeModelProviderReferenceMapperSqlContractTest.java` | 验证引用查询显式携带 tenant_id、活动知识库条件和 embedding/rerank 两个供应商字段。 | 修改知识库引用规则时同步。 |
| `.../knowledge/infrastructure/persistence/typehandler/KnowledgeBaseTypeHandlerTest.java` | JSONB chunk_policy/retrieval_config 往返与非法值（maxTokens=0、overlap>=maxTokens、topK=0/101、scoreThreshold=1.5）→ SQLException。 | TypeHandler 变化时同步。 |

### 文件上传（提示词 12 已落地）

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../knowledge/file/KnowledgeFile.java` | 知识库文件领域聚合（26 字段）：上传幂等键、展示名/原始文件名、objectKey（对 API 不透明，`toString()` 不含）、contentType/sha256/大小、集中状态机 `transitionTo`、processingParams/metadata（JSONB 深拷贝）、版本与审计字段；compact 构造器集中校验（sha256 必须 64 位小写十六进制、displayName/originalFilename 非空且 ≤512、processingParams/metadata 必须 JSON 对象）。 | 文件列表/详情/下载与解析 Worker 的唯一领域事实；objectKey 只做服务端寻址，永不进入响应。 |
| `.../knowledge/file/KnowledgeFileStatus.java` | 文件入库生命周期状态机（UPLOADED/QUEUED/PARSING/CHUNKING/EMBEDDING/INDEXING/READY/FAILED/DELETING/DELETED，与 V6 CHECK 一致），`canTransitionTo` 集中定义合法转换。本阶段只产生 UPLOADED→QUEUED（上传事务直接落终态 QUEUED）。 | 解析/分块/Embedding/删除流程按此推进状态，禁止在服务里散落分支。 |
| `.../knowledge/file/KnowledgeFilePage.java` | 文件分页结果（不可变列表 + 非负总数）。 | 列表端口返回它，禁止直接返回持久化对象。 |
| `.../knowledge/file/DocumentType.java` | 允许上传的文档类型枚举（TEXT_PLAIN/TEXT_MARKDOWN/PDF/DOCX）携带规范 MIME；`fromCanonicalMime` 按内容嗅探出的 MIME 解析（忽略 `;` 后参数）。 | 类型由内容决定，文件名/Content-Type 头从不作为类型事实。 |
| `.../knowledge/application/port/out/KnowledgeFileRepository.java` | 文件持久化端口：`save`（加入调用方事务）、`findById`（显式 tenant+kb+id，跨租户不可区分）、`findByUploadIdempotencyKey`（tenant+kb+key，**不过滤** deleted_at——重放语义要看全量历史）、`page`（显式租户分页 + 可选 status）。 | 应用服务只依赖该端口，禁止暴露 Mapper。 |
| `.../knowledge/application/service/KnowledgeFileService.java` | 文件上传/读取应用服务：流式 spool 到临时文件（`MAX_UPLOAD_BYTES=50MB` 防御上限 + `LimitedInputStream` 超限即失败）同时算 SHA-256 与大小；空文件/未知类型/伪造 MIME 400；文件名只作展示与派生扩展名；`ObjectStorageGateway.put` 写 MinIO 后再由 `KnowledgeFileSubmissionService` 同一事务落库；数据库失败（含知识库并发删除和 DuplicateKeyException 竞争）立即补偿删除已上传对象、补偿失败记 `[ALARM]` 但绝不误报成功；`Idempotency-Key` 归一化（trim、≤128、同 key 同 hash 重放 `replayed=true`、同 key 不同 hash 409）；list 先校验当前租户知识库存在，get/content 按 tenant+kb+file 读取，跨租户与不存在统一 404、存储失败映射 `EXTERNAL_SERVICE_ERROR`。 | 上传线程不解析文档、不调用 Embedding/Milvus；已由 Worker 异步入库链承接。 |
| `.../knowledge/application/service/KnowledgeFileSubmissionService.java` | 文件落库的事务边界：同一 Spring 事务内先以 `FOR KEY SHARE` 锁定并确认活动知识库，再调用 `TaskSubmissionService.submit`（TASK_TYPE=`knowledge_file.ingest`、AGGREGATE=`knowledge_file`、EVENT=`knowledge_file.ingested`、maxAttempts=3、eventMaxRetries=3）并写 `UPLOADED→QUEUED` 的 `knowledge_files` 行；task/outbox payload 只带 file_id（不含 objectKey/内容），task 幂等键刻意为 null（上传幂等由 KB 作用域 key 承载，避免与 tenant+taskType 唯一索引冲突）；processingParams 记录 task/outbox id。 | `FOR KEY SHARE` 与知识库删除的 `FOR UPDATE` 配对，阻止向已删除知识库提交文件；上传幂等重放永不到达本服务。 |
| `.../knowledge/application/service/PersistUploadedFileCommand.java`、`UploadFileCommand.java`、`UploadFileResult.java`、`FileContent.java` | 上传命令/结果值对象：`UploadFileCommand` 携带 tenantId/kbId/actorId/Idempotency-Key/原始文件名/InputStream；`PersistUploadedFileCommand` 携带预生成 fileId 与落库字段；`UploadFileResult` 携带 file + replayed；`FileContent` 携带流式下载的 InputStream/contentType/size/displayName（调用方负责关闭流）。 | Controller 只构造命令，不接触 Mapper/领域细节。 |
| `.../knowledge/application/service/DocumentTypeDetector.java`、`TikaDocumentTypeDetector.java` | 内容类型检测端口与 Tika 实现：Tika magic bytes 嗅探 + 可信内容回退——Markdown 前 16KB 匹配标题/围栏启发式（Tika 把 markdown 报成 text/plain 时提升为 TEXT_MARKDOWN）、OOXML zip 同时含 `[Content_Types].xml`(wordprocessingml) 与 `word/document.xml` 时判 DOCX。 | 本里程碑唯一用 Tika 的地方；真实解析（PDFBox/POI）留给解析 Worker。 |
| `.../knowledge/infrastructure/persistence/entity/KnowledgeFilePo.java` | `knowledge_files` 持久化对象：UUID、枚举、JSONB processing_params/metadata（TypeHandler）、`@Version` 乐观锁。 | 仅供 Mapper/Converter 使用。 |
| `.../knowledge/infrastructure/persistence/converter/KnowledgeFilePersistenceConverter.java` | KnowledgeFilePo ↔ KnowledgeFile 双向转换；损坏持久化行转稳定内部错误。 | 表字段变化时同步维护。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeFileMapper.java` | 文件 Mapper：显式租户的 `selectByIdAndTenant`（含 deleted_at IS NULL）、幂等历史 `selectByUploadIdempotencyKey`（tenant+kb+key 排序、**不过滤** deleted_at）、分页 `selectPage` + `countAll`（同条件、ORDER BY created_at DESC、LIMIT/OFFSET、可选 status）；分块写入侧新增 `selectByIdAndTenantForUpdate`（`FOR UPDATE` 行锁，tenant/kb/id + deleted_at IS NULL）与 `updateChunkStatistics`（tenant/kb/file/deleted_at + `version=#{version}` 条件更新，返回受影响行数）。全部显式 tenant_id、不加 `@InterceptorIgnore`。 | 新增自定义 SQL 时保持显式租户条件。 |
| `.../knowledge/infrastructure/persistence/repository/MyBatisKnowledgeFileRepository.java` | 把文件持久化端口适配到 Mapper（save/findById/findByUploadIdempotencyKey/page），新增 `findByIdForUpdate`（分块替换事务的 `FOR UPDATE` 锁定）与 `updateChunkStatistics`（版本守卫条件更新 chunk_count/token_count）。 | Controller/跨模块不得绕过端口。 |
| `.../knowledge/application/service/KnowledgeFileServiceTest.java` | 单元测试：成功上传（QUEUED + task + outbox、对象键/metadata）、缺失/禁用知识库、空文件、不支持类型、空白/超长文件名、超限、trim、幂等重放（无二次 put/save/task）、不同内容 409、DuplicateKeyException 竞争重放/冲突、DB 失败补偿删除（delete 键 == put 键）绝不误报成功、存储 put 失败 → EXTERNAL_SERVICE_ERROR、list 分页校验与跨租户知识库 404、get 未命中/跨租户、content 流式与存储失败映射。 | 服务规则变化时同步。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeFileMapperSqlContractTest.java` | 反射锁定文件 Mapper 租户隔离 SQL 契约：活读显式 tenant/kb/id + deleted_at IS NULL，幂等历史查询显式租户但**不过滤** deleted_at（历史可见性），分页/统计同条件 + LIMIT/OFFSET，均无 `@InterceptorIgnore`。 | 新增文件 SQL 时在此补断言。 |

### 本地文档解析（提示词 13 已落地）

范围：只做「受控对象流 → ParsedDocument」，不写 chunk、不调模型、不启动 Redis consumer。Worker（提示词 17）负责从 `ObjectStorageGateway` 取流交给 `ParserRegistry`。

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../knowledge/document/ParseSource.java` | 解析输入：objectKey/fileName/mimeType/size/InputStream，紧凑构造器校验非空、size≥0。解析器持有并负责关闭流；**解析器永不自行从任意 URL 下载文件**，只接受服务端受控流与元数据；安全 `toString()` 脱敏对象键、文件名和流。 | Worker 从对象存储取流后构造，交给 `ParserRegistry.parse`。 |
| `.../knowledge/document/ParsedSection.java` | 章节值对象：sectionPath（如 `"1.1"`）/heading/content/pageNumber/startOffset/endOffset/metadata；自身校验 offset 范围长度等于 content 长度、pageNumber 为 1-based；安全 `toString()` 不输出 heading/content/metadata。 | 提示词 14 Chunker 消费的唯一引用定位来源。 |
| `.../knowledge/document/ParsedDocument.java` | 解析结果：title/text/pageCount/sections；构造器校验 sections 有序、连续、逐节 `text.substring(startOffset,endOffset)` 恒等于 content 且精确覆盖全 text；安全 `toString()` 不输出标题或正文。 | 解析到分块之间的标准格式（外部 OCR 后续也产出同构）。 |
| `.../knowledge/document/DocumentParser.java` | 解析端口：`supportedMimeTypes()` + `parse(ParseSource)`，异常已转稳定 `ErrorCode`，不抛第三方异常。 | 每个供应商一个实现；外部 MinerU/PaddleX 按同一端口接入。 |
| `.../knowledge/document/ParseProperties.java` | `knowagent.parse.*` 类型安全配置（maxBytes/maxPages/maxUncompressedBytes/maxCharacters/timeout），未配置或非正数回退安全默认值。 | API 与 Worker 共用；env 样例见两侧 application.yml。 |
| `.../knowledge/document/ParseBudget.java` | 协作式解析超时：按当前时间与超时期限差抛出 `DOCUMENT_TIMEOUT`（在页/段落边界检查）。 | 所有解析器在循环边界调用。 |
| `.../knowledge/document/SourceSpool.java` | 把受控源流 spool 到有界临时文件供 PDFBox/POI 随机访问；**所有路径关闭调用方流**（成功、超限、读失败），temp 文件全路径删除；按声明 size 与 `LimitedInputStream` 双重上限。 | Worker 上传后落地到 object store，读取时仍走此有界 spool。 |
| `.../knowledge/document/SectionBuilder.java` | 章节状态机：行流 → 标题（Markdown `#` 或 DOCX Heading 样式）开新章节、经典大纲编号（`1`/`1.1`）；非标题续接当前章节；content 含尾随换行以保证与 text 精确对齐；字符预算超限抛 `DOCUMENT_TOO_LARGE`。 | 供 TXT/Markdown/DOCX 解析器复用。 |
| `.../knowledge/document/TxtMarkdownParser.java` | `text/plain`/`text/markdown`：UTF-8（BOM）、UTF-16 BOM、宽松单字节回退解码并归一化 `\r\n→\n`；Markdown 按 `#` 标题切节。 | 明文/README 类文件。 |
| `.../knowledge/document/PdfParser.java` | `application/pdf`（PDFBox 3）：每页一个章节带 1-based pageNumber，文本按阅读顺序；页数上限前置拒绝；**可加载但无文本 → `OCR_REQUIRED`**（扫描件，不伪造文本）；损坏 → `CORRUPT_DOCUMENT`。 | 扫描 PDF 需外部 OCR（MinerU/PaddleX）时返回明确信号。 |
| `.../knowledge/document/DocxParser.java` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document`（POI XWPF）：`ZipSecureFile.setMaxEntrySize` + 中央目录单入口/累计解压预算预检（超限 → `DOCUMENT_TOO_LARGE`）；Heading id 或样式显示名（含本地化“标题 n”）开节，表格按行读取顺序加入；`OPCPackage` 独立关闭，文档构造失败不泄漏句柄。 | 标题样式解析供 Chunker 继承路径。 |
| `.../knowledge/document/ParserRegistry.java` | 按已检测规范 MIME 选择唯一解析器；无支持解析器时先关闭源流再抛 `UNSUPPORTED_DOCUMENT_TYPE`；两个解析器争同一 MIME 在构造期失败；MIME 映射不可变 → 并发安全。 | Worker 把文件行上的规范 MIME 交给它。 |
| `.../knowledge/document/TestDocuments.java`、`TestSources.java`、`CloseTrackingInputStream.java` | 测试夹具：真实 PDF/DOCX 生成（PDFBox `Standard14Fonts.FontName` 字体、POI id/本地化样式/表格）、累计 ZIP 展开夹具、受控源构造、关闭追踪流。 | 解析器测试共用。 |
| `.../knowledge/document/TxtMarkdownParserTest.java`、`PdfParserTest.java`、`DocxParserTest.java`、`ParserRegistryTest.java`、`ParsedDocumentContractTest.java` | 解析相关 39 个单元测试：TXT/Markdown/分页 PDF/带 Heading DOCX 的页码、标题路径、本地化样式、文本顺序、字符范围；空文件、损坏 PDF/DOCX、超限页数、单入口与累计超限解压、未知 MIME 稳定错误码；InputStream 成功/异常/未知类型路径均关闭；值对象日志脱敏；注册表确定性 + 多线程。 | 解析器行为变化时同步。 |

### 确定性文本分块与持久化（提示词 14 已落地）

范围：只做「ParsedDocument → 确定性 ChunkDraft → `knowledge_chunks` 持久化」。**不生成向量、不调 EmbeddingGateway/Milvus**。Token 估算在接入供应商 tokenizer 前用经过测试的确定性估算实现，并在 chunk metadata 标记算法版本——不把字符数冒充精确 token 数。ChunkPolicy 的 `maxTokens`/`overlapTokens` 恒以 token 为单位。

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../knowledge/chunk/TokenStream.java` | 位置原子 token 流：`record Token(int startChar,int endChar)`；`tokenCountAt(charOffset)` 二分前缀计数、`tokenStartChar/tokenEndChar`；token 边界恒落在码点之间，绝不切开 Unicode 代理对。 | 所有分块策略偏移换算的底座。 |
| `.../knowledge/chunk/TokenCounter.java`、`TokenStream.java` | tokenizer 端口与 positioned-token 载体：`tokenize(text)`/`countTokens(text)`/`algorithmVersion()`；`TokenStream.fromTokens` 校验 token 有序、不重叠、位于文本范围内且不切开 Unicode 代理对。 | 接入供应商 tokenizer 时通过公开工厂构造同一端口结果并换版本号。 |
| `.../knowledge/chunk/DeterministicTokenCounter.java` | 「char-run-v1」确定性估算：CJK 表意/全角=1 token、其余非空白连续码点每 4 个=1 token、空白=0；tokenCount 以 `tokenCountAt(end)-tokenCountAt(start)` 前缀差分定义，估算结果确定、可复现。 | 供应商 tokenizer 接入后作为参考实现保留；元数据 `token_estimator` 标记算法版本。 |
| `.../knowledge/chunk/DeterministicChunker.java` | 三策略分块器：RECURSIVE 全文本按段落/句子/换行偏好边界、MARKDOWN_HEADING 以 ParsedSection 为硬边界（chunk 不跨节）并透传 pageNumber/sectionPath、TOKEN_WINDOW 定长 token 窗口精确 overlap；`chooseCut` 预算内取最远偏好边界、超长无分隔文本 `safeSplit` 安全退化（含 `tokenEndChar(target-1)` 修正，窗口精确 maxTokens token）、`overlapStart` 钳制 overlap<块大小并保证至少一个 token 前进——不产生空 chunk、不无限循环；`sha256Hex` 与 `pathSegments`（`"1.1"`→`["1","1.1"]`）；同输入+策略重复运行产生同顺序同内容同哈希。 | 供应商 tokenizer 接入后仍可做参考实现。 |
| `.../knowledge/chunk/ChunkIndexStatus.java` | chunk 索引状态：PENDING/INDEXING/READY/FAILED（与 V6 `index_status` CHECK 一致）。 | 后续 Embedding 任务推进状态。 |
| `.../knowledge/chunk/KnowledgeChunk.java` | 领域模型：UUID 预生成、tenant/kb/file、chunkIndex、content/contentHash、字符/Token offset、pageNumber、sectionPath(List\<String\>)、metadata(Map\<String,String\>)、indexStatus、错误字段、version、时间戳；compact 构造器集中校验，`toString()` 刻意不含 content（Rule 10：原始文件内容不落日志）。 | 持久化与向量写入共用的事实来源。 |
| `.../knowledge/application/port/out/KnowledgeChunkRepository.java` | chunk 持久化端口：`replaceAll`（删除该文件旧 chunk 后整批插入）/`findByFile`，均以 (tenant, kb, file) 显式三元组作用域。 | 只暴露端口，禁止 Mapper 外泄。 |
| `.../knowledge/application/service/ChunkWriteService.java` | 分块写入事务服务：单个 `@Transactional` 内 `FOR UPDATE` 锁定文件行（不存在/跨租户 → 404）→ drafts 转 PENDING KnowledgeChunk（tenant/kb/file/chunkIndex/contentHash 确定性 UUID，相同重试保留未来 Milvus id）→ 整集合替换（`(tenant, file, chunk_index)` 唯一约束兜底防重复索引）→ `updateChunkStatistics` 版本守卫条件更新 chunk_count/token_count/version（版本不符 → 409）；任一失败整体回滚，旧数据/新数据不半替换。**不推进文件状态、不调 Embedding。** | Worker 分块任务与未来重解析复用的唯一写入口。 |
| `.../knowledge/infrastructure/persistence/entity/KnowledgeChunkPo.java` | `knowledge_chunks` 持久化对象：`@TableId(IdType.INPUT)`（UUID Java 生成）、`@Version`、section_path/metadata 用结构化 JSONB TypeHandler。 | 仅供 Mapper/Converter 使用。 |
| `.../knowledge/infrastructure/persistence/typehandler/StringListJsonbTypeHandler.java`、`StringMapJsonbTypeHandler.java` | section_path（JSON 数组）与 metadata（JSON 对象）的 JSONB 结构化映射，损坏 JSON 转 SQLException。 | 字段结构变化时同步。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeChunkMapper.java` | chunk Mapper：`selectByFile`/`deleteByFile` 均显式 (tenant, kb, file) 三元组 + ORDER BY chunk_index，**不加** `@InterceptorIgnore`（留在租户插件下作 fail-closed 兜底）。 | 新增 chunk SQL 时保持显式租户条件。 |
| `.../knowledge/infrastructure/persistence/converter/KnowledgeChunkPersistenceConverter.java` | KnowledgeChunkPo ↔ KnowledgeChunk 双向转换；损坏持久化行转稳定内部错误，错误消息不含 content。 | 表字段变化时同步维护。 |
| `.../knowledge/infrastructure/persistence/repository/MyBatisKnowledgeChunkRepository.java` | 把 chunk 持久化端口适配到 Mapper：replaceAll = deleteByFile 后逐条 insert（同一调用方事务）。 | 跨模块不得绕过端口。 |
| `.../knowledge/chunk/DeterministicTokenCounterTest.java` | 估算契约 9 例：CJK/全角=1、run ceil(len/4)、空白=0、emoji 代理对安全、确定性、algorithmVersion=="char-run-v1"、外部 positioned-token 工厂范围校验。 | 估算算法或 TokenStream 契约变化时同步。 |
| `.../knowledge/chunk/ChunkDraftTest.java` | 草稿构造校验与防御性拷贝 10 例，含 hash/content 与 tokenCount/offset 一致性。 | 字段变化时同步。 |
| `.../knowledge/chunk/DeterministicChunkerTest.java` | 三策略 20 例：token 预算与 overlap 恒成立、边界偏好、overlap<块大小、MARKDOWN_HEADING 不跨节并在章节边界重新 token 化且透传 page/sectionPath、TOKEN_WINDOW 精确窗口并完整保留分隔空白、CJK/Emoji 代理对不切开、超长无分隔文本安全退化不无限循环、确定性、pathSegments 展开、SHA-256 哈希。 | 分块规则变化时同步。 |
| `.../knowledge/chunk/KnowledgeChunkTest.java` | 领域构造校验、offset 配对/顺序、toString 不含 content 共 8 例。 | 领域规则变化时同步。 |
| `.../knowledge/infrastructure/persistence/typehandler/KnowledgeChunkTypeHandlerTest.java` | section_path/metadata JSONB 往返 + 损坏 JSON → SQLException 共 5 例。 | TypeHandler 变化时同步。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeChunkMapperSqlContractTest.java` | 反射锁定 selectByFile/deleteByFile 显式 (tenant, kb, file) 且无 `@InterceptorIgnore` 共 2 例。 | 新增 chunk SQL 时补断言。 |
| `.../knowledge/application/service/ChunkWriteServiceTest.java` | 假仓储验证：替换为 PENDING + 统计更新、相同重试 UUID 稳定且内容变化换 ID、缺失文件 404 零写入、版本竞争 409 共 4 例。 | 服务规则变化时同步。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeFileMapperSqlContractTest.java` | （新增断言）分块替换锁 `FOR UPDATE` 显式租户、统计更新版本守卫。 | 修改文件 SQL 时同步。 |

### Worker 文件入库应用链（提示词 17 已落地）

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../knowledge/application/service/KnowledgeFileIngestionCommand.java`、`KnowledgeFileIngestionOutcome.java` | 可信事件转成的内部命令与 ACK 决策；携带 tenant/event/consumer/file/payloadHash，不携带 object key 或 SDK 对象。 | 仅由 Worker 适配层构造，不能从 HTTP DTO 直接绑定。 |
| `.../knowledge/application/service/KnowledgeFileIngestionService.java` | 文件入库 saga 编排：服务端重建 MinIO key 并流式解析，推进 PARSING/CHUNKING/EMBEDDING/INDEXING，整集合替换 chunk，调用提示词 15 Embedding 网关，file 级 delete + upsert Milvus 后完成 READY。外部调用刻意不包数据库事务。 | 新增 parser/model/vector 实现时保持端口依赖和幂等补偿，不宣称强事务。 |
| `.../knowledge/application/service/KnowledgeFileIngestionStateService.java` | PostgreSQL 短事务切片：Inbox 预检、tenant+file 行锁、Task claim/租约、状态与进度、chunk index 状态、READY+SUCCEEDED+Inbox 原子完成，以及 retry/FAILED 退避。 | 状态迁移与 Inbox 完成点的唯一入口。 |
| `.../knowledge/application/service/IngestionFailure.java` | 把解析、模型、MinIO、Milvus 异常映射为稳定 ErrorCode、净化截断消息和 retryable；区分暂态与永久失败。 | 新增外部错误类型时先扩充映射和失败测试。 |
| `.../knowledge/application/port/out/KnowledgeFileRepository.java`、`.../KnowledgeChunkRepository.java` 及 MyBatis 适配器 | 新增 Worker 所需 tenant+file 锁、status+version 条件迁移、chunk PENDING/INDEXING/READY/FAILED 批量状态更新；自定义 SQL 显式 tenant+kb+file。 | 禁止 Worker 或跨模块直接调用 Mapper。 |
| `.../knowledge/application/service/KnowledgeFileIngestionServiceTest.java`、`KnowledgeFileIngestionStateServiceTest.java` | 共 8 例：完整阶段、向量补偿、解析永久失败、模型限流/Milvus 暂态失败、重试预算、Inbox 写入时机与重复短路。 | 修改状态、退避、完成事务或错误分类时同步。 |

### Milvus 向量存储适配器（提示词 16 已落地）

范围：`VectorStoreGateway` 的 Milvus 实现——collection 初始化、批量 upsert、过滤检索、按文件幂等删除。不实现 Redis Worker、Rerank 或问答生成。

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../knowledge/infrastructure/vector/MilvusVectorProperties.java` | `knowagent.vector.milvus.*` 类型安全配置：uri（启用开关）/username/password/token/databaseName/collectionName（缺省 `knowledge_chunks`）/dimension（必填 [1,65536]）/indexType（HNSW 默认，FLAT/AUTOINDEX）/HNSW 参数（m/efConstruction/searchEf）/六个超时；相似度固定 COSINE 不可配置；空白/非法值启动即失败；`toString()` 脱敏凭据。 | 与 API/Worker 共用；MILVUS_DIMENSION 必须与 embedding 模型输出一致。 |
| `.../knowledge/infrastructure/vector/VectorStoreException.java` | 向量边界稳定异常：携带 `ErrorCode`（VECTOR_UNAVAILABLE/VECTOR_SCHEMA_MISMATCH/VECTOR_BAD_RESPONSE/VALIDATION_ERROR），消息恒为固定文案，不携带 Milvus 错误体/endpoint/凭据/向量。 | 一个 catch 点即可按 errorCode 映射稳定 API 错误。 |
| `.../knowledge/infrastructure/vector/MilvusFilterBuilder.java` | 受控 filter 构造器：恒含 tenant_id + knowledge_base_id，可选 file_id in 列表（逐 UUID 校验、`\`/`'` 转义），不拼接任意用户表达式。 | 检索与删除共用；后续检索接口的 fileIds 过滤也走它。 |
| `.../knowledge/infrastructure/vector/MilvusVectorEntityMapper.java` | VectorChunk → gson upsert 行：校验批次非空、tenant/kb/file/chunk 关系一致、维度 == 配置维度、数值有限、批内 chunkId 不重复；entity id 恒等于 chunkId；只写检索所需标量与向量（不写正文）。 | 索引任务的 upsert 前置校验唯一入口。 |
| `.../knowledge/infrastructure/vector/MilvusSearchResultMapper.java` | Milvus SearchResult → VectorHit：只取 id/file_id/score（content 恒 null）；缺 id/score/file_id、非 UUID id、非有限 score → VECTOR_BAD_RESPONSE。 | 检索结果只暴露必要标量。 |
| `.../knowledge/infrastructure/vector/MilvusErrorMapper.java` | SDK/超时异常 → VectorStoreException：COLLECTION_NOT_FOUND → VECTOR_SCHEMA_MISMATCH，其余 SDK 错误/超时/中断 → VECTOR_UNAVAILABLE（中断恢复标志位）；unwrap CompletionException/ExecutionException。 | 所有 SDK 调用共用同一错误分类。 |
| `.../knowledge/infrastructure/vector/MilvusCallExecutor.java` | 每个 SDK 调用在专用守护线程池 + CompletableFuture.orTimeout 下执行，按操作独立超时；结果丢弃底层超时调用；close 关闭线程池。 | 连接/搜索/写入/删除/初始化超时的统一实施点。 |
| `.../knowledge/infrastructure/vector/VectorMetrics.java` | 非敏感指标：operations 计数器（collection/operation/outcome）、entities 计数器（数量）、duration 计时器；无 MeterRegistry 时 no-op；绝不记录向量/文本/ID。 | Worker 链路直接复用同一指标名。 |
| `.../knowledge/infrastructure/vector/MilvusClientAccess.java`、`SdkMilvusClientAccess.java` | 窄 SDK 接缝与 MilvusClientV2 委托：hasCollection/createCollection/createCollectionIndex/describeCollection/describeIndex/loadCollection/upsert/search/delete。 | 让适配器可单测、SDK 使用面收敛在基础设施包内。 |
| `.../knowledge/infrastructure/vector/MilvusVectorStoreAdapter.java` | VectorStoreGateway 主实现：upsert（entity mapper 校验 → UpsertReq → 校验 upsertCnt == 行数，不符 VECTOR_BAD_RESPONSE）、search（维度/有限校验 → 受控 filter → COSINE 搜索 → 映射 hits → minimumScore 过滤）、deleteByFile（恒含 tenant/kb/file；deleteCnt==0 幂等成功）。 | 检索接口（提示词 18）与删除 Worker 直接调用。 |
| `.../knowledge/infrastructure/vector/MilvusIndexParams.java`、`MilvusCollectionSchema.java` | 索引参数（HNSW m/efConstruction + 检索 ef；FLAT/AUTOINDEX 空）与固定集合 schema（id VARCHAR 主键 autoID=false、embedding FLOAT_VECTOR、五个标量）构造。 | 启动创建/校验与 schema 契约的唯一来源。 |
| `.../knowledge/infrastructure/vector/MilvusSchemaValidator.java` | 校验已存在 collection：主键/autoID/维度/标量字段/embedding 类型/COSINE 索引；任一不符抛 VECTOR_SCHEMA_MISMATCH（拒绝启动，不删数据）。 | 启动对账与升级检测。 |
| `.../knowledge/infrastructure/vector/MilvusCollectionInitializer.java` | SmartLifecycle 幂等初始化：不存在 → 创建 + 索引 + load；存在 → 仅校验；异常使应用上下文启动失败；绝不 drop。 | 让 schema/维度不匹配在启动即暴露。 |
| `.../knowledge/infrastructure/vector/MilvusVectorStoreFactory.java` | 程序化装配 gateway + initializer（共享 executor/metrics），供集成测试与 CLI 使用。 | Spring 配置与测试共用同一组合。 |
| `.../knowledge/infrastructure/vector/UnavailableVectorStoreGateway.java`、`VectorStoreConfiguration.java`、`VectorStoreFallbackConfiguration.java` | 未配置 MILVUS_ENDPOINT 时 fail-fast 网关（任何操作 VECTOR_UNAVAILABLE）；`@ConditionalOnProperty(uri)` 启用配置与互补回退配置精确互斥，任一上下文恰好一个 VectorStoreGateway Bean。 | 无 Docker/Milvus 环境照常启动。 |
| `.../knowledge/infrastructure/vector/MilvusFilterBuilderTest.java` | filter 转义与构造契约 7 例：恒含 tenant/kb、file in 保持顺序、null 项 fail closed、`\`/`'` 转义、恶意表达式不可逃逸。 | 过滤语法变化时同步。 |
| `.../knowledge/infrastructure/vector/MilvusVectorPropertiesTest.java` | 配置契约 10 例：默认值、空白 uri/超长 collection/dimension 越界/index-type 受限/非法 HNSW 参数与超时拒绝、toString 脱敏凭据。 | 配置变化时同步。 |
| `.../knowledge/infrastructure/vector/MilvusVectorEntityMapperTest.java` | upsert 输入契约 5 例：entity id == chunk UUID、空批次/维度不符/NaN/Infinity/批内重复 chunkId 拒绝。 | 校验规则变化时同步。 |
| `.../knowledge/infrastructure/vector/MilvusSearchResultMapperTest.java` | 检索响应契约 4 例：id/fileId/score 映射（content null）、缺 id/score/fileId、非 UUID、NaN 分数 → VECTOR_BAD_RESPONSE。 | 响应解析变化时同步。 |
| `.../knowledge/infrastructure/vector/MilvusErrorMapperTest.java` | 错误转换 6 例：COLLECTION_NOT_FOUND → SCHEMA_MISMATCH、SDK 错误/超时/中断 → UNAVAILABLE（中断恢复标志）、消息不含 SDK 敏感正文、已映射异常透传。 | 错误分类变化时同步。 |
| `.../knowledge/infrastructure/vector/MilvusCallExecutorTest.java` | 超时包装 3 例：成功返回值、SDK 失败映射、慢调用被超时中止。 | 超时语义变化时同步。 |
| `.../knowledge/infrastructure/vector/VectorMetricsTest.java` | 指标契约 3 例：operation/entities/duration 名称与 tag、无 registry no-op、非正实体数不记录。 | 指标变化时同步。 |
| `.../knowledge/infrastructure/vector/MilvusSchemaValidatorTest.java` | 启动 schema 校验 8 例：匹配通过、维度/主键/autoID/向量字段/标量字段/非 COSINE 索引/空索引列表拒绝。 | 集合契约变化时同步。 |
| `.../knowledge/infrastructure/vector/MilvusCollectionInitializerTest.java` | 启动装配 3 例：缺失集合创建+索引+load、已存在仅校验、不兼容拒绝且不删数据。 | 初始化语义变化时同步。 |
| `.../api/database/MilvusVectorStoreIT.java` | 真实 Milvus 2.5.6 容器集成 6 例：幂等初始化、COSINE 搜索按 tenant/kb/file 过滤、跨租户 chunkId/fileId 无结果、重复 upsert 不重复实体与重复 delete 幂等、PostgreSQL chunk UUID == Milvus 主键、维度不匹配拒绝启动且不删数据。 | 修改适配器/集合契约/隔离语义时执行 `mvn -Pdocker-it verify`。 |

### 语义检索应用链（提示词 18 已落地）

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../knowledge/application/service/KnowledgeRetrievalCommand.java`、`KnowledgeRetrievalResult.java`、`KnowledgeCitation.java` | HTTP 无关的检索命令、结果与引用；命令携带可信 tenant，引用内容/页码/章节来自 PostgreSQL，`toString()` 不输出 query 或正文。 | API/RAG 调用方只能通过这些应用对象进入，不透传 SDK 或 PO。 |
| `.../knowledge/application/service/KnowledgeRetrievalService.java` | 检索编排：ACTIVE KB/Embedding 配置与 READY fileIds 校验 → 单 query Embedding → Milvus 候选 → tenant+kb 批量 PG 回查 → chunk/file READY 裁剪 → 去重、threshold、topK、排名；rerank 开启但无适配器时明确拒绝。 | 后续接入真实 RerankGateway 时替换显式拒绝分支，不能伪造分数。 |
| `.../knowledge/application/port/out/KnowledgeRetrievalRepository.java`、`RetrievalChunkRecord.java` | 批量回查端口与扁平读取记录；显式携带 tenant/kb/chunkIds，并同时返回 chunk 与文件生命周期事实。 | 应用层以 PG 记录做最终权限/状态判断，不信任 Milvus 标量。 |
| `.../knowledge/infrastructure/persistence/entity/KnowledgeRetrievalChunkPo.java`、`.../repository/MyBatisKnowledgeRetrievalRepository.java` | MyBatis 检索专用 PO 与端口适配器；PO 不泄漏到 Controller。 | 表字段变化时同步映射。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeChunkMapper.java` | 新增 tenant+kb+chunk UUID 列表批量 JOIN `knowledge_files` 查询；两表都显式限定 tenant/kb，动态参数是已解析 UUID。 | 保持自定义 SQL 的显式租户条件和批量查询，禁止 N+1/裸 id 查询。 |
| `.../knowledge/application/port/out/KnowledgeRetrievalObserver.java`、`.../infrastructure/retrieval/MicrometerKnowledgeRetrievalObserver.java` | best-effort 检索指标：tenant/provider 非敏感 ID、outcome、候选/结果数、耗时；不记录 query、正文、向量、file/chunk ID。 | 指标系统故障不得改变检索结果。 |
| `.../knowledge/application/service/KnowledgeRetrievalServiceTest.java` | 8 例覆盖调用顺序、默认/覆盖参数、跨租户 fileId、READY 权威裁剪、排序/threshold/topK/重复与缺失命中、空结果、rerank 拒绝和输入边界。 | 改检索策略或权威来源时同步。 |
| `.../knowledge/infrastructure/persistence/mapper/KnowledgeChunkMapperSqlContractTest.java` | 新增 2 例锁定批量回查显式 tenant/kb、动态 UUID 与生命周期字段；该类现共 4 例。 | 修改检索 SQL 时同步。 |

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
| `knowagent-workspace/pom.xml` | 工作区与对象存储模块依赖定义；引入 `io.minio:minio:8.5.17`（Spring Boot BOM 不托管、固定版本，与 Compose 固定的 MinIO 服务器镜像匹配）。 | 新增存储用途（附件/Agent 产物）时复用同一 SDK。 |
| `.../workspace/package-info.java` | 声明虚拟工作区和文件存储边界。 | 保持路径安全规则说明。 |
| `.../workspace/path/VirtualPath.java` | 规范化虚拟路径并拒绝 `..` 路径穿越。 | 所有 Agent 文件访问先转换为该类型。 |
| `.../workspace/storage/ObjectKey.java` | 对象存储键值对象。 | 统一 tenant/workspace/file 的键命名。 |
| `.../workspace/storage/PutObjectCommand.java` | 带租户的对象上传命令。 | API 上传与内部产物保存共同使用。 |
| `.../workspace/storage/GetObjectCommand.java` | 带租户的对象读取命令。 | 禁止仅凭 ObjectKey 跨租户读取。 |
| `.../workspace/storage/DeleteObjectCommand.java` | 带租户的对象删除命令。 | 禁止仅凭 ObjectKey 跨租户删除。 |
| `.../workspace/storage/StoredObject.java` | 包含租户、对象键、类型、大小和散列的存储结果。 | 数据库附件记录引用该结果。 |
| `.../workspace/storage/ObjectStorageGateway.java` | 只接受租户命令的 put/stat/get/delete 端口。 | MinIO 适配器与不可用回退共同实现；调用方禁止提供完整物理键。 |
| `.../workspace/storage/ObjectStorageException.java` | 对象存储边界的稳定失败类型：`Reason` 枚举（OBJECT_NOT_FOUND/ACCESS_DENIED/UNAVAILABLE/INVALID_OPERATION）是契约，消息永不含 MinIO 错误体/endpoint/bucket/凭据。 | 一个 catch 点即可按 Reason 映射稳定 API 错误。 |
| `.../workspace/storage/StorageKeys.java` | 服务端对象键构造与所有权检查：`knowledgeFileSource` 生成 `tenants/{tenantId}/knowledge-bases/{kbId}/files/{fileId}/source`，`isOwnedBy` 校验 `tenants/{tenantId}/` 前缀。 | 适配器每个操作重新校验前缀，跨租户寻址在 MinIO 调用前即拒绝。 |
| `.../workspace/storage/MinioObjectStorageAdapter.java` | MinIO Java SDK 适配器：put 流式 `putObject` 并把 SHA-256 写入 `x-amz-meta-sha256` 用户元数据；stat 从响应头还原 size/contentType/sha256；get 返回 closeable `InputStream`；delete 对缺失对象幂等成功；SDK 异常映射稳定 `ObjectStorageException`；每次操作先 `requireOwnedKey` 校验租户前缀。 | 真实容器/Compose 下的对象存储唯一实现。 |
| `.../workspace/storage/MinioObjectStorageProperties.java` | `minio.*` 类型安全配置：endpoint/access-key/secret-key/bucket/region，bucket 缺省 `knowledge`；空白校验使错误配置启动即失败；`toString()` 固定把 accessKey/secretKey 输出为 `[REDACTED]`。 | 由 `MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY/MINIO_BUCKET/MINIO_REGION` 环境变量绑定，禁止日志展开真实凭据。 |
| `.../workspace/storage/MinioObjectStorageConfiguration.java` | `minio.endpoint` 配置时装配 `MinioClient` + `ObjectStorageGateway`（启动时幂等 `bucketExists`/`makeBucket` 确保 bucket 存在）；条件与回退配置精确互补，任一上下文恰好一个网关 Bean。 | API 与 Worker 共用同一适配器。 |
| `.../workspace/storage/ObjectStorageFallbackConfiguration.java`、`UnavailableObjectStorageGateway.java` | 未配置 MinIO 时的 fail-fast 回退：条件（`minio.endpoint` 缺失或 `false`）与启用配置精确互补；任何存储操作抛稳定 `UNAVAILABLE` 而非静默成功。 | 让无 Docker/无 MinIO 的环境和既有测试继续启动。 |
| `.../workspace/path/VirtualPathTest.java` | 验证路径规范化和穿越攻击拦截。 | 增加 Windows 分隔符、空路径和编码边界测试。 |
| `.../workspace/storage/ObjectStorageCommandTest.java` | 验证所有存储操作必须携带租户并校验上传元数据。 | MinIO 适配器测试继续覆盖物理键隔离。 |
| `.../workspace/storage/StorageKeysTest.java` | 验证对象键形状与 `isOwnedBy` 前缀判定（含 `tenants/{tenantId}` 自身不带尾斜杠的边界）。 | 键命名变化时同步。 |
| `.../workspace/storage/MinioObjectStoragePropertiesTest.java` | 验证配置对象 `toString()` 不包含 accessKey/secretKey 原值且保留 `[REDACTED]` 标记。 | 新增凭据字段时同步脱敏断言。 |

## 10. `knowagent-observability`

### 10.1 领域与应用层（Task / Outbox / Inbox 持久化基础）

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-observability/pom.xml` | 任务、审计、指标和评估模块依赖定义；本阶段引入 MyBatis-Plus、Jackson 与 PostgreSQL JDBC 支撑 Task/Outbox/Inbox 持久化，租户行级与乐观锁拦截器复用 security 模块全局 `mybatisPlusInterceptor`，JSONB 复用其 `JsonNodeJsonbTypeHandler`。 | 后续加入 Micrometer、追踪和评估实现。 |
| `.../observability/package-info.java` | 声明可观测与评估能力边界，明确本模块拥有异步工作持久化基础（task/outbox/inbox）及其应用端口，PostgreSQL 是唯一事实来源。 | 保持模块级说明。 |
| `.../observability/task/Task.java` | 不可变后台任务记录（25 字段）：任务类型、聚合信息、可选 idempotencyKey、状态、阶段、进度、JSONB payload/result、attempt/maxAttempts、next_retry_at、锁租约、错误与版本；compact 构造器集中校验，`claimed()` 返回 RUNNING + 新租约 + attempt+1 + version+1，且拒绝 attempt 已耗尽的 Task（attemptCount>=maxAttempts 抛 IllegalArgumentException）；`toString()` 刻意不含 payload/result/errorMessage。 | Worker 认领与状态机写回的唯一领域事实；所有写入都可用上一版本 + 状态守卫。 |
| `.../observability/task/TaskStatus.java` | Task 状态机：PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED（与 V9 CHECK 一致），显式终态标志；`canTransitionTo` 集中定义合法转换（PENDING→RUNNING/FAILED/CANCELLED，RUNNING→SUCCEEDED/FAILED/PENDING/CANCELLED；FAILED/SUCCEEDED/CANCELLED 终态不可逆——可重试失败走 RUNNING→PENDING、最终失败走 RUNNING→FAILED）。 | 状态转换只能通过该枚举判定，数据库 status+version 守卫兜底。 |
| `.../observability/outbox/OutboxEvent.java` | 不可变事务性 Outbox 事件记录：聚合信息、事件类型、JSONB payload/headers、retry/maxRetries、next_retry_at、锁租约、last_error、published_at、版本；`claimed()/published()/failure()` 转换方法返回 version+1 的新实例，`failure()` 按 `RetryPolicy` 计算退避或在预算耗尽时置 DEAD_LETTER；compact 构造器对 lastError 统一净化（`ErrorMessageSanitizer`）；`toString()` 不含 payload/headers/lastError。 | 竞争发布者的领域事实；每次写入都以上一版本 + 状态守卫。 |
| `.../observability/outbox/OutboxStatus.java` | Outbox 状态机：PENDING/PROCESSING/PUBLISHED/DEAD_LETTER（与 V9 CHECK 一致），显式终态标志。 | 与 outbox_events 表、重试逻辑共用。 |
| `.../observability/outbox/RetryPolicy.java` | 确定性指数退避纯函数：第 n 次重试延迟 `min(max, base*2^(n-1))`，无抖动；`DEFAULT` 为 1s 基数 / 5min 封顶。 | 测试可精确断言 `next_retry_at`；生产默认使用。 |
| `.../observability/inbox/InboxEvent.java` | 不可变消费者幂等回执：consumerName、eventId、eventType、可空 64 位小写 payload_hash、processedAt。 | 重复回执依赖 `uq_inbox_events_consumer_event` 唯一约束，不产生业务副作用。 |
| `.../observability/application/port/out/package-info.java` | 声明 observability 应用层访问数据库的输出端口边界。 | 应用服务只依赖端口，禁止暴露 Mapper。 |
| `.../observability/application/port/out/TaskStore.java` | Task 持久化输出端口：`save`、显式租户 `findById`、FOR UPDATE+租约 `claim`、status+version `transition`，以及 Worker 阶段内以 worker+version 守卫续租并写 stage/progress 的 `updateProgress`。 | Worker 认领/状态机与认证查询共用；跨模块不得绕过。 |
| `.../observability/application/port/out/TaskTransition.java` | 一次 Task 状态转换的目标值对象（targetStatus/stage/progress/result/errorCode/errorMessage/retryable/nextRetryAt），与当前行分离以便用上一版本+状态守卫；compact 构造器对 errorMessage 统一净化（`ErrorMessageSanitizer`），持久化层不可能收到原始消息；安全 `toString()` 排除 result 与 errorMessage。 | `TaskStore.transition` 的入参；禁止在日志中展开任务结果和错误原文。 |
| `.../observability/application/port/out/OutboxEventStore.java` | Outbox 输出端口：`append`（加入调用方事务）、`findById`（显式租户）、`claimReady`（FOR UPDATE SKIP LOCKED 竞争发布，跨租户文档化例外）、`markPublished`/`markFailed`（status+version 守卫，返回 1/0）。 | 竞争发布者与 RedisOutboxPublisher XADD 之间的桥梁。 |
| `.../observability/application/port/out/InboxEventStore.java` | Inbox 输出端口：`recordProcessed`（唯一约束 + ON CONFLICT DO NOTHING，重复回执返回 false 表示「已处理」）、`wasProcessed`（按 tenant+consumer+event 查询）。 | 消费者写回执与幂等判断。 |
| `.../observability/application/service/TaskSubmission.java` | 异步工作入端口：`submit(SubmitTaskCommand)` 在同一事务写 Task + Outbox 并返回双 id。 | knowledge 等模块发起异步入库的唯一入口，禁止绕过。 |
| `.../observability/application/service/SubmitTaskCommand.java` | 不可变提交命令：tenantId/taskType/aggregate/可选 idempotencyKey/taskPayload/maxAttempts/eventType/eventPayload/eventHeaders/eventMaxRetries；紧凑构造器只校验 tenantId 非空，业务校验在 `TaskSubmissionService.validate`；`toString()` 刻意不含 taskPayload/eventPayload/eventHeaders。 | 由业务模块在事务内构造，tenantId 只来自调用方。 |
| `.../observability/application/service/TaskSubmissionResult.java` | 提交结果：taskId + outboxEventId + createdAt，同事务写出的两个 id。 | HTTP 202 响应的数据来源。 |
| `.../observability/application/service/TaskSubmissionService.java` | `@Service` 实现 `TaskSubmission`：先集中校验（taskType≤64、JSONB 必须对象、maxAttempts/eventMaxRetries∈[1,100]），再同一事务 `tasks.save` + `outboxEvents.append`，失败即抛 VALIDATION_ERROR 且不写任何行。 | 业务模块在 `@Transactional` 内调用，与业务记录同生共灭。 |
| `.../observability/application/service/TaskQueryService.java` | `@Transactional(readOnly=true)` 租户内 Task 读取，支撑 `GET /api/v1/tasks/{id}`；租户由调用方（principal）传入，跨租户与不存在统一 404。 | Controller 只依赖该服务，不接触 Mapper。 |
| `.../observability/application/service/OutboxPublisherService.java` | 竞争发布者服务（broker 无关）：`claim(limit, workerId, lease)` 抢占、`publish(event)` 成功标记（丢竞争 409）、`fail(event, rawError)` 把原始错误交给 `OutboxEvent.failure()`（领域构造时净化）按 `RetryPolicy` 计算重试/死信（丢竞争 409）。 | RedisOutboxPublisher 已按 XADD 结果调用 publish/fail；其他事件发布器复用同一端口。 |
| `.../observability/application/service/ErrorMessageSanitizer.java` | Task 与 Outbox 错误文本共用的**唯一净化边界**：`TaskTransition`/`OutboxEvent` 构造即净化——稳定脱敏常见凭证（api_key/Authorization Bearer/client_secret/password/JWT/裸 `sk-` → `<redacted>`）、去除控制字符（保留 tab/换行）、截断到默认 2000 字符；原始消息不可能到达 `error_message`/`last_error`。 | 禁止把密钥或原始文件内容写进错误文本；新增凭证形态在此登记。 |

### 10.2 持久化适配（PO / Mapper / Repository / Converter / JSONB）

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../observability/infrastructure/persistence/entity/TaskPo.java` | `tasks` 持久化对象：UUID、枚举、JSONB payload/result（`JsonNodeJsonbTypeHandler`）、`@Version` 乐观锁、应用输入主键；无 `tenant_id` 显式赋值时由租户插件从上下文填充（但本模块自定义 SQL 一律显式携带）。 | 仅供 Mapper/Converter 使用。 |
| `.../observability/infrastructure/persistence/entity/OutboxEventPo.java` | `outbox_events` 持久化对象：UUID、枚举、JSONB payload/headers、`@Version`、应用输入主键（V9 无 updated_at）。 | 仅供 Mapper/Converter 使用。 |
| `.../observability/infrastructure/persistence/entity/InboxEventPo.java` | `inbox_events` 持久化对象：UUID、consumerName、eventId、eventType、payload_hash、processedAt（无乐观锁）。 | 仅供 Mapper/Converter 使用。 |
| `.../observability/infrastructure/persistence/mapper/TaskMapper.java` | `tasks` Mapper：显式租户读/行锁、`claimForExecution`、`updateRunningProgress`（RUNNING+version+locked_by 守卫并续租）、`transitionTask`。全部 `@InterceptorIgnore(tenantLine="1")` + 显式 tenant_id，由 SQL 契约测试锁定。 | Worker 认领、阶段进度与状态机写回；新增 SQL 保持租户条件与守卫。 |
| `.../observability/infrastructure/persistence/mapper/OutboxEventMapper.java` | `outbox_events` Mapper：`selectByIdAndTenant`（显式租户读）、`selectClaimable`（**跨租户文档化全局例外**，`FOR UPDATE SKIP LOCKED` + `next_retry_at, created_at` 排序 + PENDING/租约过期 PROCESSING）、`markProcessing`/`markPublished`/`markFailed`（status+version+tenant 守卫条件更新）。 | 竞争发布的核心 SQL；白名单契约锁定唯一全局例外。 |
| `.../observability/infrastructure/persistence/mapper/InboxEventMapper.java` | `inbox_events` Mapper：`recordProcessed`（INSERT ... ON CONFLICT ON CONSTRAINT uq_inbox_events_consumer_event DO NOTHING，显式 tenant_id 列）、`selectProcessed`（tenant+consumer+event 存在性查询）。 | 幂等回执的唯一写路径。 |
| `.../observability/infrastructure/persistence/repository/MyBatisTaskStore.java` | 把 `TaskStore` 端口适配到 TaskMapper：save 直接 insert；findById 走显式租户读；claim 先 `selectByIdAndTenantForUpdate` 锁行再 `claimForExecution` 条件更新，返回带 post-claim version 的 `Task.claimed()`；transition 先经 `canTransitionTo` 再以版本守卫更新。 | Controller/跨模块不得绕过端口。 |
| `.../observability/infrastructure/persistence/repository/MyBatisOutboxEventStore.java` | 把 `OutboxEventStore` 端口适配到 OutboxEventMapper：append 直接 insert；claimReady 锁行后逐行 markProcessing，行锁下守卫失败即跳过，返回 `OutboxEvent.claimed()` 集合（并发绝不重叠）。 | RedisOutboxPublisher 竞争发布的持久化入口。 |
| `.../observability/infrastructure/persistence/repository/MyBatisInboxEventStore.java` | 把 `InboxEventStore` 端口适配到 InboxEventMapper：recordProcessed 以受影响行数为真值（幂等），wasProcessed 走租户+consumer+event 存在性查询。 | 消费者写回执。 |
| `.../observability/infrastructure/persistence/converter/TaskPersistenceConverter.java` | TaskPo ↔ Task 双向转换；损坏持久化行转稳定 INTERNAL_ERROR 而非静默默认；OffsetDateTime/Instant 与必填字段集中处理。 | 表字段变化时同步维护。 |
| `.../observability/infrastructure/persistence/converter/OutboxEventPersistenceConverter.java` | OutboxEventPo ↔ OutboxEvent 双向转换；领域允许空 headers，NOT NULL 列落空 JSON 对象；损坏行转稳定内部错误。 | 表字段变化时同步维护。 |
| `.../observability/infrastructure/persistence/converter/InboxEventPersistenceConverter.java` | InboxEventPo ↔ InboxEvent 双向转换；损坏行转稳定内部错误。 | 表字段变化时同步维护。 |
| `.../observability/infrastructure/persistence/config/ObservabilityPersistenceConfiguration.java` | `@MapperScan(basePackageClasses = TaskMapper.class)` 扫描 observability Mapper；**不**定义自己的 `MybatisPlusInterceptor`（复用 security 全局单例，拦截器同时施加于共享 SqlSessionFactory）。 | 保持与 security 全局拦截器共享。 |

### 10.3 observability 单元测试

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `.../observability/task/TaskStatusTest.java` | `@ParameterizedTest` + `@CsvSource` 验证合法/非法 Task 状态转换、终态不可逆、null 目标拒绝。 | 状态机扩展时同步维护。 |
| `.../observability/task/TaskTest.java` | 24 字段 Task compact 构造器校验（空/超长 taskType、progress 越界、非对象 payload/result、attempt 越界、半组锁、completed 早于 started、负版本）与 `claimed()` 语义；新增 `claimingAnExhaustedTaskIsRejected`（attempt 耗尽拒绝）与 `toStringNeverExposesPayloadOrResult`。 | 领域规则变化时同步。 |
| `.../observability/outbox/OutboxStatusTest.java` | 验证 Outbox 状态终态标志与 CHECK 一致性。 | 状态变化时同步。 |
| `.../observability/outbox/OutboxEventTest.java` | 事件构造校验（空白聚合、非对象 payload/headers、retry 越界、半组锁、PUBLISHED 缺 publishedAt、负版本）与 claimed/published/failure 转换、退避倍增、预算耗尽死信。 | 领域规则变化时同步。 |
| `.../observability/outbox/RetryPolicyTest.java` | 延迟倍增 1/2/4/8s、封顶 5s、DEFAULT 1s/5min、非法参数拒绝。 | 退避策略变化时同步。 |
| `.../observability/application/service/TaskSubmissionServiceTest.java` | 内联 FakeTaskStore/FakeOutboxEventStore + with* 命令构建器：提交原子写双 id、每提交 id 唯一、各类校验失败、校验失败零写入。 | 服务规则变化时同步。 |
| `.../observability/application/service/TaskQueryServiceTest.java` | 内联 fake：租户严格透传、缺失任务统一 404。 | 查询语义变化时同步。 |
| `.../observability/application/port/out/TaskTransitionTest.java` | 锁定 Task 侧净化边界：构造即脱敏（api_key/Bearer→`<redacted>`）、null 保持 null、控制字符剥离并截断 2000；`toString()` 不输出 result/errorMessage。 | 净化规则或日志输出字段变化时同步。 |
| `.../observability/application/service/OutboxPublisherServiceTest.java` | 内联 fake：limit 校验、worker/lease 透传、publish/fail 丢竞争 409、fail 净化错误（去 NUL、截断 2000、**断言 api_key 密钥不存在**、Bearer 脱敏）、预算耗尽死信。 | 发布服务规则变化时同步。 |
| `.../observability/application/service/ErrorMessageSanitizerTest.java` | 控制字符剥离（NUL/ESC/DEL）、截断（含先剥离后计数）、默认封顶、null 与负长度；脱敏：labeled 凭证保留字段名、Authorization Bearer/裸 sk-/JWT 替换、稳定幂等。 | 净化规则变化时同步；新增凭证形态在此补用例。 |
| `.../observability/infrastructure/persistence/mapper/ObservabilityMapperSqlContractTest.java` | 反射锁定 observability 租户隔离 SQL 契约：11 个绕过 tenant-line 的方法白名单、唯一全局例外 `OutboxEventMapper.selectClaimable`、每条绕过 SQL 显式 tenant_id（INSERT 断言写 tenant_id 列）、claim 的 SKIP LOCKED/排序、Task claim 的 `attempt_count < max_attempts`、处理/发布/失败/Task claim/transition 的 status+version+tenant 守卫、Inbox 唯一约束幂等与租户条件。新增绕过方法即构建失败。 | 新增 observability 自定义 SQL 时在此补断言。 |

## 11. `knowagent-api`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-api/pom.xml` | 聚合全部业务模块并引入 Spring MVC、Security、数据库、Redis、Flyway；`docker-it` Profile 使用 Failsafe 运行 Testcontainers（含 `org.testcontainers:minio` 真实对象存储 IT）。 | Controller、SseEmitter、请求 DTO、认证过滤器和基础设施 Bean 在此装配；数据库迁移集成测试用 `mvn -Pdocker-it verify` 执行。 |
| `.../api/KnowAgentApiApplication.java` | HTTP API 进程的 Spring Boot 启动入口；Mapper 扫描由各业务模块配置负责。 | 保持薄启动类，避免全根包扫描把应用端口误注册为 Mapper。 |
| `.../api/config/SecurityBootstrapConfiguration.java` | 基于 Servlet `SecurityFilterChain` 的安全配置：类级 `@EnableMethodSecurity` 启用 `@PreAuthorize` 方法鉴权（拒绝时复用 `JsonAccessDeniedHandler` 输出 JSON 403）；放行健康检查和系统信息接口，并在认证过滤器之后、`AuthorizationFilter` 之前注册 `TenantContextFilter`。 | 替换为 JWT、RBAC 和统一未认证响应时，保持认证、租户上下文、授权三者的执行顺序。 |
| `.../api/config/TenantContextFilter.java` | `OncePerRequestFilter`：从 SecurityContextHolder 的认证 principal 解析 TenantPrincipal 写入 TenantContext，并在 finally 清理；不信任任何客户端请求头。 | 内联构造，不注册为 Spring Bean，避免被 Boot 二次注册到普通 Servlet 链。 |
| `.../api/bootstrap/AdminBootstrapProperties.java` | 把 `bootstrap.*` 配置映射到 `BOOTSTRAP_ENABLED`、`BOOTSTRAP_TENANT_SLUG`、`BOOTSTRAP_ADMIN_LOGIN`、`BOOTSTRAP_ADMIN_PASSWORD` 等环境变量。 | 启动装配使用，字段名即文档。 |
| `.../api/bootstrap/AdminBootstrapConfiguration.java` | 启用 bootstrap 配置属性并注册 AdminBootstrapRunner Bean。 | 保持 `proxyBeanMethods=false`，只做装配不做逻辑。 |
| `.../api/bootstrap/AdminBootstrapRunner.java` | `ApplicationRunner`：未启用时记 INFO 并跳过；启用后校验参数并调用初始化，缺参/弱密码包装为 IllegalStateException 拒绝启动，消息不含原始密码，成功日志只输出 slug 与 login。 | 是初始化唯一触发点，禁止暴露为 HTTP 端点。 |
| `.../api/system/SystemInfoController.java` | 提供 `/api/v1/system/info`，用于验证 API 已启动。 | 可增加公开版本信息，不能暴露密钥和内部配置。 |
| `.../api/auth/AuthController.java` | `POST /api/v1/auth/login`（匿名）：映射 LoginCommand、调用 Login 并签发 Access Token；`POST /api/v1/auth/refresh`（匿名）：把 RefreshCommand 委托给 RefreshAuthenticationService；`POST /api/v1/auth/logout`（匿名）：映射 LogoutCommand 调用 RefreshTokens，返回 204。 | 只做 HTTP 装配，不接触 Mapper；刷新事务协调不放在 Controller。 |
| `.../api/auth/RefreshAuthenticationService.java` | API 层刷新事务门面：在同一 `@Transactional(noRollbackFor=RefreshTokenInvalidException.class)` 边界内调用 RefreshTokens 完成数据库轮换、调用 AccessTokenIssuer 签名并构造 LoginResponse。 | JWT 签名或响应构造失败时回滚消费与子 token 插入；重放异常仍提交家族撤销，同时保持 security 模块不依赖 JWT/Web。 |
| `.../api/auth/dto/LoginRequest.java`、`LoginResponse.java`、`RefreshTokenRequest.java` | 登录请求/响应与轮换请求 DTO：请求校验 tenantSlug/loginName/password 非空与长度上限、refreshToken 非空且不超过 512 字符，响应携带 tokenType、accessToken、refreshToken、expiresIn；所有字符串输出均隐藏敏感字段（RefreshTokenRequest 字符串输出隐藏原始 token）。 | 校验失败由 ApiExceptionHandler 统一转 JSON 400。 |
| `.../api/auth/LoginProperties.java`、`LoginConfiguration.java` | 把 `auth.login.*` 配置映射为类型安全属性并装配 `LoginPolicies` Bean（maxFailedAttempts、lockDuration、refreshTokenTtl）。 | 字段名即文档，阈值禁止在代码里写死。 |
| `.../api/user/UserController.java`、`dto/MeResponse.java`、`dto/UserItemResponse.java`、`dto/UserPageResponse.java` | `GET /api/v1/users/me`：从 `@AuthenticationPrincipal TenantPrincipal` 读取当前身份，调用 CurrentUserService，返回 userId/tenantId/tenantSlug/loginName/displayName/roles/permissions（角色与权限排序稳定，不包含密码哈希、锁定计数等内部字段）。`GET /api/v1/users` 与 `GET /api/v1/users/{userId}`：均 `@PreAuthorize("hasAuthority('USER_READ')")`，从 principal 取 `tenantId()` 调用 UserQueryService，返回 `UserPageResponse`/`UserItemResponse`（仅 userId/departmentId/loginName/displayName/email/phoneNumber/status/createdAt，结构上不可能泄露内部字段）；分页默认 page=1、size=20。 | 依赖已有 Access Token 认证链，匿名访问得到 JSON 401；管理查询跨租户 userId 与不存在用户统一 404，非法参数 400。 |
| `.../api/error/ApiErrorResponse.java`、`ApiExceptionHandler.java` | 统一 JSON 错误响应与 `@RestControllerAdvice`：BusinessException 按 ErrorCode 映射 HTTP 状态（401/403/404/409/502/500），DTO 校验与畸形 JSON 统一 400 VALIDATION_ERROR，无匹配路由 404 RESOURCE_NOT_FOUND，`MethodArgumentTypeMismatchException`（非法枚举/非法 UUID 等）统一 400 VALIDATION_ERROR，`MaxUploadSizeExceededException` → 413 PAYLOAD_TOO_LARGE、`MissingServletRequestPartException` → 400。刻意不提供 `Exception.class` 兜底，避免把方法级鉴权 AccessDeniedException 和 405/415 吞成 500。 | 后续所有 Controller 复用；错误码与状态映射变化只改这一处。 |
| `.../resources/application.yml` | API 端口、数据源、Redis、Flyway、multipart 上传限制（`max-file-size=50MB` 第一道防线，413）、Actuator 和日志配置。 | 通过环境变量覆盖，不在文件中写生产密码。 |
| `.../resources/db/migration/V1__baseline.sql` | 已发布的 Flyway 空基线，用于固定迁移起点。 | 保持内容不变，禁止修改已执行迁移的校验和。 |
| `.../resources/db/migration/V2__identity_core.sql` 至 `V11__mcp.sql` | 按身份、权限、凭据、模型、知识库、聊天、运行时、异步任务、Skills 和 MCP 创建 31 张 MVP 表。 | 只允许新增更高版本迁移；字段、约束和锁语义以 `docs/database-schema.md` 为准。 |
| `.../api/database/FlywaySchemaIT.java` | 在 PostgreSQL 16 Testcontainer 中验证迁移、租户约束、Token 家族、Run 并发、Outbox 抢占和事务回滚。 | Docker 可用时通过 `docker-it` Profile 运行；默认构建不启动容器。 |
| `.../api/database/SecurityPersistenceIT.java` | 使用 PostgreSQL 16 和真实 Mapper 验证认证表映射、有效角色、跨租户隔离、乐观锁、Refresh Token 行锁，并启动真实 Spring Boot 上下文验证生产装配。 | Docker 可用时通过 `docker-it` Profile 运行；失败或跳过时不得勾选测试计划。 |
| `.../api/database/TenantIsolationIT.java` | 使用 PostgreSQL 16 和真实 TenantLineInnerInterceptor 验证普通查询自动追加 tenant 条件、缺上下文查询/写入 fail closed、显式 tenant SQL 无法枚举跨租户行、无租户 PO 插入由上下文填充。 | 新增租户拦截器行为或自定义 SQL 时在此补充跨租户用例。 |
| `.../api/config/TenantContextFilterTest.java` | 用 Mock 请求/响应验证过滤器从 principal 注入租户、finally 清理、同线程先后请求不残留、不信任 X-Tenant-Id 头、下游抛异常后上下文仍被清理。 | 过滤器注册或 principal 解析变化时同步维护。 |
| `.../api/bootstrap/AdminBootstrapRunnerTest.java` | 用假服务验证未启用跳过、缺密码/缺登录/弱密码拒绝启动且消息不泄露原始值、合法配置执行并规范化输入。 | Runner 参数或异常语义变化时同步维护。 |
| `.../api/database/AdminBootstrapIT.java` | Testcontainers 上跑真实 Spring 上下文：首次执行与重复执行、Argon2id 哈希、失败整体回滚，以及过期绑定在唯一约束下原地恢复并重新获得 ADMIN 权限。 | Docker 可用时通过 `docker-it` Profile 运行；过期绑定恢复后仍必须只有一条自然键记录。 |
| `.../api/database/AuthFlowIT.java` | Testcontainers 上跑真实安全链登录闭环：正确密码登录返回 token 并用其访问 `/api/v1/users/me`（验证 refresh token 仅存 SHA-256 哈希、响应不含密码哈希/锁定字段/token 明文）、错误密码/禁用/锁定返回稳定错误码、连续失败触发锁定且成功登录清除计数、16 个并发错误密码全部累计并触发锁定（计数 = 401 数，原子递增不丢计数）、真实失败锁定的账号在锁定窗口过期后恢复登录、tenant-A 登录无法加载 tenant-B 用户与角色、DTO 校验失败统一 JSON 400。 | Docker 可用时通过 `docker-it` Profile 运行；登录闭环与轮换/登出按关注点拆分，轮换见 RefreshRotationIT。 |
| `.../api/database/RefreshRotationIT.java` | Testcontainers 上跑真实安全链轮换闭环：单次轮换、重放撤销、并发收敛、登出幂等、保存点唯一冲突、禁用/锁定账户、明文不落库；额外注入 JWT 编码失败，验证 API 事务门面把根 token 消费和子 token 插入整体回滚，并覆盖 ACTIVE + 未来锁定时间。 | Docker 可用时通过 `docker-it` Profile 运行；修改轮换、签名、重放或账户锁定语义时保持此用例。 |
| `.../api/database/LoginConcurrencyIT.java` | 独立 PostgreSQL 容器 + 真实 Spring 上下文，把 Hikari 池压到 4 并同时发起 4 个错误密码登录：证明登录失败链路不再持有嵌套事务，单个登录最多一个连接，所有请求在 20 秒内返回 401/403（无超时、无 500），失败计数不丢失并达到锁定阈值，正确密码随后被 403 拒绝。 | 这是针对「外层事务 + REQUIRES_NEW 嵌套导致连接池死锁」的回归测试，修改登录事务边界时保持此用例；旧实现会在此池上超时。 |
| `.../api/database/UserQueryIT.java` | Testcontainers 上跑真实安全链：`AdminBootstrap` 引导 alpha/beta 两租户 + 裸 SQL 插入用户/角色/`user_roles`（真实 `PasswordHasher` Argon2id），登录取真实 token 后验证——匿名 401、无 `USER_READ` 角色 403、过期 `user_roles` 绑定 403、ADMIN 只列出本租户 7 个用户且 tenant-B 用户不出现、`ORDER BY created_at DESC` 分页顺序与 total 稳定、keyword/status 过滤、page=0/size=101/OFFSET 溢出/status=BOGUS/`/users/not-a-uuid` → 400、跨租户 userId → 404、详情不含 passwordHash/loginFailedCount/loginLockedUntil。 | Docker 可用时通过 `docker-it` Profile 运行；同时验证租户插件能正确改写带 LIKE/ESCAPE/COALESCE 的分页/统计自定义 SQL。 |
| `.../api/modelprovider/ModelProviderController.java` | `POST/GET/GET/{id}/PATCH/{id}/DELETE/{id}/POST/{id}/health-check` 供应商管理接口；读需 `MODEL_PROVIDER_READ`、写需 `MODEL_PROVIDER_WRITE`，租户只来自 `@AuthenticationPrincipal`。 | 复用现有 `@PreAuthorize` + JSON 403/404/409 链路。 |
| `.../api/modelprovider/dto/CreateModelProviderRequest.java`、`UpdateModelProviderRequest.java`、`EnabledModelRequest.java`、`EnabledModelResponse.java`、`ModelProviderResponse.java`、`ModelProviderPageResponse.java`、`HealthCheckResponse.java` | HTTP DTO 与领域值对象分离；请求使用 Bean Validation，响应只含 hasSecret/capabilities/enabledModels/publicConfig，不含 ciphertext/keyVersion/内部 header；请求 `toString()` 隐藏明文 secret/headers。 | 字段结构变化时同步，禁止直接暴露领域对象。 |
| `.../api/database/ModelProviderIT.java` | Testcontainers 真实安全链覆盖 RBAC、租户隔离、密文、JSONB、清密钥、软删复用、非法配置、跨租户复合外键，以及供应商删除与知识库引用并发锁协议；当前 12/12 通过。 | 修改供应商事务、外键或锁协议时执行 `mvn -Pdocker-it verify`。 |
| `.../api/knowledgebase/KnowledgeBaseController.java` | `POST/GET/GET/{id}/PATCH/{id}/DELETE/{id}` 知识库管理接口：读需 `KNOWLEDGE_BASE_READ`、写需 `KNOWLEDGE_BASE_WRITE`，租户只来自 `@AuthenticationPrincipal`，响应只映射领域对象的安全字段。 | 复用现有 `@PreAuthorize` + JSON 401/403/404/409 链路。 |
| `.../api/knowledgebase/dto/CreateKnowledgeBaseRequest.java`、`UpdateKnowledgeBaseRequest.java`、`KnowledgeBaseResponse.java`、`KnowledgeBasePageResponse.java`、`ChunkPolicyRequest.java`、`ChunkPolicyResponse.java`、`RetrievalConfigRequest.java`、`RetrievalConfigResponse.java` | HTTP DTO 与领域值对象分离；请求用 Bean Validation，`toDomain()` 集中做领域校验（非法值稳定 400）；响应不含 version/tenantId/createdBy/updatedBy/deletedAt 等内部字段。 | 字段结构变化时同步，禁止直接暴露领域对象。 |
| `.../api/database/KnowledgeBaseIT.java` | Testcontainers 真实安全链覆盖知识库 CRUD：匿名 401、无权限 403、创建默认值/绑定启用供应商、非法 slug/分块/检索配置/供应商能力/半配置/enabled_models 模型目录 400、跨租户供应商与资源 404、slug 重复与乐观锁冲突 409（并发更新不覆盖）、并发 slug 竞争一胜一 409、供应商删除并发时创建阻塞并 404 不落库、列表分页/过滤、空库删除 + slug 复用、有活动文件删除 409；当前 18/18 通过。 | 修改知识库事务、状态机、锁协议或文件守卫时执行 `mvn -Pdocker-it verify`。 |
| `.../api/task/TaskController.java` | `GET /api/v1/tasks/{id}` 异步任务状态接口：需 `TASK_READ`（`@PreAuthorize`），租户只来自 `@AuthenticationPrincipal TenantPrincipal`，跨租户与不存在任务统一 404。 | 复用现有 `@PreAuthorize` + JSON 401/403/404 链路。 |
| `.../api/task/dto/TaskResponse.java` | Task 状态响应 DTO：只含 id/taskType/aggregate/status/stage/progress/attemptCount/maxAttempts/errorCode/errorMessage/retryable/时间戳，刻意不含 payload/result（可能携带存储键或解析内容）与 tenantId/lockedBy/lockedUntil/version 等内部字段。 | 字段结构变化时同步，禁止直接暴露领域对象。 |
| `.../api/database/TaskOutboxInboxIT.java` | Testcontainers 真实安全链覆盖异步持久化基础：业务记录+Task+Outbox 同事务回滚/独立提交、两个并发发布者 claim 不重叠且并集完整、租约未过期不可抢占/过期可回收、失败递增重试+指数退避+DEAD_LETTER、status+version 守卫阻止完成陈旧事件、**Task 重试耗尽不可再认领/不可再入 PENDING 但可转 FAILED（不触发 ck_tasks_attempts）**、重复 Inbox 回执只执行一次并报告已处理、tenant-A 数据对 tenant-B 不可见，以及 HTTP 匿名 401/无 `TASK_READ` 403/管理员 200 且响应不含 payload/result/tenantId/锁/version/跨租户 404；当前 13/13 通过。 | 修改任务/Outbox/Inbox 事务边界、锁语义或幂等规则时执行 `mvn -Pdocker-it verify`。 |
| `.../api/knowledgebase/KnowledgeFileController.java` | `POST/GET/GET/{fileId}/GET/{fileId}/content` 文件接口：上传 `@PostMapping(consumes=multipart/form-data)` 成功返回 **202**（`@ResponseStatus(ACCEPTED)`，不解析文件、不调用 Embedding/Milvus，只入队）；content 返回 `StreamingResponseBody` 流式下载 + `Content-Disposition: attachment` + contentLength + contentType；写需 `KNOWLEDGE_FILE_WRITE`、读需 `KNOWLEDGE_FILE_READ`，租户只来自 `@AuthenticationPrincipal`，`Idempotency-Key` 请求头原样透传。 | 复用现有 `@PreAuthorize` + JSON 401/403/404 链路。 |
| `.../api/knowledgebase/dto/UploadFileResponse.java`、`KnowledgeFileResponse.java`、`KnowledgeFilePageResponse.java` | 文件 HTTP DTO：`UploadFileResponse` 只含 fileId/taskId/status/replayed/sha256/size/createdAt；`KnowledgeFileResponse` 只含展示名/原始名/类型/扩展名/sha256/size/status/时间戳——结构上不可能泄露 objectKey/processingParams（task/outbox id）/错误内部字段/审计 userId；分页响应复用领域 `KnowledgeFilePage`。 | 字段结构变化时同步，禁止直接暴露领域对象。 |
| `.../api/database/MinioStorageIT.java` | 真实 MinIO Testcontainer（与 Compose 同镜像）锁定对象存储边界契约：put/stat/get/delete 往返 + 存储的 SHA-256 元数据、tenant-A 键带前缀且 tenant-B 寻址被拒（stat/get/delete 均 INVALID_OPERATION）、跨租户键与任意非前缀键对四种操作均被拒、删除缺失对象幂等成功；当前 5/5 通过。 | 修改适配器、键形状或隔离语义时执行 `mvn -Pdocker-it verify`。 |
| `.../api/database/KnowledgeFileUploadIT.java` | Testcontainers 真实 PG + MinIO + 安全链的端到端上传：TXT/PDF/DOCX 均 202 + QUEUED + 各一条 file/task/outbox；空/超限（50MB spool 上限）/伪造 MIME/未知类型稳定 400；缺失/禁用知识库 404/409；数据库失败（PL/pgSQL 触发器）补偿删除孤儿对象且 0 行 0 对象；同 Idempotency-Key 同 hash 重放（replayed=true、同 fileId/taskId、不重复写入）与不同 hash 409；匿名 401、无权限 403；list/detail/content 不泄露 objectKey/bucket/processing_params/内部字段，content 字节一致且带 attachment 头；跨租户知识库与跨知识库读取统一 404；列表 status 过滤与分页；并发删除先以 `FOR UPDATE` 持锁时上传等待，删除提交后上传 404、0 file/task/outbox 且 MinIO 对象已补偿；当前 14/14 通过。 | 修改上传事务、幂等、补偿或泄露控制时执行 `mvn -Pdocker-it verify`。 |
| `.../api/database/KnowledgeChunkIT.java` | Testcontainers 真实 PostgreSQL 驱动 ChunkWriteService（无 HTTP，租户上下文仿过滤器设置）：替换式写入 chunk_count/token_count 与实际行数一致、version 0→1、全部 PENDING 且 UUID 各不相同；同一文件相同替换重试幂等（chunk_index 无重复、行数/内容/UUID 均不变）；重复 chunk_index 触发 `UNIQUE(tenant_id, file_id, chunk_index)` 后**整体回滚**——旧 chunk 集合完整保留、文件统计不回退；tenant-B 不能查询/替换/删除 tenant-A chunk；beta 自有文件与 alpha chunk 互不可见；当前 5/5 通过。 | 修改分块事务/锁/幂等/租户隔离时执行 `mvn -Pdocker-it verify`。 |
| `.../api/knowledgebase/KnowledgeRetrievalController.java` | `POST /api/v1/knowledge-bases/{knowledgeBaseId}/retrieval`；需 `KNOWLEDGE_RETRIEVE`，tenant 只来自 principal，Controller 只映射 DTO/命令并调用应用服务。 | 不得调用 Mapper、Chat 或供应商 SDK。 |
| `.../api/knowledgebase/dto/KnowledgeRetrievalRequest.java`、`KnowledgeRetrievalResponse.java`、`KnowledgeCitationResponse.java` | 检索 HTTP DTO：query 必填，topK/threshold 可缺省，fileIds 最多 100；响应只含 kbId 和可验证引用，引用正文可返回但 `toString()` 排除正文，不含 tenant/objectKey/向量/供应商/内部 metadata。 | 字段变化时同步 Bean Validation 与泄露测试。 |
| `.../api/knowledgebase/KnowledgeRetrievalApiContractTest.java` | 2 例锁定方法级 `KNOWLEDGE_RETRIEVE`、ADMIN 权限授予以及请求 DTO 不含 tenant/字符串输出不含 query。 | 修改权限或 DTO 时同步。 |
| `.../api/database/KnowledgeRetrievalIT.java` | 真实 PostgreSQL 16 + Milvus 2.5.6 检索集成 1 例：验证 tenant+kb filter，故意注入标量作用域与 PG 所有权不一致的 chunkId，证明 PG 回查丢弃；同时验证 READY 裁剪、引用字段来源、跨租户 fileId 在 Embedding 前 404。 | 通过 `-Pdocker-it -Dit.test=KnowledgeRetrievalIT` 执行；不得放进默认构建。 |

## 12. `knowagent-worker`

| 文件 | 当前作用 | 后续使用方式 |
|---|---|---|
| `knowagent-worker/pom.xml` | 聚合 knowledge/model/workspace/observability/security 与 Redis、数据库运行依赖；测试侧引入 Flyway、Testcontainers PostgreSQL/MinIO/Milvus，Failsafe `docker-it` 默认跳过。已移除本任务不需要的 agent-runtime/extension 依赖。 | 默认 verify 不启动 Docker；真实链路用 `-Pdocker-it`。 |
| `.../worker/KnowAgentWorkerApplication.java` | 非 Web Worker 启动入口；只扫描 Worker、knowledge、model、workspace、observability，并显式导入安全持久化配置；不暴露业务 HTTP，也不装配 Agent Runtime。 | 新增后台能力时先评估模块方向再扩扫描边界。 |
| `.../worker/stream/IngestionEventCodec.java`、`IngestionEventEnvelope.java`、`InvalidEventEnvelopeException.java` | 固定 schemaVersion=1/eventType 的白名单信封；根字段和 payload 字段严格限制，payloadHash 用 SHA-256，校验发生在 TenantContext 之前。 | 版本升级应新增明确兼容分支，禁止透传 object key/secret/任意 JSON。 |
| `.../worker/stream/WorkerTenantScope.java` | 从已校验事件建立系统 TenantPrincipal；调用前清旧值、finally 清理，避免线程池 tenant-A→tenant-B 残留。 | 所有可信异步入口复用此作用域。 |
| `.../worker/stream/RedisOutboxPublisher.java` | 周期性小批 claim；XADD 固定 Stream 后才条件更新 PG PUBLISHED；Redis 失败走 V9 fail/backoff/dead-letter，XADD 后 PG 竞争失败保留重复投递窗口由 Inbox 收敛。 | 不在此引入分布式事务或把原始文件写入消息。 |
| `.../worker/stream/RedisIngestionConsumer.java` | XREADGROUP 手动 ACK；成功/终态/Inbox 重复才 ACK，DEFERRED/异常保留 pending；用 XPENDING + XCLAIM reclaim 死亡消费者消息。 | consumer name 必须实例唯一，group 固定共享。 |
| `.../worker/stream/IngestionStreamProperties.java`、`WorkerStreamConfiguration.java`、`.../worker/resources/application.yml` | 固定 key 和类型安全 group/consumer/batch/poll/reclaim/Task lease/Outbox lease；调度间隔与 publisher/consumer 开关由配置控制。 | 多副本用同 group、不同 consumer；配置表见 README/.env.example。 |
| `.../worker/stream/IngestionEventCodecTest.java`、`RedisOutboxPublisherTest.java`、`RedisIngestionConsumerTest.java`、`WorkerTenantScopeTest.java` | 10 例锁定信封、防泄露、XADD→PG 顺序、失败退避、手动 ACK、pending reclaim、上下文清理。 | 修改投递/信封/租户边界时同步。 |
| `.../worker/stream/WorkerIngestionPipelineIT.java` | 真实 PostgreSQL 16、Redis 7、MinIO、Milvus 2.5.6 四容器 4 例：崩溃窗口重复投递一次执行、PDF pending reclaim、DOCX READY/Task progress、双 Worker 并发同事件不重复 chunk/向量；同时核对 PG chunk UUID 与 Milvus ID。 | 通过 `-Pdocker-it -Dit.test=WorkerIngestionPipelineIT` 执行；不得放进默认构建。 |
| `.../worker/WorkerComponentScanTest.java` | 锁定 Worker 的最小目标包扫描与安全持久化显式导入，防止退回全根包扫描。 | Worker 新增模块依赖时先评估再更新白名单。 |

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
