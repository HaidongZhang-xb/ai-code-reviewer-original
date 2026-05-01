package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试代码验证结果
 *
 * 验证级别(对应 Meta TestGen-LLM 四级过滤管道):
 *  - Level 1 SYNTAX:  JavaParser 语法解析
 *  - Level 2 COMPILE: Docker 容器内 mvn 编译
 *  - Level 3 EXECUTE: Docker 容器内 mvn 测试执行
 *  - Level 4 COVERAGE: JaCoCo 覆盖率(暂未实现)
 *
 * @author 张海东
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    private String testClassName;

    /** 最高通过的验证级别 */
    private String highestPassedLevel;

    /** 是否通过(综合判断,任一关键级别失败则 false) */
    private boolean passed;

    /** 失败原因(失败时填写) */
    private String errorMessage;

    /** 检测到的问题列表 */
    private List<String> issues;

    // ===== Level 2 编译相关 =====
    private Boolean compileSuccess;
    private String compileOutput;

    // ===== Level 3 测试执行相关 =====
    private Boolean executeSuccess;
    private Integer testsRun;
    private Integer testsPassed;
    private Integer testsFailed;
    private String executeOutput;

    /** 简化构造函数 - 兼容老代码(只做语法验证时用) */
    public ValidationResult(String testClassName, String level, boolean passed,
                            String errorMessage, List<String> issues) {
        this.testClassName = testClassName;
        this.highestPassedLevel = passed ? level : "FAILED";
        this.passed = passed;
        this.errorMessage = errorMessage;
        this.issues = issues == null ? new ArrayList<>() : issues;
    }
}