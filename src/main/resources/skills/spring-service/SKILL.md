# Spring Service 评审清单

适用于 @Service 类、业务逻辑层。

## 必查项

### 一、事务
- 多张表写操作是否加 @Transactional
- @Transactional(rollbackFor = Exception.class) 显式指定回滚类型
- @Transactional 方法内部不要调用 try-catch 吞异常(会导致事务不回滚)
- 同类内方法调用 @Transactional 不生效(代理失效)
- @Transactional 方法不应过长,避免大事务

### 二、空指针与边界
- DAO 查询返回的对象使用前是否判空
- Optional 的使用是否得当(get() 直接调用是反模式)
- 集合操作前是否判空(NullPointerException 高发区)

### 三、异常处理
- 业务异常应抛出自定义异常,而非吞掉
- 不应 catch (Exception e) 而不处理或只打日志
- 异常信息应包含足够上下文(订单号、用户 ID 等)

### 四、缓存一致性
- 数据更新后是否清理/更新对应缓存
- "先删缓存再更新 DB" vs "先更新 DB 再删缓存" 的一致性问题
- 缓存穿透/击穿/雪崩的防护(空值缓存、互斥锁、随机过期)

### 五、外部调用
- 远程调用是否设置了超时
- 是否有重试机制(注意幂等性)
- 是否考虑了熔断降级(Sentinel / Resilience4j)

### 六、并发
- 共享状态字段是否有线程安全问题
- 是否使用了线程池(避免 new Thread)
- @Async 方法返回值是否使用 CompletableFuture

## 反模式
- Service 之间互相循环依赖
- Service 直接调用其他 Service 的私有逻辑(应通过接口)
- 把 HTTP 层的 Request/Response 对象传到 Service