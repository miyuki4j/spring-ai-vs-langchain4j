package com.example.langchain4jdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LangChain4j 1.20.0 + Spring Boot 3.5.16 学习项目。
 *
 * <p>跑起来：mvn spring-boot:run（默认端口 8082）
 * <p>然后：curl "http://localhost:8082/api/chat?message=你好"
 */
@SpringBootApplication
public class Langchain4jDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(Langchain4jDemoApplication.class, args);
    }
}
