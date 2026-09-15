package com.example.springaidemo.tools;

import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 用「游戏服务端运维」这个你熟悉的领域来演示 Spring AI 的 tool calling。
 *
 * <p>注意：这里是写死的假数据，目的是让你先跑通「模型 -> 决定调用工具 -> 后端执行 -> 模型总结」
 * 这条链路。真实项目里把方法体换成查库/查监控接口即可。
 *
 * <p>关键点：{@code @Tool} 标注的方法，其 description 是给模型看的，不是给人看的。
 * 模型靠它决定「什么时候该调这个工具」，所以描述要写清楚「做什么、参数是什么含义」。
 */
@Component
public class GameServerTools {

    /** 假装这里有区服 -> 在线人数的数据。 */
    private static final Map<String, Integer> ONLINE = Map.of(
            "s1", 3_214,
            "s2", 1_870,
            "s3", 542);

    @Tool(description = "查询指定区服当前的在线玩家数。参数 zone 是区服编号，例如 s1、s2、s3。")
    public int onlinePlayers(String zone) {
        return ONLINE.getOrDefault(zone.toLowerCase(), -1);
    }

    @Tool(description = "查询指定版本号的热更状态，返回 applied 或 rolled-back。参数 version 例如 1.4.2。")
    public String hotfixStatus(String version) {
        return version.startsWith("1.4") ? "applied" : "rolled-back";
    }
}
