package com.zhanghaidong.reviewer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 返回的结构化评审结果
 *
 * @author 张海东
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResult {

    /**
     * 整体评分 0-100
     */
    private Integer overallScore;

    /**
     * 整体总结
     */
    private String summary;

    /**
     * 行级评论列表
     */
    private List<ReviewComment> comments;
}
