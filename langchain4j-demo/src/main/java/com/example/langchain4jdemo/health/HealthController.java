package com.example.langchain4jdemo.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免费的探活 + 配置自述端点，给 {@code dashboard/} 的对照台用。
 *
 * <p>与 spring-ai-demo 侧的 {@code HealthController} 一一对应，字段也尽量对齐，
 * 便于页面上做「实际生效配置」的并排展示。
 *
 * <p>这里比对面多两个字段，因为它们是 LangChain4j 侧特有的坑点：
 * <ul>
 *   <li>{@code returnThinking} —— 不配 {@code true}，服务端返回的 {@code reasoning_content}
 *       会被<b>静默丢掉</b>（不报错、不告警）
 *   <li>{@code reasoningEffort} —— DeepSeek 的思考开关在 LangChain4j 侧没有对应参数，
 *       只能靠 OpenAI 的 {@code reasoning_effort}（实测 {@code none} 等效关思考）
 * </ul>
 * 这两条都是「配错了也不会报错、只是行为不对」的类型，最适合摊在页面上让人一眼看到。
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
        body.put("app", env.getProperty("spring.application.name", "langchain4j-demo"));
        body.put("framework", "LangChain4j 1.20.0");
        body.put("bootVersion", "3.5.16");
        body.put("webStack", "Spring MVC（servlet 栈）");
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("port", env.getProperty("server.port", "(未配置)"));
        // 走 OpenAI 兼容模块，不是 DeepSeek 专用模块
        body.put("model", env.getProperty("langchain4j.open-ai.chat-model.model-name", "(未配置)"));
        body.put("baseUrl", env.getProperty("langchain4j.open-ai.chat-model.base-url", "(未配置)"));
        body.put("returnThinking", env.getProperty("langchain4j.open-ai.chat-model.return-thinking", "(未配置)"));
        body.put("reasoningEffort", env.getProperty("langchain4j.open-ai.chat-model.reasoning-effort", "(未设置)"));
        body.put("modelStarter", "langchain4j-open-ai-spring-boot-starter");
        return body;
    }
}
