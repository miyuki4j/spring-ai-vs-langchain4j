package com.example.langchain4jdemo.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 客户端装配 —— <b>全靠手写，没有一处自动配置</b>。
 *
 * <p>这是本项目和 Spring AI 的第三次「同题装配对照」，也是差距最大的一次：
 *
 * <table border="1">
 *   <caption>接一个 MCP server 的装配成本</caption>
 *   <tr><th></th><th>Spring AI 2.0.1</th><th>LangChain4j 1.20.0</th></tr>
 *   <tr>
 *     <td>依赖</td>
 *     <td>{@code spring-ai-starter-mcp-client}</td>
 *     <td>{@code langchain4j-mcp}（<b>且必须钉 beta30</b>，1.20.0 是 404）</td>
 *   </tr>
 *   <tr>
 *     <td>自动配置</td>
 *     <td>有：{@code McpToolCallbackAutoConfiguration} 产出
 *         {@code List<McpSyncClient>} + {@code SyncMcpToolCallbackProvider}</td>
 *     <td><b>没有任何自动配置</b>，下面两个 Bean 必须手写</td>
 *   </tr>
 *   <tr>
 *     <td>配置方式</td>
 *     <td>yml 里写 {@code spring.ai.mcp.client.streamable-http.connections.*}</td>
 *     <td>代码里 new</td>
 *   </tr>
 * </table>
 *
 * <p>但也要看到另一面：手写意味着<b>可编程</b>。中间那层
 * {@link McpToolProvider.Builder} 提供了 Spring AI 侧没法直接用配置表达的能力 ——
 * 比如 {@code filterToolNames(...)}（只暴露远端工具的一个子集）、
 * {@code toolNameMapper(...)}（重命名，避免多个 server 之间的工具名撞车）、
 * {@code failIfOneServerFails(...)}。这是「库」相对「框架」的典型取舍。
 *
 * <p><b>⚠️ 一个真实的代价：这里会让应用启动变硬。</b>
 * {@code McpToolProvider} 在构造时会去问远端要工具清单，
 * 所以 mcp-skill-server 没起，P2 就会**启动失败**。
 * 这是把「工具来自远端」这件事的代价摆到了台面上，不是缺陷 ——
 * 做演示/离线开发时，用 {@code MCP_ENABLED=false} 把它关掉（见 application.yml）。
 */
@Configuration
public class McpClientConfig {

    /**
     * 传输层 + 客户端。
     *
     * <p>{@link StreamableHttpMcpTransport} 是 MCP 现行标准的 HTTP 传输。
     * 它的三个可选兄弟是 {@code StdioMcpTransport}（把 server 当子进程拉起）、
     * {@code SseMcpTransport}（旧方案）和 {@code WebSocketMcpTransport}。
     *
     * <p>这里把 {@code logRequests/logResponses} 打开，是为了让你能在日志里
     * <b>亲眼看到 JSON-RPC 往返</b> —— {@code initialize}、{@code tools/list}、
     * {@code tools/call} 都会原样打出来。排「工具列表为空」「连不上」这类问题时，
     * 这个日志就是第一现场。
     *
     * <p>{@code destroyMethod = "close"}:{@link McpClient} 是 {@code AutoCloseable}，
     * 容器关闭时要让它把 HTTP 连接和会话收干净，否则进程可能挂着不退。
     */
    @Bean(destroyMethod = "close")
    McpClient skillMcpClient(@Value("${mcp.skill-server.url:http://localhost:8099/mcp}") String url) {
        McpTransport transport = StreamableHttpMcpTransport.builder()
                .url(url)
                .logRequests(true)
                .logResponses(true)
                .build();

        return DefaultMcpClient.builder()
                .transport(transport)
                .clientName("langchain4j-demo")
                .clientVersion("1.0.0")
                // 协议版本不显式指定 —— DefaultMcpClient 会自己探测：
                // 先试现代协议的 server/discover（2026-07-28），失败再回落到
                // initialize（2025-11-25）。对面是 Spring AI 2.0.1 + 官方 SDK 2.0.0，
                // 实测它的协议版本上限就是 2025-11-25（≤ 上限的请求原样回显），
                // 所以回落这一版正好落在对方天花板上，两边谈成。
                .build();
    }

    /**
     * 把 MCP 客户端包装成 LangChain4j 认识的 {@link ToolProvider}。
     *
     * <p><b>这个 Bean 一旦存在，{@code @AiService} 代理就会自动挂上它</b> ——
     * 不需要在任何地方 {@code @Autowired}，也不需要写 {@code toolProvider = "..."}。
     *
     * <p>机制见 {@code AiServicesAutoConfig}（字节码/source 确认）：
     * 它在启动时改 {@code @AiService} 的 BeanDefinition，逐个试接
     * 「容器里恰好存在一个」的协作者 —— {@code ChatModel}、{@code StreamingChatModel}、
     * {@code ChatMemoryProvider}、{@code ContentRetriever}、{@code RetrievalAugmentor}、
     * {@code ModerationModel}，以及这里的 {@code ToolProvider}：
     * <pre>
     *   addBeanReference(ToolProvider.class, aiServiceAnnotation,
     *                    aiServiceAnnotation.toolProvider(), toolProviders,
     *                    "toolProvider", "toolProvider", propertyValues);
     * </pre>
     * 和记忆那一课是<b>同一个机制</b>。它的三种失败模式（0 个静默不生效 /
     * 2 个抛冲突 / 1 个生效）在 {@code MemoryConfig} 里已写过，此处不重复。
     */
    @Bean
    ToolProvider mcpToolProvider(McpClient skillMcpClient) {
        return McpToolProvider.builder()
                .mcpClients(skillMcpClient)
                // true = 远端出问题时抛异常，而不是静默返回空工具集。
                // 本项目刻意选 true：前几课反复吃过「静默失败」的亏
                // （TokenStream 忘了 start()、@AiService 返回 ChatResponse、
                //  记忆 advisor 被静默顶掉），宁可启动就炸，也不要运行时悄悄少一批工具。
                .failIfOneServerFails(true)
                .build();
    }
}
