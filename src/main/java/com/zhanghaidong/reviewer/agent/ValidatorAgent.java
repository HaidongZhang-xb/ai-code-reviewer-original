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
import com.zhanghaidong.reviewer.service.DockerSandboxService;
import com.zhanghaidong.reviewer.service.DockerSandboxService.SandboxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试代码验证 Agent
 *
 * 完整复刻 Meta TestGen-LLM 论文的过滤管道(本项目实现 Level 1-3):
 *  - Level 1 SYNTAX:  JavaParser 语法解析(快,本地执行)
 *  - Level 2 COMPILE: Docker 容器内 mvn test-compile
 *  - Level 3 EXECUTE: Docker 容器内 mvn test
 *  - Level 4 COVERAGE: JaCoCo 覆盖率(规划中)
 *
 * 任一级失败即停止后续验证(fail fast)
 *
 * @author 张海东
 */
@Slf4j
@Component
public class ValidatorAgent implements Agent {

    private final JavaParser javaParser = new JavaParser();
    private final DockerSandboxService sandboxService;

    public ValidatorAgent(DockerSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

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
        // ============= Level 1: 语法验证 =============
        ValidationResult syntax = validateSyntax(tg);
        if (!syntax.isPassed()) {
            log.warn("[{}] ❌ Level 1 语法失败: {}", name(), syntax.getErrorMessage());
            return syntax;
        }
        log.info("[{}] ✅ Level 1 语法通过: {}", name(), tg.getTestClassName());

        // ============= Level 2/3: Docker 沙箱 =============
        if (!sandboxService.isEnabled()) {
            log.info("[{}] 沙箱未启用,跳过 Level 2/3,以 Level 1 结果为准", name());
            return syntax;
        }

        SandboxResult sandbox = sandboxService.compileAndTest(
                tg.getTestClassName(), tg.getTestCode());

        return mergeWithSandbox(syntax, sandbox);
    }

    /**
     * Level 1: JavaParser 语法验证(原逻辑保留)
     */
    private ValidationResult validateSyntax(TestGenResult tg) {
        List<String> issues = new ArrayList<>();

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

        Optional<ClassOrInterfaceDeclaration> classOpt =
                cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (classOpt.isEmpty()) {
            return new ValidationResult(tg.getTestClassName(), "SYNTAX", false,
                    "未找到类声明", issues);
        }
        ClassOrInterfaceDeclaration testClass = classOpt.get();

        long testMethodCount = testClass.getMethods().stream()
                .filter(this::hasTestAnnotation)
                .count();

        if (testMethodCount == 0) {
            return new ValidationResult(tg.getTestClassName(), "SYNTAX", false,
                    "未找到 @Test 方法", issues);
        }
        if (testMethodCount < 3) {
            issues.add("⚠️ 测试方法数 " + testMethodCount + ",建议至少 3 个");
        }

        boolean hasAssertion = testClass.getMethods().stream()
                .filter(this::hasTestAnnotation)
                .anyMatch(this::hasAssertion);
        if (!hasAssertion) {
            return new ValidationResult(tg.getTestClassName(), "SYNTAX", false,
                    "测试方法缺少断言,疑似假测试", issues);
        }

        boolean hasMockito = tg.getTestCode().contains("@Mock")
                || tg.getTestCode().contains("@InjectMocks");
        if (!hasMockito) {
            issues.add("⚠️ 未使用 Mockito,可能未隔离外部依赖");
        }

        if (tg.getTestCode().contains("import org.junit.Test;")) {
            issues.add("⚠️ 使用了 JUnit 4 的 @Test,应改用 JUnit 5");
        }

        return new ValidationResult(tg.getTestClassName(), "SYNTAX", true, null, issues);
    }

    /**
     * 把 Level 1 结果与沙箱结果合并
     */
    private ValidationResult mergeWithSandbox(ValidationResult syntax, SandboxResult sandbox) {
        ValidationResult result = new ValidationResult();
        result.setTestClassName(syntax.getTestClassName());
        result.setIssues(syntax.getIssues() != null ? syntax.getIssues() : new ArrayList<>());

        // Level 2: 编译
        result.setCompileSuccess(sandbox.isCompilePassed());
        result.setCompileOutput(truncate(sandbox.getCompileOutput()));

        if (!sandbox.isCompilePassed()) {
            result.setHighestPassedLevel("SYNTAX");
            result.setPassed(false);
            result.setErrorMessage("Level 2 编译失败: " + truncate(sandbox.getCompileOutput()));
            log.warn("[{}] ❌ Level 2 编译失败: {}", name(), result.getTestClassName());
            return result;
        }
        log.info("[{}] ✅ Level 2 编译通过: {}", name(), result.getTestClassName());

        // Level 3: 执行
        result.setExecuteSuccess(sandbox.isExecutePassed());
        result.setExecuteOutput(truncate(sandbox.getExecuteOutput()));

        if (sandbox.getStats() != null) {
            result.setTestsRun(sandbox.getStats().testsRun);
            result.setTestsPassed(sandbox.getStats().testsPassed);
            result.setTestsFailed(sandbox.getStats().testsFailed);
        }

        if (!sandbox.isExecutePassed()) {
            result.setHighestPassedLevel("COMPILE");
            result.setPassed(false);
            result.setErrorMessage("Level 3 执行失败: " + sandbox.getErrorMessage());
            log.warn("[{}] ❌ Level 3 执行失败: {}", name(), result.getTestClassName());
            return result;
        }

        result.setHighestPassedLevel("EXECUTE");
        result.setPassed(true);
        log.info("[{}] ✅ Level 3 执行通过: {} ({} run, {} passed, {} failed)",
                name(), result.getTestClassName(),
                result.getTestsRun(), result.getTestsPassed(), result.getTestsFailed());
        return result;
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

    private String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= 1500) return s;
        return s.substring(0, 1500) + "\n... [输出已截断] ...";
    }
}