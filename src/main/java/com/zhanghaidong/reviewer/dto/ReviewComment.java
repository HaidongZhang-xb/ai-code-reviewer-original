package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewComment {

    private String filePath;

    /**
     * LLM 给的预估行号(可能不准,仅作降级用)
     */
    private Integer lineNumber;

    /**
     * 问题代码的精确片段(单行,从原始代码复制,我们用它来反查真实行号)
     */
    private String codeSnippet;

    private String severity;

    /**
     * 问题分类: BUG / SECURITY / PERFORMANCE / STYLE / DESIGN
     */
    private String category;

    /**
     * 问题描述
     */
    private String message;

    /**
     * 修复建议
     */
    private String suggestion;
}