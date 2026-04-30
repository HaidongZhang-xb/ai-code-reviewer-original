package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行级评审意见
 *
 * @author 张海东
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewComment {

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 行号(对应 patch 中新文件的行号)
     */
    private Integer lineNumber;

    /**
     * 严重等级: BLOCKER / CRITICAL / MAJOR / MINOR / INFO
     */
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
