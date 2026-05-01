package com.zhanghaidong.reviewer.agent;

import com.zhanghaidong.reviewer.dto.AgentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 流水线编排器
 *
 * 设计参考 Spring AI Alibaba Graph 的 StateGraph 模式:
 *  - 节点(Node) = Agent
 *  - 状态(State) = AgentState,在节点间传递
 *  - 边(Edge) = 这里是固定顺序,后续可扩展为基于 state 的条件分支
 *
 * @author 张海东
 */
@Slf4j
@Component
public class AgentPipeline {

    private final List<Agent> agents;

    /**
     * Spring 会按 Bean 注入顺序传入,这里强制指定顺序
     */
    public AgentPipeline(ReviewerAgent reviewer,
                         TestGenAgent testGen,
                         ValidatorAgent validator) {
        this.agents = List.of(reviewer, testGen, validator);
    }

    public AgentState run(AgentState initialState) {
        log.info("🚀 Agent Pipeline 启动: {} 个节点", agents.size());
        long start = System.currentTimeMillis();

        for (Agent agent : agents) {
            if (!agent.shouldExecute(initialState)) {
                log.info("⏭️ 跳过 [{}] (shouldExecute=false)", agent.name());
                continue;
            }
            long nodeStart = System.currentTimeMillis();
            try {
                agent.execute(initialState);
                initialState.getExecutedAgents().add(agent.name());
                log.info("⏱️ [{}] 耗时 {} ms",
                        agent.name(), System.currentTimeMillis() - nodeStart);
            } catch (Exception e) {
                log.error("❌ [{}] 抛出未捕获异常,继续下一个 Agent", agent.name(), e);
                initialState.setErrorMessage(agent.name() + ": " + e.getMessage());
            }
        }

        log.info("🏁 Pipeline 完成: 共耗时 {} ms,执行节点 {}",
                System.currentTimeMillis() - start,
                initialState.getExecutedAgents());
        return initialState;
    }
}