# Spring Controller 评审清单

适用于 @Controller / @RestController 类。

## 必查项

### 一、参数校验
- 入参对象是否加了 @Valid / @Validated 注解
- 路径参数是否做了非空、范围、格式校验
- @RequestParam 是否设置了合理的 required / defaultValue

### 二、鉴权与权限
- 敏感接口是否有 @PreAuthorize / 自定义鉴权注解
- 是否存在用户 ID 直接来自前端而未做校验的越权风险
- 是否暴露了内部接口(如 /admin/*) 而未限制访问

### 三、HTTP 语义
- GET 接口是否有副作用(应避免)
- POST/PUT/DELETE 的语义是否正确(创建/全量更新/删除)
- 返回状态码是否合理(404 / 400 / 401 / 403 / 500)

### 四、响应规范
- 是否使用统一的 Result/ApiResponse 包装
- 是否泄露了内部异常栈、敏感字段(密码、手机号、身份证)
- 大列表是否做了分页

### 五、性能与可用性
- 接口耗时操作是否考虑异步化(CompletableFuture / @Async)
- 是否有限流注解(Sentinel / RateLimiter)
- 文件上传是否限制大小、类型

### 六、日志与可观测性
- 关键业务操作是否有 INFO 日志
- 异常分支是否打了日志(避免 e.printStackTrace())

## 反模式(看到立即标记)
- Controller 直接操作数据库(应通过 Service)
- Controller 内拼接 SQL
- 把 HttpServletRequest 透传到 Service 层
- 在 Controller 里 catch Exception 后吞掉(应交给 GlobalExceptionHandler)