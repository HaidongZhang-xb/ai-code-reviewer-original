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

        // 拼接 diff 文本
        String diffText = files.stream()
                .filter(f -> f.getPatchText() != null && !f.getPatchText().isBlank())
                .map(f -> "===== 文件: " + f.getFilename() + " (" + f.getStatus() + ") =====\n" + f.getPatchText())
                .collect(Collectors.joining("\n\n"));

        if (diffText.isBlank()) {
            return new ReviewResult(100, "diff 为空,跳过评审", List.of());
        }

        // 结构化输出转换器
        BeanOutputConverter<ReviewResult> converter = new BeanOutputConverter<>(ReviewResult.class);
        String formatHint = converter.getFormat();

        String userPrompt = """
                你是一名资深 Java 后端工程师,请对下面的 git diff 做代码评审。

                【评审要点】
                1. 潜在的 BUG(空指针、并发问题、边界条件)
                2. 安全风险(SQL 注入、XSS、敏感信息泄露)
                3. 性能问题(N+1、不必要的循环、阻塞调用)
                4. 代码风格(命名、注释、可读性)
                5. 设计问题(分层、职责、可测试性)

                【输出要求】
                - 严格使用下面的 JSON 格式输出,不要带 markdown 代码块,不要任何额外文字
                - comments 数组中,lineNumber 指 patch 中新文件的行号(@@ -a,b +c,d @@ 中 c 起算)
                - severity 取值: BLOCKER / CRITICAL / MAJOR / MINOR / INFO
                - category 取值: BUG / SECURITY / PERFORMANCE / STYLE / DESIGN
                - 如果代码没问题,comments 返回空数组,overallScore 给 90 以上

                【输出格式】
                %s

                【待评审的 diff】
                %s
                """.formatted(formatHint, diffText);

        log.debug("发送给 LLM 的 prompt 长度: {}", userPrompt.length());

        String response = chatClient.prompt()
                .user(userPrompt)
                .call()
                .content();

        log.debug("LLM 原始返回:\n{}", response);

        try {
            return converter.convert(response);
        } catch (Exception e) {
            log.error("解析 LLM 返回失败,原始内容: {}", response, e);
            return new ReviewResult(0, "评审结果解析失败: " + e.getMessage(), List.of());
        }
    }
}
