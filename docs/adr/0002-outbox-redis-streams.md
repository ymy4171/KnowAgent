# ADR 0002：采用 PostgreSQL Outbox 与 Redis Streams

- 状态：已接受
- 日期：2026-08-05
- 作者：KnowAgent 项目组
- 评审人：项目维护者

## 上下文

文件解析和 Agent Run 需要异步执行、消费者组竞争、失败重试和短期事件回放，同时业务状态与任务投递必须避免双写不一致。PostgreSQL 是最终状态来源，Redis 已用于缓存和运行事件。

## 决策驱动因素

- 业务事务与待发布事件必须原子提交。
- 支持 Worker 消费组、ACK、pending reclaim 和幂等重试。
- 支持 Run 事件短期回放和 SSE `Last-Event-ID`。
- 降低面试项目的基础设施数量和运维成本。

## 备选方案

1. 数据库轮询任务表：一致性简单，但实时性和并发消费能力有限。
2. RabbitMQ：路由和确认成熟，但事件回放与按游标读取需要额外存储。
3. Kafka：持久化和回放最强，但本地资源、运维和主题治理成本高于当前需求。
4. 云 SQS：托管成本低，但本地演示依赖云账号，且不适合作为 SSE 事件回放源。
5. Redis Streams：已有 Redis 基础设施，具备消费者组、游标和 pending 管理，但持久化和长期保留弱于 Kafka。

## 决策

业务事务同时写入 PostgreSQL 状态和 Outbox。独立 Publisher 将未发送事件发布到 Redis Streams，成功后标记 Outbox。Worker 使用消费者组幂等处理并 ACK。PostgreSQL 保存最终状态，Redis 只保存投递状态和短期运行事件。

## 后果

正向：解决数据库提交与消息发送的崩溃窗口；基础设施较少；事件游标可直接服务 SSE 重连。

负向：Redis AOF 仍可能丢失最后少量事件；消费者组 pending 需要主动回收；Outbox 会产生额外写放大和清理任务。

风险控制：开启 AOF；所有消费者使用业务幂等键；设置事件保留窗口；Redis 回放缺失时回退 PostgreSQL；监控 Outbox backlog 和 pending 数量。

## 实现约束（2026-08-18 更新）

`knowagent-observability` 提供 V9 Task/Outbox/Inbox 端口与持久化，`knowagent-worker` 已接通固定 Redis Stream 的发布和消费，`knowagent-knowledge` 执行文件入库 saga。以下约束已经由单元测试和真实 PostgreSQL 16、Redis 7、MinIO、Milvus 2.5.6 集成测试锁定：

- **事实来源与提交顺序**：上传事务原子写 `knowledge_files(QUEUED)`、PENDING Task 和 PENDING Outbox。Publisher 先对 Redis 执行 XADD，成功后才以 `status + version` 条件更新 PostgreSQL 为 PUBLISHED；Redis 只负责至少一次投递，UI 只查询 PostgreSQL 的 File/Task 状态。
- **竞争发布与失败**：Publisher 小批调用 `FOR UPDATE SKIP LOCKED` claim，同时回收租约过期的 PROCESSING 事件。不同 API/Worker 实例用 `locked_by/locked_until` 和 version 串行化；Redis 写失败按 V9 的 `retry_count/max_retries/next_retry_at` 有界退避，预算耗尽进入 DEAD_LETTER。claim 是唯一允许跨租户扫描的 SQL，事件后续操作仍显式携带 tenant_id。
- **允许重复投递**：XADD 成功、PG 标记前崩溃时，租约到期后事件会再次 XADD。此窗口不尝试伪造分布式事务，消费者依靠 Inbox `(consumer_name,event_id)` 唯一键收敛；真实集成测试证明 Stream 可出现两条记录而业务和 Inbox 最终各执行一次。
- **最小事件信封**：固定 schemaVersion=1 和 eventType=`knowledge_file.ingested`，字段仅为 `eventId/eventType/tenantId/aggregateId/occurredAt/schemaVersion/payload`，payload 仅允许 `file_id` 且必须等于 aggregateId。信封拒绝额外字段，所以不会承载 secret、原始文件、Bucket、object key 或任意物理路径。
- **Consumer group 与恢复**：Worker 使用 XREADGROUP 手动 ACK；只有完成数据库终态事务或确认 Inbox 已处理后才 ACK。消费者死亡留下的 PEL 通过 XPENDING 筛选空闲消息并以 XCLAIM 转移给存活消费者；暂态失败和仍被其他租约持有的任务不 ACK，等待 reclaim。
- **可信租户上下文**：消费者在创建 TenantContext 前先验证 schemaVersion、eventType、tenantId 和 payload；通过后由 `WorkerTenantScope` 先清理、再安装系统 principal，并在 finally 清理。无效信封在上下文外拒绝并 ACK，避免 poison message 无限循环。
- **幂等完成点**：Inbox 不在业务开始时写入。READY + Task SUCCEEDED + Inbox 插入位于同一个短 PostgreSQL 事务；永久失败或重试耗尽则 FAILED + Task FAILED + Inbox 同事务提交。重复消费者在文件行锁、Task 租约和 Inbox 唯一约束下只能有一个有效执行者。
- **状态与重试**：文件严格按 `QUEUED→PARSING→CHUNKING→EMBEDDING→INDEXING→READY` 推进；每段状态更新与 Task stage/progress 使用独立短事务。失败写稳定 errorCode、净化并截断的 errorMessage 和最终 retryable；模型限流/超时、网络、MinIO/Milvus 暂态不可用可退避重试，损坏格式、非法配置和错误响应永久失败。Task 的 `attempt_count < max_attempts` 约束决定最终一次不能回 PENDING。
- **外部系统的一致性**：MinIO 流、Parser、Chunker、Embedding 和 Milvus 调用不处于数据库事务。每次重试整集合替换 PG chunks，索引前按 tenant+knowledge_base+file 幂等删除旧向量，再用与 PG chunk UUID 相同的 Milvus 主键 upsert；因此实现的是可补偿的最终一致，不声明全链路强事务。
- **租户隔离**：Worker 文件锁和所有批量/状态 SQL 显式包含 tenant_id；chunk 和向量操作同时限定 tenant_id、knowledge_base_id、file_id。普通查询仍由租户插件 fail-closed 加固，跨租户资源不通过事件字段绕过应用边界。
- **HTTP 与范围**：现有上传和 Task 查询接口不变；本决策不新增 Worker HTTP 接口，也不实现检索 API、RAG 生成、SSE 或 Agent Runtime。
