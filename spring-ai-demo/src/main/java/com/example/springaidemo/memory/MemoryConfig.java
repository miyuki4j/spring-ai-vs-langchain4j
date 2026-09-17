package com.example.springaidemo.memory;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆的装配 —— 只有这一个 Bean，这是 Spring AI 侧最想让你看到的一点。
 *
 * <p><b>为什么这么少？</b>因为 {@code ChatMemory} 和 {@code ChatMemoryRepository} 两个 Bean
 * 已经由 {@code spring-ai-autoconfigure-model-chat-memory} 自动配好了（引进依赖就有），
 * 业务侧唯一要补的是「把记忆挂到调用链上」这一步 —— 也就是这个 Advisor。
 *
 * <p>对照 LangChain4j：那边的 starter 里<b>一个记忆相关的自动配置都没有</b>
 * （{@code AutoConfiguration.imports} 里只有 {@code LangChain4jAutoConfig} 一个空壳，
 * 承载 properties 用的）。所以 LangChain4j 侧从 Store 到 Provider 全得自己写，
 * 见 langchain4j-demo 的 {@code config/MemoryConfig}。
 *
 * <p><b>Advisor 是什么？</b>把它理解成 Servlet Filter / {@code HandlerInterceptor}：
 * {@code before()} 里从 {@link ChatMemory} 读历史塞进 prompt，{@code after()} 里把本轮新的
 * user/assistant 消息写回去。你在 GRC 里天天和 xio/gnet 的 Handler 链打交道，
 * 这是同一类东西 —— 只不过拦截的对象从「网络包」变成了「模型调用」。
 */
@Configuration
public class MemoryConfig {

    /**
     * 把自动配置好的 {@link ChatMemory} 包成 Advisor。
     *
     * <p>注意 {@code builder(...)} 的参数是 {@code ChatMemory}（**会话管理器**，不是某一个会话）——
     * 具体读写哪个会话，由每次调用时传的 {@code conversationId} 决定，
     * 见 {@code MemoryController} 里的
     * {@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))}。
     * 这个常量值是 {@code "chat_memory_conversation_id"}（javap 从常量池读出）。
     */
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    /*
     * ─────────────────────────────────────────────────────────────────────
     * 想改窗口大小？自动配置的 ChatMemory 用的是 MessageWindowChatMemory 的
     * DEFAULT_MAX_MESSAGES = 20，而且 2.0.1 的 autoconfigure 模块里没有
     * ChatMemoryProperties（javap 确认过，整个 jar 只有 ChatMemoryAutoConfiguration
     * 一个类），所以**没有配置项能改它**。唯一办法是自己声明一个同名 Bean 覆盖：
     *
     *   @Bean
     *   public ChatMemory chatMemory(ChatMemoryRepository repository) {
     *       return MessageWindowChatMemory.builder()
     *               .chatMemoryRepository(repository)
     *               .maxMessages(6)      // 3 轮对话（每轮 user+assistant 两条）
     *               .build();
     *   }
     *
     * 但本项目没有这么做 —— 因为 MemoryController 把「窗口大小」做成了可传参，
     * 现场构造小窗口来演示「锚点那一轮被挤掉后，回忆轮就答不出来了」这个现象。
     * 覆盖全局 Bean 会把那个演示路径堵死。
     *
     * ⚠️ 还有一个坑：**别把 advisor 挂到注入的 ChatClient.Builder 上**。
     * ChatClient.Builder 是 prototype，但控制器只注入一次就一直用；
     * defaultAdvisors 是往列表里 addAll，每请求调一次就多叠一个，
     * 旧 advisor 会一直留在链上。本 Bean 是**按调用**传进去的，
     * 不是 builder 上的默认值 —— 完整故障链见 MemoryController 的类注释。
     *
     * 生产上换成 Redis 也在这里：把 starter 换成
     * spring-ai-starter-model-chat-memory-repository-redis 即可，
     * ChatMemoryRepository 的 String key 语义和你 GRC 里的 key 设计几乎是一一对应的。
     * ─────────────────────────────────────────────────────────────────────
     */
}
