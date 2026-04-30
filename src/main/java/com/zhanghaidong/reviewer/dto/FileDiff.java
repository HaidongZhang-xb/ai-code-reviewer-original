package com.zhanghaidong.reviewer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 单个文件的 diff 信息(适配 Gitee API)
 *
 * Gitee /repos/{owner}/{repo}/pulls/{number}/files 返回结构:
 * [
 *   {
 *     "filename": "src/main/java/Foo.java",
 *     "status": "modified",
 *     "additions": "10",
 *     "deletions": "2",
 *     "patch": {
 *       "diff": "@@ -1,3 +1,5 @@\n ...",
 *       "new_path": "...",
 *       "old_path": "..."
 *     }
 *   }
 * ]
 *
 * @author 张海东
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileDiff {

    /**
     * 新文件路径
     */
    private String filename;

    /**
     * 状态: added / modified / removed / renamed
     */
    private String status;

    /**
     * Gitee 把 patch 包成对象,内部的 diff 字段才是真正的 unified diff 文本
     */
    private Patch patch;

    /**
     * Gitee 这两个字段返回的是字符串("10"),所以用 String 类型最稳
     */
    private String additions;

    private String deletions;

    /**
     * 取真正的 diff 文本
     */
    public String getPatchText() {
        return patch == null ? null : patch.getDiff();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Patch {
        /**
         * 标准 unified diff 文本
         */
        private String diff;

        @JsonProperty("new_path")
        private String newPath;

        @JsonProperty("old_path")
        private String oldPath;
    }
}