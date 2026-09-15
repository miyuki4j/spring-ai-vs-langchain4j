package com.example.springaidemo.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.springaidemo.tools.GameServerTools;

import reactor.core.publisher.Flux;

/**
 * Spring AI 的三个核心动作，一个类看完：
 *
 * <ol>
 *   <li>{@code /api/chat}          —— 最基础的 call()，一次问答
 *   <li>{@code /api/chat/stream}   —— stream()，流式返回（SSE）
 *   <li>{@code /api/chat/agent}    —— tools()，让模型自己决定调哪个工具（Agent 的最小形态）
 * </ol>
 *
 * <p>注意 {@link ChatClient} 是 Spring AI 的门面（facade），它由 Boot 自动配置提供的
 * {@link ChatClient.Builder} 构建。业务代码只依赖 ChatClient，不直接碰具体模型实现
 * —— 这就是「换模型不改业务代码」的那一层抽象。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final GameServerTools tools;

    public ChatController(ChatClient.Builder builder, GameServerTools tools) {
        this.tools = tools;
        this.chatClient = builder
                .defaultSystem("你是一个游戏服务端运维助手，回答要简洁、准确，用中文。")
                .build();
    }

    /** 最基础的一次问答。 */
    @GetMapping
    public String chat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    /** 流式输出，浏览器/curl 能看到逐字返回。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }

    /** 带工具的一次问答：模型自己决定要不要调 onlinePlayers / hotfixStatus。 */
    @GetMapping("/agent")
    public String agent(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .tools(tools)
                .call()
                .content();
    }
}
