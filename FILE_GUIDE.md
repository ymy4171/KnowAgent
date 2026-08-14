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
| `.../security/domain/role/SecurityPermissions.java` | 集中定义后续管理接口需要的稳定权限码（TENANT_/DEPARTMENT_/USER_/ROLE_/MODEL_PROVIDER_ 读写、AUDIT_READ），并提供 `ADMIN_ROLE_PERMISSIONS`；`USER_ADMIN` 本阶段只定义常量、不加入 `ADMIN_ROLE_PERMISSIONS`、不授予任何人。 | 任何地方都从它引用权限码，禁止散落魔法字符串。 |
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
| `.../api/error/ApiErrorResponse.java`、`ApiExceptionHandler.java` | 统一 JSON 错误响应与 `@RestControllerAdvice`：BusinessException 按 ErrorCode 映射 HTTP 状态（401/403/404/409/502/500），DTO 校验与畸形 JSON 统一 400 VALIDATION_ERROR，无匹配路由 404 RESOURCE_NOT_FOUND，`MethodArgumentTypeMismatchException`（非法枚举/非法 UUID 等）统一 400 VALIDATION_ERROR。刻意不提供 `Exception.class` 兜底，避免把方法级鉴权 AccessDeniedException 和 405/415 吞成 500。 | 后续所有 Controller 复用；错误码与状态映射变化只改这一处。 |
| `.../resources/application.yml` | API 端口、数据源、Redis、Flyway、Actuator 和日志配置。 | 通过环境变量覆盖，不在文件中写生产密码。 |
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
