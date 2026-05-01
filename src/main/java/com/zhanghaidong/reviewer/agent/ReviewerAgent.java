package com.zhanghaidong.reviewer.agent;

import com.zhanghaidong.reviewer.dto.AgentState;
import com.zhanghaidong.reviewer.dto.ReviewResult;
import com.zhanghaidong.reviewer.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ReviewerAgent implements Agent {

    private final ReviewService reviewService;

    public ReviewerAgent(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public String name() {
        return "ReviewerAgent";
    }

    @Override
    public void execute(AgentState state) {
        log.info("🤖 [{}] 开始执行", name());
        try {
            ReviewResult result = reviewService.review(state.getFiles(), state.getContextMap());
            state.setReviewResult(result);
            log.info("✅ [{}] 完成: 评分={}, 评论数={}",
                    name(),
                    result.getOverallScore(),
                    result.getComments() == null ? 0 : result.getComments().size());
        } catch (Exception e) {
            log.error("❌ [{}] 失败", name(), e);
            state.setReviewResult(new ReviewResult(0, "评审 Agent 异常: " + e.getMessage(), java.util.List.of()));
        }
    }
}