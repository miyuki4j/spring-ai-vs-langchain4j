package com.example.springaidemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI 2.0.1 + Spring Boot 4.1.1 学习项目。
 *
 * <p>跑起来：mvn spring-boot:run（默认端口 8081）
 * <p>然后：curl "http://localhost:8081/api/chat?message=你好"
 */
@SpringBootApplication
public class SpringAiDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiDemoApplication.class, args);
    }
}
