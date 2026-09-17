package com.example.langchain4jdemo.chat;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.langchain4jdemo.assistant.GameOpsAssistant;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;

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

    /**
     * 思考模式（{@code reasoning_content}）对照端点 —— 和 spring-ai-demo 的
     * {@code /api/chat/think} 是同一道题。
     *
     * <p><b>关键点：LangChain4j 也拿得到思考内容</b>，路径是
     * {@link AiMessage#thinking()}，前置条件是 application.yml 里
     * {@code langchain4j.open-ai.chat-model.return-thinking: true}。
     *
     * <p><b>注意返回类型必须是 {@code AiMessage}，不能写 {@code ChatResponse}</b> ——
     * 写后者会让框架把模型当成「JSON 生成器」，详见
     * {@link com.example.langchain4jdemo.assistant.GameOpsAssistant#chatWithThinking} 的注释。
     *
     * <p>和 Spring AI 的差别不在「能不能拿」，而在<b>「能不能控制开关」</b>：
     * <ul>
     *   <li>Spring AI：配置层（{@code thinking.type}）+ 请求层（{@code enableThinking()}）都能控制，
     *       所以对面那个端点能传参做 A/B。
     *   <li>LangChain4j：OpenAI 兼容模块<b>不发 {@code thinking} 参数</b>（实测请求体顶层只有
     *       model / messages / temperature / stream / tools），所以这里没有 {@code thinking}
     *       开关可传 —— 开不开由服务端默认值决定（DeepSeek 默认开）。要真关掉，只能靠
     *       {@code custom-parameters} 自己塞这个字段。
     * </ul>
     */
    @GetMapping("/think")
    public ThinkResult think(@RequestParam String message) {
        // 取 Result<AiMessage> 而不是裸 AiMessage：思考内容在 AiMessage 上，
        // 而 token 用量只在 Result 上 —— 两个都要，就得往下剥一层。
        Result<AiMessage> result = assistant.chatWithThinking(message);
        AiMessage aiMessage = result.content();
        String thinking = aiMessage.thinking();
        return new ThinkResult(
                aiMessage.text(),
                thinking,
                thinking == null ? 0 : thinking.length(),
                Tokens.from(result.tokenUsage()));
    }

    /**
     * 流式思考：用 {@link PartialThinking} 回调接住思考分片，同样用 {@code R:} / {@code C:}
     * 前缀区分思考和正式回答，方便和 Spring AI 侧的输出逐帧对比。
     *
     * <p>注意要 {@code return-thinking: true} 配在 {@code streaming-chat-model} 段上，
     * 只在 {@code chat-model} 段配是不够的 —— 两个模型是两套独立配置。
     */
    @GetMapping(value = "/think/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter thinkStream(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(0L);

        assistant.chatStream(message)
                .onPartialThinking(partialThinking -> send(emitter, "R:" + partialThinking.text()))
                .onPartialResponse(partial -> send(emitter, "C:" + partial))
                .onCompleteResponse(response -> emitter.complete())
                .onError(emitter::completeWithError)
                .start();

        return emitter;
    }

    private static void send(SseEmitter emitter, String data) {
        try {
            emitter.send(data);
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /** {@code /think} 的返回体，字段与 spring-ai-demo 侧<b>完全对齐</b>，便于页面直接并排绘制。 */
    public record ThinkResult(String answer, String reasoningContent, int reasoningChars, Tokens tokens) {
    }

    /**
     * token 用量。
     *
     * <p>字段名刻意跟 Spring AI 侧的 {@code prompt / completion / total} 保持一致，
     * 而不是用 LangChain4j 自己的叫法（{@code input / output / total}）——
     * 两侧术语统一，页面上才画得成一张可直接对读的条形图。
     */
    public record Tokens(Integer prompt, Integer completion, Integer total) {

        static Tokens from(TokenUsage usage) {
            return usage == null ? null
                    : new Tokens(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
        }
    }
}
