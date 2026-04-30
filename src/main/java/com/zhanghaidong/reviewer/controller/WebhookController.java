package com.zhanghaidong.reviewer.controller;

import com.zhanghaidong.reviewer.dto.FileDiff;
import com.zhanghaidong.reviewer.dto.PullRequestEvent;
import com.zhanghaidong.reviewer.dto.ReviewComment;
import com.zhanghaidong.reviewer.dto.ReviewResult;
import com.zhanghaidong.reviewer.service.GiteeService;
import com.zhanghaidong.reviewer.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Gitee Webhook 接收入口
 *
 * @author 张海东
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final GiteeService giteeService;
    private final ReviewService reviewService;
    private final String webhookPassword;

    private final ConcurrentMap<String, Long> processedPrs = new ConcurrentHashMap<>();

    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L; // 5 分钟
    public WebhookController(GiteeService giteeService,
                             ReviewService reviewService,
                             @Value("${gitee.webhook-password}") String webhookPassword) {
        this.giteeService = giteeService;
        this.reviewService = reviewService;
        this.webhookPassword = webhookPassword;
    }

    /**
     * 健康检查
     */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "ai-code-reviewer");
    }

    /**
     * Gitee Webhook 接收入口
     * Gitee 在请求头里会带 X-Gitee-Event,值为 "Merge Request Hook" 表示 PR 事件
     */
    @PostMapping("/gitee")
    public ResponseEntity<Map<String, String>> handle(
            @RequestHeader(value = "X-Gitee-Event", required = false) String event,
            @RequestHeader(value = "X-Gitee-Token", required = false) String tokenHeader,
            @RequestBody PullRequestEvent payload) {

        log.info("收到 Gitee Webhook: event={}, action={}", event, payload.getAction());

        // 1. 鉴权:Gitee 密码校验有两种方式
        //    - 明文密码(放在 body.password 字段)
        //    - 签名(放在 X-Gitee-Token header)
        //    这里用明文密码方式,简单
        if (!webhookPassword.equals(payload.getPassword())
                && !webhookPassword.equals(tokenHeader)) {
            log.warn("Webhook 鉴权失败");
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }

        // 2. 只处理 PR 事件,且只在 open / update 时触发
        if (!"Merge Request Hook".equals(event)) {
            log.info("非 PR 事件,跳过: {}", event);
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }
        if (payload.getAction() == null
                || !(payload.getAction().equals("open") || payload.getAction().equals("update"))) {
            log.info("非 open/update 动作,跳过: {}", payload.getAction());
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        // 3. 异步处理,Webhook 立即返回 200
        processAsync(payload);

        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    /**
     * 异步处理评审流程
     */
    @Async
    public void processAsync(PullRequestEvent payload) {
        try {
            String owner = payload.getOwner();
            String repo = payload.getRepoName();
            Integer number = payload.getPullRequest().getNumber();

            String headSha = payload.getPullRequest().getHead() != null
                    ? payload.getPullRequest().getHead().getSha()
                    : "unknown";

            // === 去重逻辑开始 ===
            String dedupKey = String.format("%s/%s#%d@%s", owner, repo, number, headSha);
            long now = System.currentTimeMillis();

            // 清理过期 key(避免内存膨胀)
            processedPrs.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS);

            Long lastTime = processedPrs.putIfAbsent(dedupKey, now);
            if (lastTime != null && now - lastTime < DEDUP_WINDOW_MS) {
                log.info("⏭️ 跳过重复评审: {} (上次评审于 {} 秒前)",
                        dedupKey, (now - lastTime) / 1000);
                return;
            }

            log.info("===== 开始评审: {}/{} PR #{} =====", owner, repo, number);

            // 拿 diff
            List<FileDiff> files = giteeService.getPullRequestFiles(owner, repo, number);
            if (files == null || files.isEmpty()) {
                log.warn("PR 没有文件变更");
                return;
            }

            // 只评审 Java 文件
            List<FileDiff> javaFiles = files.stream()
                    .filter(f -> f.getFilename() != null && f.getFilename().endsWith(".java"))
                    .toList();
            log.info("Java 文件数: {} / 总变更文件数: {}", javaFiles.size(), files.size());

            if (javaFiles.isEmpty()) {
                log.info("没有 Java 文件变更,跳过");
                return;
            }

            // 调 LLM 评审
            ReviewResult result = reviewService.review(javaFiles);

            // 控制台打印(Day 1 核心交付物)
            printResult(owner, repo, number, result);

            // Day 2 会把这里改成回写到 Gitee,Day 1 先发一条整体评论验证 API 通畅
            String summary = buildSummary(result);
            giteeService.createPullRequestComment(owner, repo, number, summary);

            log.info("===== 评审完成: {}/{} PR #{} =====", owner, repo, number);

        } catch (Exception e) {
            log.error("评审流程失败", e);
        }
    }

    private void printResult(String owner, String repo, Integer number, ReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
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

    private String buildSummary(ReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 AI 代码评审报告\n\n");
        sb.append("**总分**: ").append(result.getOverallScore()).append(" / 100\n\n");
        sb.append("**总结**: ").append(result.getSummary()).append("\n\n");
        if (result.getComments() != null && !result.getComments().isEmpty()) {
            sb.append("### 详细问题\n\n");
            int i = 1;
            for (ReviewComment c : result.getComments()) {
                sb.append("**").append(i++).append(". [").append(c.getSeverity()).append("] [")
                        .append(c.getCategory()).append("] ")
                        .append(c.getFilePath()).append(":").append(c.getLineNumber()).append("**\n");
                sb.append("- 问题: ").append(c.getMessage()).append("\n");
                sb.append("- 建议: ").append(c.getSuggestion()).append("\n\n");
            }
        }
        sb.append("\n_由 ai-code-reviewer 自动生成_");
        return sb.toString();
    }
}
