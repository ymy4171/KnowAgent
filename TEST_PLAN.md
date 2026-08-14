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

## 3. 数据库、事务与 Outbox

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

- [ ] tenant-A 上传的对象物理键固定带 tenant-A 前缀。
- [ ] tenant-B 无法读取或删除 tenant-A 对象。
- [ ] 上传成功但事务失败时执行对象补偿清理。
- [ ] TXT、PDF、DOCX 分别选择正确解析器。
- [ ] 不支持的 MIME、空文件、超限文件返回明确错误码。
- [ ] 分块满足 chunkSize 和 overlap，页码与章节元数据不丢失。
- [ ] 同一文件任务重试不重复创建 chunk。
- [ ] Embedding 批处理遵守 token 和批大小限制。
- [ ] PostgreSQL chunk 与 Milvus entity 使用相同 UUID。
- [ ] Milvus 检索同时过滤 tenant_id 和 knowledge_base_id。
- [ ] 文件删除后 PostgreSQL、MinIO 和 Milvus 最终一致。

验收：Testcontainers/Compose 启动 PostgreSQL、MinIO 和 Milvus，完成上传到检索的端到端测试。

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
