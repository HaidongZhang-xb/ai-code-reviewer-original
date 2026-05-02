package com.zhanghaidong.reviewer.agent;

import com.zhanghaidong.reviewer.dto.AgentState;
import com.zhanghaidong.reviewer.dto.FileContext;
import com.zhanghaidong.reviewer.dto.MethodContext;
import com.zhanghaidong.reviewer.dto.TestGenResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 为改动的方法生成 JUnit 5 + Mockito 单测
 * 参考 Meta TestGen-LLM 论文的"生成-编译-执行-覆盖率"四级过滤思路
 *
 * @author 张海东
 */
@Slf4j
@Component
public class TestGenAgent implements Agent {

    /** 单方法体过长跳过(避免 token 浪费) */
    private static final int MAX_METHOD_BODY_FOR_TESTGEN = 2000;

    /** 每次最多为多少个方法生成测试(避免一个 PR 改了几十个方法时炸) */
    private static final int MAX_METHODS_PER_RUN = 5;

    private final ChatClient chatClient;

    public TestGenAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String name() {
        return "TestGenAgent";
    }

    @Override
    public boolean shouldExecute(AgentState state) {
        // 评审失败/分数过低时不生成测试,有些 case 是 LLM 直接放弃了
        if (state.getReviewResult() == null) return false;
        // 没改动方法就不需要生成
        return state.getContextMap() != null
                && state.getContextMap().values().stream()
                .anyMatch(c -> !c.getChangedMethods().isEmpty());
    }

    @Override
    public void execute(AgentState state) {
        log.info("🤖 [{}] 开始执行", name());
        int generated = 0;

        outer:
        for (FileContext ctx : state.getContextMap().values()) {
            if (ctx.getClassName() == null) continue;
            // 只为业务类生成,跳过 Controller/Mapper(它们更适合集成测试)
            if (!isUnitTestable(ctx)) {
                log.debug("[{}] 跳过非业务类: {} (skill={})",
                        name(), ctx.getFilePath(), ctx.getSkillName());
                continue;
            }

            for (MethodContext method : ctx.getChangedMethods()) {
                if (generated >= MAX_METHODS_PER_RUN) {
                    log.info("[{}] 达到单次生成上限 {},跳过剩余方法", name(), MAX_METHODS_PER_RUN);
                    break outer;
                }

                if (method.getBody() == null
                        || method.getBody().length() > MAX_METHOD_BODY_FOR_TESTGEN) {
                    log.debug("[{}] 方法过长跳过: {}", name(), method.getSignature());
                    continue;
                }

                TestGenResult tg = generateTestForMethod(ctx, method);
                if (tg != null) {
                    String key = ctx.getFilePath() + "#" + method.getSignature();
                    state.getGeneratedTests().put(key, tg);
                    generated++;
                }
            }
        }

        log.info("✅ [{}] 完成: 生成 {} 个测试", name(), generated);
    }

    /**
     * 简单判断:Service / 默认类适合单测,Controller/Mapper/并发跳过
     */
    private boolean isUnitTestable(FileContext ctx) {
        String skill = ctx.getSkillName();
        if (skill == null) return true;
        return switch (skill) {
            case "spring-service", "default" -> true;
            default -> false;
        };
    }

    private TestGenResult generateTestForMethod(FileContext ctx, MethodContext method) {
        String testClassName = "GeneratedTest";

        String userPrompt = """
        你是一名资深 Java 测试工程师,请为下面的方法生成 **JUnit 5** 单元测试。

        【被测类信息】
        类名: %s
        类注解: %s
        依赖字段:
        %s

        【被测方法】
        签名: %s
        方法源码:
        %s

        【⚠️ 沙箱执行约束 - 这部分至关重要,违反将导致编译失败】

        测试代码将在隔离 Docker 沙箱中执行,**沙箱里只有 JUnit 5 和 Mockito,没有任何被测项目的类**。

        因此你必须遵守以下规则:

        1. **不要写 package 声明**(测试类放在默认包)

        2. **不要 import 任何被测项目的类**(包括被测类本身、Entity、Mapper、Service 等)

        3. **不要使用 @InjectMocks / @Mock 注入被测类**(因为沙箱里没有被测类)

        4. **必须把被测方法的代码完整复制到测试类内部**作为静态方法,直接测试这个静态副本

        5. 只能 import 以下类型:
           - org.junit.jupiter.api.* (Test, DisplayName, BeforeEach 等)
           - org.junit.jupiter.api.Assertions.*
           - java 标准库 (java.util.*, java.lang.*, java.time.* 等)

        6. 至少 3 个 @Test 方法,覆盖正常 / 边界 / 异常分支
        7. 每个测试用 @DisplayName 写中文说明
        8. 必须包含 Assertions 断言

        9. **直接输出 Java 源码,不要带 markdown 代码块标记,不要任何额外说明文字**

        【正确示例】
        如果被测方法是 `public boolean isPalindrome(String s)`,正确的测试代码应该是:

        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.DisplayName;
        import static org.junit.jupiter.api.Assertions.*;

        class GeneratedTest {
            // 把被测方法完整复制到这里
            static boolean isPalindrome(String s) {
                if (s == null) return false;
                int left = 0, right = s.length() - 1;
                while (left < right) {
                    if (s.charAt(left) != s.charAt(right)) return false;
                    left++;
                    right--;
                }
                return true;
            }

            @Test
            @DisplayName("正常分支:回文字符串返回 true")
            void normalCase() {
                assertTrue(isPalindrome("aba"));
                assertTrue(isPalindrome("abba"));
            }

            @Test
            @DisplayName("边界情况:空串和单字符")
            void boundaryCase() {
                assertTrue(isPalindrome(""));
                assertTrue(isPalindrome("a"));
            }

            @Test
            @DisplayName("异常输入:null 返回 false")
            void nullCase() {
                assertFalse(isPalindrome(null));
            }
        }

        【错误示例(以下写法会导致编译失败)】
        ❌ 错误1: package com.example.util;        // 不要 package
        ❌ 错误2: import com.example.util.MyUtil;   // 不要 import 被测类
        ❌ 错误3: @InjectMocks private MyUtil util; // 不要注入被测类
        ❌ 错误4: util.isPalindrome("aba")          // 不要调用被测类实例,调内部静态副本
        """.formatted(
                ctx.getClassName(),
                String.join(" ", ctx.getClassAnnotations()),
                String.join("\n", ctx.getFields()),
                method.getSignature(),
                method.getBody()
        );

        try {
            String testCode = chatClient.prompt().user(userPrompt).call().content();
            // LLM 偶尔会加 markdown 包裹,清掉
            testCode = stripMarkdownFence(testCode);

            log.info("[{}] ✓ 已生成: {}.{}",
                    name(), ctx.getClassName(), shortMethodName(method.getSignature()));

            return new TestGenResult(
                    ctx.getClassName(),
                    method.getSignature(),
                    testClassName,
                    testCode,
                    "覆盖正常/边界/异常分支"
            );
        } catch (Exception e) {
            log.warn("[{}] 生成失败: {}", name(), method.getSignature(), e);
            return null;
        }
    }

    private String stripMarkdownFence(String code) {
        if (code == null) return null;
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

    private String shortMethodName(String signature) {
        int paren = signature.indexOf('(');
        if (paren < 0) return signature;
        String head = signature.substring(0, paren);
        int lastSpace = head.lastIndexOf(' ');
        return lastSpace < 0 ? head : head.substring(lastSpace + 1);
    }
}