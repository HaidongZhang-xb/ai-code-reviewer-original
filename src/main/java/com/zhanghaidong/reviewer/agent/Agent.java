package com.zhanghaidong.reviewer.agent;

import com.zhanghaidong.reviewer.dto.AgentState;

/**
 * Agent 接口
 * 每个 Agent 读取 state、产出结果、写回 state
 *
 * @author 张海东
 */
public interface Agent {

    /** Agent 名称(用于日志和追踪) */
    String name();

    /**
     * 执行 Agent 逻辑
     * 实现类应保证:即使本 Agent 失败也不抛异常,而是把错误记录到 state
     */
    void execute(AgentState state);

    /**
     * 是否需要执行(支持条件跳过)
     * 默认总是执行
     */
    default boolean shouldExecute(AgentState state) {
        return true;
    }
}