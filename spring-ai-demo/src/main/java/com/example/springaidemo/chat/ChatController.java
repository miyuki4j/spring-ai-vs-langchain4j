package com.example.springaidemo.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.springaidemo.tools.GameServerTools;

import reactor.core.publisher.Flux;

/**
 * Spring AI 的核心动作，一个类看完：
 *
 * <ol>
 *   <li>{@code /api/chat}          —— 最基础的 call()，一次问答
 *   <li>{@code /api/chat/stream}   —— stream()，流式返回（SSE）
 *   <li>{@code /api/chat/agent}    —— tools()，让模型自己决定调哪个工具（Agent 的最小形态）
 *   <li>{@code /api/chat/think}    —— 思考模式，取 DeepSeek 的 {@code reasoning_content}
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

    /**
     * 思考模式（{@code reasoning_content}）对照端点。
     *
     * <p>Spring AI 的 DeepSeek <b>专用模块</b>把「思考」做成了一等公民，而且有两个层级的开关：
     * <ol>
     *   <li><b>配置层</b>：{@code spring.ai.deepseek.chat.thinking.type}（application.yml，全局生效）
     *   <li><b>请求层</b>：{@code DeepSeekChatOptions.builder().enableThinking() / disableThinking()}
     * </ol>
     * 本端点走第 2 种，所以<b>同一个进程里就能做 A/B 对照</b>，不用改配置重启 ——
     * 这一点恰恰是 LangChain4j 的 OpenAI 兼容模块做不到的：它<b>根本没有 thinking 参数</b>，
     * 思考开不开完全由服务端默认值决定（DeepSeek 服务端默认是开的，见 README）。
     *
     * <p>取思考内容靠 {@link DeepSeekAssistantMessage#getReasoningContent()} ——
     * 普通 {@code AssistantMessage} 上没有这个字段，所以要先判断类型再强转。
     *
     * @param thinking true = 开思考（默认），false = 关思考。关掉能显著省 token 和延迟
     */
    @GetMapping("/think")
    public ThinkResult think(@RequestParam String message,
                             @RequestParam(defaultValue = "true") boolean thinking) {
        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder();
        if (thinking) {
            options.enableThinking();
        }
        else {
            options.disableThinking();
        }

        ChatResponse response = chatClient.prompt()
                .user(message)
                .options(options)
                .call()
                .chatResponse();

        AssistantMessage output = response.getResult().getOutput();
        String reasoning = (output instanceof DeepSeekAssistantMessage deepseek) ? deepseek.getReasoningContent() : null;

        // 顺手把 token 用量带出来：关掉思考后 completion 会显著变小，
        // 这是「思考模式更贵」最直观的证据。
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        ThinkResult.Tokens tokens = usage == null ? null
                : new ThinkResult.Tokens(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());

        return new ThinkResult(output.getText(), reasoning, reasoning == null ? 0 : reasoning.length(), tokens);
    }

    /**
     * 流式思考：思考内容会跟着 ChatResponse 分片一起流出来，每片带上前缀方便肉眼区分 ——
     * {@code R:} 是 reasoning（思考），{@code C:} 是 content（正式回答）。
     *
     * <p>注意这里用的是 {@code stream().chatResponse()} 而不是 {@code stream().content()}：
     * 后者只给你 content 的字符串，思考内容会被丢掉。
     */
    @GetMapping(value = "/think/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> thinkStream(@RequestParam String message,
                                    @RequestParam(defaultValue = "true") boolean thinking) {
        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder();
        if (thinking) {
            options.enableThinking();
        }
        else {
            options.disableThinking();
        }

        return chatClient.prompt()
                .user(message)
                .options(options)
                .stream()
                .chatResponse()
                .handle((response, sink) -> {
                    AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
                    if (output instanceof DeepSeekAssistantMessage deepseek && deepseek.getReasoningContent() != null) {
                        sink.next("R:" + deepseek.getReasoningContent());
                    }
                    else if (output != null && output.getText() != null && !output.getText().isEmpty()) {
                        sink.next("C:" + output.getText());
                    }
                });
    }

    /**
     * {@code /think} 的返回体。
     *
     * <p>带上 {@code reasoningChars} 是为了让对照实验一眼可判：长度 0 = 没拿到思考。
     * {@code tokens} 是这次调用的用量（思考模式下 completion 会明显变大，因为思考也算输出 token）。
     */
    public record ThinkResult(String answer, String reasoningContent, int reasoningChars, Tokens tokens) {

        /** token 用量。{@code completion} 含思考 token，所以思考开着时会明显偏大。 */
        public record Tokens(Integer prompt, Integer completion, Integer total) {
        }
    }
}
