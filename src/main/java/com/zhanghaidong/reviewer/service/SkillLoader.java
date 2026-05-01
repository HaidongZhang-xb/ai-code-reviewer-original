package com.zhanghaidong.reviewer.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动时加载 classpath:skills/*&#47;SKILL.md 到内存
 * key = 目录名(spring-controller / mybatis-mapper / ...)
 *
 * @author 张海东
 */
@Slf4j
@Component
public class SkillLoader {

    public static final String DEFAULT_SKILL = "default";

    @Getter
    private final Map<String, String> skills = new HashMap<>();

    @PostConstruct
    public void load() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:skills/*/SKILL.md");
            for (Resource resource : resources) {
                String name = extractSkillName(resource);
                String content = readContent(resource);
                if (name != null && content != null) {
                    skills.put(name, content);
                    log.info("✅ 已加载 Skill: {} (长度 {} 字符)", name, content.length());
                }
            }
            log.info("Skill 加载完成,共 {} 个: {}", skills.size(), skills.keySet());

            if (!skills.containsKey(DEFAULT_SKILL)) {
                log.warn("⚠️ 未找到 default Skill,LLM 评审可能缺少兜底清单");
            }
        } catch (Exception e) {
            log.error("加载 Skill 失败", e);
        }
    }

    /**
     * 从 Resource 路径提取 skill 名(目录名)
     * 例如 file:.../skills/spring-controller/SKILL.md -> spring-controller
     */
    private String extractSkillName(Resource resource) {
        try {
            String path = resource.getURL().getPath();
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i > 0; i--) {
                if ("SKILL.md".equals(parts[i])) {
                    return parts[i - 1];
                }
            }
        } catch (Exception e) {
            log.warn("无法解析 Skill 名: {}", resource, e);
        }
        return null;
    }

    private String readContent(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("读取 Skill 内容失败: {}", resource, e);
            return null;
        }
    }

    /**
     * 获取指定 Skill 内容,找不到则返回 default Skill
     */
    public String getSkillOrDefault(String name) {
        if (name != null && skills.containsKey(name)) return skills.get(name);
        return skills.getOrDefault(DEFAULT_SKILL, "");
    }
}