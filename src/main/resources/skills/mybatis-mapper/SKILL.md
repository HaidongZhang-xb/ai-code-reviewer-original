# MyBatis Mapper 评审清单

适用于 Mapper 接口、@Mapper / @MapperScan 标记的类、XML Mapper。

## 必查项

### 一、SQL 注入
- 是否使用了字符串拼接(${} 而非 #{})
- ORDER BY 字段是否做了白名单校验(${} 在排序场景常见但危险)
- LIKE 条件的 % 是否在 Java 侧拼接,而非 SQL 内拼接

### 二、性能
- 单条查询返回 List 时是否设置了 LIMIT(防止全表)
- 循环里调用 Mapper 的反模式(典型 N+1)
- 是否使用了 IN 大量元素(>1000 应分批)
- COUNT(*) 是否可以用 EXISTS 替代
- SELECT * 应改为指定字段

### 三、索引
- WHERE 条件字段是否有索引(尤其新加字段)
- 函数操作字段会导致索引失效(如 WHERE DATE(create_time) = ...)
- 隐式类型转换会导致索引失效(如 varchar 字段传 int)
- LIKE 'xxx%' 走索引,'%xxx' 不走

### 四、事务与一致性
- 多表更新是否在 Service 层套了 @Transactional
- 批量插入是否使用 foreach + insert into ... values (...)而非循环调用

### 五、动态 SQL
- if 条件判空建议同时判 null 和 ''
- where 1=1 是反模式,应使用 <where> 标签
- 动态字段更新是否有"全字段被置 null"的风险

### 六、返回值映射
- resultType / resultMap 与实际查询字段是否对齐
- 关联查询是否用了 association / collection,而非两次查询

## 反模式
- Mapper 里写业务逻辑(应在 Service)
- 在 Mapper 接口上加 @Transactional(应该加在 Service)
- 一个 Mapper 跨多个领域(违反单一职责)