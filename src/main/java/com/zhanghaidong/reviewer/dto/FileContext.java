package com.zhanghaidong.reviewer.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件级别的上下文信息
 *
 * @author 张海东
 */
@Data
@NoArgsConstructor
public class FileContext {

    /** 文件路径 */
    private String filePath;

    /** 类名(如 SetmealServiceImpl) */
    private String className;

    /** 类上的注解(如 @Service @RestController) */
    private List<String> classAnnotations = new ArrayList<>();

    /**
     * 类的依赖字段(如 @Autowired SetmealMapper setmealMapper)
     * 形式: "private final SetmealMapper setmealMapper"
     */
    private List<String> fields = new ArrayList<>();

    /** 文件内所有方法的签名(辅助 LLM 理解类结构) */
    private List<String> allMethodSignatures = new ArrayList<>();

    /** 本次改动涉及的方法(完整源码) */
    private List<MethodContext> changedMethods = new ArrayList<>();
}