package com.example.langchain4jdemo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class Langchain4jDemoApplicationTests {

    /**
     * 验证容器能起来，关键是 {@code @AiService} 生成的代理 Bean 被正确注册。
     *
     * <p>不调用真实模型，所以没有有效 API key 也能通过。
     */
    @Test
    void contextLoads() {
    }
}
