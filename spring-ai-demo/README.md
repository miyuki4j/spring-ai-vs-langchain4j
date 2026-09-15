# spring-ai-demo

Spring Boot **4.1.1** + Spring AI **2.0.1** + **DeepSeek**。端口 **8081**。

## 跑

```powershell
$env:DEEPSEEK_API_KEY = "sk-..."
mvn spring-boot:run
```

```powershell
curl "http://localhost:8081/api/chat?message=你好"
curl "http://localhost:8081/api/chat/stream?message=讲讲JVM的类加载"
curl "http://localhost:8081/api/chat/agent?message=s1区服现在多少人在线？"
```

## 为什么用 DeepSeek 专用 starter

依赖是 `spring-ai-starter-model-deepseek`，**不是** `spring-ai-starter-model-openai`。
这不是风格问题，是实际差异：

| | 专用 DeepSeek starter | 走 OpenAI 兼容 |
|---|---|---|
| 端点路径 | 默认 `/chat/completions` ✓ 正好对上 | 默认拼 `/v1/chat/completions`，要手动改 |
| `thinking` 开关 | `spring.ai.deepseek.chat.thinking.type` | 没有 |
| `reasoning-effort` | `spring.ai.deepseek.chat.reasoning-effort` | 没有 |
| 取思考内容 | `DeepSeekAssistantMessage.getReasoningContent()` | 没有 |
| 多轮对话里处理 `reasoning_content` | 框架已处理 | 自己想办法 |

**而且切换 provider 时业务代码一行都不用改**——`ChatController` 只依赖 `ChatClient`，
这就是这层抽象的价值。你可以自己验证：把 pom 里 DeepSeek 依赖换成注释里的 OpenAI 依赖，
`ChatController.java` 完全不动。

## DeepSeek 配置要点

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:sk-REPLACE-ME}
      chat:
        options:
          model: ${AI_MODEL:deepseek-flash}   # 或 deepseek-v4-pro
          temperature: 0.7
        thinking:
          type: ${AI_THINKING:disabled}
```

- **模型名**：`deepseek-flash`（V4.1-Flash，便宜、1M 上下文、支持视觉）、
  `deepseek-v4-pro`（V4-Pro-0813，更强更贵）。
  **`deepseek-chat` / `deepseek-reasoner` 已退役，别再用了。**
- **思考模式默认关掉**，三个原因：思考模式下 `temperature` 完全无效、
  `tool_choice=required` 会 400、更慢更贵。想开：`$env:AI_THINKING="enabled"`。
- 想换模型不用改代码：`$env:AI_MODEL="deepseek-v4-pro"`。

## 三个端点对应三个概念

| 端点 | 代码 | 概念 |
|---|---|---|
| `/api/chat` | `chatClient.prompt().user(m).call().content()` | 最基础的调用 |
| `/api/chat/stream` | `...stream().content()` → `Flux<String>` | 流式（Reactor 风格） |
| `/api/chat/agent` | `...tools(tools).call()` | 工具调用 / Agent 最小形态 |

## 依赖为什么是 `spring-boot-starter-webmvc`

Spring Boot 4 里 `spring-boot-starter-web` 已废弃，官方推荐 `spring-boot-starter-webmvc`。
这是 Boot 4 最容易踩的迁移点之一。

## pom 里注释掉的部分

`spring-ai-bom:2.0.1` 已包含这些模块，用到时去掉注释即可：

- `spring-ai-starter-model-chat-memory` + `...-repository-jdbc` —— 多轮对话记忆
- `spring-ai-starter-mcp-client` —— MCP 客户端
- `spring-ai-starter-vector-store-pgvector` —— pgvector 向量库
- `spring-ai-starter-model-ollama` —— 本地模型

## 未验证提醒

本项目**没有被编译过**（写它的时候本次会话的 shell 不可用）。
API 写法若报错，请对照 `https://docs.spring.io/spring-ai`、
`https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1`、
`https://api-docs.deepseek.com`，
不要照抄中文博客。详见上一层目录的 `README.md`。
