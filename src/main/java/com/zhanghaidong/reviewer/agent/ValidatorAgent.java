package com.zhanghaidong.reviewer.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.zhanghaidong.reviewer.dto.AgentState;
import com.zhanghaidong.reviewer.dto.TestGenResult;
import com.zhanghaidong.reviewer.dto.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 验证 TestGenAgent 生成的测试代码
 *
 * 当前实现: 语法级验证(JavaParser 解析 + 静态规则检查)
 * 后续可扩展: 完整 mvn 编译(需在 Docker 沙箱中拉取项目依赖)
 *
 * @author 张海东
 */
@Slf4j
@Component
public class ValidatorAgent implements Agent {

    private final JavaParser javaParser = new JavaParser();

    @Override
    public String name() {
        return "ValidatorAgent";
    }

    @Override
    public boolean shouldExecute(AgentState state) {
        return state.getGeneratedTests() != null && !state.getGeneratedTests().isEmpty();
    }

    @Override
    public void execute(AgentState state) {
        log.info("🤖 [{}] 开始执行,待验证测试数 {}", name(), state.getGeneratedTests().size());
        int passed = 0;
        int failed = 0;

        for (Map.Entry<String, TestGenResult> entry : state.getGeneratedTests().entrySet()) {
            TestGenResult tg = entry.getValue();
            ValidationResult vr = validate(tg);
            state.getValidations().add(vr);
            if (vr.isPassed()) passed++;
            else failed++;
        }
        log.info("✅ [{}] 完成: 通过 {} / 失败 {}", name(), passed, failed);
    }

    private ValidationResult validate(TestGenResult tg) {
        List<String> issues = new ArrayList<>();

        // === 1. JavaParser 解析 ===
        ParseResult<CompilationUnit> parseResult = javaParser.parse(tg.getTestCode());
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            String errMsg = parseResult.getProblems().stream()
                    .limit(3)
                    .map(Problem::getMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("解析失败");
            return new ValidationResult(tg.getTestClassName(), "SYNTAX", false,
                    "语法错误: " + errMsg, issues);
        }
        CompilationUnit cu = parseResult.getResult().get();

        // === 2. 类结构检查 ===
        Optional<ClassOrInterfaceDeclaration> classOpt =
                cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (classOpt.isEmpty()) {
            return new ValidationResult(tg.getTestClassName(), "SYNTAX", false,
                    "未找到类声明", issues);
        }
        ClassOrInterfaceDeclaration testClass = classOpt.get();

        // === 3. 必须有 @Test 注解的方法 ===
        long testMethodCount = testClass.getMethods().stream()
                .filter(this::hasTestAnnotation)
                .count();

        if (testMethodCount == 0) {
            return new ValidationResult(tg.getTestClassName(), "SYNTAX", false,
                    "未找到 @Test 方法", issues);
        }
        if (testMethodCount < 3) {
            issues.add("⚠️ 测试方法数 " + testMethodCount + ",建议至少 3 个(正常/边界/异常)");
        }

        // === 4. 必须包含断言 ===
        boolean hasAssertion = testClass.getMethods().stream()
                .filter(this::hasTestAnnotation)
                .anyMatch(this::hasAssertion);

        if (!hasAssertion) {
            return new ValidationResult(tg.getTestClassName(), "SYNTAX", false,
                    "测试方法缺少断言(assertXxx),疑似假测试", issues);
        }

        // === 5. 应使用 Mockito ===
        boolean hasMockito = tg.getTestCode().contains("@Mock")
                || tg.getTestCode().contains("@InjectMocks")
                || tg.getTestCode().contains("Mockito.");
        if (!hasMockito) {
            issues.add("⚠️ 未使用 Mockito,可能未隔离外部依赖");
        }

        // === 6. JUnit 5 规范 ===
        if (tg.getTestCode().contains("import org.junit.Test;")) {
            issues.add("⚠️ 使用了 JUnit 4 的 @Test,应改用 JUnit 5 (org.junit.jupiter.api.Test)");
        }

        log.info("[{}] ✓ {} 验证通过 ({} 个测试方法,{} 条建议)",
                name(), tg.getTestClassName(), testMethodCount, issues.size());

        return new ValidationResult(tg.getTestClassName(), "SYNTAX", true, null, issues);
    }

    private boolean hasTestAnnotation(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            if ("Test".equals(ann.getNameAsString())) return true;
        }
        return false;
    }

    private boolean hasAssertion(MethodDeclaration method) {
        String body = method.getBody().map(Object::toString).orElse("");
        return body.contains("assert")
                || body.contains("Assertions.")
                || body.contains("verify(");
    }
}