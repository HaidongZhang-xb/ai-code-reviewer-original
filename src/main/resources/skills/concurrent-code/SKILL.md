# 并发代码评审清单

适用于含 synchronized、Lock、volatile、CAS、并发集合、ThreadPool 的代码。

## 必查项

### 一、锁的使用
- synchronized 的锁对象是否合理(避免锁 String、Integer 等可变对象引用)
- Lock 是否在 finally 块中 unlock(防止死锁)
- 锁粒度是否过粗(影响并发度)或过细(线程安全风险)
- 锁顺序是否一致(防止死锁)

### 二、可见性
- 多线程共享变量是否使用 volatile
- 双重检查锁(DCL)是否使用 volatile 修饰单例字段
- 不可变对象优先于可变对象 + 锁

### 三、原子性
- 复合操作(check-then-act)是否使用 AtomicXxx 或加锁
- ConcurrentHashMap 的 putIfAbsent / computeIfAbsent 优于先 get 后 put
- i++ 不是原子操作(读取-修改-写入三步)

### 四、线程池
- 是否使用 Executors.newXxx(可能导致 OOM,建议自定义 ThreadPoolExecutor)
- 核心线程数 / 最大线程数 / 队列长度 / 拒绝策略 是否合理
- 线程池是否有名字(便于排查问题)
- 任务异常是否被吞掉(submit 返回的 Future 不调 get 异常会丢)

### 五、并发容器
- HashMap 在多线程场景应替换为 ConcurrentHashMap
- ArrayList 在多线程场景应替换为 CopyOnWriteArrayList(读多写少) 或加锁
- 迭代时修改集合会抛 ConcurrentModificationException

### 六、CompletableFuture
- 是否使用了正确的线程池(默认 ForkJoinPool 不适合 IO 密集)
- 异常分支是否处理(exceptionally / handle)
- get() 是否设了超时

## 反模式
- 在 synchronized 块内做 IO/远程调用
- 用 Thread.sleep 等待并发完成
- 锁住 this 暴露给外部(被外部 lock(obj) 风险)
- 使用 Date 而非 LocalDateTime(Date 非线程安全)