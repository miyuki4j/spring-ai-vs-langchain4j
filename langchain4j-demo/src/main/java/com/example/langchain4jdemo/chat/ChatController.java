package com.example.langchain4jdemo.chat;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.langchain4jdemo.assistant.GameOpsAssistant;

/**
 * 和 spring-ai-demo 的 ChatController 对照着看，重点在 stream 方法：
 *
 * <ul>
 *   <li><b>Spring AI</b>：{@code chatClient.stream().content()} 直接返回 {@code Flux<String>}，
 *       Reactor 风格，你不需要管线程和完成信号。
 *   <li><b>LangChain4j</b>：{@code TokenStream} 是**回调风格**
 *       （onPartialResponse / onCompleteResponse / onError / start），
 *       要自己桥接到 Spring MVC 的 {@link SseEmitter}。
 * </ul>
 *
 * <p><b>注意方法名</b>：LangChain4j 早期版本用的是 {@code onNext} / {@code onComplete}，
 * 现在叫 {@code onPartialResponse} / {@code onCompleteResponse}。
 * 网上大量教程（和不少 AI 生成的代码）还在用旧名字，会直接编译不过。
 *
 * <p>注意 {@code start()} 不能忘 —— TokenStream 是懒执行的，不调 start 什么都不会发生。
 * 这是个很容易踩的坑。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final GameOpsAssistant assistant;

    public ChatController(GameOpsAssistant assistant) {
        this.assistant = assistant;
    }

    /** 最基础的一次问答（这里已经带上了工具调用能力）。 */
    @GetMapping
    public String chat(@RequestParam String message) {
        return assistant.chat(message);
    }

    /** 流式输出。用 SseEmitter 把 LangChain4j 的回调桥接成 SSE。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不超时

        assistant.chatStream(message)
                .onPartialResponse(token -> {
                    try {
                        emitter.send(token);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onCompleteResponse(response -> emitter.complete())
                .onError(emitter::completeWithError)
                .start(); // 忘了这句就永远收不到任何东西

        return emitter;
    }
}
