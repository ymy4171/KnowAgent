# ADR 0005：采用 Milvus 作为向量数据库

- 状态：已接受
- 日期：2026-08-09
- 作者：KnowAgent 项目组
- 评审人：项目维护者

## 上下文

知识库检索需要保存大量 embedding，并按 `tenant_id`、`knowledge_base_id`、`file_id` 和 `chunk_id` 过滤。PostgreSQL 继续保存业务元数据，向量索引可以独立重建。

## 决策驱动因素

- 支持向量近邻检索和标量过滤。
- 支持批量写入、删除和索引调优。
- 向量规模可独立于业务数据库扩展。
- 与 Yuxi 原有 Milvus 行为和迁移经验接近。

## 备选方案

1. pgvector：部署简单、事务一致性好，但向量负载会与业务数据库竞争资源。
2. Elasticsearch/OpenSearch：文本和混合检索成熟，但向量能力不是本项目唯一需求，集群资源较重。
3. Milvus：向量检索能力和索引类型丰富，标量过滤明确，但引入 etcd、对象存储和额外运维成本。

## 决策

采用 Milvus 2.5.6 保存向量和检索过滤字段。PostgreSQL 保存 chunk 正文、归属和索引状态；Milvus 数据可以依据 PostgreSQL 重建。所有检索必须包含 tenant 与 knowledge base 过滤条件。

## 后果

正向：向量负载独立扩展；索引和批量检索能力明确；符合目标项目的 RAG 展示需求。

负向：本地 Compose 服务更多；PostgreSQL 与 Milvus 存在最终一致性窗口；需要处理部分写入和重建。

风险控制：使用索引任务状态和幂等 chunk UUID；先写 PostgreSQL 状态，再异步写 Milvus；失败任务可重试；定期执行元数据与向量对账。

## 实现约束（提示词十六 落地）

`knowagent-knowledge` 的 `infrastructure.vector` 包提供 Milvus Java SDK V2 API（`io.milvus:milvus-sdk-java:2.5.6`，与 Compose 固定的 Milvus 2.5.6 服务端匹配）适配器，实现 `VectorStoreGateway` 端口。固定契约与安全边界：

- **集合契约**：主键 `id` 为 VARCHAR（autoID=false，maxLength 64），保存 PostgreSQL chunk UUID 字符串——Milvus entity id 与 PostgreSQL chunk UUID 逐项一致；`embedding` 为 FLOAT_VECTOR，维度来自已验证配置 `knowagent.vector.milvus.dimension`（[1, 65536]）；标量字段 `tenant_id`/`knowledge_base_id`/`file_id`/`chunk_id`（VARCHAR 64）与 `embedding_model_spec`（VARCHAR 512）；相似度固定 COSINE（不可配置）。chunk 正文与元数据只存 PostgreSQL，Milvus 只存检索所需标量与向量。
- **启动初始化**：`MilvusCollectionInitializer` 以 SmartLifecycle 幂等执行——collection 不存在时按固定 schema 创建并**同步**建索引（`milvus-sdk-java` V2 `CreateCollectionReq`/`IndexParam`，`sync=true` 等待索引构建完成，避免 describeIndex/检索与构建竞态）再 load；已存在时 `describeCollection`/`describeIndex` 校验 schema/维度/主键/autoID/索引 metric，任一不匹配即抛 `VECTOR_SCHEMA_MISMATCH` 拒绝启动，**绝不 drop 或改动既有 collection**。
- **索引配置**：`knowagent.vector.milvus.index-type`（HNSW 默认，可 FLAT/AUTOINDEX）与参数（HNSW：`m`/`ef-construction` 建索引、`search-ef` 检索）由配置固定；COSINE 固定不变。
- **写入**：`upsert` 前校验批次非空、tenant/kb/file/chunk 关系一致、向量维度等于配置维度且数值有限、批内 chunkId 不重复；Milvus entity id 恒等于 chunkId；重复 upsert 是幂等替换（不产生重复实体）；ack 的 upsertCnt 与提交行数不一致映射 `VECTOR_BAD_RESPONSE`。
- **检索**：filter 由 `MilvusFilterBuilder` 受控构造，恒含 `tenant_id` + `knowledge_base_id`，可选 `file_id in [...]`（Milvus 2.5 的 `in` 右侧必须是方括号列表）；fileId 逐个按 UUID 校验并转义（`\`/`'`），不拼接任意用户表达式；检索使用 **STRONG 一致性**（刚索引的 chunk 立即可见，满足「写入即检索」的 RAG 语义）；结果只返回 id、file_id、score（`VectorHit.content` 恒为 null，由应用层按 tenant + chunk ids 从 PostgreSQL 回查正文）；COSINE 分数越大越相似，`minimumScore` 阈值在应用层过滤。
- **删除**：`deleteByFile` 恒含 tenant/kb/file 三元组；filter 无匹配行（对象不存在）视为幂等成功（`deleteCnt == 0`）。
- **超时与错误映射**：连接/搜索/写入/删除/初始化超时独立配置，所有 SDK 调用经 `MilvusCallExecutor`（CompletableFuture + orTimeout）限时执行；SDK 异常映射稳定 `VECTOR_UNAVAILABLE`（网络/服务端/超时）、`VECTOR_SCHEMA_MISMATCH`（collection 缺失）、`VECTOR_BAD_RESPONSE`（响应缺 id/score/非法 UUID/计数不符），消息恒为固定文案，不携带 Milvus 错误体/endpoint/凭据/向量内容。
- **指标**：`VectorMetrics` 只记录 collection/operation/outcome/耗时与实体数量，绝不记录向量或 chunk 文本；无 MeterRegistry 时 no-op。
- **装配**：`VectorStoreConfiguration` 在 `knowagent.vector.milvus.uri`（`MILVUS_ENDPOINT`）配置时启用，`VectorStoreFallbackConfiguration` 缺省提供 `UnavailableVectorStoreGateway`（任何操作稳定 `VECTOR_UNAVAILABLE`）——API/Worker 无 Docker/Milvus 也能启动；API 与 Worker 共用同一前缀配置。
- **租户隔离**：写入、检索、删除全部同时限定 tenant_id 与 knowledge_base_id（需要时 file_id/chunk_id）；跨租户 chunkId/fileId 即使伪造也得不到结果（`MilvusVectorStoreIT` 真实容器验证）。

## 复审条件

当数据规模较小且运维成本优先，或 PostgreSQL 已具备足够向量容量时，重新评估 pgvector。
