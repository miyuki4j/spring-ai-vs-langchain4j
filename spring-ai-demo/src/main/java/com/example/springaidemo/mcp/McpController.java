package com.example.springaidemo.mcp;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 客户端端点：让模型用上「别人家的工具」。
 *
 * <p><b>这个类最值得看的地方，是它「看不出 MCP」。</b>
 * 三个端点里没有任何一处在写 HTTP、JSON-RPC、握手、会话 id —— 只有一句
 * {@code .tools(mcpTools)}，和一个本地 {@code @Tool} 对象用法一模一样。
 * 这就是 MCP 想达到的效果：<b>工具的来源对上层透明</b>。
 *
 * <p>那远端与本地到底差在哪？差在这三点，而且只有真跑起来才看得见：
 * <ol>
 *   <li><b>工具清单是运行时拿的。</b> {@link ToolCallbackProvider#getToolCallbacks()}
 *       背后是一次 {@code tools/list} 往返。server 那边加一个工具、重启，
 *       客户端这边<b>一行代码都不用改</b>就会多出来 —— 编译期完全不知道它存在。</li>
 *   <li><b>多了一个会失败的环节。</b> 进程内调用不会「连不上」，跨进程会。
 *       所以有了 {@code withMcp} 这个开关，用来做「有工具 / 没工具」的对照。</li>
 *   <li><b>调用是网络往返。</b> 模型的每一次工具调用都要经 MCP server 转一圈，
 *       延迟和本地方法差一个数量级（这也是为什么简单工具不建议上 MCP）。</li>
 * </ol>
 *
 * <p>与 LangChain4j 侧 {@code /api/chat/mcp} 的返回体逐字段对齐，方便对照台并排画。
 */
@RestController
@RequestMapping("/api/chat/mcp")
public class McpController {

    private static final String SYSTEM = "你是一个游戏服务端助手，既能查区服状态，也能查技能配置。回答要简洁、准确，用中文。";

    private final ChatClient chatClient;

    /**
     * MCP 工具提供者。
     *
     * <p>具体类型是 {@code SyncMcpToolCallbackProvider}，由
     * {@code McpToolCallbackAutoConfiguration} 自动配置产出：它把容器里所有
     * {@code McpSyncClient}（每个连接一个）收集起来，把远端 {@code tools/list}
     * 的结果包装成 Spring AI 的 {@code ToolCallback}。
     *
     * <p>这里按接口 {@link ToolCallbackProvider} 注入而不是具体类型，
     * 是因为上层根本不需要知道工具是 MCP 来的还是本地的 —— 这个抽象
     * 让「换掉工具来源」不需要改这个类。
     */
    private final ToolCallbackProvider mcpTools;

    public McpController(ChatClient.Builder builder, ToolCallbackProvider mcpTools) {
        this.mcpTools = mcpTools;
        this.chatClient = builder.defaultSystem(SYSTEM).build();
    }

    /**
     * 带 MCP 工具的一问一答。
     *
     * @param message 问题
     * @param withMcp true = 挂上 MCP 工具（默认）；false = <b>不挂</b>，
     *                用来做对照 —— 同一道题、同一个模型，只差工具，
     *                对照组答不出技能信息才说明工具真的起作用了
     */
    @GetMapping
    public McpAnswer chat(@RequestParam String message,
                          @RequestParam(defaultValue = "true") boolean withMcp) {
        long start = System.nanoTime();
        ChatResponse response = (withMcp
                ? this.chatClient.prompt().user(message).tools(this.mcpTools)
                : this.chatClient.prompt().user(message))
            .call()
            .chatResponse();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        Generation result = response.getResult();
        String answer = result == null || result.getOutput() == null ? "" : result.getOutput().getText();

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        McpAnswer.Tokens tokens = usage == null ? null
                : new McpAnswer.Tokens(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());

        List<String> names = toolNames();
        return new McpAnswer(answer, withMcp, names.size(), names, elapsedMs, tokens);
    }

    /**
     * 单独把「MCP 那边报了哪些工具」列出来。
     *
     * <p>这个端点看着简单，却是 MCP 和前几课最本质的区别所在：
     * 这里列出来的东西，<b>这个 Java 进程编译时一个都不知道</b>。
     * 它们是启动时向 8099 问回来的。
     */
    @GetMapping("/tools")
    public List<String> tools() {
        return toolNames();
    }

    /** 取一次远端工具清单。每次调用都会真的去问（除非客户端配了缓存）。 */
    private List<String> toolNames() {
        ToolCallback[] callbacks = this.mcpTools.getToolCallbacks();
        return Arrays.stream(callbacks)
            .map(cb -> cb.getToolDefinition().name())
            .sorted()
            .toList();
    }

    /** 返回体，与 LangChain4j 侧 {@code /api/chat/mcp} 逐字段对齐。 */
    public record McpAnswer(String answer, boolean withMcp, int toolCount, List<String> toolNames,
                            long elapsedMs, Tokens tokens) {

        public record Tokens(Integer prompt, Integer completion, Integer total) {
        }
    }
}
