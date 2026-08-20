# KnowAgent 知识库 RAG 阶段开发提示词

本文承接 [DEVELOPMENT_PROMPTS.md](DEVELOPMENT_PROMPTS.md) 已完成的“认证、授权与租户”里程碑，提供第 3～5 周“知识库 RAG”阶段的可执行提示词。提示词从九继续编号，并按真实依赖顺序拆分；一次只执行一个，当前提示词完成验收后再进入下一个。

本阶段只完成模型供应商、知识库、文件入库、异步处理、Embedding、Milvus 检索和引用，不提前实现 Agent Runtime、聊天生成、SSE、Tools、Skills、MCP 或知识图谱。

## 1. 使用方式

1. 在 KnowAgent 仓库根目录开启新的 Codex/IDEA AI 任务。
2. 先粘贴“统一前置提示词”，再粘贴当前编号的提示词。
3. 要求编码 Agent 先扫描现有实现、测试和 Git 状态，不得根据本文假设覆盖已存在代码。
4. 每条提示词都必须完成代码、测试、文档和变更报告，不能只输出设计方案。
5. 一次提示词只解决一个可验收切片；出现前置问题时先修复，不得静默跳到后续功能。
6. 已执行的 `V1` 至 `V11` Flyway 文件禁止修改；现有表足以完成本阶段，确需调整时必须说明理由并新增 `V12__*.sql` 或更高版本。

## 2. 统一前置提示词

```text
你正在维护 Java 21 项目 KnowAgent。开始编码前必须完整阅读并遵守：

- README.md
- PLAN.md
- docs/architecture.md
- docs/database-schema.md
- docs/adr/0001-modular-monolith.md
- docs/adr/0002-outbox-redis-streams.md
- docs/adr/0003-mybatis-plus.md
- docs/adr/0004-spring-mvc.md
- docs/adr/0005-milvus.md
- TEST_PLAN.md
- FILE_GUIDE.md
- KNOWLEDGE_DEVELOPMENT_PROMPTS.md

开始时先执行并报告：

1. `git status --short --branch`，识别并保留用户已有修改。
2. 扫描本任务涉及模块的 POM、现有端口、数据库迁移和测试。
3. 说明本次准备修改的文件范围、模块依赖方向、事务边界和不做范围。
4. 涉及 Spring AI、MinIO 或 Milvus SDK 时，先查询与仓库固定版本对应的官方文档；不得凭过时 API 猜实现。

固定技术事实：

- Java 21、Spring Boot 3.5.9、Spring MVC、Spring Security。
- Spring AI 1.1.8、MyBatis-Plus、PostgreSQL 16、Redis 7、MinIO、Milvus 2.5.6。
- 项目是 Maven 多模块模块化单体，PostgreSQL 是业务状态最终事实来源。
- `knowagent-api` 只负责 HTTP DTO、Controller、安全装配和异常映射。
- `knowagent-worker` 负责异步消费和任务执行，不暴露业务 HTTP 接口。
- `knowagent-model` 隔离模型供应商和 Chat/Embedding/Rerank 网关。
- `knowagent-knowledge` 承担知识库、文件、解析、分块、向量检索的领域和应用逻辑。
- `knowagent-workspace` 提供对象存储边界和 MinIO 适配器。
- `knowagent-observability` 承担 Task、Outbox、Inbox 和审计等基础能力。
- `knowledge_bases`、`knowledge_files`、`knowledge_chunks`、`model_providers`、`tasks`、`outbox_events`、`inbox_events` 已由 V5、V6、V9 创建。
- `knowledge_chunks` 是可重建数据，不使用软删除；PostgreSQL chunk UUID 必须与 Milvus entity ID 相同。

强制工程规则：

1. Controller 不得调用 Mapper；跨模块不得绕过应用端口直接调用其他模块 Mapper。
2. 领域对象、持久化对象、供应商 SDK 对象和 HTTP DTO 必须分离。
3. tenant_id 只能来自已认证 TenantPrincipal/TenantContext 或可信异步事件信封，不能接受客户端覆盖。
4. 普通 MyBatis-Plus 查询可由租户插件改写；自定义 SQL、锁查询、统计、批量更新和 Worker SQL 必须显式包含 tenant_id。
5. 跨租户资源枚举统一返回 404；权限不足返回 403；不能通过状态码或错误消息泄露其他租户资源。
6. MinIO object key 必须由服务端构造并包含 tenantId；客户端不得提交完整对象键、Bucket 或物理路径。
7. Milvus 写入、检索和删除必须同时限定 tenant_id、knowledge_base_id，并在需要时限定 file_id/chunk_id。
8. UUID 在 Java 中预生成；状态名与 PostgreSQL CHECK 大写值完全一致；状态迁移集中校验并使用 version 或条件更新防并发覆盖。
9. JSONB 使用 Jackson/TypeHandler 结构化映射，不手工拼接 JSON；动态 SQL 和 Milvus filter 不直接拼接未验证的用户输入。
10. API Key、加密主密钥、解密后的 Header、对象存储密钥和原始文件内容不得出现在日志、异常、toString 或响应中。
11. 文件处理必须流式限制大小，关闭 InputStream；不能无上限把上传文件、PDF 或 DOCX 全部读入内存。
12. 外部调用必须配置连接、读取和总超时，并把供应商错误映射为稳定 ErrorCode；日志只保留 providerId、requestId 等非敏感诊断字段。
13. 默认 `mvn clean verify` 不启动 Docker；需要真实 PostgreSQL、Redis、MinIO、Milvus 的测试放入 Failsafe `docker-it` Profile。
14. 只有测试真实通过后才能勾选 TEST_PLAN.md；不得改弱断言、删除既有测试或伪造测试数量。
15. 每条提示词完成后更新 FILE_GUIDE.md；架构、接口、配置或运行流程变化时同步 README.md 和 docs/architecture.md。

完成后的固定报告格式：

- 先列出按严重程度发现并修复的问题。
- 逐个列出新增/修改文件及其作用。
- 列出执行过的完整测试命令、用例数量和结果。
- 说明数据库迁移、配置项、接口和状态变化。
- 列出仍未完成的范围和真实剩余风险。
```

## 3. 提示词九：模型供应商配置与密钥加密

```text
请实现 KnowAgent 的模型供应商配置基础能力。本任务只完成 model_providers 的领域、持久化、加密和管理接口，不调用真实 Chat/Embedding/Rerank 模型。

模块边界：

- 领域、应用端口、应用服务和供应商持久化适配放在 knowagent-model。
- HTTP DTO 和 Controller 放在 knowagent-api。
- 加密实现通过端口隔离，不把加密 SDK 类型暴露给领域层。
- 复用 V5__model_providers.sql，不修改 V1-V11。

接口：

- POST /api/v1/model-providers
- GET /api/v1/model-providers
- GET /api/v1/model-providers/{id}
- PATCH /api/v1/model-providers/{id}
- DELETE /api/v1/model-providers/{id}
- POST /api/v1/model-providers/{id}/health-check 暂时只实现适配器配置校验或明确返回“未接入”，不要伪造健康结果。

必须实现：

1. 建立 ModelProvider 领域模型、状态/能力值对象、Persistence Object、Mapper、Repository 和 Converter。
2. provider_key 规范化为小写并遵守数据库正则；租户内活动 provider_key 唯一。
3. 支持 adapterType=OPENAI_COMPATIBLE，维护 baseUrl、embeddingBaseUrl、rerankBaseUrl、capabilities、enabledModels 和 publicConfig。
4. API Key 与自定义 Header 使用 AES-256-GCM 等具认证能力的加密；每次加密使用随机 nonce，密文携带可解析信封与 keyVersion。
5. 主密钥只从环境变量/外部配置读取；没有密钥但请求写入秘密时拒绝启动或拒绝操作，不能退化为明文。
6. 更新时未提交 secret 表示保留旧值；显式清除需要独立布尔命令，不能用空字符串产生歧义。
7. 列表和详情只返回 hasSecret、capabilities、模型和公开配置，不返回 ciphertext、解密值、secretKeyVersion 或内部 Header。
8. 删除使用软删除和乐观锁；被活动知识库引用时返回 409，不能依赖数据库异常作为正常控制流。
9. 新增并集中定义 MODEL_PROVIDER_READ、MODEL_PROVIDER_WRITE 权限，ADMIN 角色获得这些权限；Controller 使用 @PreAuthorize。
10. 自定义分页/统计/更新 SQL 显式包含 tenant_id，跨租户 ID 返回 404。

测试：

- 密钥加密后不包含明文，相同明文两次密文不同，正确密钥可解密，错误 keyVersion 或篡改密文失败。
- DTO、命令、领域对象、异常和日志字符串全部隐藏秘密。
- tenant-A 无法读取、更新或删除 tenant-B provider。
- 软删除后 tenant 内 provider_key 可复用。
- 缺少权限返回 403，具备权限的管理员可 CRUD。
- PostgreSQL Testcontainers 验证 JSONB、乐观锁、部分唯一索引和跨租户复合关系。

不要实现模型调用、Embedding、Milvus 或知识库接口。完成后更新 README.md、docs/architecture.md、FILE_GUIDE.md 和对应测试清单。
```

## 4. 提示词十：知识库领域、持久化与 CRUD

```text
请实现 knowledge_bases 的完整最小 CRUD，先建立稳定的知识库边界，不实现文件上传、解析、Embedding 或检索。

接口：

- POST /api/v1/knowledge-bases
- GET /api/v1/knowledge-bases
- GET /api/v1/knowledge-bases/{id}
- PATCH /api/v1/knowledge-bases/{id}
- DELETE /api/v1/knowledge-bases/{id}

实现要求：

1. 在 knowagent-knowledge 建立 KnowledgeBase 领域模型、状态、ChunkPolicy、RetrievalConfig、Repository、应用服务和持久化适配器。
2. ChunkPolicy 以现有 ChunkPolicy 端口为基础，至少支持 RECURSIVE、MARKDOWN_HEADING、TOKEN_WINDOW；校验 chunkSize > 0、overlap >= 0 且 overlap < chunkSize。
3. RetrievalConfig 使用类型安全 DTO/值对象，至少包含 topK、scoreThreshold、是否启用 rerank；限制 topK 和阈值范围。
4. 创建和更新知识库时校验 embedding/rerank provider 属于当前租户、未删除、enabled，且声明对应能力；provider/model 必须成对出现。
5. 列表支持受控分页、name/slug 过滤和 status 精确过滤；数据与 count SQL 使用相同显式 tenant 条件。
6. slug 规范化并按 V6 约束验证；HTTP 响应不暴露 Persistence Object 或未定义 JSONB。
7. 更新和状态切换使用 version 乐观锁；ACTIVE、DISABLED、DELETING、DELETED 的合法转换集中定义。
8. 本任务中的 DELETE 只允许删除没有未删除文件的知识库，执行软删除并置 DELETED；存在文件时返回 409，完整级联删除留到提示词十九。
9. 集中定义 KNOWLEDGE_BASE_READ、KNOWLEDGE_BASE_WRITE 权限，并加入 ADMIN_ROLE_PERMISSIONS；所有端点使用方法级鉴权。
10. 所有 ID 查询使用 tenant_id + id；跨租户资源统一 404。

测试：

- 创建、分页、详情、修改、禁用、空知识库删除主流程。
- 非法 slug、chunk policy、retrieval config、provider 能力和 provider/model 半配置返回稳定 400/409。
- 乐观锁冲突返回 409，不覆盖后来者数据。
- tenant-A 不能枚举 tenant-B 知识库或绑定 tenant-B provider。
- 软删除后 slug 可复用；存在活动文件时不能删除。
- 权限链路覆盖匿名 401、无权限 403、有权限成功。

不要实现文件表 Mapper、MinIO、Task、Worker 或向量库。完成后更新接口文档、FILE_GUIDE.md 和架构说明。
```

## 5. 提示词十一：Task、Outbox 与 Inbox 持久化基础

```text
请实现知识库异步入库所需的 Task、Outbox 和 Inbox 持久化基础，但本任务不连接 Redis、不启动 Worker、不处理文件。

模块建议：

- Task/Outbox/Inbox 的领域、端口和持久化适配优先放在 knowagent-observability。
- 知识库模块只依赖这些应用端口，不直接调用其 Mapper。
- 若现有模块依赖方向会形成环，先说明并使用最小端口拆分解决，不把所有实现塞入 knowagent-api。

必须实现：

1. 建立 Task、OutboxEvent、InboxEvent 的领域模型、状态枚举、Persistence Object、Mapper、Repository/Store 和 Converter。
2. Task 状态严格对应 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED；Outbox 对应 PENDING/PROCESSING/PUBLISHED/DEAD_LETTER。
3. 提供“业务数据 + Task + Outbox”同事务写入的应用边界，调用方可在一个 Spring 事务中提交。
4. Outbox claim 使用 PostgreSQL `FOR UPDATE SKIP LOCKED`，按 next_retry_at、created_at 排序，设置 locked_by/locked_until 和 PROCESSING。
5. 发布成功使用条件更新置 PUBLISHED；失败增加 retry_count、计算退避时间，达到上限置 DEAD_LETTER；所有更新带 version/当前状态守卫。
6. Task claim/状态更新同样使用显式 tenant_id、锁租约和条件更新；过期租约可回收。
7. Inbox 依赖 `(consumer_name, event_id)` 唯一约束实现幂等，重复事件返回“已处理”而不是 500。
8. payload/headers/result 使用结构化 JSONB TypeHandler；错误消息截断并清理秘密和原始文件内容。
9. PostgreSQL 是事实来源，不能把 Redis 当作唯一任务状态。
10. 提供 `GET /api/v1/tasks/{id}` 的查询应用服务和 HTTP 接口；只允许当前租户读取，权限使用 TASK_READ 或与知识库读权限组合的明确规则。

测试：

- 业务记录、Task、Outbox 任一步失败时整个事务回滚。
- 两个 publisher 并发 claim 时不会拿到同一事件。
- 租约过期后可重新 claim；未过期不能被抢占。
- 重复 inbox event 只执行业务一次。
- 重试计数、退避、DEAD_LETTER 和乐观锁行为正确。
- tenant-A 不能 claim、查询或更新 tenant-B 记录。
- 默认构建不启动 Docker，PostgreSQL 并发测试放入 docker-it。

不要连接 Redis，不要实现上传和解析。完成后同步 ADR-0002 的实现约束、docs/architecture.md、FILE_GUIDE.md 和 TEST_PLAN.md。
```

## 6. 提示词十二：MinIO 适配器与文件上传事务

```text
请实现 MinIO 对象存储适配器和知识库文件上传接口，提交成功后只进入 QUEUED，不在 HTTP 线程中解析文档。

接口：

- POST /api/v1/knowledge-bases/{knowledgeBaseId}/files，multipart/form-data，返回 202。
- GET /api/v1/knowledge-bases/{knowledgeBaseId}/files
- GET /api/v1/knowledge-bases/{knowledgeBaseId}/files/{fileId}
- GET /api/v1/knowledge-bases/{knowledgeBaseId}/files/{fileId}/content，可选；如实现必须流式下载并鉴权。

对象存储要求：

1. 在 knowagent-workspace 实现现有 ObjectStorageGateway 的 MinIO 适配器；使用固定配置 Bucket，应用启动时幂等确保 Bucket 存在。
2. 上传使用 MinIO Java SDK 的流式 putObject；读取返回可关闭流，stat/read/delete 都映射稳定异常。
3. object key 只由服务端生成，格式固定为 `tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/files/{fileId}/source` 或等价的确定性安全格式。
4. Gateway 的 put/get/delete 命令必须同时携带 tenantId 和 ObjectKey；适配器再次验证 object key 前缀，防止调用方越权。
5. 删除不存在的对象视为幂等成功；所有 InputStream 必须关闭。

上传事务：

1. 校验知识库属于当前租户且 ACTIVE，校验 KNOWLEDGE_FILE_WRITE 权限。
2. 文件 ID、Task ID、Outbox ID 在 Java 中预生成。
3. 限制请求大小、文件非空、允许的 MIME；使用 Apache Tika 或可信内容检测识别类型，不能只信任文件名和 Content-Type Header。
4. 流式计算 SHA-256 和大小；文件名只作为 display/original metadata，不能参与物理路径。
5. MinIO 上传成功后，在一个数据库事务中写 knowledge_files、PENDING Task 和 Outbox；文件状态从 UPLOADED/QUEUED 按集中状态机转换。
6. 数据库事务失败时立即补偿删除已上传对象；补偿删除失败必须记录可告警的稳定错误，不得伪报上传成功。
7. `Idempotency-Key` 映射 upload_idempotency_key：同租户、同知识库、同 key 且同文件哈希返回原 file/task；同 key 不同内容返回 409。
8. 列表和详情只读取当前租户；响应不暴露 Bucket、object_key、内部错误堆栈或 MinIO 凭据。
9. 集中定义 KNOWLEDGE_FILE_READ、KNOWLEDGE_FILE_WRITE 权限并授予 ADMIN。

测试：

- tenant-A object key 固定含 tenant-A 前缀，tenant-B 无法 stat/read/delete。
- TXT/PDF/DOCX 合法类型可上传；空文件、超限文件、伪造 MIME、未知类型被稳定拒绝。
- MinIO 成功而数据库事务失败时对象被补偿删除。
- 同幂等键同内容不产生第二个文件、Task 或 Outbox；不同内容返回 409。
- 列表、详情、下载覆盖 401、403、跨租户 404。
- 使用真实 MinIO Testcontainers/Compose 测试 put/stat/get/delete，不只测试 mock。

不要在 Controller 中解析文件，不要调用 Embedding 或 Milvus。完成后更新配置示例、README.md、架构上传时序、FILE_GUIDE.md 和 TEST_PLAN.md。
```

## 7. 提示词十三：解析器注册表与 TXT/PDF/DOCX 解析

```text
请实现 DocumentParser 注册表和第一批本地文档解析器。本任务只做“对象流 -> ParsedDocument”，不写 chunk、不调模型、不启动 Redis consumer。

实现要求：

1. 复用并完善 knowagent-knowledge 现有 DocumentParser、ParseSource、ParsedDocument、ParsedSection，不为每个供应商复制一套模型。
2. 实现 ParserRegistry：按经过检测的 MIME 选择唯一解析器；无支持解析器时抛稳定 UNSUPPORTED_DOCUMENT_TYPE。
3. 至少实现 UTF-8/可检测编码的 TXT/Markdown、PDF、DOCX。
4. PDF 使用 PDFBox，DOCX 使用 Apache POI，类型检测使用 Tika；只引入完成任务所需的最小依赖并锁定受父 POM 管理或明确版本。
5. ParsedSection 保留 sectionPath、pageNumber、字符范围和 metadata；Markdown 标题层级、PDF 页码、DOCX 标题样式尽可能保留。
6. 解析限制使用配置属性：最大字节数、最大页数、最大解压后大小、最大文本字符数、超时；拒绝 zip bomb、损坏文件和空文本。
7. 解析器不得自行从任意 URL 下载文件；只接受服务端 ObjectStorageGateway 提供的受控流和元数据。
8. 异常转换为稳定错误码，错误消息不包含原文、对象键、文件系统路径或第三方堆栈。
9. MinerU/PaddleX OCR 保持 EXTERNAL_SERVICE/后续范围；扫描 PDF 无文本时返回可重试或“需要 OCR”的明确结果，不伪造文本。

测试：

- 使用小型测试夹具验证 TXT、Markdown、分页 PDF、带 Heading 的 DOCX。
- 页码、标题路径、文本顺序和字符范围正确。
- 空文件、损坏 PDF/DOCX、超限页数、超限解压大小、未知 MIME 返回稳定错误。
- InputStream 在成功和异常路径都被关闭。
- ParserRegistry 对同一类型选择确定且无线程安全问题。

不要实现文件状态推进、数据库 chunk、Embedding 或向量库。完成后更新 FILE_GUIDE.md，并在架构文档记录本地解析与外部 OCR 的边界。
```

## 8. 提示词十四：确定性分块与 Chunk 持久化

```text
请实现确定性文本分块和 knowledge_chunks 持久化。本任务输入 ParsedDocument，输出并保存 chunk，但不生成向量。

实现要求：

1. 完善现有 Chunker、ChunkPolicy、ChunkDraft，至少实现 RECURSIVE、MARKDOWN_HEADING、TOKEN_WINDOW 三种策略。
2. 统一 TokenCounter 端口；在未接入供应商 tokenizer 前使用经过测试的确定性估算实现，并在 metadata 标记算法版本，不能把字符数冒充精确 token 数。
3. chunkSize 和 overlap 以策略定义的单位执行，保证 overlap < chunkSize；禁止空 chunk 和无限循环。
4. 尽量在段落、句子、换行边界切分；不能切断 Unicode 代理对；超长无分隔文本必须安全退化。
5. 每个 ChunkDraft 生成稳定 chunkIndex、contentHash、tokenCount、字符/Token offset、pageNumber、sectionPath 和 metadata。
6. 建立 KnowledgeChunk 领域模型、Persistence Object、Mapper、Repository 和 Converter；UUID 在 Java 预生成。
7. 同一文件分块写入放在事务中并锁定 tenant_id + file_id；重试采用“替换该文件当前 chunk 集合”或等价幂等策略，不能产生重复 chunkIndex。
8. chunk 写入初始 index_status=PENDING；更新 knowledge_files 的 chunk_count、token_count 和 version 使用条件更新。
9. 任何删除、替换、查询 SQL 都显式包含 tenant_id 和 knowledge_base_id/file_id，不能只依赖裸 file UUID。
10. JSONB section_path/metadata 使用结构化 TypeHandler。

测试：

- 三种策略的边界、重叠、空白、中文、英文、Emoji 和超长单词用例。
- 页码和标题路径从 ParsedSection 传递到所有相关 chunk。
- 相同输入和策略重复运行产生相同顺序、内容和哈希。
- 同一文件任务重试后数据库没有重复 chunk，chunk_count 与实际行数一致。
- 分块事务失败时旧数据/新数据不会处于半替换状态。
- tenant-A 不能查询、替换或删除 tenant-B chunk。

不要调用 EmbeddingGateway 或 Milvus。完成后更新 FILE_GUIDE.md、TEST_PLAN.md 和分块策略说明。
```

## 9. 提示词十五：EmbeddingGateway 与批处理适配器

```text
请实现 OpenAI-compatible EmbeddingGateway，并完成批处理、超时、维度和错误契约。本任务不写 Milvus，也不启动完整文件 Worker。

实现要求：

1. 保持 knowagent-model 现有 EmbeddingGateway 为核心端口；必要时扩展请求/响应值对象，但不把 Spring AI 或供应商 DTO 暴露给 knowledge 模块。
2. 使用 Spring AI 1.1.8 EmbeddingModel/EmbeddingRequest/EmbeddingResponse 能力完成适配；不得手写与 Spring AI 重复的协议客户端，除非 OpenAI-compatible 差异有测试证据。
3. 根据当前 tenant 的 ModelProvider 配置构造或缓存模型客户端；缓存键至少包含 tenantId、providerId、configVersion，配置更新后旧客户端必须失效。
4. 解密 secret/header 只存在于调用边界内，不进入日志、异常、缓存 key 或业务对象。
5. 实现 BatchPlanner，同时遵守最大文本条数、估算 token 总量和供应商请求体限制；空输入直接拒绝。
6. 验证返回向量数量与输入一致、顺序一致、每个向量非空、有限数值且维度一致；维度与 Milvus collection 配置不一致时在索引前失败。
7. 配置连接超时、读取超时、总超时和有限重试；只对 429、明确 5xx/网络暂态错误重试，4xx 配置错误不重试。
8. 错误映射为稳定 MODEL_AUTH_FAILED、MODEL_RATE_LIMITED、MODEL_TIMEOUT、MODEL_BAD_RESPONSE 等 ErrorCode；不返回供应商原始敏感正文。
9. 添加调用计时、provider/model、批次数和 token 估算等非敏感指标；不得记录 chunk 原文或 embedding 数组。

测试：

- WireMock 覆盖正常多批、顺序保持、429 后重试、401 不重试、超时、响应数量错误、维度错误、NaN/Infinity。
- 验证 BatchPlanner 同时遵守 batch size 和 token 限制。
- provider 更新 configVersion 后客户端缓存失效。
- tenant-A 不能使用 tenant-B provider；日志和异常不含 API Key、Header 和原文。
- Spring 上下文只装配明确选择的 Embedding adapter，不因多个 provider Bean 冲突启动失败。

不要实现 ChatModelGateway、Rerank、Milvus 或 Agent。完成后更新模型配置文档、FILE_GUIDE.md 和 TEST_PLAN.md。
```

## 10. 提示词十六：Milvus 向量存储适配器

```text
请实现 knowagent-knowledge 的 Milvus VectorStoreGateway 适配器，完成 collection 初始化、批量 upsert、过滤检索和按文件幂等删除。

集合契约：

- 主键 `id` 使用 VARCHAR 保存 PostgreSQL chunk UUID 字符串，autoID=false。
- `embedding` 使用 FLOAT_VECTOR，维度来自已验证配置。
- 标量字段至少包含 tenant_id、knowledge_base_id、file_id、chunk_id、embedding_model_spec。
- 相似度统一使用 COSINE；索引类型和参数通过配置固定并在架构文档说明。
- PostgreSQL 保存 chunk 正文和元数据，是引用详情事实来源；Milvus 只保存检索所需标量和向量。

实现要求：

1. 适配现有 VectorStoreGateway、VectorChunk、VectorQuery、VectorHit，不把 Milvus SDK 类型暴露给应用层。
2. 使用 Milvus Java SDK V2 API；启动时幂等检查/创建 collection、索引并 load，已有集合的 schema/维度不匹配时拒绝启动，不能自动 drop 生产集合。
3. upsert 前验证批次非空、UUID/租户/知识库/文件关系一致、向量维度和数值合法；Milvus entity id 必须等于 chunkId。
4. 搜索 filter 由受控构造器生成，始终包含 tenant_id 和 knowledge_base_id；可选 file_id 列表逐个按 UUID 校验后转义，不能拼接任意用户表达式。
5. deleteByFile 始终包含 tenant_id、knowledge_base_id、file_id；对象不存在视为成功。
6. 搜索结果只返回 id、score 和必要标量；随后由 PostgreSQL 按 tenant + chunk IDs 回查内容。
7. 设置连接、搜索和写入超时；错误映射为 VECTOR_UNAVAILABLE、VECTOR_SCHEMA_MISMATCH、VECTOR_BAD_RESPONSE 等稳定错误。
8. 指标记录集合、操作、数量和耗时，不记录向量内容或 chunk 文本。

测试：

- 单元测试覆盖 filter 转义、维度检查、UUID 映射和错误转换。
- 真实 Milvus 2.5.x 集成测试覆盖建集合、load、upsert、COSINE 搜索、按 tenant/kb/file 过滤和删除。
- tenant-A 查询即使使用 tenant-B chunkId/fileId 也得不到结果。
- PostgreSQL chunk UUID 与 Milvus 主键逐项一致。
- 重复 upsert 不产生重复实体，重复 delete 成功。
- schema 或 dimension 不兼容时启动/索引明确失败，不删除已有数据。

不要实现 Redis worker、Rerank 或问答生成。完成后更新 ADR-0005、docs/architecture.md、FILE_GUIDE.md 和 TEST_PLAN.md。
```

## 11. 提示词十七：Outbox 发布、Redis Streams 与入库 Worker

```text
请把上传后的 QUEUED 文件串成完整异步入库链：PostgreSQL Outbox -> Redis Streams -> Worker -> 解析 -> 分块 -> Embedding -> Milvus。

发布端：

1. OutboxPublisher 周期性使用 `FOR UPDATE SKIP LOCKED` 小批 claim 可发布事件。
2. 事件信封至少包含 eventId、eventType、tenantId、aggregateId、occurredAt、schemaVersion 和 payload；不包含秘密、原始文件或任意对象路径。
3. 发布到固定 Redis Stream 后才条件更新 PostgreSQL 为 PUBLISHED；崩溃窗口允许重复投递，由 Inbox 消费幂等解决。
4. 发布失败按 V9 字段更新退避、租约和 DEAD_LETTER；多个 API 实例不能重复 claim 同一事件。

Worker：

1. 使用 Redis Streams consumer group 消费，成功完成数据库事务后 ACK；支持 pending reclaim 和消费者崩溃恢复。
2. 收到事件先校验 schemaVersion、eventType 和 tenantId，然后在 try/finally 中建立/清理可信 Worker TenantContext。
3. 使用 Inbox `(consumer_name,event_id)` 保证业务只执行一次；不能在业务完成前写“已处理”。
4. 文件入库状态严格推进：QUEUED -> PARSING -> CHUNKING -> EMBEDDING -> INDEXING -> READY；失败进入 FAILED，并写稳定 errorCode、截断 errorMessage、retryable。
5. 对 tenant_id + file_id 使用数据库锁/条件更新，确保同一文件同时只有一个有效执行者。
6. 从 MinIO 读取受控对象流，调用 ParserRegistry、Chunker、EmbeddingGateway、VectorStoreGateway；Embedding 按提示词十五批处理。
7. 重试前幂等清理/覆盖该文件旧向量与 chunk，保证不产生重复 chunk；PG chunk UUID 与 Milvus ID 始终一致。
8. Task stage/progress 与文件状态同步更新；PostgreSQL 是 UI 查询的事实来源，Redis 只负责投递。
9. 对模型限流、暂态网络错误、Milvus/MinIO 短暂不可用进行有界退避；格式损坏、配置非法等永久错误不自动重试。
10. 外部调用无法纳入数据库事务，明确使用幂等操作和补偿实现最终一致，禁止声明“全链路强事务”。

测试：

- Outbox publisher 崩溃在“Redis 已写、PG 未标记”后重启，业务最终只执行一次。
- 两个 Worker 并发收到同一事件不重复 chunk/向量。
- pending 消息在消费者死亡后被其他消费者 reclaim。
- TXT/PDF/DOCX 从 MinIO 到 READY 的完整状态和 Task progress 正确。
- 解析、Embedding、Milvus 分别失败时状态、retryable 和重试次数正确。
- Worker 处理 tenant-A 后处理 tenant-B 不残留 TenantContext。
- 真实 PostgreSQL、Redis、MinIO、Milvus 集成测试放入 docker-it。

不要实现检索 API、RAG 生成、SSE 或 Agent Runtime。完成后更新 ADR-0002、架构入库时序、运行配置、README.md、FILE_GUIDE.md 和 TEST_PLAN.md。
```

## 12. 提示词十八：语义检索、可选 Rerank 与引用

```text
请实现知识库语义检索接口，返回相关 chunk 和可验证引用；本任务不调用 ChatModelGateway，不生成自然语言答案。

接口：

- POST /api/v1/knowledge-bases/{knowledgeBaseId}/retrieval

请求至少包含 query、topK、scoreThreshold 和可选 fileIds；tenantId 不得出现在可覆盖业务上下文的位置。topK/threshold 未传时使用知识库 RetrievalConfig。

检索链路：

1. 校验 KNOWLEDGE_RETRIEVE 权限、知识库属于当前租户且 ACTIVE，并验证 Embedding provider/model 可用。
2. 使用 EmbeddingGateway 生成单个查询向量。
3. 调用 VectorStoreGateway，强制 filter tenant_id + knowledge_base_id；fileIds 必须逐个验证属于同一租户和知识库且状态 READY。
4. Milvus 只返回候选 chunkId/score；应用层按 tenant_id + knowledge_base_id + chunkIds 批量回查 PostgreSQL。
5. 丢弃数据库不存在、index_status 非 READY、文件非 READY 或租户/知识库不匹配的命中，不能信任 Milvus 标量替代数据库权限校验。
6. 保持 Milvus 排名顺序；应用 scoreThreshold 和受控 topK。
7. 若知识库配置 rerank 且已有可用 RerankGateway，则对候选执行 rerank；尚未实现时必须明确拒绝该配置或关闭，不得伪造分数。
8. 响应引用至少包含 chunkId、fileId、displayName、contentExcerpt/content、pageNumber、sectionPath、score、rank；不返回 object_key、供应商配置或内部 metadata。
9. 查询、原文和向量默认不写日志；指标只记录 tenant/provider 的非敏感标识、候选数和耗时。
10. 统一映射空结果、模型超时、Milvus 不可用和配置错误；空结果不是 500。

测试：

- query -> embedding -> Milvus -> PostgreSQL -> citation 的顺序与数据映射正确。
- tenant-A 永远不能检索 tenant-B chunk，即使伪造 fileIds/chunkIds。
- 只返回 READY 文件和 READY chunk；删除中、失败和未完成数据不参与检索。
- topK、threshold、排序、空结果、重复 Milvus 命中和缺失 PG chunk 行为稳定。
- 引用页码、章节路径、文件名和 chunk 内容与 PostgreSQL 一致。
- 真实 Milvus 集成测试验证 tenant + knowledge_base 过滤，不只断言 mock 收到字符串。

不要实现 RAG Prompt、Chat、Conversation、SSE 或 Agent。完成后更新接口说明、架构检索链路、FILE_GUIDE.md 和 TEST_PLAN.md。
```

## 13. 提示词十九：删除最终一致、任务重试与运维接口

```text
请完成知识库文件生命周期收尾：异步删除、失败任务重试、取消边界和可运维状态查询。

接口：

- DELETE /api/v1/knowledge-bases/{knowledgeBaseId}/files/{fileId}，返回 202 和删除 Task。
- POST /api/v1/tasks/{taskId}/retry
- POST /api/v1/tasks/{taskId}/cancel，仅在尚未执行或当前阶段可安全取消时允许。
- GET /api/v1/tasks/{taskId}
- GET /api/v1/tasks，可选，若实现必须分页且按 tenant 过滤。

删除流程：

1. HTTP 事务锁定 tenant_id + knowledgeBaseId + fileId，把可删除文件置 DELETING，创建 Task 和 Outbox 后提交。
2. 一旦 DELETING，检索接口立即停止返回该文件；不能等外部数据删完才隔离。
3. Worker 幂等执行：删除 Milvus 中该 tenant/kb/file 向量 -> 删除 MinIO 对象 -> 物理删除 knowledge_chunks -> 文件置 DELETED 并设置 deleted_at -> Task SUCCEEDED。
4. Milvus/MinIO 返回 not found 视为成功；外部删除成功而数据库提交失败时，重试仍能最终完成。
5. 不能先硬删除 knowledge_files，否则失去重试和审计锚点。
6. 删除失败时文件保持 DELETING 或进入可明确重试的 FAILED 规则，状态机与检索过滤必须一致。

重试与取消：

1. 只允许 retryable=true、FAILED、未超过 maxAttempts 的 Task 重试；用 version/条件更新防止双重重试。
2. 重试创建新的 Outbox 事件或恢复原任务时必须保持明确幂等键；并发点击最多产生一次有效投递。
3. RUNNING 且已进入不可逆外部写阶段的任务不能承诺立即取消；使用 cancel_requested_at 在安全检查点终止。
4. CANCELLED、SUCCEEDED 和不可重试 FAILED 任务重复操作返回稳定 409 或幂等结果，文档固定语义。
5. 所有 Task 查询、更新、claim SQL 显式 tenant_id；跨租户 taskId/fileId 返回 404。

测试：

- 文件删除后 PostgreSQL、MinIO、Milvus 最终一致且引用不再出现。
- 外部对象已不存在、向量已不存在、数据库提交失败重试等路径最终收敛。
- 两次并发删除/重试只产生一个有效任务或事件。
- 不可重试、超次数、执行中和已成功任务的 retry/cancel 行为稳定。
- tenant-A 不能观察或操作 tenant-B Task/File。
- 失败消息不包含对象键、文件原文、API Key 或供应商响应正文。

完成后更新文件删除时序、Task 状态说明、README.md、FILE_GUIDE.md 和 TEST_PLAN.md。
```

## 14. 提示词二十：知识库 RAG 里程碑集成验收

```text
请对“知识库 RAG”里程碑进行完整收尾，不开始 Agent Runtime、聊天生成或 SSE。

第一部分：代码和安全审查

1. 检查 Controller 是否调用 Mapper，模块依赖是否形成环，供应商 SDK 是否泄露到领域层。
2. 检查所有自定义 SQL、锁、统计、批量更新、Worker 查询是否显式 tenant_id。
3. 检查所有 MinIO object key 和 Milvus filter 是否强制 tenant/kb/file 边界。
4. 检查 API Key、密文、Header、对象键、原始文档、chunk 原文和向量是否进入日志、异常或 toString。
5. 检查上传事务、Outbox 投递、Inbox 幂等、Worker 重试和删除补偿是否与文档一致。
6. 检查状态转换、乐观锁、Task 租约、Outbox 租约和 Redis pending reclaim 的并发行为。
7. 检查输入流、模型客户端和 SDK 资源在所有路径被关闭。

第二部分：自动化验收

1. 默认 `mvn clean verify` 必须通过且不依赖 Docker。
2. `mvn -Pdocker-it verify` 使用真实 PostgreSQL 16、Redis 7、MinIO 和 Milvus 2.5.x。
3. 至少完成以下 E2E：
   - 登录 tenant-A 管理员。
   - 创建模型供应商和知识库。
   - 上传 TXT、PDF、DOCX，观察 Task 到 READY。
   - 分别检索三个文档并校验 citation。
   - 重复投递/失败重试不产生重复 chunk 或 Milvus entity。
   - 删除文件后 PostgreSQL、MinIO、Milvus 最终一致。
4. 建立 tenant-B，使用 tenant-A 的 providerId、knowledgeBaseId、fileId、taskId、chunkId 逐项枚举，必须全部失败且不泄露存在性。
5. 使用 WireMock 覆盖 Embedding 429、401、超时、坏响应和维度错误。
6. 覆盖 MinIO 上传成功但数据库回滚、Redis 重复投递、Worker 崩溃 reclaim、Milvus 删除已完成后数据库提交失败等故障注入。
7. 逐项核对 TEST_PLAN.md 第 4 节；只有真实通过的条目才勾选。

第三部分：文档和演示

1. README.md 写清本阶段完成范围、启动依赖、配置变量和演示步骤，不写真实凭据。
2. docs/architecture.md 与真实代码同步上传、入库、检索和删除时序。
3. FILE_GUIDE.md 覆盖所有新增手写文件。
4. TEST_PLAN.md 记录实际测试证据；如有未通过项保持未勾选并解释。
5. 给出从登录到上传、查询任务、检索、删除的 curl/HTTP 演示顺序，Token 和密钥只使用占位符。

最终报告：

- 发现项按 P0/P1/P2 排序，先修复再给结论。
- 逐文件说明新增/修改内容。
- 报告单元测试与每组 Docker IT 的真实数量和结果。
- 列出接口、状态机、配置和数据流最终清单。
- 明确本阶段未实现：Chat/RAG 生成、Conversation、Agent Request/Run、SSE、Tools/Skills/MCP、OCR 外部服务和知识图谱。
```

## 15. 本阶段完成定义

全部提示词完成后，应满足：

- 管理员可以配置加密存储的 OpenAI-compatible 模型供应商。
- 可以创建、分页查询、修改、禁用和删除知识库，所有操作按租户隔离并受 RBAC 控制。
- 可以把 TXT、PDF、DOCX 上传到 MinIO，HTTP 请求只创建 File、Task 和 Outbox，不同步解析。
- Redis Streams Worker 可以幂等完成解析、分块、Embedding 和 Milvus 索引。
- 同一文件重复任务不会产生重复 chunk，PostgreSQL chunk UUID 与 Milvus entity ID 一致。
- 检索始终过滤 tenant_id 和 knowledge_base_id，只返回 PostgreSQL 验证后的 READY chunk 与引用。
- 失败任务可以安全重试，重复 Outbox/Redis 消息只产生一次业务效果。
- 文件删除在 PostgreSQL、MinIO、Milvus 间最终一致，删除中的文件立即退出检索。
- 默认 Maven 构建不需要 Docker，`docker-it` 覆盖真实 PostgreSQL、Redis、MinIO、Milvus 和故障恢复。
- README、架构、TEST_PLAN 和 FILE_GUIDE 与实现一致。

完成这一里程碑后，再生成并执行“Agent 配置、Conversation、Request/Run、Outbox Worker 与双阶段 SSE”提示词。
