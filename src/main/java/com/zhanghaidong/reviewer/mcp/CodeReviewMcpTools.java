package com.zhanghaidong.reviewer.mcp;

import com.zhanghaidong.reviewer.dto.*;
import com.zhanghaidong.reviewer.service.JavaContextExtractor;
import com.zhanghaidong.reviewer.service.ReviewService;
import com.zhanghaidong.reviewer.service.SkillLoader;

import com.zhanghaidong.reviewer.service.TestGenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 暴露给 MCP 客户端调用的 Tools
 *
 * 通过 @Tool 注解声明,被 Spring AI MCP Server 自动发现并注册到协议端点
 *
 * @author 张海东
 */
@Slf4j
@Component
public class CodeReviewMcpTools {

    // 全部用 ObjectProvider 延迟解析,打破循环依赖
    private final ObjectProvider<ReviewService> reviewServiceProvider;
    private final ObjectProvider<JavaContextExtractor> contextExtractorProvider;
    private final ObjectProvider<SkillLoader> skillLoaderProvider;
    private final ObjectProvider<TestGenService> testGenServiceProvider;

    public CodeReviewMcpTools(ObjectProvider<ReviewService> reviewServiceProvider,
                              ObjectProvider<JavaContextExtractor> contextExtractorProvider,
                              ObjectProvider<SkillLoader> skillLoaderProvider,
                              ObjectProvider<TestGenService> testGenServiceProvider) {
        this.reviewServiceProvider = reviewServiceProvider;
        this.contextExtractorProvider = contextExtractorProvider;
        this.skillLoaderProvider = skillLoaderProvider;
        this.testGenServiceProvider = testGenServiceProvider;
    }

    // =========================================================
    // Tool 1: 评审一段 Java 源码
    // =========================================================
    @Tool(
            name = "review_code",
            description = """
                    评审一段 Java 源码,输出问题清单(包含严重程度、位置、修复建议)。
                    适用于 IDE 集成场景,例如 Cursor/Claude Code 用户请求 AI 评审当前文件时调用。
                    返回 JSON 格式的评审结果,包含 overallScore、summary、comments 三个字段。
                    """
    )
    public ReviewResult reviewCode(
            @ToolParam(description = "Java 文件名,例如 UserController.java(用于自动路由到对应评审清单)")
            String filename,
            @ToolParam(description = "完整的 Java 源码")
            String sourceCode) {

        log.info("🔌 [MCP] review_code 被调用: filename={}, codeLen={}",
                filename, sourceCode == null ? 0 : sourceCode.length());

        if (sourceCode == null || sourceCode.isBlank()) {
            return new ReviewResult(0, "源码为空,无法评审", List.of());
        }

        FileDiff fakeDiff = new FileDiff();
        fakeDiff.setFilename(filename == null ? "Unknown.java" : filename);
        fakeDiff.setStatus("added");
        FileDiff.Patch patch = new FileDiff.Patch();
        patch.setDiff(buildPseudoPatch(sourceCode));
        fakeDiff.setPatch(patch);
        fakeDiff.setAdditions(String.valueOf(sourceCode.split("\n").length));
        fakeDiff.setDeletions("0");

        FileContext ctx = contextExtractorProvider.getObject().extract(
                fakeDiff.getFilename(),
                sourceCode,
                allLineNumbers(sourceCode)
        );

        Map<String, FileContext> contextMap = Map.of(fakeDiff.getFilename(), ctx);
        return reviewServiceProvider.getObject().review(List.of(fakeDiff), contextMap);
    }

    // =========================================================
    // Tool 2: 为某个方法生成 JUnit 测试
    // =========================================================
    @Tool(
            name = "generate_tests",
            description = """
                    为给定的 Java 方法生成 JUnit 5 + Mockito 单元测试代码。
                    返回完整的测试类源码字符串。
                    """
    )
    public String generateTests(
            @ToolParam(description = "被测类名,例如 UserService")
            String className,
            @ToolParam(description = "类的依赖字段(每行一个),例如 'UserMapper userMapper'")
            String fieldsText,
            @ToolParam(description = "被测方法的完整源码")
            String methodCode) {

        log.info("🔌 [MCP] generate_tests 被调用: class={}, methodLen={}",
                className, methodCode == null ? 0 : methodCode.length());

        return testGenServiceProvider.getObject().generate(className, fieldsText, methodCode);
    }
    // =========================================================
    // Tool 3: 查询某个文件命中的 Skill
    // =========================================================
    @Tool(
            name = "route_skill",
            description = """
                    分析一段 Java 源码,判断它属于哪种代码场景(Spring Controller / MyBatis Mapper /
                    Service / 并发代码 / 默认),返回对应的 Skill 名称和评审清单内容。
                    用于 IDE 在评审前先了解代码场景。
                    """
    )
    public Map<String, String> routeSkill(
            @ToolParam(description = "文件名")
            String filename,
            @ToolParam(description = "Java 源码(用于判断含并发关键字等场景)")
            String sourceCode) {

        log.info("🔌 [MCP] route_skill 被调用: filename={}", filename);

        FileContext ctx = contextExtractorProvider.getObject().extract(
                filename,
                sourceCode,
                Collections.emptySet()
        );

        String skill = ctx.getSkillName();
        String content = skillLoaderProvider.getObject().getSkillOrDefault(skill);

        return Map.of(
                "skill", skill == null ? "default" : skill,
                "className", ctx.getClassName() == null ? "" : ctx.getClassName(),
                "checklist", content
        );
    }

    // =========================================================
    // Tool 4: 列出所有可用的 Skill
    // =========================================================
    @Tool(
            name = "list_skills",
            description = "列出所有可用的评审 Skill 名称及其内容(供 IDE 浏览能力)"
    )
    public Map<String, String> listSkills() {
        log.info("🔌 [MCP] list_skills 被调用");
        return skillLoaderProvider.getObject().getSkills();
    }
    // ========== 内部工具方法 ==========

    /**
     * 把整段源码包装成"全新增"格式的 patch,
     * 让 ReviewService 的 patch 解析逻辑能正常工作
     */
    private String buildPseudoPatch(String sourceCode) {
        String[] lines = sourceCode.split("\n");
        StringBuilder sb = new StringBuilder();
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            sb.append("+").append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * 返回所有行号(MCP 调用场景下,认为整个文件都是新代码)
     */
    private java.util.Set<Integer> allLineNumbers(String sourceCode) {
        int total = sourceCode.split("\n").length;
        java.util.Set<Integer> set = new HashSet<>();
        for (int i = 1; i <= total; i++) set.add(i);
        return set;
    }

//    private String stripMarkdownFence(String code) {
//        if (code == null) return "";
//        code = code.trim();
//        if (code.startsWith("```")) {
//            int firstNewline = code.indexOf('\n');
//            if (firstNewline > 0) code = code.substring(firstNewline + 1);
//        }
//        if (code.endsWith("```")) {
//            code = code.substring(0, code.lastIndexOf("```")).trim();
//        }
//        return code;
//    }
}