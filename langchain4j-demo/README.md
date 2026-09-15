# langchain4j-demo

Spring Boot **3.5.16** + LangChain4j **1.20.0** + **DeepSeek**。端口 **8082**。

## 跑

```powershell
$env:DEEPSEEK_API_KEY = "sk-..."
mvn spring-boot:run
```

```powershell
curl "http://localhost:8082/api/chat?message=你好"
curl "http://localhost:8082/api/chat/stream?message=讲讲JVM的类加载"
```

## DeepSeek 怎么接：走 OpenAI 兼容

**LangChain4j 没有 DeepSeek 模块。** 这不是我猜的，是查证过的：
`langchain4j-deepseek`、`langchain4j-deepseek-spring-boot-starter`、
`langchain4j-community-deepseek` 在 Maven Central 上**全是 404**，
官方的「所有支持的模型」对照表里也没有 DeepSeek 这一行
（只在 OpenAI 那一行的 Thinking 列注了个 `✅ (DeepSeek)`）。

所以只能走 OpenAI 兼容：

```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: ${DEEPSEEK_API_KEY:sk-REPLACE-ME}
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1}   # 注意 /v1
      model-name: ${AI_MODEL:deepseek-flash}
```

两个容易踩的点：

1. **base-url 要带 `/v1`**。LangChain4j 的 OpenAI 模块默认是
   `https://api.openai.com/v1`，它约定 base-url 里含版本段。
   而 DeepSeek 官方文档给的 base_url 是 `https://api.deepseek.com`，路径 `/chat/completions`。
   两边差一个 `/v1`。配置里做成了环境变量，**如果 404 就去掉 `/v1` 再试**：
   ```powershell
   $env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
   ```
2. **模型名用 `deepseek-flash`**。LangChain4j 官方文档里那段 DeepSeek 示例还在用
   已经退役的 `deepseek-chat`——文档是旧的，别抄。

**`/v1` 是实测确认可用的**：实例日志里的真实请求是
`POST https://api.deepseek.com/v1/chat/completions`，DeepSeek 返回的是**鉴权错误**
而不是 404，说明这个路径被正常路由。所以不用去掉 `/v1`。

## ⚠️ 必须单独配 `streaming-chat-model`（否则流式端点 500）

这个坑实测踩过：`/api/chat` 正常，`/api/chat/stream` 返回 **HTTP 500**，
而且**启动日志一切正常**。

根因（用 `javap` 读 jar 确认，不是猜的）：

```java
public class OpenAiChatModel          implements dev.langchain4j.model.chat.ChatModel
public class OpenAiStreamingChatModel implements dev.langchain4j.model.chat.StreamingChatModel
```

**这是两个独立的类**，`OpenAiChatModel` 不实现 `StreamingChatModel`。
所以只配 `langchain4j.open-ai.chat-model.*` 时，容器里**没有 `StreamingChatModel` Bean**，
`@AiService` 里返回 `TokenStream` 的方法要到**调用时**才失败。

修法就是 `application.yml` 里补上：

```yaml
langchain4j:
  open-ai:
    chat-model:            # 给普通问答用
      ...
    streaming-chat-model:  # 给 TokenStream 用，必须单独配
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
      model-name: ${AI_MODEL:deepseek-flash}
```

补上之后不会有 Bean 冲突（两个类实现的是不同接口）。
`AiWiringTests` 专门断言这两个 Bean 都存在 —— **因为 `contextLoads` 那种
"容器能起来就算过"的测试抓不住这个 bug**：缺 Bean 不影响启动，只在调用时炸。

## ⚠️ 流式 + 工具调用 + DeepSeek 的已知坑（**实测未复现**）

> **实测更新（2026-09-15）**：这一节描述的坑**没有复现**。
> 用 `/api/chat/stream?message=How many players are online on server s1?`
> （流式 + 真实触发工具调用）测试，返回 **HTTP 200**，回答逐 token 流出 `3214` ——
> **默认配置就能跑通**，不需要 `accumulateToolCallId(false)`。
>
> 下面内容保留作为备用方案和原理解释。若以后遇到 tool call id 相关的怪问题再回来改。
> 可能只在多工具并行、或 ID 被拆成多段时才暴露（本次测试只有单次简单调用）。

LangChain4j 官方文档明确写了：DeepSeek 和 Qwen 这类 API **每个 chunk 都发送完整的
tool call ID**，必须关掉 ID 累加：

```java
OpenAiStreamingChatModel.builder()
    .baseUrl("https://api.deepseek.com/v1")
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .modelName("deepseek-flash")
    .accumulateToolCallId(false)   // ← DeepSeek / Qwen 必须 false
    .build();
```

**麻烦在于 `accumulateToolCallId` 没有对应的 Spring Boot 配置项**，只能在代码里设。
所以：

- `chat()`（非流式）：**不受影响**，正常用
- `chatStream()`：如果报工具调用相关的错（tool call id 对不上 / 400），就是撞上这一条

**这是已知的上游限制，不是你写错了。** 解决方式是手写一个 `OpenAiStreamingChatModel` Bean；
但注意手写 Bean 可能和 starter 自动配置的 Bean 冲突，报
`multiple components of the same type` 时改用显式装配：

```java
@AiService(wiringMode = WiringMode.EXPLICIT, streamingChatModel = "myStreamingModel", tools = "gameServerTools")
```

## 另一个 DeepSeek 相关的限制

因为走的是 OpenAI 兼容模块，**DeepSeek 的 `thinking` / `reasoning_effort` 参数
LangChain4j 不暴露**。后果：

- 思考模式由 DeepSeek 服务端决定（默认开启），你**关不掉**
- 因此 `temperature: 0.7` **实际会被 DeepSeek 忽略**（思考模式下该参数无效）
- 拿不到 `reasoning_content`

对比之下 Spring AI 的专用 DeepSeek starter 这些都能配。**这是本次对比里
Spring AI 一个实打实的优势**，值得记进你的面试话术。

## 为什么 Spring Boot 是 3.5.16 而不是 4.1.1

LangChain4j 有**两套并行 starter**：

```
langchain4j-spring-boot-starter        → spring-boot-starter:3.5.13 编译
langchain4j-spring-boot4-starter       → spring-boot-starter:4.0.5  编译
```

两套都发布了（1.20.0-beta30）。这里选 Boot 3 线，因为 3.5.16 与编译目标 3.5.13
同一补丁线，风险最低。Boot 4 升级路径写在 `pom.xml` 的注释里。

## 核心：声明式 AI Service

```java
@AiService
public interface GameOpsAssistant {
    @SystemMessage("...")
    String chat(@UserMessage String message);
}
```

你只写接口，不写实现。**这是和 Spring AI 最本质的心智差异**：
LangChain4j 是声明式（接口 + 注解，实现由框架生成），
Spring AI 是命令式（`ChatClient` 链式调用，每一步你自己写）。

### 工具是自动接进来的

默认装配模式 `AUTOMATIC` 会**自动接入所有含 `@Tool` 方法的 Bean**——
`GameServerTools` 标了 `@Component`，所以它自动生效，**不需要**写 `tools = "..."`。

`@AiService` 确实有 `tools` 属性，但它的 Javadoc 明确写着只在
`wiringMode = EXPLICIT` 时使用。AUTOMATIC 模式下写它是未定义行为
（本项目第一版就是这么写的，已按官方源码修正）。要显式指定就用：

```java
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "openAiChatModel",
    streamingChatModel = "openAiStreamingChatModel",
    tools = "gameServerTools")
```
**注意 EXPLICIT 模式下必须把所有组件都写全**，漏一个就启动失败。

## 流式的两个坑

### 坑 1：方法名（编译期就会撞到）

LangChain4j 早期用 `onNext` / `onComplete`，**1.20.0 里这两个方法已经不存在了**：

| 旧名（0.x 时代，网上教程和 AI 生成的代码仍在用） | 1.20.0 的正确名字 |
|---|---|
| `onNext(Consumer<String>)` | **`onPartialResponse(Consumer<String>)`** |
| `onComplete(Runnable)` | **`onCompleteResponse(Consumer<ChatResponse>)`** |
| `onError(Consumer<Throwable>)` | `onError(...)` 没变 |
| `start()` | `start()` 没变 |

依据：`TokenStream` 的[官方 Javadoc](https://docs.langchain4j.dev/apidocs/dev/langchain4j/service/TokenStream.html)
方法列表里**没有** `onNext`。

`TokenStream` 还提供了一些你可能用得上的回调：`onPartialThinking`（思考过程）、
`onPartialToolCall`、`onToolExecuted`、`beforeToolExecution`、`onRetrieved`、`onIntermediateResponse`。

### 坑 2：懒执行 + 静默失败

```java
assistant.chatStream(message)
        .onPartialResponse(token -> ...)
        .onCompleteResponse(response -> ...)
        .onError(...)
        .start();   // ← 忘了这句，什么都不会发生，而且不报错
```

`TokenStream` 是**回调风格**且**懒执行**：不调 `start()` 就一个请求都不会发，
**而且不抛异常**。调试时非常费时间，所以 `ChatController` 里专门标了注释。

## 未验证提醒

本项目**没有被编译过**（写它的时候本次会话的 shell 不可用）。
`TokenStream` 的方法名已经按官方 Javadoc 修正过一次（见上面「坑 1」）。
若还有报错，请对照 `https://docs.langchain4j.dev`、
`https://github.com/langchain4j/langchain4j/releases/tag/1.20.0`、
`https://api-docs.deepseek.com`。**不要抄中文博客和 AI 生成的代码片段** ——
`onNext` 这个错就是从那个年代遗留下来的。
