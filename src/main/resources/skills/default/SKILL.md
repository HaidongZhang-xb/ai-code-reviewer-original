# 默认通用评审清单

适用于所有 Java 文件,作为兜底。

## 必查项

### 一、空安全
- 方法返回值使用前是否判空
- 集合 .get(0) 之前是否判 isEmpty()
- 链式调用(a.b().c().d()) 中间环节是否可能为 null

### 二、异常处理
- catch 后是否吞异常
- 异常类型过宽(catch Exception)是否合理
- 异常信息是否包含足够上下文

### 三、可读性
- 方法是否过长(>50 行考虑拆分)
- 嵌套是否过深(>3 层考虑提取方法 / 早返回)
- 魔法数字是否提取为常量
- 命名是否表达意图

### 四、资源管理
- IO/Connection/Stream 是否使用 try-with-resources
- 文件、流、连接是否有泄漏风险

### 五、集合操作
- foreach 中删除元素会抛 ConcurrentModificationException
- Stream 操作中的副作用(应避免修改外部状态)
- equals 和 hashCode 是否成对实现

### 六、日志
- 是否使用 SLF4J 占位符 log.info("user: {}", id) 而非字符串拼接
- 敏感信息(密码、token、身份证)是否被记录
- 异常日志是否包含堆栈(log.error("xxx", e) 而非 log.error("xxx" + e))