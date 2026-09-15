package com.example.springaidemo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpringAiDemoApplicationTests {

    /**
     * 只验证 Spring 容器能正常装配：ChatClient、ChatModel、GameServerTools 三个 Bean 都在。
     *
     * <p>这个测试**不会**真的调用模型，所以在没有有效 API key 的情况下也能通过
     * —— 这也是学习项目应该保持的性质：单元测试不依赖外部服务和花钱的调用。
     */
    @Test
    void contextLoads() {
    }
}
