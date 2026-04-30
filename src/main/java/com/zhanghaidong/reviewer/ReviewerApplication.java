package com.zhanghaidong.reviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 应用启动类
 *
 * @author 张海东
 */
@EnableAsync
@SpringBootApplication
public class ReviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewerApplication.class, args);
    }
}
