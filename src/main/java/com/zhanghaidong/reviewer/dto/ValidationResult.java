package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    /** 对应的测试类名 */
    private String testClassName;

    /** 验证级别: SYNTAX(语法解析) / COMPILE(完整编译,需依赖) */
    private String level;

    /** 是否通过 */
    private boolean passed;

    /** 错误信息(失败时填写) */
    private String errorMessage;

    /** 检测到的问题列表 */
    private java.util.List<String> issues;
}