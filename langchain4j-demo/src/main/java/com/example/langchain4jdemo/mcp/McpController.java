package com.example.langchain4jdemo.mcp;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.langchain4jdemo.assistant.GameOpsAssistant;
import com.example.langchain4jdemo.tools.GameServerTools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

/**
 * MCP 端点 —— 与 spring-ai-demo 的 {@code mcp/McpController} 逐字段对齐。
 *
 * <p>和对面一样，这个类的业务代码里<b>看不出 MCP</b>：
 * 一次 {@code assistant.chatWithTools(sid, message)} 而已，工具从哪来是装配期决定的。
 * 真正的差别在配置那一层，把两个文件摆在一起看最清楚：
 *
 * <pre>
 *   Spring AI     application.yml 里加 6 行 YAML，代码 0 行
 *   LangChain4j   代码里手写 2 个 @Bean（McpClient + McpToolProvider），yml 0 行
 * </pre>
 *
 * <p><b>{@code withMcp} 这个开关是本端点的实验设计核心。</b>
 * 两组用同一个模型、同一道题、同一个接口方法，唯一变量是「挂不挂 MCP 工具」：
 * <ul>
 *   <li>{@code withMcp=true} —— 注入的 {@code @AiService} 代理，
 *       它已经被自动挂上了 {@code ToolProvider}（MCP）和本地 {@code @Tool} Bean；</li>
 *   <li>{@code withMcp=false} —— {@link AiServices#builder(Class)} 现场构造的新实例，
 *       <b>只挂本地 {@code GameServerTools}</b>，不挂 MCP。</li>
 * </ul>
 * 对照组答不出技能配置，才说明 MCP 工具真的在起作用 ——
 * 和记忆那一课「锚点 + 对照」是同一套方法论：
 * <b>能被别的途径拿到的信息，不能当证据。</b>
 *
 * <hr>
 *
 * <p><b>⚠️ 这里踩过一个真坑，修法和原因都留在这里。</b>
 *
 * <p>第一版跑出来的结果是「对照组居然也答对了」。查证据链：
 * MCP server 侧的 {@code tools/call} 计数只有 2（两侧实验组各一次），
 * <b>对照组根本没调 MCP</b>；但它的请求体 {@code messages} 里赫然带着
 * <b>上一轮实验组的完整问答</b>（含 {@code tool} 结果和最终答案），
 * 于是模型把上一轮答案原样复述了一遍 —— 看起来"答对"，其实是抄的。
 *
 * <p>根因有两条，叠在一起才成立：
 * <ol>
 *   <li>{@code chatWithTools} 原本<b>没有 {@code @MemoryId}</b>，
 *       框架会给这类方法分配一个<b>默认记忆槽</b>；</li>
 *   <li>两个"独立"的服务实例如果共用同一个 {@code ChatMemoryProvider}，
 *       就会落到同一个槽里 —— <b>记忆的边界不在服务实例上，在 Provider 和 id 上</b>。</li>
 * </ol>
 * 这和记忆那一课的「{@code ChatMemoryService} 用 {@code computeIfAbsent} 缓存实例」
 * 是同一类问题的两面。
 *
 * <p><b>修法（两道保险，本类都有）：</b>
 * <ol>
 *   <li>接口方法加上 {@code @MemoryId}，每次调用传一个<b>随机 sessionId</b> ——
 *       两组各自全新会话，天然隔离，实验可重复；</li>
 *   <li>对照组用自己的 {@link InMemoryChatMemoryStore} 实例，
 *       从存储层面就和实验组隔开。</li>
 * </ol>
 *
 * <p>顺带一个值得注意的副作用：由于 MCP 工具是挂在<b>接口级代理</b>上的，
 * 加了这个 {@code ToolProvider} Bean 之后，P2 里<b>所有</b>走 {@code @AiService} 的方法
 * （包括 {@code /api/chat}、{@code /api/chat/memory}）都会带上 MCP 工具。
 * 这是声明式的连带效应，不是 bug —— 但排障时要记得。
 */
@RestController
@RequestMapping("/api/chat/mcp")
public class McpController {

    /** 注入了 {@code @AiService} 代理：自动带本地工具 + MCP 工具。 */
    private final GameOpsAssistant assistant;

    /** 对照组：只挂本地工具，不挂 MCP，且用独立的记忆存储。 */
    private final GameOpsAssistant assistantWithoutMcp;

    private final McpClient mcpClient;

    public McpController(GameOpsAssistant assistant,
                         ChatModel chatModel,
                         GameServerTools tools,
                         McpClient skillMcpClient) {
        this.assistant = assistant;
        this.mcpClient = skillMcpClient;

        // 对照组：现场构造一个「没有 MCP」的服务实例。
        //
        // 用自己 new 的 InMemoryChatMemoryStore，而不是复用注入的 gameOpsChatMemoryProvider ——
        // 这是上面那个「对照组抄了实验组答案」的坑的第二道保险。
        ChatMemoryStore isolatedStore = new InMemoryChatMemoryStore();
        this.assistantWithoutMcp = AiServices.builder(GameOpsAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(isolatedStore)
                        .build())
                .tools(tools)
                .build();
    }

    /**
     * @param message 问题
     * @param withMcp true = 带 MCP 工具（默认），false = 只带本地工具（对照）
     */
    @GetMapping
    public McpAnswer chat(@RequestParam String message,
                          @RequestParam(defaultValue = "true") boolean withMcp) {
        // 每次调用给一个全新的 sessionId —— 两组各自干净开局。
        // 不这么做的话，同一个默认记忆槽会在多次请求之间累积，
        // 第二次跑实验就会带着第一次的答案（实测被这个坑咬过，见类注释）。
        String sessionId = "mcp-" + (withMcp ? "on-" : "off-") + UUID.randomUUID();

        long start = System.nanoTime();
        Result<String> result = withMcp
                ? this.assistant.chatWithTools(sessionId, message)
                : this.assistantWithoutMcp.chatWithTools(sessionId, message);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        TokenUsage usage = result.tokenUsage();
        McpAnswer.Tokens tokens = usage == null ? null
                : new McpAnswer.Tokens(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());

        List<String> names = toolNames();
        return new McpAnswer(result.content(), withMcp, names.size(), names, elapsedMs, tokens);
    }

    /** 远端报了哪些工具（每次真的去问一次 MCP server）。 */
    @GetMapping("/tools")
    public List<String> tools() {
        return toolNames();
    }

    /**
     * 取远端工具清单。
     *
     * <p>注意这里用的是 {@link McpClient#listTools()} —— 这是 MCP 协议里
     * {@code tools/list} 的直接映射。它跨进程、跨框架，对面是不是 Java 写的都无所谓。
     * 对比之下，本地工具的清单来自反射扫描 {@code @Tool} 注解，只在这个 JVM 里成立。
     */
    private List<String> toolNames() {
        return this.mcpClient.listTools()
                .stream()
                .map(ToolSpecification::name)
                .sorted()
                .toList();
    }

    /** 返回体，与 Spring AI 侧 {@code /api/chat/mcp} 逐字段对齐。 */
    public record McpAnswer(String answer, boolean withMcp, int toolCount, List<String> toolNames,
                            long elapsedMs, Tokens tokens) {

        public record Tokens(Integer prompt, Integer completion, Integer total) {
        }
    }
}
