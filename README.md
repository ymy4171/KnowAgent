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

项目目前已经完成多模块工程、核心领域端口、API/Worker 启动入口、Flyway V1-V11 共 31 张业务表和 Docker Compose 基础设施。已实现：开发者管理员初始化（Argon2id 哈希、幂等、事务回滚）、Access Token 基础设施（Spring Security 官方 JOSE 签发/校验、租户声明与 TenantContext）、登录与当前用户接口（`POST /api/v1/auth/login` 返回 access/refresh token，Refresh Token 只存 SHA-256 哈希；`GET /api/v1/users/me` 返回当前身份、角色与权限；登录不持有外层事务——成功写入与失败计数各自独立单连接事务，并发失败登录不会耗尽连接池；失败计数数据库内原子递增并支持临时锁定，未知租户/用户与错误密码统一响应且工作量一致防计时枚举）、Refresh Token 轮换与登出（`POST /api/v1/auth/refresh` 单次使用并在事务内统一锁定家族根 token（`id = family_id` FOR UPDATE）后轮换出同家族子 token，旧 token 重放、并发冲突或唯一子 token 冲突（保存点恢复）撤销整个家族并返回稳定 401；账户状态与未来锁定时间使用共享策略校验；API 事务门面覆盖轮换与 Access Token 签名，签名失败时消费和子 token 插入整体回滚；`POST /api/v1/auth/logout` 为事务方法，按 token 定位家族根撤销仍有效 token，重复登出幂等；原始 token 永不落库或出现在日志）、最小 RBAC 闭环（生产启用 Method Security，`TenantPrincipal` 携带不可变 roles+permissions 并作为 JWT permissions claim 唯一来源；`GET /api/v1/users` 与 `GET /api/v1/users/{userId}` 需 `USER_READ`，租户一律来自认证 principal，分页/统计 SQL 显式携带 tenant_id 并留在租户插件下，跨租户 userId 与不存在用户统一 404，响应 DTO 结构上不可能泄露密码哈希等内部字段；`USER_ADMIN` 本阶段仅定义常量不授予任何人）、模型供应商配置（`POST/GET/GET/{id}/PATCH/{id}/DELETE/{id}/POST/{id}/health-check` 于 `/api/v1/model-providers`：provider_key 小写规范化且租户内活动唯一；`MODEL_PROVIDER_READ/WRITE` 方法级鉴权；API Key 与自定义 Header 用 AES-256-GCM 加密——随机 nonce、`aesgcm.v{n}.{nonce}.{ciphertext}` 信封携带 keyVersion，主密钥只从 `MODEL_PROVIDER_SECRET_KEY` 读取、缺密钥时写秘密被拒而非退化明文；列表/详情只返回 hasSecret/capabilities/enabledModels/publicConfig；更新未提交 secret 保留旧值、`clearSecret`/`clearHeaders` 独立布尔清除；删除软删 + 乐观锁，被活动知识库引用返回 409；自定义分页/统计/更新 SQL 显式 tenant_id、跨租户 404；health-check 明确返回未接入、不伪造 HEALTHY）、知识库 CRUD（`POST/GET/GET/{id}/PATCH/{id}/DELETE/{id}` 于 `/api/v1/knowledge-bases`：slug 小写规范化且符合 V6 正则、租户内活动唯一（部分唯一索引防并发重复）；`KNOWLEDGE_BASE_READ/WRITE` 方法级鉴权；创建/更新时校验 embedding/rerank 供应商必须属于当前租户、未删除、启用且声明对应能力，provider/model 必须成对，且 `enabled_models` 目录非空时必须存在「模型名+能力」完全匹配记录（空目录允许任意模型名）——跨租户供应商绑定统一 404，非法配置稳定 400；创建与更新在同一事务内先以 `FOR KEY SHARE` 锁定供应商，与供应商删除的 `FOR UPDATE` 串行化，堵住「验证后插入引用已删供应商」的 TOCTOU；`ChunkPolicy`（RECURSIVE/MARKDOWN_HEADING/TOKEN_WINDOW，chunkSize>0 且 overlap∈[0,chunkSize)）与 `RetrievalConfig`（topK∈[1,100]、scoreThreshold∈[0,1]、rerankEnabled）作为类型安全值对象持久化 JSONB；版本乐观锁，并发更新/删除冲突 409 且不覆盖新数据，并发修改 slug 竞争同样稳定返回 409（不 500）；状态机 ACTIVE/DISABLED/DELETING/DELETED 集中定义，仅 ACTIVE↔DISABLED 可正向切换；删除只允许无未删除文件的知识库——软删到 DELETED、有文件 409、全量级联删除留待文件入库阶段；列表带分页与 name/slug 模糊、status 精确过滤，数据与统计 SQL 同条件；响应不含版本/租户/审计内部字段，跨租户资源统一 404）、异步任务持久化基础（`knowagent-observability` 承载 Task/Outbox/Inbox：业务记录 + Task + Outbox 在同一事务内写入（`TaskSubmissionService.submit`，先校验再写入、失败整体回滚，UUID 全部在 Java 预生成）；Task 状态机严格 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED，Outbox 严格 PENDING/PROCESSING/PUBLISHED/DEAD_LETTER，转换先经领域校验再由数据库 status+version 条件更新兜底；Outbox 竞争发布用 `FOR UPDATE SKIP LOCKED` 按 `next_retry_at, created_at` 抢占并置租约，失败按 `RetryPolicy` 指数退避回 PENDING、预算耗尽置 DEAD_LETTER，发布/失败都以上一版本+状态守卫（丢竞争 409）；Task claim 显式 tenant_id + FOR UPDATE 行锁 + 租约，租约过期可回收，且以 `attempt_count < max_attempts` 守卫——预算耗尽的 Task 不可再认领、最后一次失败必须转 FAILED 而非重试（避免触发 `ck_tasks_attempts`）；Inbox 幂等依赖 `uq_inbox_events_consumer_event` 唯一约束 + `ON CONFLICT DO NOTHING`，重复回执返回「已处理」而非错误；payload/result/headers 用 JSONB TypeHandler 结构化映射，错误消息统一经 `ErrorMessageSanitizer` 同一净化边界（`TaskTransition`/`OutboxEvent` 构造即净化）脱敏常见凭证（api_key/Authorization Bearer/JWT 等 → `<redacted>`）并截断后落库，Task/OutboxEvent/SubmitTaskCommand 的 `toString()` 不输出 payload/result/headers；observability 所有自定义 SQL 绕过 tenant-line 并以显式 `tenant_id` 为唯一隔离机制，白名单与唯一全局例外（跨租户 claim）由 `ObservabilityMapperSqlContractTest` 锁定；`GET /api/v1/tasks/{id}` 需 `TASK_READ`，响应不含 payload/result/租户/锁/版本等内部字段，匿名 401、无权限 403、跨租户与不存在统一 404；PostgreSQL 是唯一事实来源，Redis 发布与 Worker 消费留待后续阶段）、MinIO 对象存储适配器与知识库文件上传（`knowagent-workspace` 实现 MinIO Java SDK 8.5.17 适配器：固定配置 bucket（`minio.bucket` 缺省 `knowledge`）启动时幂等确保存在，对象键由服务端生成 `tenants/{tenantId}/knowledge-bases/{kbId}/files/{fileId}/source`，适配器在每个操作前重新校验 `tenants/{tenantId}/` 前缀——跨租户寻址在 MinIO 调用前即被拒（稳定 INVALID_OPERATION），stat/get/delete 映射稳定错误原因、删除缺失对象幂等成功、所有 InputStream 关闭，未配置 `minio.endpoint` 时回退 fail-fast 的 `UnavailableObjectStorageGateway`；`POST/GET/GET/{fileId}/GET/{fileId}/content` 于 `/api/v1/knowledge-bases/{knowledgeBaseId}/files`：上传为 multipart/form-data 且成功只返回 **202**——HTTP 线程流式 spool（50MB 防御上限）同时计算 SHA-256 与大小，类型由 Apache Tika + 可信内容回退从内容嗅探（TXT/PDF/DOCX/TEXT_MARKDOWN 可上传，空文件/伪造 MIME/未知类型稳定 400），文件名只作展示元数据，随后写 MinIO、再在**同一事务**写 `knowledge_files`（状态机 UPLOADED→QUEUED 集中定义）+ PENDING Task + Outbox——上传线程不解析文档、不调用 Embedding 或 Milvus；数据库失败立即补偿删除已上传对象（补偿失败记录 `[ALARM]` 但绝不误报成功）；`Idempotency-Key` 作用域为（租户, 知识库）——同 key 同内容重放返回原 fileId/taskId（replayed=true）、同 key 不同内容 409；读接口需 `KNOWLEDGE_FILE_READ`、写需 `KNOWLEDGE_FILE_WRITE`（集中定义并授予 ADMIN），list/detail/content 严格租户作用域、跨租户与不存在统一 404，响应与下载响应结构上不含 object_key/bucket/processing_params/内部错误栈/MinIO 凭据，content 为认证 + 流式下载）、本地文档解析（`knowagent-knowledge` 的 `document` 包：`ParserRegistry` 按内容嗅探出的规范 MIME 选择唯一解析器，产出标准 `ParsedDocument`——title/text/pageCount + 精确分区的 `ParsedSection`（sectionPath/heading/pageNumber/字符偏移/metadata），这是后续分块的唯一契约；`TxtMarkdownParser`（text/plain|text/markdown，UTF-8/UTF-16 BOM 解码、Markdown `#` 标题切节）、`PdfParser`（application/pdf，PDFBox 3，每页一节带 1-based pageNumber，按阅读顺序提取）、`DocxParser`（application/vnd.openxmlformats-officedocument.wordprocessingml.document，Apache POI，Heading/标题 样式开节、表格按读取顺序），只引入最小依赖 pdfbox 3.0.5 与 poi-ooxml 5.4.1（Spring Boot 均不托管、显式固定版本）；解析器只接受服务端 `ObjectStorageGateway` 提供的受控流（`ParseSource`），不自行从任意 URL 下载；`knowagent.parse.*` 类型安全限制（maxBytes/maxPages/maxUncompressedBytes/maxCharacters/timeout，未配置回退安全默认）拒绝空/损坏/超限输入，DOCX 以 `ZipSecureFile` + 中央目录预检防 zip-bomb；异常转稳定错误码（空 `EMPTY_DOCUMENT`、损坏 `CORRUPT_DOCUMENT`、超限 `DOCUMENT_TOO_LARGE`、未知 MIME `UNSUPPORTED_DOCUMENT_TYPE`、超时 `DOCUMENT_TIMEOUT`），错误消息固定文案不含原文/对象键/路径/第三方堆栈；源流在成功与异常路径均关闭、临时 spool 文件全路径删除；扫描 PDF 可加载但无文本时稳定返回 `OCR_REQUIRED`（不伪造文本），MinerU/PaddleX OCR 属后续 `EXTERNAL_SERVICE` 范围）、确定性文本分块与 knowledge_chunks 持久化（`knowagent-knowledge` 的 `chunk` 包 + `ChunkWriteService`：`TokenCounter` 端口统一 token 估算，未接入供应商 tokenizer 前用「char-run-v1」确定性估算并在每个 chunk metadata 标记算法版本（不把字符数冒充精确 token 数），`TokenStream` 位置原子 token（`record Token(int startChar,int endChar)`）边界恒落在码点之间、绝不切开 Unicode 代理对；RECURSIVE/MARKDOWN_HEADING/TOKEN_WINDOW 三策略全部落地，chunkSize/overlap 恒以 token 为单位且 overlap<chunkSize，不产生空 chunk、不无限循环，超长无分隔文本 `safeSplit` 安全退化；MARKDOWN_HEADING 以 `ParsedSection` 为硬边界（chunk 不跨节），页码 pageNumber 与标题路径（`"1.1"`→`["1","1.1"]`）从覆盖章节透传到所有相关 chunk；同输入+策略重复运行产生同顺序同内容同哈希；每个 ChunkDraft 稳定携带 chunkIndex/SHA-256 contentHash/tokenCount/字符与 Token offset/pageNumber/sectionPath/metadata；`KnowledgeChunk` 领域模型 + Po/Mapper/Repository/Converter 建立，UUID 全部 Java 预生成（`@TableId(IdType.INPUT)`），section_path/metadata 用 `StringListJsonbTypeHandler`/`StringMapJsonbTypeHandler` 结构化映射 JSONB；`ChunkWriteService.replaceChunks` 单一事务先 `FOR UPDATE` 锁定文件行（不存在/跨租户 404）→ 整集合替换（重试幂等，`UNIQUE(tenant_id, file_id, chunk_index)` 唯一约束兜底杜绝重复索引）→ `version` 守卫条件更新 chunk_count/token_count/version（冲突 409），任一步失败整体回滚、旧数据/新数据不半替换；chunk 初始 `index_status=PENDING`；所有 chunk 查询/替换/删除 SQL 显式携带 tenant_id + knowledge_base_id + file_id 且不加 `@InterceptorIgnore`；本提示词**不生成向量、不调 Embedding/Milvus、不推进文件状态**，Embedding 与 Worker 驱动的解析/分块执行留待后续提示词）、OpenAI-compatible Embedding 调用网关（`knowagent-model` 新增 `embedding` 端口/值对象与 `infrastructure.embedding` 适配器：`EmbeddingGateway.embed(EmbeddingRequest)` 每调用按当前租户解析 ModelProvider 并校验适配器/启用/EMBEDDING 能力/模型目录——跨租户或缺失返回 `RESOURCE_NOT_FOUND`、其余非法配置稳定 `MODEL_CONFIGURATION_ERROR`，校验失败不发任何请求；`BatchPlanner` 同时遵守最大文本条数、char-run-v1 确定性估算 token 总量与请求体大小上限，空/空白输入直接拒绝、单个超限文本拒绝、批次严格保持输入顺序；用 Spring AI 1.1.8 `OpenAiApi`+`OpenAiEmbeddingModel` 只做协议（不手写重复客户端），客户端按 `(tenantId, providerId, configVersion)` LRU 缓存、`configVersion` 更新即失效、`maxClientCacheSize` 有界，API Key 与自定义 Header 只在客户端构建边界解密进入请求头，解密值永不进入日志/异常/缓存键/业务对象；配置连接/读取/总超时（所有批次共享 deadline）与有界重试——只对 429、明确 5xx 与网络暂态重试、4xx 配置错误不重试，重试在总超时内进行；自定义 `OpenAiResponseErrorHandler` 不读取供应商正文（默认 Spring AI 错误处理器会把正文带进异常、默认重试模板无 429 处理，两者都被替换），错误沿 cause 链映射稳定 `MODEL_AUTH_FAILED/MODEL_RATE_LIMITED/MODEL_TIMEOUT/MODEL_BAD_RESPONSE/MODEL_SERVICE_ERROR/MODEL_CONFIGURATION_ERROR`；向量按数量/顺序/非空/有限性/维度逐批校验（NaN/Infinity 拒绝、跨批维度不一致与总数不符都在索引前失败），维度与 Milvus collection 配置不一致时由调用方在索引前比对失败；`EmbeddingMetrics` 记录 providerId/model/outcome/耗时/批次数/估算 token 等非敏感指标，不记录 chunk 原文或向量数组，无 MeterRegistry 时 no-op；`knowagent.model.embedding.*` 超时/重试/批限/缓存可配置，API 与 Worker 共用同一装配；WireMock 契约测试覆盖正常多批、顺序保持、429 重试、401/403 不重试、读取超时、响应数量/维度/NaN/Infinity 错误、configVersion 缓存失效、跨租户 404、密钥与 Header 不泄露，Spring 上下文只装配一个明确选择的 EmbeddingGateway Bean；本提示词**不写 Milvus、不启动完整文件 Worker**）。后续优先实现：

阶段说明：上段按历次提示词保留的“留待后续”描述是实现当时的边界；当前事实以本节后面的“异步文件入库（提示词 17 已落地）”为准，Redis 发布、Worker 消费、解析、分块、Embedding 与 Milvus 写入已经接通。

实现边界补充：模型供应商密钥配置现在由 `knowagent-model` 统一装配，API 与 Worker 共用 `MODEL_PROVIDER_SECRET_KEY`；删除供应商会在事务内先锁定供应商行再检查知识库引用，知识库写接口同样遵循配套的 `FOR KEY SHARE` 锁协议（供应商变更锁定由知识库创建/更新事务执行）。

文件上传并发边界：对象写入 MinIO 后，文件落库事务会以 `FOR KEY SHARE` 重新锁定并确认知识库仍为活动状态，与知识库删除侧的 `FOR UPDATE` 配对。删除先提交时上传返回 404，file/task/outbox 全部不落库，并补偿删除已上传对象；文件列表同样先校验当前租户知识库，跨租户知识库统一返回 404。MinIO 配置对象的日志表示会脱敏 access key 与 secret key。

本地解析安全边界：`ParseSource`、`ParsedSection` 和 `ParsedDocument` 的日志表示不输出对象键、文件名、标题、正文或 metadata；未知 MIME 也会关闭输入流。DOCX 同时限制单个入口和累计解压大小，按 Heading id 或样式显示名（含本地化“标题 n”）识别章节，并确保底层 `OPCPackage` 在文档构造失败时关闭。`.env.example` 与 Compose 已统一提供并透传 `PARSE_*` 解析预算。

分块与落库安全边界：`KnowledgeChunk` 与 `KnowledgeChunkPersistenceConverter` 的 `toString()`/错误消息刻意不含 chunk content（Rule 10：原始文件内容永不落日志）；替换事务同时用显式 `(tenant_id, knowledge_base_id, file_id)` 三元组与租户插件的 fail-closed 兜底双重隔离，tenant-B 对 tenant-A chunk 的查询/替换/删除一律 404 或空集合（`KnowledgeChunkIT` 在真实 PostgreSQL 上验证）。

Milvus 向量存储适配器（提示词 16 已落地）：`knowagent-knowledge` 的 `infrastructure.vector` 包用 Milvus Java SDK V2 API（`io.milvus:milvus-sdk-java:2.5.6`）实现 `VectorStoreGateway` 端口——启动时幂等创建/校验 collection（VARCHAR chunk-UUID 主键 autoID=false、FLOAT_VECTOR 维度来自已验证配置、tenant/kb/file/chunk/model-spec 标量）与 COSINE 索引（HNSW 默认，索引类型与参数由配置固定）并 load，已存在 collection 的 schema/维度不匹配时拒绝启动、绝不 drop；upsert 前校验批次/维度/数值/关系一致且 entity id 恒等于 chunk UUID；检索与删除经 `MilvusFilterBuilder` 受控 filter 恒含 tenant_id + knowledge_base_id（可选 file_id 逐 UUID 校验转义），跨租户 chunkId/fileId 伪造无结果；结果只返回 id/fileId/score，正文由 PostgreSQL 回查；deleteByFile 无匹配视为幂等成功；连接/搜索/写入/删除/初始化超时独立配置，错误映射稳定 `VECTOR_UNAVAILABLE`/`VECTOR_SCHEMA_MISMATCH`/`VECTOR_BAD_RESPONSE`（API 映射 503/500/502）；`VectorMetrics` 只记 collection/operation/outcome/数量/耗时；`MILVUS_ENDPOINT` 未配置时装配 fail-fast 的 `UnavailableVectorStoreGateway`，无 Docker/Milvus 环境照常启动。真实 Milvus 2.5.6 容器集成测试 `MilvusVectorStoreIT` 6 例通过（建集合/load/upsert/COSINE 搜索/tenant+kb+file 过滤/幂等 upsert 与 delete/UUID 主键一致/维度不匹配拒绝启动不删数据）。

异步文件入库（提示词 17 已落地）：上传完成的 QUEUED 文件通过 `PostgreSQL Outbox → 固定 Redis Stream → consumer group Worker → MinIO 流式读取 → ParserRegistry → Chunker → EmbeddingGateway → Milvus` 处理。Publisher 用 `FOR UPDATE SKIP LOCKED` 小批抢占，XADD 成功后才把 PG 标记 PUBLISHED；该崩溃窗口允许重复消息，Worker 通过 Inbox `(consumer_name,event_id)`、file 行锁、Task 租约和条件更新保证业务一次完成。Worker 手动 ACK 并用 XPENDING + XCLAIM 恢复死亡消费者；文件状态严格推进 `QUEUED→PARSING→CHUNKING→EMBEDDING→INDEXING→READY`，Task stage/progress 同步写 PostgreSQL，暂态错误有界重试，永久错误或预算耗尽进入 FAILED。外部系统调用不处于数据库事务内：重试通过 PG chunk 整集合替换、Milvus file 级幂等删除和相同 chunk UUID upsert 最终收敛，不声明全链路强事务。

Worker Stream 的固定 key 为 `knowagent:knowledge-file-ingestion`；可通过 `.env.example` 中的 `WORKER_STREAM_GROUP`、`WORKER_CONSUMER_NAME`、批大小、poll/reclaim 时间、Task/Outbox 租约、调度间隔和启停开关配置。多副本必须使用同一 group、不同 consumer name（Compose 默认回退容器 HOSTNAME）。默认 `mvn clean verify` 不启动 Docker；真实链路执行：

```powershell
mvn -ntp -am -pl knowagent-worker -Pdocker-it "-Dtest=__NoUnitTests__" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=WorkerIngestionPipelineIT" verify
```

语义检索接口（提示词 18 已落地）：`POST /api/v1/knowledge-bases/{knowledgeBaseId}/retrieval` 需 `KNOWLEDGE_RETRIEVE` 权限。租户只取自认证 `TenantPrincipal`，请求体不接受 `tenantId`；`topK`、`scoreThreshold` 缺省时读取知识库 `RetrievalConfig`，可选 `fileIds` 必须逐个属于当前租户、当前知识库且状态为 READY。链路固定为单查询 Embedding → Milvus tenant+knowledge_base（可选 file）受控过滤 → 按 chunk UUID 批量回查 PostgreSQL → READY 状态与权限二次裁剪 → 保持 Milvus 排序并应用 threshold/topK。Milvus 只提供候选 id/score；引用的文件名、正文、页码、章节路径全部来自 PostgreSQL。空命中返回 `citations: []`，不调用 Chat、不生成答案；配置开启 rerank 但尚无可用适配器时明确返回 `MODEL_CONFIGURATION_ERROR`，不伪造 rerank 分数。

请求示例：

```json
{
  "query": "如何配置知识库？",
  "topK": 5,
  "scoreThreshold": 0.72,
  "fileIds": ["11111111-1111-1111-1111-111111111111"]
}
```

成功响应只返回可验证引用，不回显 query、tenant、对象键、向量或供应商配置：

```json
{
  "knowledgeBaseId": "22222222-2222-2222-2222-222222222222",
  "citations": [{
    "chunkId": "33333333-3333-3333-3333-333333333333",
    "fileId": "11111111-1111-1111-1111-111111111111",
    "displayName": "guide.pdf",
    "content": "...",
    "pageNumber": 5,
    "sectionPath": ["2", "2.1"],
    "score": 0.91,
    "rank": 1
  }]
}
```

1. 用户、角色、租户管理写接口
2. 文件删除级联与 PostgreSQL/MinIO/向量库最终一致（删除接口与 Worker 侧执行链）
3. 可选 RerankGateway 供应商适配器与检索重排
4. Agent 配置、RAG 问答和 SSE 流式输出

## 设计文档

- [项目计划](./PLAN.md)
- [认证阶段开发提示词](./DEVELOPMENT_PROMPTS.md)
- [Yuxi Java 重构指南](./YUXI_REFACTOR_GUIDE.md)
- [系统架构说明](./docs/architecture.md)
- [可执行测试计划](./TEST_PLAN.md)
- [架构决策记录](./docs/adr/)
