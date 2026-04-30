package com.zhanghaidong.reviewer.service;

import com.zhanghaidong.reviewer.dto.FileDiff;
import com.zhanghaidong.reviewer.dto.ReviewResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 代码评审核心服务
 *
 * @author 张海东
 */
@Slf4j
@Service
public class ReviewService {

    /**
     * 单个 patch 最大字符数,超过则截断,避免 token 浪费
     */
    private static final int MAX_PATCH_CHARS = 8000;

    private final ChatClient chatClient;

    public ReviewService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 对一组文件 diff 做评审,返回结构化结果
     */
    public ReviewResult review(List<FileDiff> files) {
        if (files == null || files.isEmpty()) {
            return new ReviewResult(100, "没有需要评审的文件", List.of());
        }

        String diffText = files.stream()
                .filter(f -> f.getPatchText() != null && !f.getPatchText().isBlank())
                .map(this::formatFileDiff)
                .collect(Collectors.joining("\n\n"));

        if (diffText.isBlank()) {
            return new ReviewResult(100, "diff 为空,跳过评审", List.of());
        }

        BeanOutputConverter<ReviewResult> converter = new BeanOutputConverter<>(ReviewResult.class);
        String formatHint = converter.getFormat();

        String userPrompt = """
        你是一名资深 Java 后端工程师,请对下面的 git diff 做代码评审。

        【评审要点】
        1. 潜在的 BUG(空指针、并发问题、边界条件、异常处理)
        2. 安全风险(SQL 注入、XSS、敏感信息泄露)
        3. 性能问题(N+1、不必要的循环、阻塞调用)
        4. 代码风格(命名、注释、可读性)
        5. 设计问题(分层、职责、可测试性)

        【关键约束 - 这部分非常重要】
        - codeSnippet 字段:从 diff 中**精确复制有问题的那一行代码**(去掉前面的 + 号),
          我们会用这段代码在文件中反查真实行号。**必须是 diff 中实际出现的代码**,
          不要改写、不要省略,**单行**,长度不超过 100 字符。
        - lineNumber 字段:你估算的新文件行号,作为辅助信息(不需要非常精确)
        - filePath 必须严格匹配 diff 中给出的文件路径
        - 只评论新增(+)或上下文行,不要评论被删除(-)的行
        - severity 取值: BLOCKER / CRITICAL / MAJOR / MINOR / INFO
        - category 取值: BUG / SECURITY / PERFORMANCE / STYLE / DESIGN
        - 如果代码没问题,comments 返回空数组,overallScore 给 90 以上
        - 不要罗列鸡毛蒜皮的 STYLE 问题,聚焦真正有价值的 MAJOR 及以上问题(MINOR 最多 2 条)

        【输出格式】严格按下面的 JSON,不带 markdown,不带任何额外文字
        %s

        【待评审的 diff】
        %s
        """.formatted(formatHint, diffText);

        log.debug("发送给 LLM 的 prompt 长度: {}", userPrompt.length());

        try {
            String response = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();

            log.debug("LLM 原始返回:\n{}", response);
            return converter.convert(response);
        } catch (Exception e) {
            log.error("LLM 调用或结果解析失败", e);
            return new ReviewResult(0, "评审失败: " + e.getMessage(), List.of());
        }
    }

    /**
     * 格式化单个文件的 diff,过长则截断
     */
    private String formatFileDiff(FileDiff f) {
        String patch = f.getPatchText();
        if (patch.length() > MAX_PATCH_CHARS) {
            log.info("patch 过长被截断: file={}, 原长度={}", f.getFilename(), patch.length());
            patch = patch.substring(0, MAX_PATCH_CHARS) + "\n... [patch 过长,后续内容已截断] ...";
        }
        return "===== 文件: " + f.getFilename() + " (" + f.getStatus() + ") =====\n" + patch;
    }
}