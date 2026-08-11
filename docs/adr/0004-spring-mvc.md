# ADR 0004：API 采用 Spring MVC，运行时保留 Reactor Flux

- 状态：已接受
- 日期：2026-08-09
- 作者：KnowAgent 项目组
- 评审人：项目维护者

## 上下文

API 使用 MyBatis-Plus、JDBC 和 Flyway，这些组件是阻塞式；模型和 Agent 事件流使用 Reactor Flux。若 API 采用 WebFlux，阻塞数据库调用会占用事件循环线程，必须为每次调用额外调度和审计。

## 决策驱动因素

- 主业务链是阻塞式 PostgreSQL 事务。
- 需要 SSE 流式输出，但不要求全链路非阻塞。
- 降低线程模型、事务上下文和调试复杂度。
- 保留 ChatModelGateway 与 AgentOrchestrator 的 Flux 契约。

## 备选方案

1. 全量 WebFlux + R2DBC：非阻塞一致，但需要放弃 MyBatis-Plus并重写数据访问。
2. WebFlux + JDBC：可以运行，但必须将所有阻塞调用切到专用调度器，容易发生遗漏。
3. Spring MVC + SseEmitter/响应式返回类型：数据库事务模型自然，仍支持 SSE 和异步流式响应。

## 决策

HTTP API 使用 `spring-boot-starter-web` 和 Servlet SecurityFilterChain。SSE 使用 `SseEmitter`，或在适配层消费 Flux 后写入 emitter。模型、事件发布和 Worker 内部仍可使用 Reactor。

Spring MVC 的异步支持和 SSE 参考：[Spring Framework MVC 异步请求](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)。

## 后果

正向：JDBC 事务与线程模型一致；认证和异常处理更直接；无需在每个数据库调用处切换调度器。

负向：SSE 写操作仍是阻塞式并占用异步线程；高连接数下需要配置线程池、超时和背压策略；无法获得 WebFlux 的端到端非阻塞收益。

风险控制：SSE 使用专用有界执行器；设置连接超时和发送队列上限；慢客户端触发取消；模型 Flux 的取消信号向上游传播。

## 复审条件

当数据访问全面迁移到 R2DBC，或并发长连接规模证明 Servlet 异步模型不足时，重新评估 WebFlux。
