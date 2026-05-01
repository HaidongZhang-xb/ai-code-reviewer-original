package com.zhanghaidong.reviewer.service;

import com.zhanghaidong.reviewer.dto.FileContext;
import com.zhanghaidong.reviewer.dto.FileDiff;
import com.zhanghaidong.reviewer.dto.MethodContext;
import com.zhanghaidong.reviewer.dto.ReviewComment;
import com.zhanghaidong.reviewer.dto.ReviewResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 代码评审核心服务
 *
 * Day 4 升级:按 Skill 分组评审,每组注入独立的评审清单
 *
 * @author 张海东
 */
@Slf4j
@Service
public class ReviewService {

    /** 单个 patch 最大字符数,超过则截断 */
    private static final int MAX_PATCH_CHARS = 8000;

    /** 单个方法体最大字符数,超过则截断 */
    private static final int MAX_METHOD_BODY_CHARS = 3000;

    private final ChatClient chatClient;
    private final SkillLoader skillLoader;

    public ReviewService(ChatClient chatClient, SkillLoader skillLoader) {
        this.chatClient = chatClient;
        this.skillLoader = skillLoader;
    }

    /**
     * 入口:按 Skill 分组评审,合并结果
     */
    public ReviewResult review(List<FileDiff> files, Map<String, FileContext> contextMap) {
        if (files == null || files.isEmpty()) {
            return new ReviewResult(100, "没有需要评审的文件", List.of());
        }

        // 按 skillName 分组(默认 default)
        Map<String, List<FileDiff>> groupedFiles = new LinkedHashMap<>();
        for (FileDiff f : files) {
            FileContext ctx = contextMap == null ? null : contextMap.get(f.getFilename());
            String skill = (ctx != null && ctx.getSkillName() != null)
                    ? ctx.getSkillName() : SkillLoader.DEFAULT_SKILL;
            groupedFiles.computeIfAbsent(skill, k -> new ArrayList<>()).add(f);
        }

        log.info("📚 评审分组: {}", groupedFiles.entrySet().stream()
                .map(e -> e.getKey() + "(" + e.getValue().size() + ")")
                .collect(Collectors.joining(", ")));

        // 每组独立评审,合并结果
        List<ReviewComment> allComments = new ArrayList<>();
        List<String> allSummaries = new ArrayList<>();
        int totalScore = 0;
        int validGroups = 0;

        for (Map.Entry<String, List<FileDiff>> entry : groupedFiles.entrySet()) {
            String skillName = entry.getKey();
            List<FileDiff> groupFiles = entry.getValue();

            ReviewResult groupResult = reviewSingleGroup(skillName, groupFiles, contextMap);
            if (groupResult == null) continue;

            if (groupResult.getComments() != null) {
                allComments.addAll(groupResult.getComments());
            }
            if (groupResult.getSummary() != null) {
                allSummaries.add("[" + skillName + "] " + groupResult.getSummary());
            }
            if (groupResult.getOverallScore() != null && groupResult.getOverallScore() > 0) {
                totalScore += groupResult.getOverallScore();
                validGroups++;
            }
        }

        int finalScore = validGroups > 0 ? totalScore / validGroups : 0;
        String finalSummary = allSummaries.isEmpty()
                ? "无评审结果" : String.join(" | ", allSummaries);

        return new ReviewResult(finalScore, finalSummary, allComments);
    }

    /**
     * 单个 Skill 分组的评审
     */
    private ReviewResult reviewSingleGroup(String skillName,
                                           List<FileDiff> files,
                                           Map<String, FileContext> contextMap) {
        String diffText = files.stream()
                .filter(f -> f.getPatchText() != null && !f.getPatchText().isBlank())
                .map(this::formatFileDiff)
                .collect(Collectors.joining("\n\n"));

        if (diffText.isBlank()) return null;

        // 上下文段落(只包含本组的文件)
        String contextText = buildContextText(files, contextMap);

        // 加载本组对应的 Skill 清单
        String skillContent = skillLoader.getSkillOrDefault(skillName);

        BeanOutputConverter<ReviewResult> converter = new BeanOutputConverter<>(ReviewResult.class);
        String formatHint = converter.getFormat();

        String userPrompt = """
                你是一名资深 Java 后端工程师,请根据下面的【评审清单】对 git diff 做代码评审。
                请重点检查清单中提到的项,聚焦本场景特有的风险。

                【评审清单 - %s】
                %s

                【关键约束 - 这部分非常重要】
                - codeSnippet 字段:从 diff 中**精确复制有问题的那一行代码**(去掉前面的 + 号),
                  必须是 diff 中实际出现的代码,单行,长度不超过 100 字符
                - lineNumber 字段:你估算的新文件行号,作为辅助信息(不需要非常精确)
                - filePath 必须严格匹配 diff 中给出的文件路径
                - 只评论新增(+)或上下文行,不评论被删除(-)的行
                - severity 取值: BLOCKER / CRITICAL / MAJOR / MINOR / INFO
                - category 取值: BUG / SECURITY / PERFORMANCE / STYLE / DESIGN
                - 如果代码没问题,comments 返回空数组,overallScore 给 90 以上
                - MINOR 等级最多 2 条,聚焦真正的 MAJOR 及以上问题

                【代码上下文】(辅助你理解改动方法的全貌,不在此评审)
                %s

                【输出格式】严格按下面的 JSON,不带 markdown,不带任何额外文字
                %s

                【待评审的 diff】
                %s
                """.formatted(skillName, skillContent, contextText, formatHint, diffText);

        log.debug("[{}] prompt 长度: {}", skillName, userPrompt.length());

        try {
            String response = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();
            log.debug("[{}] LLM 原始返回:\n{}", skillName, response);
            return converter.convert(response);
        } catch (Exception e) {
            log.error("[{}] LLM 调用或结果解析失败", skillName, e);
            return new ReviewResult(0, skillName + " 评审失败: " + e.getMessage(), List.of());
        }
    }

    private String buildContextText(List<FileDiff> files, Map<String, FileContext> contextMap) {
        if (contextMap == null) return "(无可用上下文)";
        StringBuilder sb = new StringBuilder();
        for (FileDiff f : files) {
            FileContext ctx = contextMap.get(f.getFilename());
            if (ctx == null || ctx.getClassName() == null) continue;

            sb.append("---- 文件: ").append(ctx.getFilePath()).append(" ----\n");
            sb.append("类: ");
            if (!ctx.getClassAnnotations().isEmpty()) {
                sb.append(String.join(" ", ctx.getClassAnnotations())).append(" ");
            }
            sb.append(ctx.getClassName()).append("\n");

            if (!ctx.getFields().isEmpty()) {
                sb.append("依赖字段:\n");
                for (String fld : ctx.getFields()) {
                    sb.append("  ").append(fld).append("\n");
                }
            }

            if (!ctx.getAllMethodSignatures().isEmpty()) {
                sb.append("类内所有方法签名(供参考):\n");
                for (String s : ctx.getAllMethodSignatures()) {
                    sb.append("  ").append(s).append("\n");
                }
            }

            if (!ctx.getChangedMethods().isEmpty()) {
                sb.append("本次涉及改动的方法(完整代码):\n");
                for (MethodContext m : ctx.getChangedMethods()) {
                    String body = m.getBody();
                    if (body.length() > MAX_METHOD_BODY_CHARS) {
                        body = body.substring(0, MAX_METHOD_BODY_CHARS) + "\n... [方法过长已截断] ...";
                    }
                    sb.append("  // ").append(m.getSignature())
                            .append(" (行 ").append(m.getStartLine())
                            .append("-").append(m.getEndLine()).append(")\n");
                    sb.append("  ").append(body.replace("\n", "\n  ")).append("\n\n");
                }
            }
            sb.append("\n");
        }
        return sb.length() == 0 ? "(无可用上下文)" : sb.toString();
    }

    private String formatFileDiff(FileDiff f) {
        String patch = f.getPatchText();
        if (patch.length() > MAX_PATCH_CHARS) {
            log.info("patch 过长被截断: file={}, 原长度={}", f.getFilename(), patch.length());
            patch = patch.substring(0, MAX_PATCH_CHARS) + "\n... [patch 过长,后续内容已截断] ...";
        }
        return "===== 文件: " + f.getFilename() + " (" + f.getStatus() + ") =====\n" + patch;
    }
}