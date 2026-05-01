package com.zhanghaidong.reviewer.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker 沙箱执行服务
 *
 * 流程:
 *  1. 在临时目录创建 Maven 工程结构
 *  2. 拷贝模板 pom.xml,写入测试代码到 src/test/java
 *  3. docker run 容器,挂载工作目录,执行 mvn 命令
 *  4. 解析输出,返回结果
 *
 * 设计要点:
 *  - 容器只跑测试代码,不依赖被测项目(适配桩通过 stub 占位)
 *  - 每次执行用独立工作目录,执行完清理
 *  - 超时强杀,防止卡死
 *
 * @author 张海东
 */
@Slf4j
@Service
public class DockerSandboxService {

    @Value("${sandbox.enabled:false}")
    private boolean enabled;

    @Value("${sandbox.docker-image:maven:3.9-eclipse-temurin-17}")
    private String dockerImage;

    @Value("${sandbox.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${sandbox.work-dir-base:${java.io.tmpdir}/ai-code-reviewer-sandbox}")
    private String workDirBase;

    /** 缓存的 pom.xml 模板内容 */
    private String pomTemplate;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("🐳 Docker 沙箱已禁用");
            return;
        }
        try {
            ClassPathResource resource = new ClassPathResource("sandbox/template/pom.xml");
            this.pomTemplate = new String(resource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            Path baseDir = Paths.get(workDirBase);
            Files.createDirectories(baseDir);

            // 启动时清理上次留下的孤儿目录
            cleanupOrphanDirs(baseDir);

            log.info("🐳 Docker 沙箱已就绪: image={}, timeout={}s, workDir={}",
                    dockerImage, timeoutSeconds, workDirBase);
        } catch (Exception e) {
            log.error("🐳 Docker 沙箱初始化失败,将禁用沙箱", e);
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 编译并执行测试代码
     *
     * @param testClassName 测试类简单名(如 SetmealServiceImplTest)
     * @param testCode      完整测试类源码
     * @return 沙箱执行结果
     */
    public SandboxResult compileAndTest(String testClassName, String testCode) {
        if (!enabled) {
            return SandboxResult.disabled();
        }

        Path workDir = null;
        try {
            // === 1. 准备工作目录 ===
            workDir = Files.createDirectory(Paths.get(workDirBase, "run-" + UUID.randomUUID()));
            log.debug("🐳 工作目录: {}", workDir);

            Path testJavaDir = workDir.resolve("src/test/java");
            Files.createDirectories(testJavaDir);

            // 写 pom.xml
            Files.writeString(workDir.resolve("pom.xml"), pomTemplate, StandardCharsets.UTF_8);

            // 提取 package 创建对应目录;写测试代码
            String packagePath = extractPackagePath(testCode);
            Path testFileDir = packagePath.isEmpty() ? testJavaDir : testJavaDir.resolve(packagePath);
            Files.createDirectories(testFileDir);
            Path testFile = testFileDir.resolve(testClassName + ".java");
            Files.writeString(testFile, testCode, StandardCharsets.UTF_8);

            // 写一个空 src/main/java(maven 要求)
            Files.createDirectories(workDir.resolve("src/main/java"));

            // === 2. 编译 ===
            log.info("🐳 [{}] 开始编译...", testClassName);
            ExecResult compile = runDocker(workDir, "mvn",
                    "test-compile",
                    "--batch-mode",
                    "--no-transfer-progress");

            if (compile.exitCode != 0) {
                log.warn("🐳 [{}] ❌ 编译失败 (exit={})", testClassName, compile.exitCode);
                return SandboxResult.compileFailure(compile.output);
            }
            log.info("🐳 [{}] ✅ 编译通过", testClassName);

            // === 3. 执行 ===
            log.info("🐳 [{}] 开始执行测试...", testClassName);
            ExecResult test = runDocker(workDir, "mvn",
                    "test",
                    "--batch-mode",
                    "--no-transfer-progress",
                    "-Dsurefire.useFile=false",
                    "-Dsurefire.printSummary=true");

            TestStats stats = parseTestResults(test.output);

            // mvn test 失败(退出码非 0)不一定是测试失败,也可能是依赖问题
            // 优先看 stats 是否解析出结果
            if (stats.testsRun > 0) {
                log.info("🐳 [{}] 测试结果: {} 跑,{} 通过,{} 失败",
                        testClassName, stats.testsRun, stats.testsPassed, stats.testsFailed);
                return SandboxResult.executeSuccess(compile.output, test.output, stats);
            } else {
                log.warn("🐳 [{}] ❌ 测试执行异常,无法解析结果 (exit={})",
                        testClassName, test.exitCode);
                return SandboxResult.executeFailure(compile.output, test.output);
            }

        } catch (Exception e) {
            log.error("🐳 沙箱执行异常: {}", testClassName, e);
            return SandboxResult.error("沙箱执行异常: " + e.getMessage());
        } finally {
            if (workDir != null) {
                cleanup(workDir);
            }
        }
    }

    /**
     * 执行 docker 命令
     */
    private ExecResult runDocker(Path workDir, String... mvnArgs) throws IOException {
        // docker run --rm -v <workDir>:/work -w /work <image> <mvnArgs...>
        CommandLine cmd = new CommandLine("docker");
        cmd.addArgument("run");
        cmd.addArgument("--rm");
        cmd.addArgument("-v");
        // Windows 路径需要转换:K:\xxx\yyy -> /k/xxx/yyy(Docker Desktop 自动支持原生路径,这里直接用)
        cmd.addArgument(workDir.toAbsolutePath() + ":/work", false);
        cmd.addArgument("-w");
        cmd.addArgument("/work");
        // 资源限制
        cmd.addArgument("--cpus=1");
        cmd.addArgument("--memory=1g");
        cmd.addArgument(dockerImage);
        for (String arg : mvnArgs) {
            cmd.addArgument(arg, false);
        }

        log.debug("🐳 执行命令: {}", cmd);

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outStream);

        DefaultExecutor executor = DefaultExecutor.builder().get();
        executor.setStreamHandler(streamHandler);
        executor.setWatchdog(ExecuteWatchdog.builder()
                .setTimeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .get());
        executor.setExitValues(null); // 允许任意退出码

        int exitCode;
        try {
            exitCode = executor.execute(cmd);
        } catch (org.apache.commons.exec.ExecuteException ee) {
            exitCode = ee.getExitValue();
        }

        return new ExecResult(exitCode, outStream.toString(StandardCharsets.UTF_8));
    }

    /**
     * 从 surefire 输出解析测试统计
     * 典型行: "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0"
     */
    private TestStats parseTestResults(String output) {
        TestStats stats = new TestStats();
        Pattern p = Pattern.compile(
                "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
        Matcher m = p.matcher(output);
        // 取最后一次匹配(汇总行)
        while (m.find()) {
            stats.testsRun = Integer.parseInt(m.group(1));
            int failures = Integer.parseInt(m.group(2));
            int errors = Integer.parseInt(m.group(3));
            int skipped = Integer.parseInt(m.group(4));
            stats.testsFailed = failures + errors;
            stats.testsPassed = stats.testsRun - stats.testsFailed - skipped;
        }
        return stats;
    }

    private String extractPackagePath(String testCode) {
        Pattern p = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
                Pattern.MULTILINE);
        Matcher m = p.matcher(testCode);
        if (m.find()) {
            return m.group(1).replace('.', '/');
        }
        return "";
    }

    /**
     * 启动时清理工作目录下的所有 run-* 残留
     */
    private void cleanupOrphanDirs(Path baseDir) {
        try {
            if (!Files.exists(baseDir)) return;
            long count = Files.list(baseDir)
                    .filter(p -> p.getFileName().toString().startsWith("run-"))
                    .peek(this::deleteRecursively)
                    .count();
            if (count > 0) {
                log.info("🐳 启动清理: 删除孤儿沙箱目录 {} 个", count);
            }
        } catch (IOException e) {
            log.warn("🐳 启动清理失败: {}", e.getMessage());
        }
    }
    private void cleanup(Path workDir) {
        deleteRecursively(workDir);
    }

    private void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) return;
            Files.walk(path)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // Windows 下文件可能被 Docker 进程短暂占用,稍等再试一次
                            try {
                                Thread.sleep(100);
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                                log.warn("🐳 清理失败,残留: {}", p);
                            }
                        }
                    });
        } catch (IOException e) {
            log.warn("🐳 清理目录异常: {}, error={}", path, e.getMessage());
        }
    }

    // =====================  内部类  =====================

    private record ExecResult(int exitCode, String output) {}

    public static class TestStats {
        public int testsRun;
        public int testsPassed;
        public int testsFailed;
    }

    /**
     * 沙箱执行的最终结果
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SandboxResult {
        private boolean compilePassed;
        private boolean executePassed;
        private String compileOutput;
        private String executeOutput;
        private String errorMessage;
        private TestStats stats;

        public static SandboxResult disabled() {
            return new SandboxResult(false, false, null, null, "沙箱已禁用", null);
        }

        public static SandboxResult error(String msg) {
            return new SandboxResult(false, false, null, null, msg, null);
        }

        public static SandboxResult compileFailure(String compileOutput) {
            return new SandboxResult(false, false, compileOutput, null,
                    "Maven 编译失败", null);
        }

        public static SandboxResult executeFailure(String compileOutput, String executeOutput) {
            return new SandboxResult(true, false, compileOutput, executeOutput,
                    "测试执行失败,无法解析结果", null);
        }

        public static SandboxResult executeSuccess(String compileOutput, String executeOutput, TestStats stats) {
            // 注意:测试通过率不影响 executePassed,执行成功就是 true
            return new SandboxResult(true, true, compileOutput, executeOutput, null, stats);
        }
    }
}