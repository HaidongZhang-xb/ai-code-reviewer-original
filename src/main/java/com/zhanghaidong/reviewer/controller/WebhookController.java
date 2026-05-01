package com.zhanghaidong.reviewer.controller;

import com.zhanghaidong.reviewer.dto.FileDiff;
import com.zhanghaidong.reviewer.dto.PullRequestEvent;
import com.zhanghaidong.reviewer.dto.ReviewComment;
import com.zhanghaidong.reviewer.dto.ReviewResult;
import com.zhanghaidong.reviewer.service.GiteeService;
import com.zhanghaidong.reviewer.service.PatchPositionResolver;
import com.zhanghaidong.reviewer.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import com.zhanghaidong.reviewer.dto.FileContext;
import com.zhanghaidong.reviewer.service.JavaContextExtractor;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.zhanghaidong.reviewer.agent.AgentPipeline;
import com.zhanghaidong.reviewer.dto.AgentState;
import com.zhanghaidong.reviewer.dto.TestGenResult;
import com.zhanghaidong.reviewer.dto.ValidationResult;

/**
 * Gitee Webhook 接收入口
 *
 * @author 张海东
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    /** PR 微小改动跳过阈值 */
    private static final int MIN_CHANGED_LINES = 3;

    /** 单次评审最多发多少条行级评论(避免刷屏) */
    private static final int MAX_LINE_COMMENTS = 15;

    /** 去重窗口 5 分钟 */
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L;

    private final GiteeService giteeService;
    private final ReviewService reviewService;
    private final PatchPositionResolver positionResolver;
    private final String webhookPassword;
    private final JavaContextExtractor contextExtractor;
    private final AgentPipeline agentPipeline;
    /** 已处理 PR 的去重缓存,key = owner/repo#number@head_sha */
    private final ConcurrentMap<String, Long> processedPrs = new ConcurrentHashMap<>();

    public WebhookController(GiteeService giteeService,
                             ReviewService reviewService,
                             PatchPositionResolver positionResolver,
                             JavaContextExtractor contextExtractor,
                             AgentPipeline agentPipeline,
                             @Value("${gitee.webhook-password}") String webhookPassword) {
        this.giteeService = giteeService;
        this.reviewService = reviewService;
        this.positionResolver = positionResolver;
        this.contextExtractor = contextExtractor;
        this.agentPipeline = agentPipeline;
        this.webhookPassword = webhookPassword;
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "ai-code-reviewer");
    }

    @PostMapping("/gitee")
    public ResponseEntity<Map<String, String>> handle(
            @RequestHeader(value = "X-Gitee-Event", required = false) String event,
            @RequestHeader(value = "X-Gitee-Token", required = false) String tokenHeader,
            @RequestBody PullRequestEvent payload) {

        log.info("收到 Gitee Webhook: event={}, action={}", event, payload.getAction());

        if (!webhookPassword.equals(payload.getPassword())
                && !webhookPassword.equals(tokenHeader)) {
            log.warn("Webhook 鉴权失败");
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }

        if (!"Merge Request Hook".equals(event)) {
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }
        if (payload.getAction() == null
                || !(payload.getAction().equals("open") || payload.getAction().equals("update"))) {
            log.info("非 open/update 动作,跳过: {}", payload.getAction());
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        processAsync(payload);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @Async
    public void processAsync(PullRequestEvent payload) {
        try {
            String owner = payload.getOwner();
            String repo = payload.getRepoName();
            Integer number = payload.getPullRequest().getNumber();
            String headSha = payload.getPullRequest().getHead() != null
                    ? payload.getPullRequest().getHead().getSha()
                    : "unknown";

            // ===== 去重 =====
            String dedupKey = String.format("%s/%s#%d@%s", owner, repo, number, headSha);
            long now = System.currentTimeMillis();
            processedPrs.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS);
            Long lastTime = processedPrs.putIfAbsent(dedupKey, now);
            if (lastTime != null && now - lastTime < DEDUP_WINDOW_MS) {
                log.info("⏭️ 跳过重复评审: {} (上次评审于 {} 秒前)",
                        dedupKey, (now - lastTime) / 1000);
                return;
            }

            log.info("===== 开始评审: {}/{} PR #{} (sha={}) =====",
                    owner, repo, number,
                    headSha.length() >= 7 ? headSha.substring(0, 7) : headSha);

            // ===== 拉文件 =====
            List<FileDiff> files = giteeService.getPullRequestFiles(owner, repo, number);
            if (files == null || files.isEmpty()) {
                log.warn("PR 没有文件变更");
                return;
            }

            List<FileDiff> javaFiles = files.stream()
                    .filter(f -> f.getFilename() != null && f.getFilename().endsWith(".java"))
                    .filter(f -> !isTrivialChange(f))
                    .toList();
            log.info("有效 Java 文件数: {} / 总变更文件数: {}", javaFiles.size(), files.size());

            if (javaFiles.isEmpty()) {
                log.info("没有需要评审的 Java 文件,跳过");
                return;
            }

            // ===== 上下文提取 =====
            Map<String, FileContext> contextMap = buildContextMap(owner, repo, headSha, javaFiles);

            // ===== Day 5: 构建 AgentState 并启动 Pipeline =====
            AgentState state = new AgentState();
            state.setOwner(owner);
            state.setRepo(repo);
            state.setPrNumber(number);
            state.setHeadSha(headSha);
            state.setFiles(javaFiles);
            state.setContextMap(contextMap);

            agentPipeline.run(state);

            // ===== 输出结果 =====
            ReviewResult result = state.getReviewResult();
            if (result == null) {
                result = new ReviewResult(0, "无评审结果", List.of());
            }
            printResult(owner, repo, number, result);

            // ===== 回写 Gitee =====
            postToGitee(owner, repo, number, javaFiles, state);

            log.info("===== 评审完成: {}/{} PR #{} =====", owner, repo, number);

        } catch (Exception e) {
            log.error("评审流程失败", e);
        }
    }

    /**
     * Day 3:拉每个 Java 文件的完整源码,用 JavaParser 提取上下文
     * 失败的文件返回空 FileContext,不阻塞主流程
     */
    private Map<String, FileContext> buildContextMap(String owner, String repo,
                                                     String headSha, List<FileDiff> javaFiles) {
        Map<String, FileContext> contextMap = new HashMap<>();
        for (FileDiff f : javaFiles) {
            // 删除的文件没必要提上下文
            if ("removed".equalsIgnoreCase(f.getStatus())) continue;

            String fullSource = giteeService.getFileContent(owner, repo, f.getFilename(), headSha);
            if (fullSource == null) {
                log.warn("无法拉取文件原文: {}", f.getFilename());
                continue;
            }

            Set<Integer> changedLines = positionResolver.extractChangedLines(f.getPatchText());
            FileContext ctx = contextExtractor.extract(f.getFilename(), fullSource, changedLines);
            contextMap.put(f.getFilename(), ctx);
        }
        return contextMap;
    }

    /**
     * 判断微小改动:additions + deletions < 阈值
     */
    private boolean isTrivialChange(FileDiff f) {
        int adds = parseIntSafe(f.getAdditions());
        int dels = parseIntSafe(f.getDeletions());
        boolean trivial = adds + dels < MIN_CHANGED_LINES;
        if (trivial) {
            log.debug("跳过微小改动: file={}, +{}, -{}", f.getFilename(), adds, dels);
        }
        return trivial;
    }

    private int parseIntSafe(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 把评审结果回写到 Gitee
     * 策略:
     *  1. 始终发一条整体总结评论(放总分和摘要)
     *  2. 每条 LLM 给的具体问题尝试发行级评论
     *  3. 行级评论失败的(行号算不出来),收集起来追加到整体评论里
     */
    private void postToGitee(String owner, String repo, Integer number,
                             List<FileDiff> javaFiles, AgentState state) {
        ReviewResult result = state.getReviewResult();
        if (result == null) result = new ReviewResult(0, "无评审结果", List.of());

        Map<String, Map<Integer, Integer>> fileLineMaps = new HashMap<>();
        for (FileDiff f : javaFiles) {
            fileLineMaps.put(f.getFilename(),
                    positionResolver.buildLineToPositionMap(f.getPatchText()));
        }

        List<ReviewComment> comments = result.getComments() == null ? List.of() : result.getComments();
        List<ReviewComment> failedComments = new java.util.ArrayList<>();
        int posted = 0;

        // 逐条发行级评论(逻辑保持 Day 4 不变)
        for (ReviewComment c : comments) {
            if (posted >= MAX_LINE_COMMENTS) {
                failedComments.add(c);
                continue;
            }

            FileDiff file = javaFiles.stream()
                    .filter(f -> f.getFilename().equals(c.getFilePath()))
                    .findFirst()
                    .orElse(null);
            if (file == null) {
                failedComments.add(c);
                continue;
            }

            Integer position = null;
            Integer realLine = null;
            PatchPositionResolver.LineAndPosition hit =
                    positionResolver.resolveBySnippet(file.getPatchText(), c.getCodeSnippet());
            if (hit != null) {
                position = hit.position();
                realLine = hit.line();
            } else {
                Map<Integer, Integer> lineMap = fileLineMaps.get(c.getFilePath());
                if (lineMap != null && c.getLineNumber() != null) {
                    position = lineMap.get(c.getLineNumber());
                    realLine = c.getLineNumber();
                }
            }

            if (position == null) {
                log.warn("无法定位: file={}, llmLine={}, snippet=[{}]",
                        c.getFilePath(), c.getLineNumber(), c.getCodeSnippet());
                failedComments.add(c);
                continue;
            }

            log.info("📍 定位成功: {} 第{}行 (position={}) - 来源:{}",
                    c.getFilePath(), realLine, position,
                    hit != null ? "snippet反查" : "LLM行号");

            boolean ok = giteeService.createLineComment(
                    owner, repo, number,
                    c.getFilePath(), position,
                    formatLineCommentBody(c)
            );
            if (ok) posted++;
            else failedComments.add(c);
        }

        // 整体总结评论(包含生成的测试)
        String summary = buildSummary(state, failedComments, posted);
        giteeService.createPullRequestComment(owner, repo, number, summary);

        log.info("评论统计: 行级={}, 降级={}, 生成测试={}",
                posted, failedComments.size(), state.getGeneratedTests().size());
    }

    /**
     * 单条行级评论的 markdown 格式
     */
    private String formatLineCommentBody(ReviewComment c) {
        String emoji = switch (c.getSeverity() == null ? "" : c.getSeverity()) {
            case "BLOCKER", "CRITICAL" -> "🚨";
            case "MAJOR" -> "⚠️";
            case "MINOR" -> "💡";
            default -> "ℹ️";
        };
        return String.format(
                "%s **[%s] [%s]** %s\n\n**建议**: %s\n\n_由 ai-code-reviewer 自动生成_",
                emoji,
                c.getSeverity(), c.getCategory(),
                c.getMessage(),
                c.getSuggestion() == null ? "(无)" : c.getSuggestion()
        );
    }

    /**
     * 整体总结评论
     */
    private String buildSummary(AgentState state, List<ReviewComment> failed, int posted) {
        ReviewResult result = state.getReviewResult();
        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 AI 代码评审报告 (Multi-Agent)\n\n");
        sb.append("**总分**: ").append(result.getOverallScore()).append(" / 100  \n");
        sb.append("**摘要**: ").append(result.getSummary()).append("  \n");
        sb.append("**已发布行级评论**: ").append(posted).append(" 条  \n");
        sb.append("**执行 Agent**: ").append(String.join(" → ", state.getExecutedAgents())).append("\n\n");

        // 降级评论
        if (!failed.isEmpty()) {
            sb.append("### ⚠️ 以下问题未能定位到具体行号,在此汇总:\n\n");
            int i = 1;
            for (ReviewComment c : failed) {
                sb.append("**").append(i++).append(". [").append(c.getSeverity()).append("] [")
                        .append(c.getCategory()).append("] ")
                        .append(c.getFilePath()).append(":").append(c.getLineNumber()).append("**\n");
                sb.append("- 问题: ").append(c.getMessage()).append("\n");
                sb.append("- 建议: ").append(c.getSuggestion()).append("\n\n");
            }
        }

        // === Day 5 新增:生成的测试代码 ===
        if (!state.getGeneratedTests().isEmpty()) {
            sb.append("### 🧪 自动生成的单元测试\n\n");
            for (Map.Entry<String, TestGenResult> entry : state.getGeneratedTests().entrySet()) {
                TestGenResult tg = entry.getValue();
                ValidationResult vr = state.getValidations().stream()
                        .filter(v -> tg.getTestClassName().equals(v.getTestClassName()))
                        .findFirst().orElse(null);

                sb.append("#### ").append(tg.getTestClassName());
                if (vr != null) {
                    sb.append(vr.isPassed() ? " ✅" : " ❌");
                }
                sb.append("\n\n");
                sb.append("**被测方法**: `").append(tg.getTargetMethod()).append("`  \n");
                sb.append("**说明**: ").append(tg.getDescription()).append("  \n");
                if (vr != null) {
                    sb.append("**验证级别**: ").append(vr.getLevel()).append("  \n");
                    if (!vr.isPassed()) {
                        sb.append("**❌ 验证失败**: ").append(vr.getErrorMessage()).append("  \n");
                    }
                    if (vr.getIssues() != null && !vr.getIssues().isEmpty()) {
                        sb.append("**建议**:\n");
                        for (String issue : vr.getIssues()) {
                            sb.append("- ").append(issue).append("\n");
                        }
                    }
                }
                sb.append("\n```java\n").append(tg.getTestCode()).append("\n```\n\n");
            }
        }

        sb.append("\n_由 ai-code-reviewer 自动生成 · Multi-Agent Pipeline_");
        return sb.toString();
    }

    private void printResult(String owner, String repo, Integer number, ReviewResult result) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("===========================================================\n");
        sb.append(" 评审结果: ").append(owner).append("/").append(repo).append(" PR #").append(number).append("\n");
        sb.append("===========================================================\n");
        sb.append(" 总分: ").append(result.getOverallScore()).append("\n");
        sb.append(" 总结: ").append(result.getSummary()).append("\n");
        sb.append("-----------------------------------------------------------\n");
        if (result.getComments() == null || result.getComments().isEmpty()) {
            sb.append(" 无具体评论\n");
        } else {
            int i = 1;
            for (ReviewComment c : result.getComments()) {
                sb.append(String.format(" [%d] [%s][%s] %s:%d\n",
                        i++, c.getSeverity(), c.getCategory(), c.getFilePath(), c.getLineNumber()));
                sb.append("     问题: ").append(c.getMessage()).append("\n");
                sb.append("     建议: ").append(c.getSuggestion()).append("\n");
            }
        }
        sb.append("===========================================================\n");
        log.info(sb.toString());
    }
}