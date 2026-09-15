package com.example.langchain4jdemo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 这个测试是为了防住一个真实踩到的 bug。
 *
 * <p>{@code OpenAiChatModel} 和 {@code OpenAiStreamingChatModel} 是两个**独立**的类，
 * 前者只实现 {@link ChatModel}，不实现 {@link StreamingChatModel}。所以只配
 * {@code langchain4j.open-ai.chat-model.*} 时，容器里没有 {@link StreamingChatModel} Bean，
 * {@code @AiService} 里返回 {@code TokenStream} 的方法会到**调用时**才失败 ——
 * 表现为 {@code /api/chat} 正常、{@code /api/chat/stream} 返回 HTTP 500，
 * 而**启动日志一切正常**。
 *
 * <p>普通的 {@code contextLoads} 抓不住它，因为缺 Bean 不影响容器启动。
 * 所以这里显式断言两个 Bean 都注册了 —— 这类回归测试的价值就在于
 * 把"只有调用时才暴露"的问题提前到构建阶段。
 */
@SpringBootTest
class AiWiringTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void chatModelBeanIsPresent() {
        assertThat(context.getBeanNamesForType(ChatModel.class))
                .as("ChatModel Bean 必须存在，否则 assistant.chat() 会在调用时失败")
                .isNotEmpty();
    }

    @Test
    void streamingChatModelBeanIsPresent() {
        assertThat(context.getBeanNamesForType(StreamingChatModel.class))
                .as("StreamingChatModel Bean 必须存在，否则 assistant.chatStream() 会在调用时抛错"
                        + "（表现为 /api/chat/stream 返回 HTTP 500）")
                .isNotEmpty();
    }
}
