package com.example.langchain4jdemo.tools;

import org.springframework.stereotype.Component;

import dev.langchain4j.agent.tool.Tool;

/**
 * 同样用「游戏服务端运维」领域来演示 LangChain4j 的 tool calling，
 * 这样你可以直接和 spring-ai-demo 里的同名类逐行对比。
 *
 * <p>和 Spring AI 的差别（第一眼就能看到的）：
 * <ul>
 *   <li>注释是 {@code dev.langchain4j.agent.tool.Tool}，值是**纯描述字符串**（没有 name 属性）
 *   <li>Spring AI 是 {@code org.springframework.ai.tool.annotation.Tool}，且是 {@code @Tool(description = "...")}
 * </ul>
 * 两家都把「方法名 = 工具名、参数名 = 参数名、注释 = 给模型的说明」这套约定做成了默认行为。
 */
@Component
public class GameServerTools {

    @Tool("查询指定区服当前的在线玩家数。参数是区服编号，例如 s1、s2、s3")
    public int onlinePlayers(String zone) {
        return switch (zone.toLowerCase()) {
            case "s1" -> 3_214;
            case "s2" -> 1_870;
            case "s3" -> 542;
            default -> -1;
        };
    }

    @Tool("查询指定版本号的热更状态，返回 applied 或 rolled-back。参数例如 1.4.2")
    public String hotfixStatus(String version) {
        return version.startsWith("1.4") ? "applied" : "rolled-back";
    }
}
