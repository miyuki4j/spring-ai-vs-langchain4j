package com.example.mcpskillserver.config;

import com.example.mcpskillserver.catalog.SkillCatalog;
import com.example.mcpskillserver.tools.SkillQueryTools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把普通 Java 方法注册成 MCP 工具。
 *
 * <p><b>这是整个 server 侧唯一一段「胶水」代码，值得看清它的机制。</b>
 *
 * <p>Spring AI 的 MCP server 自动配置（{@code ToolCallbackConverterAutoConfiguration}，
 * 源码读过）会去容器里捞两类 Bean：
 * <ul>
 *   <li>所有 {@code ToolCallback} 类型的 Bean；</li>
 *   <li>所有 {@code ToolCallbackProvider} 类型的 Bean（并排掉它自己产生的
 *       {@code SyncMcpToolCallbackProvider} / {@code AsyncMcpToolCallbackProvider}，避免自噬）。</li>
 * </ul>
 * 捞到之后统一转成 {@code McpServerFeatures.SyncToolSpecification}，
 * 这一步决定了 {@code tools/list} 里会出现什么。
 *
 * <p>所以下面这个 Bean 的作用是「告诉 server：这几个方法要对外暴露」。
 * 注意它 <b>不写成 @Component 挂在工具类上</b> —— 因为 {@code @Tool} 注解本身
 * 只是个标记，Spring AI 不会自动扫描它；必须有人显式地把对象交给
 * {@code MethodToolCallbackProvider}。（这一点和客户端的 ChatClient 一样：
 * 工具要显式挂上去，不存在「标了就生效」。）
 */
@Configuration
public class McpServerConfig {

    @Bean
    ToolCallbackProvider skillQueryToolCallbackProvider(SkillCatalog catalog) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(new SkillQueryTools(catalog))
            .build();
    }
}
