package com.zhanghaidong.reviewer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Gitee Pull Request Webhook 事件
 * 参考: https://gitee.com/help/articles/4290
 *
 * @author 张海东
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PullRequestEvent {

    /**
     * 事件类型: "Merge Request Hook"
     */
    @JsonProperty("hook_name")
    private String hookName;

    /**
     * 动作: open / update / merge / close
     */
    private String action;

    /**
     * 密码(明文校验,与 Webhook 配置一致)
     */
    private String password;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    private Repository repository;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PullRequest {
        /**
         * PR 编号(仓库内序号,用于调 API)
         */
        private Integer number;
        private String title;
        private String body;
        private String state;

        /**
         * 源分支信息
         */
        private Ref head;
        /**
         * 目标分支信息
         */
        private Ref base;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ref {
        private String ref;
        private String sha;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        /**
         * 仓库全名,例如 "zhanghaidong/demo-repo"
         */
        @JsonProperty("full_name")
        private String fullName;

        /**
         * owner 部分,例如 "zhanghaidong"
         */
        @JsonProperty("path_with_namespace")
        private String pathWithNamespace;

        private String name;

        private Owner owner;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Owner {
        private String login;
        private String username;
    }

    /**
     * 从 full_name 拆出 owner
     */
    public String getOwner() {
        if (repository == null || repository.getFullName() == null) {
            return null;
        }
        String[] parts = repository.getFullName().split("/");
        return parts.length > 0 ? parts[0] : null;
    }

    /**
     * 从 full_name 拆出 repo
     */
    public String getRepoName() {
        if (repository == null || repository.getFullName() == null) {
            return null;
        }
        String[] parts = repository.getFullName().split("/");
        return parts.length > 1 ? parts[1] : null;
    }
}
