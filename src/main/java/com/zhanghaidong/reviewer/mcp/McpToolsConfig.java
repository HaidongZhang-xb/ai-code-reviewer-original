package com.zhanghaidong.reviewer.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Tool 注册配置
 *
 * Spring AI MCP Server 自动扫描 ToolCallbackProvider Bean,
 * 把里面的 @Tool 方法暴露成 MCP 协议的 tools/list 与 tools/call 端点
 *
 * @author 张海东
 */
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider codeReviewTools(CodeReviewMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}