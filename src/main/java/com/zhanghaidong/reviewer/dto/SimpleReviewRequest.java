package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 评审入参
 * 给 IDE 调用时用,直接传代码字符串即可
 *
 * @author 张海东
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimpleReviewRequest {

    /** 文件名(用于路由 Skill,如 UserController.java) */
    private String filename;

    /** 完整的 Java 源码 */
    private String sourceCode;
}