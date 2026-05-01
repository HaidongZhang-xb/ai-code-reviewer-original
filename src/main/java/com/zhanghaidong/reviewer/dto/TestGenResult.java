package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestGenResult {

    /** 被测类全限定名 */
    private String targetClass;

    /** 被测方法签名 */
    private String targetMethod;

    /** 生成的测试类名(如 SetmealServiceImplTest) */
    private String testClassName;

    /** 生成的完整测试代码 */
    private String testCode;

    /** 简要说明(覆盖了哪些场景) */
    private String description;
}