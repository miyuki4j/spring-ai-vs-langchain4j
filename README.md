# Spring AI vs LangChain4j —— 同题对照实验（DeepSeek 版）

两个最小可运行项目，做同一件事（聊天 / 流式 / 工具调用），都用 **DeepSeek API**，专门用来横向对比这两个 Java AI 框架。

领域一律用「游戏服务端运维」，因为那是你自己的领域。

---

## 目录结构

```
AI4JWorkspaces/
├── README.md                 ← 你正在看的这份
├── spring-ai-demo/           ← Spring Boot 4.1.1 + Spring AI 2.0.1，端口 8081
│   └── src/main/java/com/example/springaidemo/
│       ├── chat/ChatController.java        ← call / stream / agent 三个端点
│       └── tools/GameServerTools.java      ← @Tool 定义
└── langchain4j-demo/         ← Spring Boot 3.5.16 + LangChain4j 1.20.0，端口 8082
    └── src/main/java/com/example/langchain4jdemo/
        ├── assistant/GameOpsAssistant.java ← @AiService 接口
        ├── chat/ChatController.java        ← call / stream 两个端点
        └── tools/GameServerTools.java      ← @Tool 定义
```

两个项目**故意不做成 Maven 多模块聚合**：各自独立、各自能单独 `mvn spring-boot:run`。

---

## 先看这个：DeepSeek 接入的关键事实

全部来自 [DeepSeek 官方 API 文档](https://api-docs.deepseek.com/)（2026-09 核实）：

| 项 | 值 |
|---|---|
| base_url（OpenAI 格式） | `https://api.deepseek.com` |
| **端点路径** | **`POST /chat/completions`——注意没有 `/v1`** |
| **可用模型** | **`deepseek-flash`**（V4.1-Flash）、**`deepseek-v4-pro`**（V4-Pro-0813） |
| **已退役的模型名** | `deepseek-chat`、`deepseek-reasoner` ← **别再用，这是最大的坑** |
| 上下文 / 最大输出 | 1M / 384K |
| 思考模式 | **服务端默认开启** |
| Tool calls | 两个模型都支持 |
| 已废弃参数 | `frequency_penalty`、`presence_penalty`（传了也被忽略） |

**三个必须知道的 DeepSeek 行为**（都来自官方文档原文）：

1. **思考模式下 `temperature` 完全无效** —— 原文 *"Has no effect in thinking mode"*。
   所以你在 `application.yml` 里配了 `temperature: 0.7` 却没效果，不是框架的锅。
2. **思考模式下 `tool_choice` 传 `required` 或指定具名工具会返回 400** ——
   原文 *"`required` and named tool choices are not supported in thinking mode; the API returns a 400 error"*。
   做 Agent 时这一条会直接咬人。
3. **思考模式计费更高、延迟更大**，因为会产出 `reasoning_content`。

所以两个项目的默认配置**都把思考模式显式关掉**（`thinking.type: disabled`），
先把主链路跑通。想看思考过程再把 `AI_THINKING=enabled` 打开。

```powershell
$env:DEEPSEEK_API_KEY = "sk-你的key"     # 两个项目共用这一个变量
```

---

## 两边怎么接 DeepSeek：这是本次最有价值的一条差异

| | Spring AI 2.0.1 | LangChain4j 1.20.0 |
|---|---|---|
| 是否有 DeepSeek 专用模块 | **有**：`spring-ai-starter-model-deepseek` | **没有**（`langchain4j-deepseek`、community 模块、starter 全是 404） |
| 接入方式 | 专用 starter，`DeepSeekChatModel` | 走 **OpenAI 兼容**：`langchain4j-open-ai-spring-boot-starter` |
| base-url | `https://api.deepseek.com` | **`https://api.deepseek.com/v1`**（LangChain4j 的 OpenAI 模块约定要带 `/v1`） |
| 路径配置 | `completions-path` 默认 `/chat/completions` ✓ 正好对上 | 由 OpenAI 模块内部拼接 |
| 配置前缀 | `spring.ai.deepseek.*` | `langchain4j.open-ai.chat-model.*` |
| `thinking` 开关 | **原生支持** `spring.ai.deepseek.chat.thinking.type` | **不支持**（OpenAI 模块没暴露这个参数） |
| `reasoning-effort` | **原生支持** | 不支持 |
| 取思考内容 | `DeepSeekAssistantMessage.getReasoningContent()` | 不支持（走 OpenAI 兼容，拿不到一等公民待遇） |
| Tool calls | 支持，用自动注册的 `ToolCallingAdvisor` | 支持，但流式下有个坑（见下） |

**一句话总结**：**Spring AI 把 DeepSeek 当一等公民，LangChain4j 把它当"OpenAI 兼容端点之一"。**
对你（国内、要用 DeepSeek）来说，这是 Spring AI 一个实打实的优势。

### ⚠️ LangChain4j 流式 + 工具调用 + DeepSeek 的坑

LangChain4j 官方文档明确写了：**DeepSeek 和 Qwen 这类 API 每个 chunk 都发送完整的
tool call ID**，必须关掉 ID 累加，否则工具调用会坏：

```java
OpenAiStreamingChatModel.builder()
    .baseUrl("https://api.deepseek.com/v1")
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .modelName("deepseek-flash")
    .accumulateToolCallId(false)   // ← DeepSeek / Qwen 必须设 false
    .build();
```

**问题**：`accumulateToolCallId` 只能在代码里设，**没有对应的 Spring Boot 配置项**。
所以如果你要"流式 + 工具调用 + DeepSeek"，得手写一个 `OpenAiStreamingChatModel` Bean。
（注意：手写 Bean 可能和 starter 自动配置的 Bean 冲突，报"multiple components of the same type"
时改用 `@AiService(wiringMode = EXPLICIT, ...)` 显式指定。）

本项目的 `chat()`（非流式）路径不受影响；`chatStream()` 若报工具调用相关错误，
就是撞上了这一条 —— 这是**已知的上游限制，不是你写错了**。

---

## 版本（2026-09-15 核实，全部来自一手源）

| 组件 | 版本 | 来源 |
|---|---|---|
| Spring Boot（项目 1） | **4.1.1** | `api.spring.io`，`current: true` 标在 4.1.1 |
| Spring AI | **2.0.1** | GitHub Releases `v2.0.1`，2026-08-21 |
| Spring Boot（项目 2） | **3.5.16** | 3.5 线最新 GA |
| LangChain4j 核心模块 | **1.20.0** | GitHub Releases，2026-09-04 |
| LangChain4j starter 模块 | **1.20.0-beta30** | 由 `langchain4j-bom:1.20.0` 解析 |
| Java | **17** | `spring-boot-starter-parent:4.1.1` 内 `<java.version>17</java.version>` |

### 为什么两个项目的 Spring Boot 版本不一样

LangChain4j 为 Boot 3 和 Boot 4 提供了**两套并行 starter**，命名规则：

```
langchain4j-{integration}-spring-boot-starter    → Spring Boot 3
langchain4j-{integration}-spring-boot4-starter   → Spring Boot 4
```

两套都发布了（1.20.0-beta30），但**各自编译时钉死的 Boot 版本不同**：

```
langchain4j-spring-boot-starter        → spring-boot-starter:3.5.13
langchain4j-spring-boot4-starter       → spring-boot-starter:4.0.5
```

所以项目 2 选 Boot 3 线（3.5.16 与 starter 编译目标 3.5.13 同一补丁线，风险最低）。
想升 Boot 4 的话，把 parent 改成 4.1.1 并把两个依赖换成 `-spring-boot4-starter` 版本，
但那套是针对 4.0.5 编译的、没跟到 4.1.x，属于"能试但没保证"。升级路径已写进 `langchain4j-demo/pom.xml` 注释。

验证方法（把版本号换成别的就 404，这个技巧很实用）：

```
https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-spring-boot-starter/1.20.0-beta30/langchain4j-spring-boot-starter-1.20.0-beta30.pom          → 200
https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-deepseek/1.20.0/langchain4j-deepseek-1.20.0.pom                                              → 404（没有就是没有）
```

### 顺带一个 Boot 4 的坑

`spring-boot-starter-web` 在 4.1.1 里**已废弃**，pom description 原文：
*"deprecated in favor of spring-boot-starter-webmvc"*。
所以项目 1 用 `spring-boot-starter-webmvc`，项目 2 在 Boot 3 上继续用 `spring-boot-starter-web`。

---

## 同一件事，两边怎么写

| 能力 | Spring AI 2.0.1 | LangChain4j 1.20.0 |
|---|---|---|
| 调用风格 | **命令式**：`ChatClient` 链式调用 | **声明式**：`@AiService` 接口，框架生成代理 |
| 入口 | `chatClient.prompt().user(m).call().content()` | `assistant.chat(m)` |
| 系统提示词 | `builder.defaultSystem("...")`（代码里） | `@SystemMessage("...")`（注解里） |
| 定义工具 | `@Tool(description = "...")`<br>`org.springframework.ai.tool.annotation.Tool` | `@Tool("...")`<br>`dev.langchain4j.agent.tool.Tool` |
| 绑定工具 | `prompt().tools(bean)`（运行时） | `@AiService(tools = "beanName")`（声明式） |
| 流式 | `stream().content()` → `Flux<String>`，**Reactor** | `TokenStream`，**回调**（onPartialResponse / onCompleteResponse / onError / **start**） |
| 配置前缀 | `spring.ai.deepseek.*` | `langchain4j.open-ai.chat-model.*` |

**最值得记住的一点**：LangChain4j 的 `TokenStream` 是**懒执行**的，
忘了 `.start()` 就什么都不会发生，**而且不报错**——静默失败。

---

## 怎么跑

```powershell
$env:DEEPSEEK_API_KEY = "sk-你的key"

# 项目 1
cd spring-ai-demo ; mvn spring-boot:run

# 项目 2（另开终端）
cd langchain4j-demo ; mvn spring-boot:run
```

```powershell
# 基础问答
curl "http://localhost:8081/api/chat?message=你好"

# 流式
curl "http://localhost:8081/api/chat/stream?message=用三句话讲讲Netty的线程模型"

# 工具调用：模型应该自己去调 onlinePlayers("s1")
curl "http://localhost:8081/api/chat/agent?message=s1区服现在多少人在线？"
```

项目 2 把 `8081` 换成 `8082`，没有 `/agent`（工具是声明式绑在 `@AiService` 上的，直接问就会触发）。

换模型不用改代码，两个项目都用 `${AI_MODEL:...}`：

```powershell
$env:AI_MODEL = "deepseek-v4-pro"   # 想上更强的
$env:AI_THINKING = "enabled"        # 项目 1 想打开思考模式
```

---

## ⚠️ 我验证了什么、没验证什么

**已核实（一手源，可复现）**：
- 所有版本号、所有 artifact 是否存在（用"对应版本 pom 的 HTTP 状态"判定）
- Spring AI BOM 2.0.1 完整模块清单；`spring-ai-starter-model-deepseek:2.0.1` 存在
- LangChain4j 存在 / 不存在 DeepSeek 模块（前者 404，后者 404）
- LangChain4j 两套 starter 各自的 Boot 编译目标（3.5.13 / 4.0.5）
- DeepSeek 当前的 base_url、端点路径、模型名、thinking 默认值、已退役模型名
- Spring AI 的 `spring.ai.deepseek.chat.completions-path` 默认 `/chat/completions`

**实测验证记录（2026-09-15）**

用 curl 打真实端点，结果：

| 端点 | 结果 |
|---|---|
| P1 `/api/chat` | ✅ 200 |
| P1 `/api/chat/stream` | ✅ 200，分块 SSE |
| P1 `/api/chat/agent`（工具调用） | ✅ 200，回答含 `3214` |
| P2 `/api/chat` | ✅ 200 |
| P2 `/api/chat/stream` | ✅ 200，分块 SSE（**原为 500，已修**，见下） |
| P2 `/api/chat`（工具调用） | ✅ 200，回答含 `3214` |
| **P2 `/api/chat/stream`（流式 + 工具调用）** | ✅ 200，逐 token 流出 `3214` |

`3214` 是 `GameServerTools` 里写死的值，模型不可能凭空知道 —— 它出现即证明
「模型决定调用工具 → 后端执行 → 结果回灌 → 模型总结」整条链路是通的。

**实测中抓到并修掉的三个真实错误**：

1. **`TokenStream.onNext` / `onComplete` 在 1.20.0 里不存在**（由 IDE 编译报错暴露）。
   正确名字是 `onPartialResponse` / `onCompleteResponse`。
   这个错名字至今仍在大量中文教程和 AI 生成的代码里流传。
2. **`@AiService(tools = "...")` 的 `tools` 属性只适用于 `wiringMode = EXPLICIT`**；
   默认的 AUTOMATIC 模式会自动接入所有含 `@Tool` 的 Bean。已按 `AiService` 官方源码修正。
3. **`/api/chat/stream` 返回 500，根因是缺 `StreamingChatModel` Bean。**
   `javap` 确认这是两个独立的类：
   `OpenAiChatModel implements ChatModel`，
   `OpenAiStreamingChatModel implements StreamingChatModel`。
   只配 `chat-model` 时容器里根本没有 `StreamingChatModel`，
   `@AiService` 里返回 `TokenStream` 的方法要到**调用时**才失败 —— **启动日志一切正常**。
   修法：补上 `langchain4j.open-ai.streaming-chat-model.*`。
   并新增 `AiWiringTests` 断言两个 Bean 都在，把这种"只在调用时暴露"的问题提前到构建阶段。

**顺带关掉了两个悬案**：

- **`/v1` 确认可用。** 实例日志里的真实请求是
  `POST https://api.deepseek.com/v1/chat/completions`，
  DeepSeek 返回的是**鉴权错误**而不是 404 —— 说明该路径被正常路由。
  这条此前"连官方文档都自相矛盾"，现在闭环。
- **补上 `streaming-chat-model` 不会造成 Bean 冲突。** 3 个测试全过，容器正常启动。

**`spring.ai.deepseek.chat.thinking.type` 已实测验证（A/B 对比）**

用本地 stub 服务器截获 Spring AI 真实发出的请求体，改配置跑两次对比：

```
thinking.type=disabled  →  "thinking":{"type":"disabled"}
thinking.type=enabled   →  "thinking":{"type":"enabled"}
```

绑定生效、值正确、原样到达线上请求体、且随配置切换。

方法见 `stub/README.md` —— 这个手法（**本地 stub + 截请求体**）不花 API 费用、
不需要真 key，以后遇到任何"某个配置到底生效没有"的疑问都可以这么验。

**至此，本文档列过的所有风险项全部闭环。**

**一条"文档警告了、但实测没复现"的坑**：LangChain4j 文档说 DeepSeek 每个 chunk 都发完整
tool call ID，流式 + 工具调用必须设 `accumulateToolCallId(false)`。
但实测（P2-4：`/stream` + 真实触发工具调用）**用默认配置就跑通了**，回答逐 token 流出 `3214`。
所以这条警告在本场景没有复现，保留备用即可。
**判断它真被触发的标准**：答案里没有 `3214`、或日志报 tool call id 相关错误。
（可能只在多工具并行调用、或 tool call ID 被拆成多段时才暴露 —— 本测试只有单次简单调用。）

**⚠️ 一个会直接影响你的环境坑**

这台机器的 `JAVA_HOME` 指向 **JDK 8**（`C:\Program Files\Java\jdk1.8.0_201`），
而 Maven 用的是 `JAVA_HOME`。所以**在普通终端里跑 `mvn` 会编译失败**
（JDK 8 不认识 `switch` 表达式），而且报错是 GBK 乱码，很难看出真正原因。
IntelliJ 用的是自己的 SDK，所以 IDE 里一切正常 —— 这就是"IDE 能跑但命令行不能"的原因。

```powershell
# 在终端里跑 Maven 前，先切到 JDK 17
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

**报错怎么办**：不要猜，也不要抄中文博客（前面已经给你看过一个伪造 API 的例子）。
直接查 `github.com/spring-projects/spring-ai/releases/tag/v2.0.1`、
`github.com/langchain4j/langchain4j/releases/tag/1.20.0`、
`docs.spring.io/spring-ai`、`docs.langchain4j.dev`、`api-docs.deepseek.com`。

---

## 下一步

1. 两个项目分别跑通 `/api/chat`，确认 DeepSeek 通
2. 跑通 `/api/chat/stream`，**亲眼看到两种流式模型的差别**
3. 跑通工具调用，看日志里模型怎么选工具（LangChain4j 侧已开 `log-requests`）
4. **试一次把 `AI_THINKING=enabled` 打开**，观察 DeepSeek 的 `reasoning_content`——
   Spring AI 侧能拿到，LangChain4j 侧拿不到。这一条对比你面试时可以直接讲
5. 加对话记忆，体会两边 `ChatMemory` 设计的不同
6. 加 MCP：把「技能配置查询」做成 MCP server，两边都接一遍
7. 到这一步，你就有能力把它们接进 `AI开发学习` 里那个诊断助手了
