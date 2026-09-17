package com.example.mcpskillserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置 —— 只放行 MCP 端点，让浏览器里的对照台能**自己说一遍 MCP 协议**。
 *
 * <p><b>为什么这个 server 也需要 CORS？</b>两个后端各自放行了自己是因为对照台要调它们；
 * 这一份的动机更微妙一点：对照台想做<b>「三方工具清单一致」</b>这个实验（见 README「MCP 实测」第五节），
 * 三方是「裸协议 / P1 / P2」。裸协议那一方本来是 {@code probe/check-mcp-server.py} 用
 * {@code urllib} 跑的 —— 那是个命令行工具，而对照台是浏览器。
 *
 * <p><b>浏览器直连 MCP 端点会撞上两件事，两个都不是 bug、都是真实约束：</b>
 * <ol>
 *   <li><b>同源策略</b>：页面在 8090，MCP 端点 8099 是另一个源 —— 需要这里显式放行，
 *       而且因为请求带 {@code Content-Type: application/json} 和自定义头 {@code Mcp-Session-Id}，
 *       浏览器会先发一个 <b>OPTIONS 预检</b>；</li>
 *   <li><b>会话 id 要能读出来</b>：MCP 的 Streamable HTTP 用响应头 {@code Mcp-Session-Id} 建立会话，
 *       而跨域响应里 JS <b>默认看不到</b>任何非简单响应头 —— 必须 {@code exposedHeaders} 显式暴露，
 *       否则 {@code resp.headers.get('Mcp-Session-Id')} 永远返回 {@code null}，
 *       表现为「第一次 initialize 成功、后续全部 400」这种很难查的错。
 * </ol>
 *
 * <p><b>这条本身就是一个值得记住的结论</b>：MCP 的设计目标是「本地进程 / 服务端到服务端」的集成，
 * 浏览器并不是它的目标客户端。想让浏览器直连，除了要过 CORS，还得自己实现
 * initialize → notifications/initialized → tools/list 这段握手 —— 而正经的 MCP 客户端
 * （两个后端用的 `McpClient`）已经把这段封好了。
 *
 * <p>默认只放行本机来源；要收紧改 application.yml 的 {@code app.cors.allowed-origin-patterns}。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    /**
     * 默认放行本机任意端口，外加 {@code null}（对应「双击 index.html 用 file:// 打开」，
     * 此时浏览器发出的 Origin 头就是字符串 {@code null}）。
     */
    public WebConfig(@Value("${app.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*],null}") String patterns) {
        this.allowedOriginPatterns = patterns.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // ⚠️ MCP 的 Streamable HTTP 端点是**函数式路由**（RouterFunction），不是 @RequestMapping。
        // 好在 Spring MVC 的 WebMvcConfigurationSupport 会把这里的配置同时应用到
        // RequestMappingHandlerMapping 和 RouterFunctionMapping 上，所以这样写是生效的
        // （已用 OPTIONS 预检实测确认，别只信这段注释）。
        //
        // 两个 mapping 都写：MCP 规范用单端点，但不同实现对子路径的处理不一样，
        // 一起放行可以避免「主端点通了、带斜杠的 404」这类偶发问题。
        for (String path : new String[] {"/mcp", "/mcp/**"}) {
            registry.addMapping(path)
                    .allowedOriginPatterns(allowedOriginPatterns)
                    // POST 是 JSON-RPC 主通道；GET 用于服务端→客户端的 SSE 流；
                    // DELETE 是客户端主动终止会话（规范里的 session termination）
                    .allowedMethods("POST", "GET", "DELETE", "OPTIONS")
                    // 放开所有请求头：MCP 会用到 Content-Type / Accept /
                    // Mcp-Session-Id / MCP-Protocol-Version，逐条列举容易漏
                    .allowedHeaders("*")
                    // 关键的一行：不暴露它，浏览器 JS 就拿不到会话 id
                    .exposedHeaders("Mcp-Session-Id")
                    // 预检结果缓存 1 小时，少发点 OPTIONS
                    .maxAge(3600);
        }
    }
}
