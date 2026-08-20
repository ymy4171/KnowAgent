# KnowAgent 可执行测试计划

## 使用规则

- 每个功能合并前勾选对应单元测试和集成测试。
- 集成测试优先使用 Testcontainers，不依赖开发机已有数据。
- 多租户测试至少准备 tenant-A、tenant-B 两组数据。
- 并发测试必须重复执行并记录超时、重试和最终数据库状态。
- 当前已完成的骨架契约标记为 `[x]`，尚未实现的业务能力保持 `[ ]`。

## 1. 架构契约与基础类型

- [x] `AgentOrchestrator` 按 RUN_STARTED、增量、终态顺序输出事件。
- [x] 取消 Flux 订阅能够传播到上游执行流。
- [x] RunEvent 使用 UUID 业务 ID，PublishedRunEvent 单独使用字符串游标。
- [x] RunEvent metadata 不受外部 Map 后续修改影响。
- [x] 文本消息不能伪装成 TOOL 消息。
- [x] 多个 Tool Call 保持原始顺序，并通过 toolCallId 关联结果。
- [x] 对象存储 put/get/delete 命令都强制携带 TenantId。
- [x] 虚拟路径拒绝绝对路径和 `..` 逃逸。
- [x] Request、Run、Task 终态标志符合定义。
- [x] INTERRUPTED 只允许恢复 RUNNING，或转为 FAILED/CANCELLED。

执行命令：

```powershell
mvn -ntp clean verify
```

## 2. 认证、授权与租户

- [x] 正确密码登录返回 access token 与 refresh token，且 refresh token 只以 SHA-256 哈希落库，原始值仅在登录响应中出现一次。
- [x] 错误密码、禁用用户、锁定账户、过期 token 分别返回稳定错误码（INVALID_CREDENTIALS 401 / ACCOUNT_DISABLED 403 / ACCOUNT_LOCKED 403 / AUTHENTICATION_REQUIRED 401），未知租户、未知用户与错误密码统一不泄露差异，且未知租户/用户执行相同工作量（租户查询 + 用户查询 + dummy Argon2）防计时枚举。
- [x] 连续错误密码达到配置阈值触发临时锁定（失败计数在独立事务提交，不随登录方法抛异常回滚，且计数在数据库内原子递增——并发错误密码不丢失计数，无法靠并发绕过锁定阈值）；临时锁定窗口过期后账号可重试，成功登录清除失败计数与过期锁定。
- [x] 登录不持有外层事务：成功路径由独立事务服务提交「状态更新 + Refresh Token 插入」，失败记录为普通独立事务（不再嵌套 REQUIRES_NEW），单个登录最多持有一个连接——连接池 4、并发 4 的失败登录全部完成，无超时或 500（见 LoginConcurrencyIT）。
- [x] `GET /api/v1/users/me` 返回当前用户身份、租户、角色与权限，不包含密码哈希、锁定计数等内部字段。
- [x] 登录 DTO 校验失败（空 slug/缺密码/畸形 JSON）统一返回 JSON 400 VALIDATION_ERROR。
- [x] refresh token 只以 SHA-256 哈希落库且只能使用一次：轮换/重放/登出在同一事务内统一锁定家族根 token（`id = family_id` FOR UPDATE，单一串行化点）后重读校验状态/过期/用户/租户，再置为 CONSUMED 并插入同一 family 的子 token（root 满足 `family_id=id`、子 token `parent_token_id` 指向旧 token）；已 CONSUMED 的 token 重放视为重放攻击，在同一事务把家族内剩余 ACTIVE token 全部置为 REVOKED（稳定 `revoke_reason`）并返回稳定 401；子 token 插入在保存点（嵌套事务）内进行，`uq_refresh_tokens_one_child` 冲突只回滚插入并同样转换为重放结果而不暴露数据库异常，其他唯一约束错误原样抛出；两个并发 refresh 至多一个成功，子 token 刷新与根 token 重放/登出并发时家族收敛为无 ACTIVE；用户必须为 ACTIVE 且不能存在未来的 `login_locked_until`（即使 status 仍为 ACTIVE），禁用与永久/临时锁定一律拒绝；API 事务门面覆盖轮换与 Access Token 签名，签名失败时旧 token 恢复 ACTIVE 且不留下未返回的子 token；登出为事务方法，按 token 定位家族并撤销仍有效 token，重复登出幂等；原始 token 永不落库或出现在日志。
- [x] tenant-A 用户通过 tenant-B 的资源 ID 查询时返回 404，不能泄露资源存在性。
- [x] 普通用户不能调用管理员接口（鉴权通过方法级权限注解 + JSON 403，见 AccessTokenSecurityIT）。
- [x] `GET /api/v1/users` 与 `GET /api/v1/users/{userId}` 需 `USER_READ`：匿名 401、无 `USER_READ` 角色 403、`ADMIN` 200；过期 `user_roles` 绑定登录成功但无权限 → 403（见 UserQueryIT）。
- [x] 用户分页查询只返回本租户用户（插入 tenant-B 用户不出现）、`ORDER BY created_at DESC` 顺序稳定、分页带正确 total；keyword 对 login_name/display_name 不区分大小写模糊匹配、`status=DISABLED` 过滤有效（见 UserQueryIT）。
- [x] 用户查询非法参数统一 400 VALIDATION_ERROR：page=0、size=101、分页 OFFSET 溢出、status=BOGUS、`/users/not-a-uuid`；详情响应不含 passwordHash/loginFailedCount/loginLockedUntil 等内部字段（见 UserQueryIT）。
- [x] 用户分页/统计 SQL 显式携带 tenant_id 与 deleted_at IS NULL，不在认证前 `@InterceptorIgnore` 白名单（留在租户插件下作为 fail-closed 兜底），由 `SecurityMapperSqlContractTest` 正向断言锁定。
- [x] MyBatis-Plus 普通查询自动添加 tenant 条件。
- [x] 认证前自定义 SQL 显式携带 tenant 条件，且绕过租户插件的方法白名单精确锁定。
- [x] 锁查询、统计查询和批量更新 SQL 显式包含 tenant_id（即使租户插件能够改写普通自定义 SQL，也不能仅依赖自动改写）：refresh token 轮换的消费（CAS）、家族撤销（批量更新）与登出 SQL 均显式携带 `tenant_id`，绕过 tenant-line 的方法白名单由 `SecurityMapperSqlContractTest` 精确锁定（15 个方法）。
- [ ] API Key、模型密钥和外部凭据不以明文存储或输出到日志。
- [x] 开发者管理员初始化幂等创建租户、`ADMIN` 系统角色、管理员用户与 `user_roles` 绑定；密码只以 Argon2id 哈希落库，日志与异常不含明文；任一步失败整体回滚。
- [x] 初始化查询与用户角色绑定 UPSERT 显式携带 tenant_id，绕过 tenant-line 的方法白名单保持精确锁定；过期绑定原地恢复且不违反唯一约束。
- [x] Access Token 由 Spring Security 官方 JOSE 组件签发与解析（不手写 JWT 编解码器）：密钥只从环境变量读取；校验签名、issuer、audience、过期时间与必需声明（sub/tenant_id/roles/permissions/jti）；合法 token 转换为 TenantPrincipal 并建立/清理 TenantContext；缺失、篡改、过期、错误 issuer/audience、缺 tenant_id、roles 缺失的 token 一律返回稳定 JSON 401；角色/权限正确映射为 GrantedAuthority；响应、日志与异常不出现 token 明文。密钥、issuer、audience、有效期通过类型安全的 `@ConfigurationProperties` 注入。

验收：使用 Testcontainers PostgreSQL 初始化两个租户，完成跨租户 ID 枚举和权限矩阵测试。管理员初始化在 PostgreSQL 16 容器中验证首次执行、幂等、哈希、事务回滚，以及过期 `user_roles` 绑定原地恢复并重新获得 ADMIN 权限（`mvn -Pdocker-it verify`）。Access Token 基础设施在 PostgreSQL 16 容器中通过真实 HTTP 安全链验证：有效 token 访问受保护端点、角色/权限映射、TenantContext 建立与清理，以及匿名访问、篡改、过期、错误 issuer/audience、缺 tenant_id、roles 缺失、畸形 tenant_id 共 7 类无效请求稳定返回 JSON 401 且不泄露 token（`mvn -Pdocker-it verify`，见 `AccessTokenSecurityIT`）。登录与当前用户接口在 PostgreSQL 16 容器中通过 MockMvc + 完整安全链验证（`mvn -Pdocker-it verify`，见 `AuthFlowIT`）：正确密码登录返回 token 并用其访问 `/api/v1/users/me`、错误密码/禁用/锁定返回稳定错误码、连续失败触发锁定且成功登录清除计数、16 个并发错误密码全部累计并触发锁定（计数 = 401 数，原子递增不丢计数）、真实失败锁定的账号在锁定窗口过期后恢复登录、tenant-A 登录无法加载 tenant-B 用户与角色、DTO 校验失败返回统一 JSON 400。连接池耗尽风险在独立容器中以 Hikari 池=4、并发错误密码=4 验证全部完成且无超时/500（`mvn -Pdocker-it verify`，见 `LoginConcurrencyIT`），证明单个登录最多持有一个连接。Refresh Token 轮换与登出在 PostgreSQL 16 容器中通过 MockMvc + 完整安全链验证（`mvn -Pdocker-it verify`，见 `RefreshRotationIT`）：轮换单次使用并插入同家族子 token、旧 token 重放撤销新 token、8 个并发 refresh 至多一个成功、过期/撤销/随机 token 均拒绝、登出撤销家族且重复登出幂等、子插入事务失败或 Access Token 签名失败时消费与插入一起回滚、原始 token 永不落库（只存 SHA-256 哈希）；家族根锁（`id = family_id` FOR UPDATE）同时被 refresh/重放/登出持有，SQL 预置真实子 token 后触发的 `uq_refresh_tokens_one_child` 冲突经保存点恢复并撤销家族（稳定 401 而非 500）、禁用/永久锁/LOCKED 临时锁/ACTIVE + 未来锁定时间均拒绝轮换、子 token 刷新与根 token 重放/登出各 8 线程并发后家族收敛为无 ACTIVE。租户内用户管理查询在 PostgreSQL 16 容器中通过 MockMvc + 完整安全链验证（`mvn -Pdocker-it verify`，见 `UserQueryIT`）：真实「角色→权限→authority→@PreAuthorize」链路下匿名 401、无 `USER_READ` 角色 403、过期绑定 403、ADMIN 只列出本租户 7 个用户且 tenant-B 用户不出现、分页顺序与 total 稳定、keyword/status 过滤、非法参数 400、跨租户 userId 404、详情不含内部字段。

### 模型供应商配置（RBAC 与租户隔离）

- [x] 创建供应商：provider_key 规范化小写并校验正则；租户内活动 key 唯一（预检查与数据库竞争均映射 409）；URL、JSON 对象、Header 和模型目录执行边界校验；API Key 与自定义 Header 用 AES-256-GCM 加密，密文不含明文；配置对象输出不泄露主密钥。
- [x] 列表/详情只返回 hasSecret/capabilities/enabledModels/publicConfig，不返回 ciphertext/解密值/secretKeyVersion/内部 Header。
- [x] 更新未提交 secret/header 保留旧值；clearSecret/clearHeaders 独立布尔命令显式清除；同时提交值与 clear 标记返回 400，空字符串不作清除哨兵。
- [x] 删除在同一事务内先以 `FOR UPDATE` 锁定活动供应商，再检查由 knowledge 模块拥有的活动知识库引用，最后执行带乐观锁守卫的软删除；被引用返回 409。
- [x] 自定义分页/统计/更新/软删 SQL 显式 tenant_id（见 ModelProviderMapperSqlContractTest）；tenant-A 无法读取/更新/删除 tenant-B provider；软删后 key 可复用；无权限 403、有权限管理员 CRUD 成功。
- [x] 既有 PostgreSQL Testcontainers 基线验证 JSONB、部分唯一索引、租户隔离和活动知识库引用（原 `ModelProviderIT` 9 用例）。
- [x] PostgreSQL 复验新增 `ModelProviderIT` 用例：非法配置 400、跨租户供应商复合外键拒绝、`FOR KEY SHARE` 引用写入与供应商删除并发时删除等待并最终返回 409。

当前验收：`mvn verify` 构建成功，244 个单元测试通过；模型模块 39 个测试覆盖加密、配置脱敏、服务校验、secret/header 三态更新、唯一键竞争、JSONB、行锁与 FOR KEY SHARE SQL、knowledge 引用查询契约。`mvn -Pdocker-it verify` 的 104 个集成测试全部通过，其中 `ModelProviderIT` 12/12 覆盖上述 PostgreSQL 加固场景。

### OpenAI-compatible Embedding 网关（提示词 15 已落地）

- [x] WireMock 契约覆盖正常多批：多批向量按批次顺序拼接，批次与输入顺序一致（`OpenAiCompatibleEmbeddingGatewayTest` 22 例）。
- [x] 顺序保持：响应 index 与输入位置不一致 → MODEL_BAD_RESPONSE。
- [x] 429 后重试成功（至多 maxAttempts 次）；401/403 配置错误与 SSL 握手等永久传输错误不重试（单次请求即失败），仅明确网络暂态错误可重试。
- [x] 读取超时 → MODEL_TIMEOUT（含被 body 转换失败 `HttpMessageNotReadableException` 包装的 `SocketTimeoutException`，沿 cause 链识别）；单次慢成功与多批累计耗时都受所有批次共享的 totalTimeout deadline 硬约束。
- [x] 响应数量错误、空向量、维度与 expectedDimensions 不一致、跨批维度不一致、NaN/Infinity 非有限值 → MODEL_BAD_RESPONSE。
- [x] BatchPlanner 同时遵守最大文本条数、估算 token 总量与请求体大小上限；JSON 控制字符转义和模型名可变开销计入请求体上界；空/空白/单个超限输入直接拒绝（`BatchPlannerTest` 9 例 + `CharRunTokenEstimatorTest` 8 例）。
- [x] provider 更新 configVersion 后客户端缓存失效（两个 WireMock 服务器验证旧客户端不再被复用）；缓存键至少含 tenantId+providerId+configVersion（`EmbeddingModelClientCacheTest` 4 例）。
- [x] tenant-A 不能使用 tenant-B provider：跨租户 providerId → RESOURCE_NOT_FOUND 且不发请求；禁用/非 EMBEDDING 能力/模型不在目录 → MODEL_CONFIGURATION_ERROR 且不发请求。
- [x] 日志与异常不含 API Key、自定义 Header 与 chunk 原文：加密 secret/header 只在客户端构建边界解密进入请求头，异常消息为固定文案（`exceptionsNeverLeakSecretsHeadersOrChunkText` 同时断言发送的请求头确实携带解密值）。
- [x] Spring 上下文只装配一个明确选择的 EmbeddingGateway Bean，无 MeterRegistry 也能启动（`EmbeddingGatewayContextTest`）。
- [x] 指标只记录 providerId/model/outcome/耗时/批次数/估算 token 等非敏感字段，不含 chunk 原文或向量数组。

当前验收：`mvn -ntp -am -pl knowagent-model test` 构建成功，模型模块 83 个单元测试通过（较原有 39 新增 44：BatchPlanner 9 + CharRunTokenEstimator 8 + 客户端缓存 4 + WireMock 网关 22 + 上下文装配 1）；全仓 `mvn -ntp clean verify` 共 525 个单元测试通过。WireMock 3.9.1 内嵌服务器基于 Jetty 11，`knowagent-model/pom.xml` 的 dependencyManagement 把 Jetty BOM 钉在 11.0.20，避免 Spring Boot BOM 提升到 Jetty 12 导致混代启动失败。

## 3. 数据库、事务与 Outbox

### 任务、Outbox 与 Inbox 持久化基础（提示词 11 已落地）

- [x] 业务记录 + Task + Outbox 在同一事务提交或全部回滚：事务内插入 `knowledge_bases` 并 `TaskSubmissionService.submit`，随后抛异常，三者全部消失（见 `TaskOutboxInboxIT.businessRecordTaskAndOutboxEventRollBackTogether`）；离开事务独立提交时 Task 与 Outbox 各自持久化且均为 PENDING。
- [x] 同一事务内 `submit` 先校验再写入：非法 taskType/maxAttempts/JSONB payload/eventMaxRetries 抛 VALIDATION_ERROR 且零写入（`TaskSubmissionServiceTest`），UUID 全部在 Java 预生成且每次提交唯一。
- [x] 两个并发 Outbox 发布者绝不 claim 到同一事件：6 条跨租户种子事件（3 alpha + 3 beta）被两个线程经 `FOR UPDATE SKIP LOCKED` 抢占，两集合不相交且并集等于全部种子（见 `twoConcurrentPublishersNeverClaimTheSameEvent`）。
- [x] 未过期租约不可抢占、过期租约可被回收：worker-a 抢占后 worker-b 在租约内 claim 为空，手工置 `locked_until` 过期后 worker-c 可重新认领并接管锁（见 `anUnexpiredLeaseIsNotPreemptableButAnExpiredOneIsReclaimed`）。
- [x] 失败按指数退避递增 retry_count 并在预算耗尽置 DEAD_LETTER：连续 3 次失败分别回到 PENDING（retry 1/2、`next_retry_at` 推迟）最后 DEAD_LETTER（retry 3），死信不再可 claim（见 `failuresAdvanceRetriesThenDeadLetterWithBackoff`）；`RetryPolicy` 1/2/4/8s 倍增并封顶。
- [x] 发布/失败以 status+version 守卫：同一 stale 事件视图的第二次 `markPublished` 与 `markFailed` 均为 0，数据库保持首次 PUBLISHED（见 `statusAndVersionGuardsPreventCompletingAStaleEvent`）；丢失竞争在应用层转 409 CONFLICT（`OutboxPublisherServiceTest`）。
- [x] 重复 Inbox 事件只执行一次并报告「已处理」：`recordProcessed` 首次 true、重复 false（`ON CONFLICT DO NOTHING` 走 `uq_inbox_events_consumer_event`），`wasProcessed` 按 tenant+consumer+event 判定，不同 consumer/不同事件不受影响（见 `duplicateInboxEventIsProcessedOnceAndReportedAsAlreadyProcessed`）。
- [x] tenant-A 的 Task/Outbox 对 tenant-B 不可见：跨租户 `findById` 与 Task `claim` 均为空（见 `tenantAStoresAreInvisibleToTenantB`）。
- [x] `GET /api/v1/tasks/{id}` 需 `TASK_READ`：匿名 401 `AUTHENTICATION_REQUIRED`、无权限查看者 403 `ACCESS_DENIED`、ADMIN 200 且响应不含 payload/result/tenantId/lockedBy/lockedUntil/version、跨租户与不存在任务统一 404 `RESOURCE_NOT_FOUND`（见 TaskOutboxInboxIT 4 条 HTTP 用例）。
- [x] Task 状态机：PENDING→RUNNING/FAILED/CANCELLED、RUNNING→SUCCEEDED/FAILED/PENDING/CANCELLED；可重试失败走 RUNNING→PENDING（排入退避）、最终失败走 RUNNING→FAILED，FAILED/SUCCEEDED/CANCELLED 终态不可逆、null 目标拒绝（`TaskStatusTest`）；Outbox 状态终态标志与 V9 CHECK 一致。
- [x] observability 租户隔离 SQL 契约由 `ObservabilityMapperSqlContractTest` 锁定：11 个绕过 tenant-line 方法白名单 + 唯一全局例外 `OutboxEventMapper.selectClaimable`，每条绕过 SQL 显式 tenant_id（INSERT 写 tenant_id 列），claim 的 SKIP LOCKED/排序、`attempt_count < max_attempts`、各 status+version+tenant 守卫、Inbox 唯一约束幂等均有断言；新增绕过方法即构建失败。
- [x] 密钥脱敏回归：Task 与 Outbox 错误统一经 `ErrorMessageSanitizer` 同一净化边界落库（`TaskTransition`/`OutboxEvent` 构造即净化，原始消息不可能到达持久化层）；api_key / Authorization Bearer / client_secret / password / JWT / 裸 `sk-` 等凭证形式稳定替换为 `<redacted>`，测试断言密钥「不存在」而非被保留（`ErrorMessageSanitizerTest` 脱敏用例、`TaskTransitionTest`、`OutboxPublisherServiceTest.failSanitizesTheErrorBeforeStoring`/`failRedactsBearerTokensBeforeStoring`）；`Task`/`OutboxEvent`/`SubmitTaskCommand` 覆盖 `toString()` 不输出 JSON payload/result/headers（`TaskTest.toStringNeverExposesPayloadOrResult`）。
- [x] Task 重试耗尽回归：claim SQL 增加 `attempt_count < max_attempts`（避免触发 V9 `ck_tasks_attempts` CHECK）、领域 `claimed()` 拒绝耗尽任务、`MyBatisTaskStore.transition` 拒绝耗尽任务再入 PENDING（最后一次失败必须 FAILED 而非 PENDING）；PostgreSQL 集成测试覆盖「耗尽任务不可再认领、可正常转 FAILED、不抛数据库异常」（见 `TaskOutboxInboxIT.exhaustedTaskCannotBeReclaimedOrRetriedButResolvesToFailed`、`TaskTest.claimingAnExhaustedTaskIsRejected`、`ObservabilityMapperSqlContractTest.taskClaimIsTenantScopedWithStatusAndLeaseGuard`）。

当前验收：`mvn verify` 构建成功，observability 模块 93 个单元测试全部通过（较 83 新增 10：密钥脱敏、TaskTransition 净化、Task 耗尽/toString、Bearer 脱敏），默认构建不启动 Docker。`mvn -Pdocker-it verify` 的集成测试全部通过，其中 `TaskOutboxInboxIT` 13/13 覆盖上述同事务回滚、并发抢占、租约、重试/退避/死信、乐观锁守卫、Inbox 幂等、跨租户隔离、重试耗尽与 HTTP 401/403/200/404 语义。

### Agent 运行时链路（后续提示词）

- [ ] Message、Request、Run、Outbox 在同一事务提交或全部回滚。
- [ ] Outbox Publisher 在发布成功前崩溃，重启后能够重新发布。
- [ ] Redis 发布成功但数据库标记前崩溃，重复发布由消费者幂等处理。
- [ ] 相同 requestId 重复提交只产生一个 Request 和一个 Run。
- [ ] 同一会话线程并发提交 20 个请求，Worker 按 FIFO 执行。
- [ ] 不同会话线程可以并行执行。
- [ ] 两个 Worker 竞争同一任务时只有一个获得执行权。
- [ ] 终态 Run 不允许恢复到 RUNNING。
- [ ] INTERRUPTED 超时任务按策略转为 FAILED。

验收：校验数据库最终行数、状态版本、Outbox backlog 和 Redis pending 列表。

## 4. 文件与知识库入库

### 知识库 CRUD（租户隔离与状态机）

- [x] 创建知识库：slug 小写规范化且符合 `^[a-z0-9][a-z0-9_-]{0,98}$`；租户内活动 slug 唯一（预检查 + 部分唯一索引竞争均映射 409）；默认 status=ACTIVE、knowledgeType=LOCAL、默认 ChunkPolicy/RetrievalConfig；响应不含 version/tenantId/createdBy/updatedBy/deletedAt（见 KnowledgeBaseIT `createAppliesDefaults`）。
- [x] 非法 slug、空/超长 name、超长 description、非对象 metadata、非法分块策略（chunkSize<=0、overlap<0、overlap>=chunkSize）、非法检索配置（topK=0/101、scoreThreshold<0/>1）统一 400 VALIDATION_ERROR（单元 + `KnowledgeBaseIT` `invalidSlugAndInvalidConfigurations`）。
- [x] 创建/更新时校验 embedding/rerank 供应商：provider 与 model 必须成对（半配置 400）；供应商必须属于当前租户且未软删（跨租户绑定 404，不泄露存在性）；必须 `enabled` 且声明对应能力（禁用、chat 充当 embedding → 400）。
- [x] 更新语义 null=保留；status 只允许 ACTIVE↔DISABLED 正向切换（无操作转换、置 DELETED/DELETING、非法转换 → 400）；重命名/更换 slug 保持租户内唯一。
- [x] 乐观锁：并发更新/删除冲突返回 409 且不覆盖更新的数据（更新与软删均以 `version = #{version}` 守卫，受影响行数 0 → 409）。
- [x] 并发修改 slug：两个并发 PATCH 竞争同一目标 slug，恰好一个 200、另一个稳定 409 CONFLICT（非 500）——部分唯一索引竞争经 DuplicateKeyException 转换，与创建逻辑一致（见 `concurrentSlugChangeResolvesToOneWinnerAndOneConflict`）。
- [x] 供应商 TOCTOU：创建/更新在同一事务内先以 `FOR KEY SHARE` 锁定活动供应商；与供应商删除（`FOR UPDATE`）并发时，创建方的锁读阻塞，删除提交后以 `deleted_at IS NULL` 重新判定 → 404 且不落库；创建先提交时删除方引用检查看到新引用 → 409（见 `createBlocksOnTheProviderLockAndSurfacesAConcurrentDeleteAs404`，以及 `ModelProviderIT.deleteWaitsForProviderUsageLockAndThenSeesTheNewReference`）。
- [x] 模型目录校验：`enabled_models` 非空时必须存在「模型名 + 能力」完全匹配记录——模型不在目录、登记为其他能力均 400；空目录表示未限定模型列表、允许任意模型名（单元测试 + KnowledgeBaseIT `invalidProviderBindingsReturn400Or404`）。
- [x] 列表：page/size 分页（size∈[1,100]，page=0/size=101/OFFSET 溢出 → 400）、`name`/`slug` 不区分大小写模糊过滤（`\`/`%`/`_` 转义）、`status` 精确过滤；数据与统计 SQL 使用同一显式 tenant_id 条件，`ORDER BY created_at DESC` 稳定（见 `listSupportsPagingAndFilters`）。
- [x] 租户隔离：tenant-A 无法枚举/读取/更新/删除 tenant-B 知识库（跨租户 id → 404，beta 列表为空）；`KnowledgeBaseMapperSqlContractTest` 锁定每条自定义 SQL 显式 tenant_id + `deleted_at IS NULL` 且无 `@InterceptorIgnore`。
- [x] 删除：空库（无未删除文件）软删到 DELETED 且 slug 可复用；存在未删除文件返回 409（`KnowledgeFileReferenceMapper.countActiveFiles` 按 tenant+knowledge_base 作用域）；删除先 `FOR UPDATE` 锁定行再检查文件并版本守卫软删。
- [x] 权限链：匿名 401、无 `KNOWLEDGE_BASE_READ/WRITE` 角色 403、ADMIN 全部成功（见 KnowledgeBaseIT `anonymous...`/`viewer...`）。
- [x] `ChunkPolicy`/`RetrievalConfig` JSONB 往返；非法存储值读取时转 SQLException（`KnowledgeBaseTypeHandlerTest`）。
- [x] 领域状态转换矩阵集中定义，`toString()` 不含配置/审计细节（`KnowledgeBaseTest`）。

当前验收：`mvn verify` 构建成功，knowledge 模块 90 个单元测试通过（领域 9 + 应用服务 67（知识库 CRUD + 文件上传/幂等/补偿全路径）+ TypeHandler 5 + SQL 契约 9（知识库 5 + 文件 3 + 引用 1），合计覆盖上述规则）。`mvn -Pdocker-it verify` 全量集成通过，其中 `KnowledgeBaseIT` 18/18 覆盖创建/详情/更新/禁用/删除、非法配置 400、slug 重复与乐观锁冲突 409、并发 slug 竞争一胜一 409、并发供应商删除时创建阻塞并 404 不落库、enabled_models 模型目录校验、跨租户 404、列表分页/过滤、权限链与文件删除守卫。

### 文件上传与对象存储（提示词 12 已落地）

- [x] tenant-A 上传的对象物理键固定带 tenant-A 前缀（`tenants/{tenantA}/knowledge-bases/{kb}/files/{fileId}/source`，服务端 `StorageKeys` 生成）。
- [x] tenant-B 无法 stat/get/delete tenant-A 对象：适配器在发起 MinIO 调用前按 `tenants/{tenantId}/` 前缀拒绝，跨租户与任意非前缀键均 INVALID_OPERATION（见 `MinioStorageIT`）。
- [x] 上传成功但数据库事务失败时执行对象补偿清理：MinIO 已写对象被立即删除，补偿失败记录 `[ALARM]` 稳定错误、绝不误报成功；数据库触发器中制造失败后断言 0 行 0 孤儿对象（见 `KnowledgeFileUploadIT`）。
- [x] 类型由内容嗅探（Apache Tika + 可信内容回退）：TXT/PDF/DOCX/TEXT_MARKDOWN 可上传；文件名/Content-Type 头不作类型事实。
- [x] 不支持的 MIME、空文件、超限文件返回明确错误码：空/伪造 MIME（PNG 字节声明 text/plain）/未知类型 → 400 VALIDATION_ERROR；超 50MB → 400（servlet 层 413 由 ApiExceptionHandler 覆盖）。
- [x] 上传只入队不解析：成功返回 202，文件置 `QUEUED` + PENDING Task + Outbox 在同一事务写入，HTTP 线程不解析文档、不调用 Embedding/Milvus。
- [x] Idempotency-Key 幂等：同 key 同内容重放返回原 fileId/taskId（replayed=true）且不产生第二个文件/Task/Outbox；同 key 不同内容 → 409 CONFLICT。
- [x] 文件读写权限集中定义并授予 ADMIN（`KNOWLEDGE_FILE_READ/WRITE`）：匿名 401、无权限角色 403、管理员 202/200。
- [x] list/detail/content 严格租户作用域：list 先校验当前租户的活动知识库，跨租户知识库、跨租户与跨知识库 fileId 统一 404（不泄露存在性）。
- [x] 响应与下载永不泄露存储内部：UploadFileResponse/KnowledgeFileResponse 不含 object_key/bucket/processing_params/错误栈/MinIO 凭据；content 为认证 + 流式下载（attachment 头、字节一致）。
- [x] 文件落库事务以 `FOR KEY SHARE` 锁定活动知识库并与删除侧 `FOR UPDATE` 配对：并发删除先持锁时上传等待，删除提交后上传返回 404、不写 file/task/outbox，并补偿删除已上传 MinIO 对象。

当前验收：knowledge 模块 92 个单元测试通过（其中 `KnowledgeFileServiceTest` 覆盖成功上传、缺失/禁用知识库、空文件、伪造/未知类型、超限、幂等重放、不同内容 409、重复键竞争、DB 失败补偿删除、存储失败映射、分页校验、跨租户知识库 list 404 与 content 流式；`KnowledgeBaseMapperSqlContractTest` 锁定文件写入使用的 `FOR KEY SHARE` SQL；`KnowledgeFileMapperSqlContractTest` 锁定自定义 SQL 显式 tenant_id、幂等历史不过滤 deleted_at），workspace 模块 9 个单元测试通过（含 MinIO 配置对象凭据脱敏）。`mvn -Pdocker-it verify` 全量集成通过（136 个），其中 `MinioStorageIT` 5/5 在真实 MinIO 容器锁定 put/stat/get/delete 往返、SHA-256 元数据、租户前缀隔离与删除缺失对象幂等；`KnowledgeFileUploadIT` 14/14 在真实 PostgreSQL + MinIO + 完整安全链覆盖端到端上传/补偿/幂等/权限/泄露控制及知识库并发删除锁协议。

### 本地文档解析（提示词 13 已落地）

- [x] `ParserRegistry` 按内容嗅探出的规范 MIME 选择唯一解析器；两个解析器声明同一 MIME 在构造期即失败（歧义不可部署），映射不可变 → 并发安全（`ParserRegistryTest` 6 例含 32 线程确定性及未知 MIME 关闭源流）。
- [x] TXT/Markdown、分页 PDF、带 Heading 的 DOCX 分别解析正确：页码（PDF 每页一节、1-based pageNumber）、标题路径（Markdown `#` 与 DOCX Heading id/本地化样式显示名 → `1`/`1.1` 大纲编号）、文本顺序与字符范围（`text.substring(startOffset,endOffset)` 恒等于 section.content，sections 精确覆盖全 text）正确（`TxtMarkdownParserTest` 10 + `PdfParserTest` 9 + `DocxParserTest` 11）。
- [x] 限制与稳定错误码：空文件/空文本 → `EMPTY_DOCUMENT`；损坏 PDF/DOCX → `CORRUPT_DOCUMENT`；超限页数、单入口或累计超限解压大小（DOCX zip-bomb 防线）、超限文本字符 → `DOCUMENT_TOO_LARGE`；未知 MIME → `UNSUPPORTED_DOCUMENT_TYPE`；扫描 PDF（可加载无文本）→ `OCR_REQUIRED`（不伪造文本，供后续外部 OCR）。
- [x] 解析器只接受服务端受控流（`ParseSource`），不自行从 URL 下载；源流在成功、解析异常与未知 MIME 路径都关闭、临时 spool 文件全路径删除（`CloseTrackingInputStream` 断言）；DOCX 的 `OPCPackage` 在 `XWPFDocument` 构造失败时也由外层资源关闭。
- [x] 错误消息为固定文案，不含原文、对象键、文件系统路径或第三方堆栈；`ParseSource`/`ParsedSection`/`ParsedDocument` 的 `toString()` 不输出对象键、文件名、标题、正文、章节标题或 metadata（`ParsedDocumentContractTest` 3 例）。

当前验收：knowledge 模块 131 个单元测试通过（其中解析相关 39 例，另 `SectionBuilder`/`SourceSpool`/`ParseBudget`/`ParseProperties` 由各解析器测试覆盖）；`mvn -ntp clean verify` 全仓 421 个单元测试通过，`mvn -ntp -Pdocker-it verify` 既有 136 个容器集成测试全部通过，`docker compose config --quiet` 通过。解析阶段不写 chunk、不调模型、不启动 Redis consumer——分块/Embedding/Worker 驱动仍属后续提示词。

### 确定性文本分块与持久化（提示词 14 已落地）

- [x] 三种策略 RECURSIVE / MARKDOWN_HEADING / TOKEN_WINDOW 全部落地：`maxTokens`/`overlapTokens` 以 token 为单位执行、`overlap < chunkSize` 恒成立（`ChunkPolicy` 不变式 + `overlapStart` 钳制），不产生空 chunk、不无限循环；MARKDOWN_HEADING 在每个 section 内独立 token 化并累计文档级 token offset，章节边界即使落在英文 run 中间也不会少算；TOKEN_WINDOW 把窗口间分隔符归入前一块并保留文档首尾空白，零重叠时 chunk 可无损拼回全文（`DeterministicChunkerTest` 20 例）。
- [x] Token 估算未接入供应商 tokenizer 前使用确定性实现并在 metadata 标记算法版本：`DeterministicTokenCounter`「char-run-v1」（CJK 表意/全角=1、其余非空白 run 每 4 码点=1、空白=0），chunk metadata 携带 `token_estimator=char-run-v1` + `chunk_strategy`，不把字符数冒充精确 token 数；`TokenStream.fromTokens` 为后续供应商 tokenizer 提供校验过的 positioned-token 工厂（`DeterministicTokenCounterTest` 9 例）。
- [x] 尽量在段落/句子/换行边界切分；超长无分隔文本安全退化（`safeSplit` 精确 maxTokens token 窗口，含 `tokenEndChar(target-1)` off-by-one 修正）；token 边界恒落在码点之间，CJK 与 Emoji（含 👨👩👧👦 代理对）永不切开（CJK/Emoji/5000 字符超长文本用例）。
- [x] 每个 ChunkDraft 生成稳定 chunkIndex、SHA-256 contentHash、tokenCount、字符/Token offset、pageNumber、sectionPath（`"1.1"`→`["1","1.1"]`）与 metadata；构造器验证 hash 与 content、tokenCount 与 token offset 差值确实一致；MARKDOWN_HEADING 以 ParsedSection 为硬边界（chunk 不跨节），页码与标题路径从覆盖 chunk 起点的 ParsedSection 透传到所有相关 chunk。
- [x] `KnowledgeChunk` 领域模型 + `KnowledgeChunkPo` + `KnowledgeChunkMapper` + `KnowledgeChunkRepository` 端口 + Converter 建立；UUID 由 tenant/kb/file/chunkIndex/contentHash 在 Java 中确定性生成（`@TableId(IdType.INPUT)`），相同重试保留未来 Milvus entity id、内容变化则生成新 id；section_path/metadata 用结构化 JSONB TypeHandler（`StringListJsonbTypeHandler`/`StringMapJsonbTypeHandler`，损坏 JSON → SQLException，`KnowledgeChunkTypeHandlerTest` 5 例）。
- [x] 同一文件分块写入单事务：`FOR UPDATE` 锁定文件行（不存在/跨租户 404）→ 整集合替换（重试幂等，`UNIQUE(tenant_id, file_id, chunk_index)` 兜底防重复索引）→ 文件统计用 `version` 守卫条件更新 chunk_count/token_count/version（版本不符 409）；任一步失败整体回滚，旧数据/新数据不半替换。
- [x] chunk 初始 `index_status=PENDING`；knowledge_files 的 chunk_count/token_count/version 用条件更新推进；本提示词不推文件状态、不调 Embedding/Milvus（不生成向量）。
- [x] 所有 chunk 查询/替换/删除 SQL 显式携带 tenant_id + knowledge_base_id + file_id，**不加** `@InterceptorIgnore`（留租户插件作 fail-closed 兜底，而非依赖裸 file UUID）——`KnowledgeChunkMapperSqlContractTest` 2 例锁定。
- [x] 幂等、回滚与租户隔离在真实 PostgreSQL 上验证：`KnowledgeChunkIT` 5/5——替换统计与实际行数一致、同替换重试不重复 chunkIndex、重复 chunkIndex 回滚后旧集合完整、tenant-B 不能查询/替换/删除 tenant-A chunk（404 + 空查询 + tenant-A 数据原样）。

当前验收：knowledge 模块 191 个单元测试通过（其中分块与持久化相关 61 例：`DeterministicTokenCounterTest` 9 + `ChunkDraftTest` 10 + `DeterministicChunkerTest` 20 + `KnowledgeChunkTest` 8 + `KnowledgeChunkTypeHandlerTest` 5 + `KnowledgeChunkMapperSqlContractTest` 2 + `ChunkWriteServiceTest` 4 + `KnowledgeFileMapperSqlContractTest` 补 3）；`mvn -ntp clean verify` 全仓 481 个单元测试通过，`mvn -ntp -Pdocker-it verify` 全部 141 个容器集成测试通过（含 `KnowledgeChunkIT` 5/5，其幂等用例同时断言相同重试保留 chunk UUID）。

### Milvus 向量存储适配器（提示词 16 已落地）

- [x] 集合契约：主键 `id` 为 VARCHAR（autoID=false）保存 PostgreSQL chunk UUID 字符串；`embedding` 为 FLOAT_VECTOR 且维度来自已验证配置 `knowagent.vector.milvus.dimension`（[1,65536]，非法值启动即失败）；标量字段至少含 tenant_id/knowledge_base_id/file_id/chunk_id/embedding_model_spec；相似度固定 COSINE（不可配置）；索引类型与参数（HNSW 默认 + m/ef-construction/search-ef，可 FLAT/AUTOINDEX）由配置固定（`MilvusCollectionSchema`/`MilvusIndexParams`/`MilvusVectorPropertiesTest` 锁定）。
- [x] 启动幂等初始化：collection 不存在时按固定 schema 创建 + 建 COSINE 索引 + load；已存在时 describeCollection/describeIndex 校验 schema/维度/主键/autoID/metric，任一不匹配抛 `VECTOR_SCHEMA_MISMATCH` 拒绝启动，**绝不 drop 既有集合**；未配置 `MILVUS_ENDPOINT` 时装配 fail-fast `UnavailableVectorStoreGateway`（任何操作稳定 `VECTOR_UNAVAILABLE`），无 Docker 环境照常启动（`MilvusCollectionInitializerTest` 3 例 + `MilvusSchemaValidatorTest` 8 例 + 真实容器 `initializesIdempotentlyAndKeepsServingData`/`anIncompatibleDimensionRefusesStartupWithoutDroppingExistingData`）。
- [x] upsert 前验证：批次非空、tenant/kb/file/chunk 关系一致、向量维度等于配置维度、数值有限（NaN/Infinity 拒绝）、批内 chunkId 不重复；Milvus entity id 恒等于 chunkId（`MilvusVectorEntityMapperTest` 5 例；真实容器 `postgresChunkUuidEqualsMilvusPrimaryKey` 逐项断言 Milvus 主键 == PostgreSQL chunk UUID 字符串）。
- [x] 检索 filter 受控构造：恒含 tenant_id + knowledge_base_id，可选 file_id in 列表逐个按 UUID 校验并转义（`\`/`'`），不拼接任意用户表达式（`MilvusFilterBuilderTest` 7 例含恶意表达式不可逃逸）；结果只返回 id/file_id/score，content 恒为 null 由 PostgreSQL 按 tenant + chunk ids 回查（`MilvusSearchResultMapperTest` 4 例）。
- [x] COSINE 搜索 + 租户隔离：真实 Milvus 容器验证 tenant-A 查询只返回 tenant-A 数据；tenant-A 使用 tenant-B 的 fileId/kbId 过滤（即使向量完全相同）也得不到任何结果；deleteByFile 恒含 tenant/kb/file 三元组，删除 tenant-B 文件不影响 tenant-A 数据（`MilvusVectorStoreIT.searchFiltersByTenantKnowledgeBaseAndFile`/`tenantAQueryWithTenantBChunkOrFileIdsGetsNoResults`）。
- [x] 幂等：重复 upsert 同一 chunkId 不产生重复实体（upsert 替换语义）；重复 deleteByFile 与删除不存在文件均幂等成功（`MilvusVectorStoreIT.repeatedUpsertDoesNotDuplicateEntitiesAndRepeatedDeleteSucceeds`）。
- [x] 超时与错误映射：连接/搜索/写入/删除/初始化超时独立配置，SDK 调用经 `MilvusCallExecutor` 限时执行；错误映射稳定 `VECTOR_UNAVAILABLE`（网络/服务端/超时/中断）、`VECTOR_SCHEMA_MISMATCH`（collection 缺失或 schema 不符）、`VECTOR_BAD_RESPONSE`（缺 id/score、非法 UUID、upsertCnt 不符），消息恒为固定文案不含 SDK 敏感正文（`MilvusErrorMapperTest` 6 例 + `MilvusCallExecutorTest` 3 例 + `MilvusVectorStoreAdapter` 计数守卫）。
- [x] 指标只记录 collection/operation/outcome/数量/耗时，不记录向量内容或 chunk 文本；无 MeterRegistry 时 no-op（`VectorMetricsTest` 3 例）。
- [x] 错误码接入统一映射：`ErrorCode` 新增 VECTOR_UNAVAILABLE/VECTOR_SCHEMA_MISMATCH/VECTOR_BAD_RESPONSE，`ApiExceptionHandler` 映射 503/500/502（编译期强制覆盖）。

当前验收：knowledge 模块单元测试新增 49 例全部通过（filter 转义 7 + 配置契约 10 + 实体映射 5 + 检索响应 4 + 错误转换 6 + 调用超时 3 + 指标 3 + schema 校验 8 + 启动装配 3）；全仓 `mvn -ntp clean verify` 共 574 个单元测试通过（默认构建不启动 Docker）；`mvn -ntp -Pdocker-it verify` 全部 147 个容器集成测试通过，其中 `MilvusVectorStoreIT` 6/6 在真实 Milvus 2.5.6 容器验证（建集合/load/upsert/COSINE 搜索/tenant+kb+file 过滤/跨租户 chunkId 与 fileId 无结果/幂等 upsert 与 delete/PostgreSQL chunk UUID == Milvus 主键/维度不匹配拒绝启动且不删数据）。

### PostgreSQL Outbox → Redis Streams → Worker 文件入库（提示词 17 已落地）

- [x] Publisher 用 `FOR UPDATE SKIP LOCKED` 小批 claim；XADD 成功后才条件更新 PUBLISHED，Redis 失败走 V9 退避/租约/DEAD_LETTER；信封只允许版本化元数据和 `file_id`，拒绝 secret/object key/原始文件字段（`RedisOutboxPublisherTest` 3 例、`IngestionEventCodecTest` 2 例）。
- [x] Consumer group 手动 ACK；业务终态事务成功后才 ACK，DEFERRED/异常留 pending；XPENDING + XCLAIM 可由存活消费者 reclaim（`RedisIngestionConsumerTest` 3 例）。
- [x] 在建立 TenantContext 前校验 schemaVersion/eventType/tenantId/payload；Worker scope 先清理、finally 再清理，tenant-A 后处理 tenant-B 以及异常路径均无残留（`WorkerTenantScopeTest` 2 例）。
- [x] Inbox 只在 READY+SUCCEEDED 或最终 FAILED 的终态事务写入；可重试失败回 PENDING 时不提前写 Inbox，重试预算耗尽后才标记最终失败（`KnowledgeFileIngestionStateServiceTest` 4 例）。
- [x] 文件严格推进 QUEUED→PARSING→CHUNKING→EMBEDDING→INDEXING→READY；解析损坏为永久错误，模型限流和 Milvus 暂态不可用可重试；索引前按 file 幂等删旧向量，PG chunk UUID 与 Milvus ID 一致（`KnowledgeFileIngestionServiceTest` 4 例）。
- [x] 真实 PostgreSQL 16 + Redis 7 + MinIO + Milvus 2.5.6：TXT 覆盖“Redis 已写、PG 未标记”重复投递但 Inbox/业务一次；PDF 覆盖死亡消费者 pending reclaim；DOCX 覆盖 MinIO→READY 与 Task stage/progress；双 Worker 并发同事件只调用一次 Embedding，chunk_index/向量均不重复（`WorkerIngestionPipelineIT` 4/4）。
- [x] 外部调用不伪装为数据库事务；Parser/Embedding/MinIO/Milvus 通过幂等 replace/delete/upsert 和失败补偿实现最终一致，PostgreSQL 是 UI 状态事实来源。
- [ ] 文件删除后 PostgreSQL、MinIO 和 Milvus 最终一致（本提示词明确不实现删除链；Milvus `deleteByFile` 已提供幂等补偿能力）。

执行命令：`mvn -ntp -am -pl knowagent-worker -Pdocker-it "-Dtest=__NoUnitTests__" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=WorkerIngestionPipelineIT" verify`；本节真实容器测试 4/4 通过。默认 `mvn clean verify` 不启动 Docker。

### 知识库语义检索接口（提示词 18 已落地）

- [x] `POST /api/v1/knowledge-bases/{knowledgeBaseId}/retrieval` 需 `KNOWLEDGE_RETRIEVE`；tenant 只取自认证 principal，请求 DTO 无 tenantId，ADMIN 已授予该权限（`KnowledgeRetrievalApiContractTest` 2 例）。
- [x] query → 单条 Embedding → Milvus → PostgreSQL → citation 顺序固定；topK/threshold 缺省读取知识库 RetrievalConfig，显式值校验 topK 1..100、threshold 0..1（`KnowledgeRetrievalServiceTest`）。
- [x] fileIds 逐个按 tenant+kb 读取且必须 READY：伪造 tenant-B/file 不存在统一 404，非 READY 409，均发生在 Embedding/Milvus 前。
- [x] Milvus 只返回候选 chunkId/score；应用按 tenant+kb+chunkIds 一次批量回查 PG，并丢弃缺失、重复、跨域、chunk 非 READY、file 非 READY/已删除命中。引用 displayName/content/pageNumber/sectionPath 均以 PG 为准，排序保持 Milvus 首次命中顺序，再应用 threshold 与最终 topK；空命中返回空列表。
- [x] 自定义批量 SQL 在 chunk 与 file 两侧都显式包含 tenant_id + knowledge_base_id，chunkIds 只能是 UUID 参数；SQL 契约类现 4/4 通过。
- [x] 真实 PostgreSQL 16 + Milvus 2.5.6 集成：Milvus filter 实际排除其他 tenant/knowledge_base；故意写入“Milvus 标量属于 A、PG chunk 实属 B”的候选后，PG 权威回查将其丢弃；只返回 READY file/chunk 且引用字段与 PG 一致（`KnowledgeRetrievalIT` 1/1）。
- [x] 未实现 RerankGateway 适配器时，`rerankEnabled=true` 明确返回 `MODEL_CONFIGURATION_ERROR`；不伪造 rerank 分数。
- [x] query、正文、向量不写日志/指标；指标只记录 tenant/provider 非敏感 ID、候选数、结果数、outcome、耗时，且 metrics 失败不改变检索语义。引用响应 `toString()` 不包含正文。
- [x] 本接口不调用 ChatModelGateway，不生成 RAG 答案、Conversation、SSE 或 Agent Run。

本节定向验收：`mvn -ntp -am -pl knowagent-api "-Dtest=KnowledgeRetrievalServiceTest,KnowledgeChunkMapperSqlContractTest,KnowledgeRetrievalApiContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 共 14/14 通过；`mvn -ntp -am -pl knowagent-api -Pdocker-it "-Dtest=__NoUnitTests__" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=KnowledgeRetrievalIT" "-Dfailsafe.failIfNoSpecifiedTests=false" verify` 共 1/1 通过。全仓 `mvn -ntp clean verify` 共 612/612 个单元测试通过，默认构建仍不启动 Docker。

## 5. Agent、工具与 SSE

- [ ] RAG 上下文只包含 Agent 绑定且当前用户可见的知识库。
- [ ] 模型文本增量实时转换为 MODEL_DELTA。
- [ ] 一次响应包含多个 Tool Call 时按顺序执行和回填。
- [ ] 未授权工具不进入模型可见工具集合。
- [ ] 工具超时、异常和非法参数产生结构化失败事件。
- [ ] 用户取消后模型流、工具调用和 Run 状态都停止。
- [ ] 每个 Run 只出现一个终态事件。
- [ ] SSE `id` 使用 PublishedRunEvent.cursor，而不是 UUID eventId。
- [ ] 携带 Last-Event-ID 重连只补发游标之后的事件。
- [ ] Redis 事件过期时回退 PostgreSQL 快照并发送 reset 事件。
- [ ] tenant-A 无法订阅 tenant-B 的 Run SSE。
- [ ] 慢客户端超过发送队列上限后被断开，不拖慢 Worker。

验收：WireMock 模拟模型流、工具调用、限流和断流，接口测试验证事件顺序与重连结果。

## 6. 前端与部署

- [ ] 登录后刷新页面能够恢复用户状态。
- [ ] 上传页面显示解析、分块、索引任务状态。
- [ ] 聊天页面逐字显示 SSE 内容并展示引用来源。
- [ ] 浏览器断网后使用 Last-Event-ID 自动恢复。
- [ ] 取消 Run 后 UI 停止追加内容并显示终态。
- [ ] Docker Compose 从空 Volume 启动全部基础服务。
- [ ] API、Worker 健康检查通过，数据库迁移只执行一次。
- [ ] 删除并重建容器但保留 Volume 后数据仍存在。
- [ ] 日志和镜像中不包含 API Key、密码或 token。

验收：Playwright 跑通登录、上传、问答、断线恢复、取消和历史恢复；Compose smoke test 使用全新项目名执行。

## 7. 面试演示回归

- [ ] 10 分钟内完成登录、上传文档、创建 Agent、流式问答和查看引用。
- [ ] 能展示 PostgreSQL、Redis Streams、MinIO、Milvus 的职责边界。
- [ ] 能演示一次失败任务重试和一次 SSE 断线恢复。
- [ ] 能说明 Request/Run 分离、Outbox 一致性和 tenant 全链路隔离。
- [ ] README、架构图、ADR 和实际代码签名保持一致。
