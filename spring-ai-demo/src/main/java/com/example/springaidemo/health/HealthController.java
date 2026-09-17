package com.example.springaidemo.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免费的探活 + 配置自述端点，给 {@code dashboard/} 的对照台用。
 *
 * <p><b>为什么需要它？</b>两个 demo 都<b>没有引入 actuator</b>，而页面打开时要显示
 * 「两个后端是否在线」。拿对话端点探活当然也能判断，但每刷新一次页面就烧一次 token
 * —— 显然不行。所以加一个只读本地配置、完全不碰模型的端点。
 *
 * <p>顺带解决第二个需求：<b>页面要显示「实际生效的配置」</b>。
 * 这一点在本项目里有真实教训 —— 之前 Tomcat 死活去监听 60154 而不是 application.yml
 * 里写的 8081，根因是宿主环境注入了 {@code SERVER_PORT} 环境变量，而它的优先级
 * <b>高于</b> yml。所以这里读的是 {@link Environment} 而不是直接读 yml：
 * {@code Environment} 拿到的是「所有配置来源合并后」的最终值，也就能把这类覆盖暴露在页面上。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final Environment env;

    public HealthController(Environment env) {
        this.env = env;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", env.getProperty("spring.application.name", "spring-ai-demo"));
        body.put("framework", "Spring AI 2.0.1");
        body.put("bootVersion", "4.1.1");
        body.put("webStack", "Spring MVC（servlet 栈）");
        body.put("javaVersion", System.getProperty("java.version"));
        // 注意：这里能读到 SERVER_PORT 之类的环境变量覆盖结果，正是我们想要的
        body.put("port", env.getProperty("server.port", "(未配置)"));
        body.put("model", env.getProperty("spring.ai.deepseek.chat.options.model", "(未配置)"));
        // 全局层的思考开关。请求层还能用 enableThinking()/disableThinking() 覆盖它
        body.put("thinking", env.getProperty("spring.ai.deepseek.chat.thinking.type", "(未配置)"));
        // 模型接入走的是 DeepSeek 专用 starter，不是 OpenAI 兼容
        body.put("modelStarter", "spring-ai-starter-model-deepseek");
        return body;
    }
}
