package com.zhanghaidong.reviewer.service;

import com.zhanghaidong.reviewer.dto.FileContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 决定一个文件走哪个 Skill
 * 路由规则按优先级从高到低尝试匹配
 *
 * @author 张海东
 */
@Slf4j
@Component
public class SkillRouter {

    private static final List<String> CONTROLLER_ANNOTATIONS = List.of(
            "@RestController", "@Controller");

    private static final List<String> MAPPER_ANNOTATIONS = List.of(
            "@Mapper", "@Repository");

    private static final List<String> SERVICE_ANNOTATIONS = List.of(
            "@Service", "@Component");

    /** 并发关键字,在文件原文中出现即触发 */
    private static final List<String> CONCURRENT_KEYWORDS = List.of(
            "synchronized", "ReentrantLock", "ReadWriteLock", "volatile",
            "AtomicInteger", "AtomicLong", "AtomicReference",
            "ConcurrentHashMap", "CopyOnWriteArrayList",
            "CompletableFuture", "ExecutorService", "ThreadPoolExecutor",
            "CountDownLatch", "CyclicBarrier", "Semaphore");

    /**
     * 路由
     *
     * @param ctx        AST 提取结果(可空)
     * @param filename   文件路径
     * @param fullSource 文件原文(用于关键字检测,可空)
     */
    public String route(FileContext ctx, String filename, String fullSource) {
        // 1. 优先按文件名后缀
        if (filename != null) {
            if (filename.endsWith("Controller.java")) {
                return debug(filename, "spring-controller", "文件名后缀 Controller");
            }
            if (filename.endsWith("Mapper.java") || filename.contains("/mapper/")) {
                return debug(filename, "mybatis-mapper", "文件名后缀 Mapper");
            }
        }

        // 2. 按类注解
        if (ctx != null && ctx.getClassAnnotations() != null) {
            List<String> anns = ctx.getClassAnnotations();
            if (containsAny(anns, CONTROLLER_ANNOTATIONS)) {
                return debug(filename, "spring-controller", "类注解 @Controller/@RestController");
            }
            if (containsAny(anns, MAPPER_ANNOTATIONS)) {
                return debug(filename, "mybatis-mapper", "类注解 @Mapper/@Repository");
            }
            if (containsAny(anns, SERVICE_ANNOTATIONS)) {
                // Service 还要再看下是否含并发关键字,有的话优先并发
                if (hasConcurrentKeyword(fullSource)) {
                    return debug(filename, "concurrent-code", "Service 内含并发关键字");
                }
                return debug(filename, "spring-service", "类注解 @Service/@Component");
            }
        }

        // 3. 兜底:含并发关键字 -> concurrent
        if (hasConcurrentKeyword(fullSource)) {
            return debug(filename, "concurrent-code", "代码含并发关键字");
        }

        return debug(filename, SkillLoader.DEFAULT_SKILL, "未匹配,使用 default");
    }

    private String debug(String filename, String skill, String reason) {
        log.info("🎯 Skill 路由: {} -> {} ({})", filename, skill, reason);
        return skill;
    }

    private boolean containsAny(List<String> source, List<String> targets) {
        for (String s : source) {
            for (String t : targets) {
                if (s.contains(t)) return true;
            }
        }
        return false;
    }

    private boolean hasConcurrentKeyword(String fullSource) {
        if (fullSource == null) return false;
        for (String kw : CONCURRENT_KEYWORDS) {
            if (fullSource.contains(kw)) return true;
        }
        return false;
    }
}