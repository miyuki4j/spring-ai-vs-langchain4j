package com.example.mcpskillserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 技能配置查询 MCP Server。
 *
 * <p>这个进程里 <b>没有大模型、没有 API key</b>，它只做一件事：
 * 把「技能配置」以 MCP 协议暴露出去，让任何 MCP 客户端来查。
 *
 * <p>它不关心客户端是 Spring AI 还是 LangChain4j，甚至是 Claude Desktop、
 * Cursor 还是某个 Python 脚本 —— 这正是 MCP 与「框架自带的工具调用」的分水岭：
 * <ul>
 *   <li>框架自带的 tool calling：工具是<b>进程内</b>的 Java 方法，只有该框架能用；</li>
 *   <li>MCP：工具是<b>跨进程、跨语言、跨框架</b>的服务，谁都能接。</li>
 * </ul>
 *
 * <p>启动后监听 8099，走 Streamable HTTP（MCP 现行标准传输，
 * 取代了早期的 HTTP+SSE 双端点方案）。
 */
@SpringBootApplication
public class McpSkillServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpSkillServerApplication.class, args);
    }
}
