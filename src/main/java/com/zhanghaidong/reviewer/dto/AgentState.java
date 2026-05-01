package com.zhanghaidong.reviewer.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 流水线的共享状态
 * 所有 Agent 都从中读取上游产出,写入自己的产出
 *
 * @author 张海东
 */
@Data
@NoArgsConstructor
public class AgentState {

    // ===== 输入 =====
    private String owner;
    private String repo;
    private Integer prNumber;
    private String headSha;

    /** 改动文件 diff */
    private List<FileDiff> files = new ArrayList<>();

    /** filePath -> AST 上下文 */
    private Map<String, FileContext> contextMap = new HashMap<>();

    // ===== Agent 中间产出 =====

    /** Reviewer Agent 输出 */
    private ReviewResult reviewResult;

    /** TestGen Agent 输出: filePath#methodSignature -> 生成的测试代码 */
    private Map<String, TestGenResult> generatedTests = new HashMap<>();

    /** Validator Agent 输出 */
    private List<ValidationResult> validations = new ArrayList<>();

    /** 已执行的 Agent 名称(用于追踪) */
    private List<String> executedAgents = new ArrayList<>();

    /** 中途失败时记录原因 */
    private String errorMessage;
}