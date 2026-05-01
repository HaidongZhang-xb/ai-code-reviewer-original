package com.zhanghaidong.reviewer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 单测生成服务(独立出来,避免 MCP Tool 直接依赖 ChatClient 导致循环依赖)
 *
 * @author 张海东
 */
@Slf4j
@Service
public class TestGenService {

    /**
     * 用 ObjectProvider 延迟获取 ChatClient,打破启动期循环依赖
     */
    private final ObjectProvider<ChatClient> chatClientProvider;

    public TestGenService(ObjectProvider<ChatClient> chatClientProvider) {
        this.chatClientProvider = chatClientProvider;
    }

    public String generate(String className, String fieldsText, String methodCode) {
        if (methodCode == null || methodCode.isBlank()) {
            return "// 错误: methodCode 不能为空";
        }

        String prompt = """
                请为下面的 Java 方法生成 JUnit 5 + Mockito 单元测试。

                被测类: %s
                依赖字段:
                %s

                方法源码:
                %s

                要求:
                1. 使用 @ExtendWith(MockitoExtension.class)
                2. 至少 3 个 @Test 方法,覆盖正常 / 边界 / 异常分支
                3. 每个测试用 @DisplayName 加中文说明
                4. 必须有断言(assertEquals/assertNotNull/assertThrows 等)
                5. 直接输出 Java 源码,不要 markdown 代码块标记
                """.formatted(
                className == null ? "Unknown" : className,
                fieldsText == null ? "(无)" : fieldsText,
                methodCode
        );

        try {
            String result = chatClientProvider.getObject()
                    .prompt().user(prompt).call().content();
            return stripMarkdownFence(result);
        } catch (Exception e) {
            log.error("生成测试失败", e);
            return "// 生成失败: " + e.getMessage();
        }
    }

    private String stripMarkdownFence(String code) {
        if (code == null) return "";
        code = code.trim();
        if (code.startsWith("```")) {
            int firstNewline = code.indexOf('\n');
            if (firstNewline > 0) code = code.substring(firstNewline + 1);
        }
        if (code.endsWith("```")) {
            code = code.substring(0, code.lastIndexOf("```")).trim();
        }
        return code;
    }
}