package com.zhanghaidong.reviewer.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * AI 与 HTTP 客户端配置
 *
 * @author 张海东
 */
@Configuration
public class AiConfig {

    /**
     * ChatClient,用于调用 LLM
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个资深的 Java 后端工程师,擅长代码评审。请用中文输出评审意见,严格按照要求的 JSON 格式返回。")
                .build();
    }

    /**
     * RestTemplate,用于调用 Gitee OpenAPI
     * 加上超时,避免 Gitee 抖动时拖死线程池
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }
}