# Web 前端迁移说明

Vue 3 客户端将在首批 `/api/v1` 接口契约稳定后，从 `../Yuxi/web` 分阶段迁移。当前目录只定义前端代码边界，不包含可运行应用。

计划目录：

- `src/apis`：统一封装 `/api/v1` 请求和 SSE 连接
- `src/stores`：保存登录用户、Agent 和会话状态
- `src/composables`：封装请求排队、Run 事件流和断线恢复
- `src/views`：实现登录、知识库、Agent、聊天和任务页面
- `src/components`：放置消息、引用、上传器等可复用领域组件

原 Yuxi 前端保持不变，作为交互行为和功能范围的参考；新前端不要求兼容旧 API。