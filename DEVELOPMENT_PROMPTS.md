# KnowAgent 开发提示词

本文提供下一阶段“认证、授权与租户”开发的可执行提示词。提示词按依赖顺序拆分，建议一次只执行一个，当前提示词验收通过后再进入下一个。

## 1. 使用方式

1. 在 KnowAgent 仓库根目录开启新的 Codex/IDEA AI 任务。
2. 先粘贴“统一前置提示词”，再粘贴当前阶段提示词。
3. 要求编码 Agent 先扫描现有代码和 Git 状态，不得覆盖用户已有修改。
4. 每个阶段都必须完成实现、测试、文档更新和变更清单，不能只给方案。
5. 已执行的 `V1` 至 `V11` Flyway 文件禁止修改；确需改表时新增 `V12__*.sql`。

## 2. 统一前置提示词

```text
你正在维护 Java 21 项目 KnowAgent。请在开始编码前完整阅读并遵守：

- README.md
- PLAN.md
- docs/architecture.md
- docs/database-schema.md
- docs/adr/0001-modular-monolith.md
- docs/adr/0003-mybatis-plus.md
- docs/adr/0004-spring-mvc.md
- TEST_PLAN.md
- FILE_GUIDE.md

当前事实：

- 项目采用 Maven 多模块模块化单体。
- HTTP 层采用 Spring Boot 3.5.9 + Spring MVC + Spring Security。
- 数据访问采用 MyBatis-Plus + PostgreSQL 16 + Flyway。
- PostgreSQL 是业务状态最终事实来源。
- Flyway V1-V11 已创建 31 张业务表并通过 PostgreSQL 16 Testcontainers 验证。
- `knowagent-security` 负责身份、认证、授权、租户上下文及其应用服务。
- `knowagent-api` 只负责 HTTP DTO、Controller、安全装配和异常映射。
- Controller 不得直接调用 Mapper；跨模块不得直接调用 Mapper。
- 普通租户查询可以使用 MyBatis-Plus 租户拦截器；自定义 SQL、锁查询、统计及批量更新必须显式包含 tenant_id。
- 除 tenants 外，所有业务表都必须按 tenant_id 隔离。
- 跨租户资源枚举统一返回 404，不泄露资源是否存在。
- 不允许在代码、迁移、测试日志或文档中写入真实密码、JWT 密钥、Token 或 API Key。
- 不引入 JPA、WebFlux、Lombok，也不把 SQL Schema 交给 ORM 自动生成。

执行规则：

1. 先检查 Git 状态、模块依赖、现有代码和测试，再说明本次具体修改范围。
2. 保留并兼容用户已有修改，不做无关重构。
3. 优先复用现有 TenantId、TenantPrincipal、BusinessException 和 ErrorCode。
4. 领域对象、持久化对象、HTTP DTO 分离；不要直接把数据库对象暴露给 Controller。
5. 所有时间使用 Instant/OffsetDateTime，并按 PostgreSQL timestamptz 正确映射。
6. 应用写入的 UUID 在 Java 中预生成，不能依赖插入后再猜测数据库生成值。
7. 状态枚举名称必须与数据库大写 CHECK 值完全一致。
8. 每完成一个阶段，运行该阶段单元测试和 Maven 构建；需要 Docker 的测试放到 Failsafe `docker-it` Profile。
9. 只有测试实际通过后才能勾选 TEST_PLAN.md 对应条目。
10. 最终明确列出修改的每个文件、每个文件的作用、测试命令与结果、剩余风险。
```

## 3. 提示词一：身份持久化基础

```text
请实现 KnowAgent 认证主链所需的第一批持久化代码，本次只覆盖 tenants、users、roles、user_roles、refresh_tokens，不要为全部 31 张表批量生成 Entity 和 Mapper。

目标：

- 在 knowagent-security 中建立 domain、application、infrastructure/persistence 分层。
- 为 Tenant、User、Role、UserRole、RefreshToken 建立必要的领域模型、状态枚举和持久化对象。
- 编写 MyBatis-Plus Mapper；复杂查询使用显式 SQL。
- 提供面向应用层的 Repository/Store 端口，应用服务不能暴露 Mapper。
- 映射 UUID、timestamptz、inet、jsonb 和 version 字段。
- roles.permissions 使用 Jackson JsonNode 或经过验证的 JSONB TypeHandler 映射为 Set<String>，禁止手工拼接 JSON。

必须实现的查询：

- 根据 slug 查询未删除且 ACTIVE 的租户。
- 根据 tenant_id + login_name 查询未删除用户。
- 根据 tenant_id + user_id 查询当前有效角色及 permissions；忽略 DISABLED、已删除或过期角色绑定。
- 根据 token_hash 查询 Refresh Token，并提供 `FOR UPDATE` 锁查询。

约束：

- 登录属于认证前流程，租户解析和用户查询必须显式传 tenant_id，不能依赖尚未建立的 TenantContext。
- `tenants` 没有 tenant_id，不能被租户插件改写。
- 自定义 SQL 每一条都要检查 tenant_id 条件；Refresh Token 的全局唯一 token_hash 查询取得记录后仍要校验租户和用户关系。
- 不修改 V1-V11；不要新增无必要迁移。
- 不实现 Controller、JWT 和登录业务。

测试：

- Mapper/转换器单元测试。
- PostgreSQL Testcontainers 验证 JSONB、UUID、状态枚举、乐观锁和跨租户查询。
- tenant-A 的 userId 不能在 tenant-B 查询中返回结果。

完成后更新 FILE_GUIDE.md，并报告包结构和依赖方向。
```

## 4. 提示词二：租户上下文与 MyBatis 隔离

```text
请实现 KnowAgent 的请求级租户上下文和 MyBatis-Plus 租户隔离，但暂不实现登录接口。

目标：

- 在 knowagent-security 中实现 TenantContext，保存 TenantPrincipal/TenantId。
- 上下文必须 fail closed：受保护业务查询没有 tenant 时拒绝执行，不能默认查询全部租户。
- 使用 try/finally 或 OncePerRequestFilter 的 finally 清理 ThreadLocal，防止 Servlet 线程复用造成租户串线。
- 配置 MyBatisPlusInterceptor + TenantLineInnerInterceptor。
- 明确忽略 tenants、Flyway 历史等没有 tenant_id 的表。
- 只允许极少数认证前 Mapper 方法绕过插件；绕过方法本身必须显式包含 tenant_id，并附测试。
- 为自定义 SQL 建立代码审查规则：锁查询、统计、批量更新必须显式 tenant_id。

不要做：

- 不从客户端任意请求头直接信任 tenant_id。
- 不允许调用方传入完整 MinIO 路径来模拟租户隔离。
- 不使用 InheritableThreadLocal。
- 不让 Controller 手工 set/clear TenantContext。

测试：

- 同一线程先后处理 tenant-A、tenant-B 请求时上下文不会残留。
- 缺少认证上下文的受保护 Mapper 查询失败。
- 普通 MyBatis-Plus 查询自动追加 tenant 条件。
- 显式 tenant SQL 无法通过 tenant-A 枚举 tenant-B 数据。
- 异常请求完成后上下文仍被清理。

完成后更新 docs/architecture.md 中的认证请求上下文说明和 FILE_GUIDE.md。
```

## 5. 提示词三：开发管理员初始化

```text
请实现可重复、无明文凭据落库的开发管理员初始化流程。

目标：

- 初始化一个租户、ADMIN 系统角色和管理员用户，并建立 user_roles 关联。
- 使用 Spring Security PasswordEncoder；按照 docs/database-schema.md 使用 Argon2 密码串。如果 Argon2 运行需要额外安全提供者，只添加最小必要依赖。
- 初始化参数来自环境变量/配置属性，例如 BOOTSTRAP_TENANT_SLUG、BOOTSTRAP_ADMIN_LOGIN、BOOTSTRAP_ADMIN_PASSWORD。
- `.env.example` 只能保留占位说明，不能提交真实密码。
- 只有配置显式启用时才执行初始化；生产环境缺失或不安全配置时必须拒绝或跳过，不能自动生成并打印密码。
- 初始化必须幂等：重复启动不能创建重复租户、用户、角色或绑定。
- 整体放在一个事务中，任一步失败全部回滚。

权限：

- ADMIN 角色 code 使用数据库要求的大写格式。
- permissions 至少包含后续管理接口需要的稳定权限码，权限码集中定义，禁止散落魔法字符串。

测试：

- 首次执行创建完整数据。
- 第二次执行不产生重复数据。
- 密码只保存哈希，日志和异常中不出现原始密码。
- 任一步失败时事务整体回滚。

不要实现公开注册接口。完成后更新 README.md 的本地初始化说明和 FILE_GUIDE.md。
```

## 6. 提示词四：JWT Access Token 基础设施

```text
请基于 Spring Security 官方 JWT/Jose 能力实现 Access Token 签发、解析与认证过滤器，不要手写 JWT 编解码器。

目标：

- 使用 Spring Security OAuth2 Resource Server/Jose 组件。
- 密钥只从环境变量或外部配置读取，不在 application.yml 中提供真实默认值。
- Access Token 声明至少包含：sub=userId、tenant_id、roles、permissions、jti、iat、exp。
- issuer、audience、访问令牌有效期使用类型安全的 @ConfigurationProperties。
- 校验签名、issuer、audience、过期时间和必需声明。
- 将合法 JWT 转换为 Authentication 和 TenantPrincipal。
- 在认证完成后建立 TenantContext，并保证请求结束清理。
- 使用 SecurityFilterChain，不保留 Spring Boot 自动生成的 in-memory 用户和开发密码。

路由规则：

- `/actuator/health/**`、`/api/v1/system/info`、`/api/v1/auth/login`、`/api/v1/auth/refresh` 允许匿名访问。
- 其他 `/api/v1/**` 默认要求认证。
- API 返回 JSON 401/403，不返回默认 HTML 页面。

测试：

- 合法 Token 可以访问受保护接口。
- 缺失、篡改、过期、issuer/audience 错误的 Token 返回稳定 401 错误。
- Token 中 tenant_id 缺失或格式非法时认证失败。
- Token 中的角色和权限正确映射为 GrantedAuthority。
- 响应、日志和异常不输出完整 Token。

本阶段只实现 Access Token 基础设施，不实现 Refresh Token 轮换。
```

## 7. 提示词五：登录与当前用户接口

```text
请实现 KnowAgent 的登录闭环和当前用户接口。

接口：

- POST /api/v1/auth/login
- GET /api/v1/users/me

登录请求使用 tenantSlug、loginName、password。响应返回 tokenType、accessToken、refreshToken、expiresIn，不返回 passwordHash、tokenHash 或内部锁字段。

登录流程：

1. 规范化 tenantSlug 和 loginName。
2. 查询 ACTIVE 且未删除租户。
3. 使用 tenant_id + login_name 查询用户。
4. 检查 DISABLED、LOCKED、login_locked_until 和软删除状态。
5. 使用 PasswordEncoder 验证密码。
6. 加载有效角色与 permissions。
7. 更新 last_login_at，并清零失败计数。
8. 签发 Access Token 和高熵随机 Refresh Token。
9. 数据库只保存 Refresh Token 的 SHA-256 十六进制散列；原始值只在响应中出现一次。

失败策略：

- 不存在的租户、用户或错误密码使用统一的无泄露错误响应。
- 登录失败计数和临时锁定阈值使用配置属性；并发更新必须使用条件更新或乐观锁。
- 禁用、锁定和错误凭据使用稳定 ErrorCode，经统一异常处理映射为 JSON。
- 不在日志中记录密码或完整 Token。

`GET /api/v1/users/me` 返回 userId、tenantId、tenantSlug、loginName、displayName、roles、permissions。

测试：

- 正确密码登录并携带 Access Token 调用 `/users/me`。
- 错误密码、禁用用户、锁定用户分别返回稳定错误码。
- 连续失败触发锁定，成功登录清零失败计数。
- tenant-A 登录不能加载 tenant-B 的用户或角色。
- DTO 校验错误返回统一 400 响应。
```

## 8. 提示词六：Refresh Token 轮换、重放检测与退出

```text
请严格按照 docs/database-schema.md 的 Token 家族契约实现 Refresh Token 轮换和退出登录。

接口：

- POST /api/v1/auth/refresh
- POST /api/v1/auth/logout

刷新事务：

1. 对客户端原始 Refresh Token 做 SHA-256，禁止明文查库或落库。
2. 使用 token_hash 查询并 `FOR UPDATE` 锁定记录。
3. 校验状态、expires_at、用户和租户状态。
4. ACTIVE Token 在同一事务中更新为 CONSUMED，并插入同 family_id 的唯一子 Token。
5. 子 Token 的 parent_token_id 指向旧 Token；根 Token 必须满足 family_id=id。
6. 签发新的 Access Token 和 Refresh Token。

重放处理：

- 已 CONSUMED Token 再次出现视为重放攻击。
- 锁定后在同一事务中将该 family_id 下所有仍有效 Token 更新为 REVOKED，并记录稳定 revoke_reason。
- 返回稳定认证错误，不泄露该家族中其他会话信息。
- 唯一子 Token 冲突要转换为幂等/重放结果，不能返回数据库堆栈。

退出：

- 使用提交的 Refresh Token 定位家族并撤销该家族仍有效 Token。
- 重复退出保持幂等。

测试必须覆盖：

- Refresh Token 只能成功使用一次。
- 两个并发刷新请求最多一个成功。
- 旧 Token 重放后，新 Token 也被撤销。
- 过期、撤销、随机 Token 均被拒绝。
- 事务失败时旧 Token 状态和新 Token 插入一起回滚。
- 原始 Token 不出现在数据库和日志中。
```

## 9. 提示词七：RBAC 与租户内用户查询

```text
请完成 KnowAgent 的最小 RBAC 闭环，不实现完整用户管理后台。

目标：

- 扩展 TenantPrincipal，使其明确携带不可变 roles 和 permissions 集合。
- 权限校验使用 Spring Security Method Security 或统一 AuthorizationService。
- 新增一个管理员可用的租户内用户分页查询接口，例如 GET /api/v1/users。
- 该接口只能返回当前 tenant 的未删除用户，并支持受控分页、loginName/displayName 模糊条件和 status 精确条件。
- tenant_id 必须来自认证上下文，不能接受客户端 query/body 覆盖。
- 通过其他租户 userId 访问详情统一返回 404。
- 分页和统计 SQL 必须显式校验 tenant_id。

权限建议：

- `USER_READ`：读取租户内用户。
- `USER_ADMIN`：后续创建、禁用和授权用户，本阶段只定义不实现写接口。

测试：

- ADMIN/具备 USER_READ 的用户可以查询。
- 普通用户返回 403。
- tenant-A 查询永远不出现 tenant-B 数据。
- tenant-B userId 对 tenant-A 返回 404。
- 过期 user_roles 不产生权限。
- 分页总数和数据查询使用相同 tenant 条件。

不要把 roles.permissions 原始 JSON 直接返回给前端；先转换为规范化权限集合。
```

## 10. 提示词八：认证里程碑集成验收

```text
请对“认证、授权与租户”里程碑进行收尾，不新增下一阶段知识库功能。

工作内容：

1. 对实现进行安全和模块边界审查，重点检查：
   - Controller 是否直接调用 Mapper。
   - 自定义 SQL 是否遗漏 tenant_id。
   - ThreadLocal 是否总能清理。
   - 日志、异常和 DTO 是否泄露密码、JWT、Refresh Token 或哈希。
   - 事务和行锁是否覆盖 Refresh Token 轮换全过程。
   - 跨租户资源是否统一返回 404。
2. 使用 MockMvc 完成 login、refresh、logout、me 和 RBAC 接口测试。
3. 新增 AuthFlowIT，使用 PostgreSQL 16 Testcontainers 初始化 tenant-A 和 tenant-B。
4. 覆盖 TEST_PLAN.md“认证、授权与租户”中的全部场景。
5. 默认 `mvn clean verify` 不启动 Docker；`mvn -Pdocker-it verify` 执行完整集成测试。
6. 只有真实通过的 TEST_PLAN.md 条目才能勾选。
7. 更新 README.md 当前阶段、FILE_GUIDE.md 文件职责和必要架构说明。

最终输出：

- 按严重程度列出审查发现及修复。
- 列出所有新增/修改文件和作用。
- 列出执行过的测试命令、测试数量和结果。
- 给出 curl/HTTP 演示顺序，但不展示真实凭据或 Token。
- 明确仍未完成的范围，并确认没有开始知识库、RAG、Agent Runtime 功能。
```

## 11. 本阶段完成定义

全部提示词完成后，应满足：

- 可以通过租户 slug、用户名和密码登录。
- Access Token 可以访问受保护接口，非法 Token 返回 JSON 401。
- Refresh Token 单次轮换，重放会撤销整个 Token 家族。
- 用户身份包含租户、角色和权限。
- 普通查询和自定义 SQL 都有租户隔离测试。
- 管理权限能够阻止普通用户调用管理接口。
- Docker 集成测试覆盖两个租户和并发刷新。
- README、TEST_PLAN 和 FILE_GUIDE 与实现保持一致。

完成这一里程碑后，按 [KNOWLEDGE_DEVELOPMENT_PROMPTS.md](KNOWLEDGE_DEVELOPMENT_PROMPTS.md) 继续执行知识库 CRUD、MinIO 文件上传、异步解析、Embedding 和 Milvus 检索任务。
