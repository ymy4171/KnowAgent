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

- [ ] 正确密码登录返回 access token 与 refresh token。
- [ ] 错误密码、禁用用户、过期 token 分别返回稳定错误码。
- [ ] refresh token 只能使用一次，重放请求被拒绝。
- [ ] tenant-A 用户通过 tenant-B 的资源 ID 查询时返回 404，不能泄露资源存在性。
- [ ] 普通用户不能调用管理员接口。
- [x] MyBatis-Plus 普通查询自动添加 tenant 条件。
- [x] 认证前自定义 SQL 显式携带 tenant 条件，且绕过租户插件的方法白名单精确锁定。
- [ ] 锁查询、统计查询和批量更新 SQL 显式包含 tenant_id（即使租户插件能够改写普通自定义 SQL，也不能仅依赖自动改写）。
- [ ] API Key、模型密钥和外部凭据不以明文存储或输出到日志。
- [x] 开发者管理员初始化幂等创建租户、`ADMIN` 系统角色、管理员用户与 `user_roles` 绑定；密码只以 Argon2id 哈希落库，日志与异常不含明文；任一步失败整体回滚。
- [x] 初始化查询与用户角色绑定 UPSERT 显式携带 tenant_id，绕过 tenant-line 的方法白名单保持精确锁定；过期绑定原地恢复且不违反唯一约束。
- [x] Access Token 由 Spring Security 官方 JOSE 组件签发与解析（不手写 JWT 编解码器）：密钥只从环境变量读取；校验签名、issuer、audience、过期时间与必需声明（sub/tenant_id/roles/permissions/jti）；合法 token 转换为 TenantPrincipal 并建立/清理 TenantContext；缺失、篡改、过期、错误 issuer/audience、缺 tenant_id、roles 缺失的 token 一律返回稳定 JSON 401；角色/权限正确映射为 GrantedAuthority；响应、日志与异常不出现 token 明文。密钥、issuer、audience、有效期通过类型安全的 `@ConfigurationProperties` 注入。

验收：使用 Testcontainers PostgreSQL 初始化两个租户，完成跨租户 ID 枚举和权限矩阵测试。管理员初始化在 PostgreSQL 16 容器中验证首次执行、幂等、哈希、事务回滚，以及过期 `user_roles` 绑定原地恢复并重新获得 ADMIN 权限（`mvn -Pdocker-it verify`）。Access Token 基础设施在 PostgreSQL 16 容器中通过真实 HTTP 安全链验证：有效 token 访问受保护端点、角色/权限映射、TenantContext 建立与清理，以及匿名访问、篡改、过期、错误 issuer/audience、缺 tenant_id、roles 缺失、畸形 tenant_id 共 7 类无效请求稳定返回 JSON 401 且不泄露 token（`mvn -Pdocker-it verify`，见 `AccessTokenSecurityIT`）。

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
