# ADR 0003：采用 MyBatis-Plus 进行关系数据访问

- 状态：已接受
- 日期：2026-08-09
- 作者：KnowAgent 项目组
- 评审人：项目维护者

## 上下文

KnowAgent 的核心查询包含显式 tenant 过滤、状态条件更新、行锁、Outbox 抢占、FIFO 调度和统计查询。项目需要直接观察 SQL，并通过 PostgreSQL 约束保证并发正确性。

## 决策驱动因素

- 自定义 SQL、锁和批量更新必须可控。
- 普通 CRUD 需要减少样板代码。
- 面试时可以清楚说明索引、执行计划和事务边界。
- 所有业务表必须执行租户隔离。

## 备选方案

1. JPA/Hibernate：聚合映射和变更跟踪成熟，但复杂锁查询、批处理和隐式 SQL 调优成本较高。
2. JdbcTemplate：SQL 最透明，但 CRUD、映射和分页样板代码较多。
3. MyBatis：SQL 可控，但基础 CRUD 仍需手写。
4. MyBatis-Plus：保留 MyBatis 自定义 SQL，同时提供 CRUD、分页和租户拦截能力。

## 决策

采用 MyBatis-Plus。普通查询使用 Mapper 和租户拦截器；自定义 SQL、锁查询、统计和批量更新必须显式包含 `tenant_id`，并通过 Testcontainers 验证。

分页与统计不使用 MyBatis-Plus `PaginationInnerInterceptor`：代码库未装配该拦截器，且审查规则要求「统计只能通过显式租户条件执行」。受保护查询的分页改用显式 `LIMIT/OFFSET` + `SELECT COUNT(*)` 的自定义 SQL，两者都显式携带 `tenant_id = #{tenantId}` 与 `deleted_at IS NULL`，可选过滤条件用静态 `COALESCE(#{x},'')=''` 表达(不用动态 `<script>`,降低租户插件 JSqlParser 改写风险)。这类查询运行在认证请求内(TenantContext 已由过滤器保证存在),不进入认证前 `@InterceptorIgnore` 白名单,租户插件会额外注入相同的 `tenant_id` 作为 fail-closed 兜底;白名单仍由 `SecurityMapperSqlContractTest` 精确锁定为认证前方法。

## 后果

正向：SQL 行为透明；复杂状态更新可精确表达；普通 CRUD 成本较低；分页与统计的可观测性与租户正确性由显式 SQL 保证。

负向：领域对象与持久化对象需要显式转换；关联加载和聚合保存需要应用层编排；租户插件不能自动覆盖所有自定义 SQL；分页排序、LIKE 转义与 limit/offset 需要服务层自行处理。

风险控制：禁止 Controller 直接调用 Mapper；审查自定义 SQL 的租户条件；为锁、索引和并发场景编写 PostgreSQL 集成测试。

## 复审条件

如果业务转为简单聚合 CRUD 且关系导航成为主要复杂度，再重新评估 JPA。
