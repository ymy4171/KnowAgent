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

## 复审条件

当数据规模较小且运维成本优先，或 PostgreSQL 已具备足够向量容量时，重新评估 pgvector。
