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

## 复审条件

当需要跨地域复制、长期事件重放、每日千万级事件或多个独立消费域时，重新评估 Kafka。
