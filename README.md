# Spring AI vs LangChain4j —— 同题对照实验（DeepSeek 版）

两个最小可运行项目，做同一件事（聊天 / 流式 / 工具调用），都用 **DeepSeek API**，专门用来横向对比这两个 Java AI 框架。

领域一律用「游戏服务端运维」，因为那是你自己的领域。

---

## 目录结构

```
AI4JWorkspaces/
├── README.md                 ← 你正在看的这份
├── start-all.ps1             ← 一键起 MCP server + 两个后端 + 对照台页面
├── verify-demos.ps1          ← 11 条用例的端到端验证脚本（起服务后跑）
├── dashboard/                ← 可视化对照台（独立静态页，端口 8090）
│   ├── index.html            ← 五个页签：问答与工具 / 流式 / 思考 / 对话记忆 / 端点与结论
│   ├── app.js                ← 同时打两侧、SSE 帧解析、同刻度时间轴、token 归一化、记忆增长图
│   └── styles.css
├── probe/                    ← 本文档各条结论的可复现验证脚本，见 probe/README.md
├── mcp-skill-server/         ← 工具提供方：Spring Boot 4.1.1 + MCP server，端口 8099
│   └── src/main/
│       ├── java/com/example/mcpskillserver/
│       │   ├── McpSkillServerApplication.java
│       │   ├── catalog/SkillCatalog.java       ← 读 skills.json 进内存（不依赖 AI 框架）
│       │   ├── config/McpServerConfig.java     ← 把 @Tool 方法包成 ToolCallbackProvider
│       │   └── tools/SkillQueryTools.java      ← 4 个 @Tool：list/get/explain/validate
│       └── resources/skills.json               ← 示例技能配置（锚点 9001 在这里）
├── spring-ai-demo/           ← Spring Boot 4.1.1 + Spring AI 2.0.1，端口 8081
│   └── src/main/java/com/example/springaidemo/
│       ├── chat/ChatController.java        ← call / stream / agent / think / think/stream
│       ├── config/WebConfig.java           ← CORS（给对照台用）
│       ├── health/HealthController.java    ← /api/health：探活 + 配置自述
│       ├── mcp/McpController.java          ← /api/chat/mcp：实验组 / 对照组 + /tools
│       ├── memory/MemoryConfig.java        ← 对话记忆：只要一个 MessageChatMemoryAdvisor
│       ├── memory/MemoryController.java    ← /api/chat/memory：四轮 + 无记忆对照 + 窗口裁剪
│       └── tools/GameServerTools.java      ← @Tool 定义
└── langchain4j-demo/         ← Spring Boot 3.5.16 + LangChain4j 1.20.0，端口 8082
    └── src/main/java/com/example/langchain4jdemo/
        ├── assistant/GameOpsAssistant.java ← @AiService 接口（含 @MemoryId 方法）
        ├── chat/ChatController.java        ← call / stream / think / think/stream
        ├── config/McpClientConfig.java     ← MCP 客户端：McpClient + McpToolProvider 全手写
        ├── config/MemoryConfig.java        ← 对话记忆：Store + Provider 全手写
        ├── config/WebConfig.java           ← CORS
        ├── health/HealthController.java    ← /api/health
        ├── mcp/McpController.java          ← /api/chat/mcp：与 P1 逐字段对齐
        ├── memory/MemoryController.java    ← /api/chat/memory：与 P1 逐字段对齐
        └── tools/GameServerTools.java      ← @Tool 定义
```

两个项目**故意不做成 Maven 多模块聚合**：各自独立、各自能单独 `mvn spring-boot:run`。
`mcp-skill-server` 同样独立 —— 它不含大模型，也不依赖任何 AI 框架。

---

## 可视化对照台（`dashboard/`）

前面那些结论都能用 curl 和脚本验，但**看不出流式逐字的节奏差，也看不出 token 柱子的长短**。
`dashboard/` 是一个独立的静态页，把同一道题**同时**打给两个后端，左右并排对照。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1
# 起来后会自动打开 http://localhost:8090/
```

手动起也行：两个后端照常用 `mvn spring-boot:run`，再补一个静态服务器
`python -m http.server 8090 --directory dashboard`。

五个页签：

| 页签 | 做什么 | 能看出什么 |
|---|---|---|
| 问答与工具调用 | 同时打 `/agent`(P1) 与 `/chat`(P2) | 哪一侧真的调了工具（`3214` 会被高亮）、耗时、响应长度 |
| 流式输出 | 同时打 `/stream` | 逐字打字，加**同一刻度**的两条时间轴：等待首字与流式输出各占多久 |
| 思考模式 | 同时打 `/think` | 思考内容折叠展示、token 条形对照（柱子长度直接可比） |
| 对话记忆 | 同时打 `/api/chat/memory` | 逐轮的**记忆条数**与 **prompt token 增长曲线**（同一 y 轴）、记忆里的角色序列、锚点命中与否；窗口下拉可现场把窗口压到 2，看锚点被挤掉 |
| 端点与结论 | —— | 端点速查表、实测结论、两侧**实际生效**的配置（读 `/api/health`） |

几个取舍值得记下来：

- **为什么页面独立部署，而不是塞进某一侧的 `static/`？** 对照台要同时调 8081 和 8082，
  塞进任何一侧，对另一侧仍然是跨域 —— 只是把问题挪了个位置。所以两边各自放行 CORS，页面独立。
- **时间轴为什么必须等两侧都流完才画？** 两条轴如果各自归一化，「看起来更快」可能只是各自把条拉满了。
  统一刻度（满格 = 较慢那一侧）才能直接横向对读。
- **`/api/health` 是为什么加的？** 两个 demo 都没引 actuator，而页面要显示「后端是否在线」。
  拿对话端点探活，每刷新一次就烧一次 token，所以加了个只读本地配置、不碰模型的端点。
  它读的是 Spring 的 `Environment`（所有配置来源合并后的最终值），
  所以**环境变量对 yml 的覆盖会在这里露出来** —— 就是之前那个 `SERVER_PORT` 坑。

页面还认几个 URL 参数，方便直连和自动化：

| 参数 | 作用 |
|---|---|
| `?tab=qa\|stream\|think\|memory\|ref` | 直接切到某个页签 |
| `&auto=1` | 进页面就自动跑一次 |
| `&thinking=false` | 预置 Spring AI 侧的思考开关 |
| `&q=自定义问题` | 覆盖输入框内容（记忆页签里它会被当成**会话 ID**） |
| `&window=2` | 预置记忆页签的窗口大小（一键截"裁剪"那组对照图） |
| `&demo=1` | **演示模式**：不打后端，用本地合成响应填充（没起后端也能看效果，含记忆页签） |

自检（验证 CORS 放行与接口形状是否满足页面所需，不需要浏览器）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\probe\run-dashboard-check.ps1
```

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
| `thinking` 开关 | **原生支持**，而且是两层：配置层 `spring.ai.deepseek.chat.thinking.type`、请求层 `DeepSeekChatOptions.Builder.enableThinking()/disableThinking()` | **没有 thinking 参数**（已抓请求体实测：顶层只有 model / messages / temperature / stream / tools） |
| `reasoning-effort` | **原生支持**（`reasoningEffortHigh()/Max()`） | **starter 有 `reasoning-effort` 配置项**（源码确认已接线到 `OpenAiChatRequestParameters`）。实测 DeepSeek 认这个参数，且 `none` 等效关掉思考 |
| 取思考内容 | `DeepSeekAssistantMessage.getReasoningContent()` | **也能拿到**：`AiMessage.thinking()`，前置条件是 `return-thinking: true` |
| 流式思考 | `stream().chatResponse()` 逐片取 `getReasoningContent()` | `TokenStream.onPartialThinking(PartialThinking)` |
| Tool calls | 支持，用自动注册的 `ToolCallingAdvisor` | 支持，但流式下有个坑（见下） |

**一句话总结**：**Spring AI 把 DeepSeek 当一等公民，LangChain4j 把它当"OpenAI 兼容端点之一"。**

但**这个差距比表面看上去小**，因为 LangChain4j 1.20.0 专门为 DeepSeek 做了兼容：

- 它的 `OpenAiChatModel` 有个 `thinkingFieldName`，**默认值就是 `"reasoning_content"`**，源码注释点名 *"This setting is intended for DeepSeek"*；
- 配套的 `return-thinking` / `send-thinking` 开关里，`return-thinking` **已经接进了 Spring Boot starter**（`ChatModelProperties.returnThinking()` → `AutoConfig` 里 `.returnThinking(...)`），所以不用手写 Bean。

真正的差别收敛成一句话：**Spring AI 能"主动控制"思考开关，LangChain4j 只能"被动接收"**（`reasoning_effort: none` 是个可用的旁路，见下）。
对你（国内、要用 DeepSeek）来说，Spring AI 在可控性上仍占优，但没到大得不能用的程度。

> ⚠️ 上一版本文档在这里写的是"LangChain4j 拿不到 `reasoning_content`"—— **这条结论是错的，已在 2026-09-16 实测推翻并更正**，证据见下面「思考模式」一节。凡是从旧博客/旧文档抄来的结论，都要落到实测才算数。

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

## 思考模式（`reasoning_content`）实测 —— 2026-09-16

这是「下一步」第 4 条，也是本文档里**推翻自己旧结论**的一节。

### 一、先搞清服务端行为（直接打 API，不经过任何框架）

`thinking` 字段传什么、不传什么，DeepSeek 的反应完全不同：

| 请求体 | `reasoning_content` | completion tokens |
|---|---|---|
| `thinking:{"type":"disabled"}` | **无** | 1 |
| **完全不传 `thinking`** | **有**（147 字符） | 44（其中 reasoning 42） |
| `thinking:{"type":"enabled"}` | 有（101 字符） | 28（其中 reasoning 26） |

**关键结论：DeepSeek 服务端默认就是「开思考」。** 所以 LangChain4j 哪怕一个 `thinking` 字段都不发，服务端照样思考、照样返回 `reasoning_content`。

顺带把 OpenAI 的 `reasoning_effort` 也测了：

| `reasoning_effort` | `reasoning_content` | completion tokens |
|---|---|---|
| `high` | 有（121 字符） | 40 |
| `max` | 有（123 字符） | 40 |
| `low` | 有（59 字符） | 39 |
| **`none`** | **无** | **1** |

**DeepSeek 认 `reasoning_effort`，而且 `reasoning_effort:"none"` 等效于关掉思考。**
这是给 LangChain4j 用的旁路 —— 它没有 `thinking` 参数，但 starter 有 `reasoning-effort` 配置项。

复现脚本：`probe/deepseek-thinking-probe.py`

### 二、两侧怎么取思考内容

两边各加了一对端点，同一道题、同一套判定：

| 端点 | 取法 |
|---|---|
| `GET :8081/api/chat/think?message=...&thinking=true\|false` | `DeepSeekAssistantMessage.getReasoningContent()` |
| `GET :8081/api/chat/think/stream?message=...&thinking=true\|false` | `stream().chatResponse()` 逐片 `getReasoningContent()` |
| `GET :8082/api/chat/think?message=...` | `AiMessage.thinking()`（需 `return-thinking: true`） |
| `GET :8082/api/chat/think/stream?message=...` | `TokenStream.onPartialThinking(PartialThinking)` |

非流式统一返回：`{"answer":..., "reasoningContent":..., "reasoningChars":N, "tokens":{...}}`。
`reasoningChars` 是判定用的：**0 = 没拿到思考**。

### 三、实测结果：两侧都拿得到

同一问题（`13 的平方是多少？只回答数字。`）：

| 用例 | answer | reasoningChars | tokens |
|---|---|---|---|
| P1 思考=开 | `169` | **65** | prompt 59 / completion 19 / total 78 |
| P1 思考=**关** | `169` | **0** | prompt 34 / completion **1** / total **35** |
| P2（无开关可传） | `169` | **63** | 日志实测 completion 20，其中 reasoning 18 |

流式两边都是 39 个思考分片 + 1 个回答分片（`R:` 前缀是思考，`C:` 是正式回答）。

**所以：**

1. **LangChain4j 拿得到 `reasoning_content`** —— 旧结论作废。
2. **Spring AI 能主动关思考，LangChain4j 不能** —— 这才是真差别。P1 关掉思考后 total 从 78 → 35，**省掉 55%**；P2 没这个开关，每次都在为 reasoning token 付钱。
   - LangChain4j 的补救：`langchain4j.open-ai.chat-model.reasoning-effort: none`（依据见上一节的实测）。
3. P2 的 prompt tokens 比 P1 大得多（398 vs 59），因为 `@AiService` 的 AUTOMATIC 模式**每次都把工具定义一起发出去** —— 这是另一条成本差异，值得记一笔。

### 四、踩到的坑：`@AiService` 返回 `ChatResponse` 是个陷阱

最初给 P2 写的声明式方法是 `ChatResponse chatWithThinking(String)` —— 直觉上"我要完整响应"就该返回它，而且 `AiServiceValidation.SUPPORTED_RETURN_TYPES` 也确实把 `ChatResponse` 列为合法返回类型。

**结果 `answer` 不是 `169`，而是一坨模型编出来的 JSON：**

```json
{"aiMessage":{"text":"169","thinking":"...","toolExecutionRequests":[],"attributes":{}},
 "metadata":{"id":"chatcmpl-13squared","modelName":"gpt-4o","tokenUsage":{...}}}
```

`id: chatcmpl-13squared`、`modelName: gpt-4o` —— **全是模型编的**，DeepSeek 根本不会返回 `gpt-4o`。

**根因（源码级）**：`AiServiceValidation.SUPPORTED_RETURN_TYPES` 只用于"校验时放行"，**不决定运行时行为**。真正决定行为的是 `ServiceOutputParser.schemaNotRequired(...)`，它的白名单是：

```
String / AiMessage / TokenStream / Response / Map / void / Void
```

**`ChatResponse` 不在其中** → 框架认为"需要 JSON schema" → 往 user 消息里追加
`You must answer strictly in the following JSON format: {...}` → 让大模型去"生成"一个 ChatResponse 形状的 JSON。

抓到的真实请求体（靠 `log-requests: true`）：

```
"content" : "13 的平方是多少？只回答数字。\nYou must answer strictly in the following JSON format: {\n\"aiMessage\": (type: dev.langchain4j.data.message.AiMessage: {\n\"text\": (type: string),\n\"thinking\": (type: string), ...
```

**修法**：返回类型换成 `AiMessage`。源码两处都能证实它是对的：

1. `schemaNotRequired` 白名单**包含 `AiMessage`** → 不追加 JSON 指令；
2. `ServiceOutputParser.parse(...)` 里有 `if (rawClass == AiMessage.class) return aiMessage;` → **原样返回真实对象**。

改完后 user 消息恢复成干净的 `"13 的平方是多少？只回答数字。"`，响应 `id` 是真实 UUID、`model` 是 `deepseek-flash`，answer 回到 `169`。

**一句话记忆**：用 `@AiService` 时**别再返回 `ChatResponse`** —— 它会被当成"要模型生成的 POJO"。要拿思考内容就用 `AiMessage`。
（`AiMessage` 不带 token 用量；官方支持的返回类型里另有 `Result<AiMessage>` 可能能带上，**本项目未实测，不下结论**。）

复现脚本：`probe/parse-p2-request.py`（从运行日志里还原真实请求体）

---

## 对话记忆实测 —— 2026-09-17

第 5 条「下一步」。两侧各加了一对 `/api/chat/memory`，**返回体逐字段对齐**，
所以页面能并排画。这一节比前几节长，因为踩到了一个**只在链路上才看得见的 bug**。

### 一、装配成本：一边开箱即用，一边必须手写

| | Spring AI 2.0.1 | LangChain4j 1.20.0 |
|---|---|---|
| 依赖 | `spring-ai-starter-model-chat-memory` | `langchain4j-spring-boot-starter`（本来就有） |
| 自动配置出的 Bean | **`ChatMemoryRepository` + `ChatMemory` 两个都有了**（`ChatMemoryAutoConfiguration`） | **一个都没有**：starter 的 `AutoConfiguration.imports` 里只有 `LangChain4jAutoConfig` 一个空壳（只承载 properties） |
| 业务侧还要写什么 | 只补一个 Advisor（`MessageChatMemoryAdvisor`） | `ChatMemoryStore` + `ChatMemoryProvider` 全手写 |
| 窗口默认值 | `MessageWindowChatMemory.DEFAULT_MAX_MESSAGES = 20`，**且没有配置项能改**（2.0.1 的 autoconfigure jar 里没有 `ChatMemoryProperties`，整个 jar 只有那一个类）→ 只能自己声明同名 Bean 覆盖 | 自己传 `maxMessages`，本来就是手写的 |
| 存储 key 类型 | `String conversationId` | `Object memoryId`（用 `Long` 当用户 id 也行） |

### 二、记忆挂在哪：Advisor 拦截 vs 接口契约

- **Spring AI**：记忆挂在 `MessageChatMemoryAdvisor` 上，**会话 id 是请求参数**：
  ```java
  .advisors(a -> a.advisors(advisor).param(ChatMemory.CONVERSATION_ID, conversationId))
  ```
  （常量值是 `"chat_memory_conversation_id"`）。同一个 `ChatClient` 每次调用想换会话就换。
- **LangChain4j**：记忆是**接口契约的一部分** —— 方法上标 `@MemoryId`，由 `ChatMemoryProvider` 分槽：
  ```java
  Result<String> chatWithMemory(@MemoryId String memoryId, @UserMessage String message);
  ```

后果不止"写法不同"：LangChain4j 的 `ChatMemoryService` 里有一张
`ConcurrentHashMap<Object, ChatMemory>` 缓存，`getOrCreateChatMemory()` 用的是 `computeIfAbsent`
（字节码确认）——**同一个 memoryId 的 `ChatMemory` 实例只创建一次**，
之后改 Provider 也不会重建。所以「按请求换窗口」在 `@AiService` 代理上**做不到**，
只能 `AiServices.builder(...)` 现场构造一个新的服务实例。

顺带一个机制差异：两个 Bean 不是靠 `@Autowired` 接上的，而是
`AiServicesAutoConfig` 注册的 `BeanFactoryPostProcessor` 在启动时改 `@AiService` 的 BeanDefinition。
它有**三种失败模式**（`addBeanReference` 字节码）：

| 情况 | 行为 |
|---|---|
| `ChatMemoryProvider` Bean **0 个** 且方法用了 `@MemoryId` | 启动抛异常（`AiServiceValidation.validateMethod` 的单向校验） |
| Provider Bean **0 个** 且方法没用 `@MemoryId` | **什么都不做，不报错不警告** —— 那类方法就是不走记忆，静默 |
| Provider Bean **2 个及以上**（`wiringMode = AUTOMATIC`） | 启动直接抛冲突，提示改用 `wiringMode = EXPLICIT` |

### 三、order：记忆在工具之前，所以工具轮的中间消息不进记忆

| advisor | 默认 order |
|---|---|
| `MessageChatMemoryAdvisor` | `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` = `HIGHEST_PRECEDENCE + 200` |
| `ToolCallingAdvisor` | `ToolCallingAdvisor.DEFAULT_ORDER` = `HIGHEST_PRECEDENCE + 300` |

数值小的在上游、先执行。记忆 advisor 在工具 advisor **之前**，
所以工具循环里那些 `assistant(tool call)` / `tool(result)` 中间消息**不会**写进对话记忆 ——
P1 的记忆里永远是一对一轮的 `user,assistant`。
而 P2 把 `system` 和工具轮的 `tool` 中间消息**一起**存进同一个窗口，
于是**同样的"20 条窗口"，一边是 10 轮、一边只够 4~5 轮**。
这个差别在页面的「窗口换算成对话轮」一行里直接写着。

### 四、实测数据

主运行（窗口 20，四轮 + 一组无记忆对照）：

| | P1 Spring AI | P2 LangChain4j |
|---|---|---|
| 每轮记忆条数 | `0 → 2 → 4 → 6` | `0 → 5 → 7 → 11` |
| 记忆里的角色 | 只有 `user,assistant` | `system` + `user/assistant` + `tool` + `assistant` |
| 回忆轮 prompt | 521 | 597 |
| 对照轮 prompt | 444 | 411 |
| 每条历史消息成本（受控对照） | **12.8 token/条**（77 ÷ 6） | **16.9 token/条**（186 ÷ 11） |

小窗口（`maxMessages=2`）—— 这一组才是重点，它是"记忆退化"的硬证据：

| | P1 Spring AI | P2 LangChain4j |
|---|---|---|
| 每轮记忆条数 | `0 → 2 → 2 → 2`（窗口封顶） | `0 → 2 → 2 → 2` |
| 最早那条 user 消息 | 变成**回忆问题本身**（第 1 轮被挤出窗口） | 变成**空**（窗口 2 只够装 `system` + 最后一条 `assistant`） |
| 锚点 `LOONG-7749` | **丢失** ✅ | **丢失** ✅ |
| 回忆轮实际回答 | 「您前面只问过 **2区** 的在线人数，结果是 **1870 人**……您并没有让我记住过任何版本号」 | 「这是本会话的第一条消息，在此之前我没有收到过任何区服查询」 |

P1 那句回答值得单独看一眼：它**精确地只记得窗口里剩下的那一轮**，
并明确否认了被裁掉的内容 —— 这不是"模型答得不好"，是记忆真的只剩 2 条。

### 五、怎么判定"记忆真的生效"：锚点 + 对照（这次最值得带走的方法）

第 2 轮让模型记住一个**工具无论如何产不出来**的值 `LOONG-7749`（工具只有 s1/s2/s3 三个区的在线数和 applied/rolled-back 两种热更状态）。
- 只看回忆轮答出了 `3214` **不算证据** —— 模型可以自己再调一次工具把它查回来，实测真发生了。
- 对照组拿**同一个问题**在一个**全新 conversationId** 上问一次，必须**连锚点都答不出**。
  一真一假，才排除了"模型蒙对"。

**成本也必须用受控对照算**：`回忆轮 prompt − 对照轮 prompt`，这两次是同一道题、同一套工具，
唯一变量是历史条数，差值才能换算成"每条历史消息值多少 token"。
直接看"prompt 逐轮递增"是错的 —— 工具轮会把工具定义和工具结果一起回灌，一次多出四五百 token，
序列是 `[906, 467, 1010, 521]` 这种锯齿形，跟记忆没关系（这条是写脚本时真踩过的，见 `probe/README.md`）。

### 六、⚠️ 本次最有价值的坑：advisor 跨请求累积（弱断言集体放行）

**现象**：小窗口那一跑，只有最硬的那条断言红 —— "窗口裁掉锚点后锚点必须丢"，
而"窗口参数被采纳""每轮历史不超窗口""最早那条 user 消息变了"**全绿**。

**根因是 Spring AI 侧的代码 bug**，三个事实叠在一起才成立：

1. `ChatClient.Builder` 是 prototype 作用域，但控制器只在构造时注入一次，之后一直是同一个实例；
   而 `defaultAdvisors(...)` 是**往列表里 `addAll`（不是覆盖）**
   （`DefaultChatClientBuilder#defaultAdvisors` → `DefaultChatClientRequestSpec.advisors`）——
   **每请求调一次就多叠一个 advisor**。
2. 两个 `MessageChatMemoryAdvisor` 的 order **相同**（都是 `HIGHEST_PRECEDENCE + 200`），
   同 order 按加入顺序执行 —— **上一轮遗留的那个排在前、先注入**。
3. `MessageChatMemoryAdvisor.before()` 里有 `isMemoryAlreadyInPrompt(prompt, memoryMessages)`：
   记忆消息如果已经是 prompt 的一段连续子序列就**跳过注入**（本意是防重复注入），
   于是新加的小窗口 advisor 被**静默吞掉**。

**合起来**：小窗口那次请求里，真正生效的是"上一轮遗留的默认窗口（20 条）"记忆，
小窗口等于没做 —— 而且全程**不报错**。

**为什么弱断言全绿**：它们读的都是我们自己 `new` 出来的那个 memory 对象，它当然是 2 条。
真正决定模型看到什么的是**链上生效的那个 advisor**。
→ **断言要挑"穿过整条链路才能得到的量"**（锚点进没进 prompt、模型说没说得出），
而不是自己手里对象的内部状态。

**修法**：`ChatClient` 只 `build()` 一次，记忆 advisor **按调用传**
（`.advisors(a -> a.advisors(advisor).param(...))`）—— 这也正是 Spring AI 的设计意图：
**记忆是调用参数，不是 builder 上的常量**。

修完的交叉验证：小窗口第 3 轮 prompt 从 1064 → 960（旧 advisor 多注入的那 2 条消息消失了），
回忆回答也从"复述全部三个事实"变成"只记得 2 区 1870"。两处独立证据指向同一个原因。

`check-memory.py` 为此留了一条**顺序敏感断言**：同一 JVM 内先默认窗口、后小窗口，
两次的锚点结果必须不同 —— 这个 bug 一旦回来，它立刻红。

### 七、复现

```powershell
# 起两个后端 -> 自检 -> 收摊，一次跑完
powershell -NoProfile -ExecutionPolicy Bypass -File .\probe\run-memory-check.ps1
# 省一半调用（只跑主运行，跳过小窗口对照）
$env:MEMCHECK_ARGS="--no-small-window"
```

对照台第 5 个页签（对话记忆）里可以点着看：
`http://localhost:8090/?tab=memory&auto=1`，把窗口下拉换成 `2` 再点一次，
就能亲眼看到锚点从"命中"变成"丢失"。

---

## MCP 实测 —— 2026-09-17

第 6 条「下一步」。这一课和前五课有本质区别：**第一次把问题空间挪出单个 JVM**。
新增一个独立进程 `mcp-skill-server`（:8099），把「技能配置查询」做成 MCP 工具，两个后端各接一遍。

### 一、为什么值得单起一个进程：MCP 与「框架自带 tool calling」的分水岭

前五课的工具（`GameServerTools`）都是**进程内的 Java 方法** —— 加个 `@Tool`，
框架用反射扫出来塞进请求。它有两个天生的天花板：

- **换个框架就没了**：那是 Spring AI 的 `@Tool` Bean，LangChain4j 认不出，反之亦然；
- **换个语言更不可能**：Python 写的配置服务根本没法被 Java 反射看到。

MCP（Model Context Protocol）把工具挪到**协议**上：工具提供方是一个独立进程，
通过 `tools/list` 报出自己有什么能力、通过 `tools/call` 接受调用。于是：

| | 框架自带 tool calling | MCP |
|---|---|---|
| 工具在哪 | 同一个 JVM 内的 Java 方法 | 另一个进程（可以不是 Java） |
| 怎么被发现 | 编译期反射扫描 | 运行期 `tools/list` 协商 |
| 客户端知道工具存在吗 | 知道（同一个代码库） | **不知道 —— 清单是运行时问来的** |
| 能不能独立部署/升级/重启 | 不能，跟着主应用一起 | 能 |

所以本课的 `mcp-skill-server` **不含任何大模型**，它只是"一本能回答问题的配置册子"。
它也**不依赖任何 AI 框架**：靠 `spring-ai-starter-mcp-server-webmvc` 起一个 WebMVC 进程，
把容器里的 `ToolCallbackProvider` Bean 转成 MCP 工具暴露出去。

### 二、装配成本：一边改 YAML，一边手写 Bean（第三次同题对照）

| | Spring AI 2.0.1 | LangChain4j 1.20.0 |
|---|---|---|
| 客户端依赖 | `spring-ai-starter-mcp-client` | `langchain4j-mcp` |
| 版本怎么定 | BOM 管（2.0.1） | **只能写 `1.20.0-beta30`** —— `1.20.0` 正式版不存在（HTTP 404，`-beta` 才有） |
| 有没有 Spring Boot starter | **有**，自动配置全套 | **没有** —— 没有任何 `AutoConfiguration.imports` 入口 |
| 接入要写什么 | `application.yml` 加 6 行 | 代码手写 2 个 `@Bean`（`McpClient` + `McpToolProvider`）+ yml 0 行 |
| 自动配置产出 | `List<McpSyncClient>` + `SyncMcpToolCallbackProvider` | 无（全靠手写） |
| **业务侧改动** | **0 行** | **0 行** |

**业务侧两边都是 0 行**，这点最值得注意：`/api/chat/mcp` 的控制器里找不到"MCP"三个字，
`GameOpsAssistant.chatWithTools()` 更是一个普通的「问一句、拿回结果」。
MCP 是**装配期**接进来的 —— Spring AI 靠自动配置往容器里放 `ToolCallbackProvider`，
LangChain4j 靠 `McpToolProvider implements ToolProvider` 被 `AiServicesAutoConfig`
当成"可选协作者"自动接到 `@AiService` 代理上。

> 这是本项目第三次做「同题对照的装配成本差」了，三次结论一致且递进：
> **流式**（P1 传 `Flux`、P2 传 `TokenStream`）→ **记忆**（P1 白送两个 Bean、P2 全手写）
> → **MCP**（P1 改 yml、P2 手写 Bean）。
> 面试时这一条可以讲成一个稳定的判断：**LangChain4j 的抽象更"纯"，Spring AI 的集成更"顺"。**

### 三、协议版本协商：版本上限是 2025-11-25，且这个上限由服务端说了算

LangChain4j 1.20.0-beta30 的 `DefaultMcpClient` 会先探测**现代协议**的
`server/discover`（请求里 `_meta` 带的版本是 `2026-07-28`），失败后才回落
**legacy** 的 `initialize`。

抓一手日志（server 侧开了 `io.modelcontextprotocol: DEBUG`，见 `.workbuddy/logs/mcp-run.log`）：
两个框架客户端**实际请求的都是 `2025-11-25`** —— LangChain4j 是先发一次
`server/discover`（日志 L59）、81ms 后回落 `initialize`（L60）；Spring AI 2.0.1 则
**不做探测、直接**发 `initialize`（L63）。这是两侧客户端的一处行为差异。

那 `2025-11-25` 这个数字到底是谁定的？**裸协议脚本把这件事问清楚了** ——
`probe/check-mcp-server.py` 第 [6] 步逐个版本发 `initialize`（6 个版本、可复跑）：

| 客户端请求 | 服务端返回 | 含义 |
|---|---|---|
| `2026-07-28` | `2025-11-25` | 超出上限 → 推行自己的最高版 |
| `2025-11-25` | `2025-11-25` | **回显**（= 服务端上限） |
| `2025-06-18` | `2025-06-18` | 回显 |
| `2025-03-26` | `2025-03-26` | 回显 |
| `2024-11-05` | `2024-11-05` | 回显 |
| `1999-01-01` | `2025-11-25` | 未知版本 → 推行自己的最高版 |

于是结论可以精确成三句话，而不是笼统的"回落走 legacy"：

1. **`2025-11-25` 是服务端（Spring AI 2.0.1 / 官方 SDK `mcp:2.0.0`）支持的协议版本上限**：
   请求值不高于它就原样回显，高过它（或根本不存在）才推行这个上限。
2. 所以两边谈成 `2025-11-25`，**不是服务端把客户端"降级"了，而是客户端本来就请求这一版** ——
   两边恰好落在服务端的天花板上握手。写成"服务端返回 legacy"是不准确的。
3. 但**现代协议的协商路径确实走不通**：`server/discover` 本 server 不实现 ——
   无会话时被传输层以 JSON-RPC `-32601`（`Session ID missing`）挡下；带着有效会话再发，
   请求会被**直接挂起、不应答**（第 [6b] 步两种情形都测了，见下）。客户端只能回落，这不冤。

> `[6b]` 实测原文：
> ```
> ① 无会话（= 客户端首次探测的真实处境）-> HTTP 400
>    {"jsonRpcError": {"code": -32601, "message": "Session ID missing"}, ...}
> ② 带会话（排除「只是缺会话」）        -> HTTP 0（超时：既不回结果、也不报错，请求被挂起）
> ```

一句话收口：**互通没问题，但走的是"客户端提案 + 服务端接受或推上限"的经典协商，
并不是 `server/discover` 那套新流程。**
这类"能跑通、但走的不是官方最新路"的事只会在抓包时露出来 —— 只看日志一切正常。
快跑一遍复现：`powershell -File .\probe\run-mcp-server-check.ps1`（秒级，不需要 API key）。

### 四、传输：Streamable HTTP 是单端点，且响应体常常是 SSE

现行标准传输是 **Streamable HTTP**：一个端点 `/mcp`，取代了旧的 HTTP+SSE 双端点方案。

两个容易写错的细节（裸协议脚本里都踩过）：

- 响应体**常常不是 JSON，而是 SSE**（`Content-Type: text/event-stream`，
  真正的载荷在 `data:` 行里）—— 所以解析时不能直接 `json.loads(body)`；
- 客户端必须同时声明 `Accept: application/json, text/event-stream`，
  **少一个就可能被服务端按规范回 406**。

### 五、怎么判定"MCP 真的通了"：三方清单一致 + 锚点对照

**第一层：三方清单必须逐项相同。** 一个裸 Python 脚本（不 import 任何 AI 框架，
纯 `urllib` 说 JSON-RPC）、P1、P2 各自报出的 `tools/list` 必须**完全一致**：

```
server（裸协议）: ['explain_effect_target', 'get_skill', 'list_skills', 'validate_condition']
P1 Spring AI    : ['explain_effect_target', 'get_skill', 'list_skills', 'validate_condition']
P2 LangChain4j  : ['explain_effect_target', 'get_skill', 'list_skills', 'validate_condition']
```

这条最便宜，却最能说明 MCP 的意义：**三个进程、两套框架、一个协议，看到同一份能力清单**，
而且这份清单**客户端在编译期完全不知道** —— 它纯粹是运行时问回来的。
这一点「框架自带 tool calling」永远做不到。

**第二层：锚点 + 对照**（方法延续自记忆那一课）。

锚点是技能 `9001「龙血·磐石」/ 目标类型 REVEALED_AREA`，
**只存在于 `mcp-skill-server` 的 `skills.json` 里，两个后端的代码里一个字都没有。**

| | 实验组（挂 MCP） | 对照组（不挂 MCP） |
|---|---|---|
| P1 Spring AI | 3399ms，答出「龙血·磐石 / #8 REVEALED_AREA」✅ | 872ms，**编造**成「烈焰斩 / 敌方单体」 |
| P2 LangChain4j | 3772ms，答出「龙血·磐石 / #8 REVEALED_AREA」✅ | 1603ms，**如实承认**「没有技能配置查询的接口」 |

两侧对照组的反应恰好展示了模型面对"没这工具"的两种典型行为：
**P1 硬编一个答案，P2 老实说不知道** —— 两种都不算答对，对照成立。

只测"挂了工具答得对"是不够的：模型可能本来就知道（信息泄漏），也可能瞎猜中了。
**必须同时有对照组，一真一假**，才排除了这两种可能。

### 六、⚠️ 本次最有价值的坑：记忆串槽让对照组"假通过"

**现象**：第一跑，11 项断言里红 1 项 —— 而且红的是 **P2 的对照组**：
它明明没挂 MCP，却答出了「龙血·磐石 / REVEALED_AREA」。

**证据链**（这次的排查价值就在"证据链"三个字上）：

1. MCP server 侧 `tools/call` 计数只有 **2**（两侧实验组各一次）——
   **对照组根本没调 MCP**，工具确实没挂上；
2. 但 P2 日志（打开 `log-requests`）里，对照组那次请求的 `messages` 赫然带着
   **上一轮实验组的完整问答**（`role:assistant` 的回答里明明白白写着答案）；
3. 于是模型只是把上一轮的答案**复述**了一遍 —— 看起来"答对"，其实是抄的。

**根因是两条叠在一起才成立的**：

1. `chatWithTools` 原本**没有 `@MemoryId`**，框架会给这类方法分配一个**默认记忆槽**；
2. 实验组（Spring 注入的 `@AiService` 代理）和对照组
   （`AiServices.builder(...)` 现场构造）**共用同一个 `ChatMemoryProvider`**，
   于是落到**同一个槽**里 —— 两个"独立"的服务实例，记忆是串的。

这和记忆那一课的「`ChatMemoryService` 用 `computeIfAbsent` 缓存实例」
**是同一类问题的两面**：**记忆的边界不在服务实例上，在 Provider 和 id 上。**

**修法（两道保险，本次都加了）：**

1. 接口方法加上 `@MemoryId`，每次调用传一个**随机 `sessionId`** ——
   两组各自全新会话，天然隔离，实验可重复；
2. 对照组用**自己 `new` 的 `InMemoryChatMemoryStore`**，从存储层面就和实验组隔开。

**这条坑的教训比坑本身重要**：对照组"答不出"这个前提**必须是可信的**。
对照组一旦抄到答案，它不会表现为"实验失败"，而会**伪装成"锚点无效，实验作废"**
—— 一个看起来像结论的东西。**假通过比假失败危险得多**，因为它会直接把错误结论写进文档。

顺带一个值得记的副作用：由于 `ToolProvider` 是挂在**接口级代理**上的，
加了 MCP 之后，P2 里**所有**走 `@AiService` 的方法（包括 `/api/chat`、`/api/chat/memory`）
都会带上 MCP 工具。这是声明式的连带效应，不是 bug —— 但排障时要记得。

### 七、一个环境变量坑：`SERVER_PORT` 会盖掉 yml 里的端口

`mcp-skill-server` 的 `application.yml` 写的是 `server.port: 8099`，
但本机环境里存在 `SERVER_PORT`，它的优先级**高于** `application.yml`，
于是进程起在了别的端口上（第一次跑直接报 `Port 51184 was already in use`）。

修法：用**命令行参数** `--server.port=8099` 覆盖 —— 命令行参数的优先级比环境变量更硬。
这个坑已在 `start-all.ps1` 和 `run-mcp-check.ps1` 里固化。

### 八、复现

```powershell
# 打包 MCP server -> 起三个服务(8099/8081/8082) -> 自检 -> 收摊，一次跑完
powershell -NoProfile -ExecutionPolicy Bypass -File .\probe\run-mcp-check.ps1
```

想单独验"MCP server 本身对不对"（不经过任何框架），先起 `mcp-skill-server`，再跑：

```powershell
python .\probe\check-mcp-server.py
```

手动直问端点：

```powershell
# 远端报出来的工具清单（每次真的去问一次 MCP server）
curl "http://localhost:8081/api/chat/mcp/tools"
curl "http://localhost:8082/api/chat/mcp/tools"

# 实验组 / 对照组
curl "http://localhost:8081/api/chat/mcp?message=技能9001叫什么名字&withMcp=true"
curl "http://localhost:8081/api/chat/mcp?message=技能9001叫什么名字&withMcp=false"
```

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
| **接入 MCP** | **`application.yml` 加 6 行**（`spring.ai.mcp.client.*`，自动配置产出 `ToolCallbackProvider`） | **手写 2 个 `@Bean`**（`McpClient` + `McpToolProvider`）—— 无 Boot starter |
| MCP 工具怎么进模型 | `.tools(mcpToolsProvider)`（显式传） | 容器里存在 `ToolProvider` Bean 就**自动**接到 `@AiService` 代理上 |
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

想验 MCP（第 6 课）就要**先起第三个进程**，而且要**先于两个后端** ——
两个后端启动时就会去拉远端工具清单，连不上会直接起不来：

```powershell
# 用一键脚本最省事（它会按 8099 → 8081/8082 的顺序起）
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1

# 或者手动
cd mcp-skill-server ; mvn spring-boot:run     # 8099，先等它就绪
```

```powershell
# 远端报出来的工具清单（这份清单两个后端编译期都不知道）
curl "http://localhost:8081/api/chat/mcp/tools"

# 实验组 vs 对照组（唯一变量是「挂不挂 MCP 工具」）
curl "http://localhost:8081/api/chat/mcp?message=技能9001叫什么名字&withMcp=true"
curl "http://localhost:8081/api/chat/mcp?message=技能9001叫什么名字&withMcp=false"
```

思考模式对照（两侧各一对，非流式返回 `answer` + `reasoningContent` + `reasoningChars` + `tokens`）：

```powershell
# Spring AI：thinking 可按请求开关，所以能 A/B
curl "http://localhost:8081/api/chat/think?message=13的平方是多少&thinking=true"    # 有思考
curl "http://localhost:8081/api/chat/think?message=13的平方是多少&thinking=false"   # 无思考、省 token

# 流式：R: 是思考分片，C: 是正式回答
curl -N "http://localhost:8081/api/chat/think/stream?message=13的平方是多少"

# LangChain4j：没有 thinking 开关可传，靠服务端默认（默认是开的）
curl "http://localhost:8082/api/chat/think?message=13的平方是多少"
curl -N "http://localhost:8082/api/chat/think/stream?message=13的平方是多少"
```

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
- LangChain4j 1.20.0 对 DeepSeek reasoning 的支持面（源码级）：`OpenAiChatModel.thinkingFieldName` 默认 `"reasoning_content"`、
  `returnThinking/sendThinking` 开关、starter 的 `ChatModelProperties.returnThinking()`/`reasoningEffort()` 是否接线到 builder
- `ServiceOutputParser.schemaNotRequired(...)` 的返回类型白名单（`ChatResponse` 不在其中）

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

**实测验证记录（2026-09-16）—— 思考模式全量验证**

`verify-demos.ps1` 已从 6 条扩到 **11 条用例**，一次跑完：

| 用例 | 结果 |
|---|---|
| P1-1..3 / P2-1..3（chat / stream / 工具调用） | ✅ 全 [OK]，工具调用仍命中 `3214` |
| P1-4 `/think` 思考=开 | ✅ `reasoningChars:65`，answer `169` |
| P1-5 `/think` 思考=关 | ✅ `reasoningChars:0`，completion 仅 1 token |
| P1-6 `/think/stream` | ✅ 39 个 `R:` 思考分片 + 1 个 `C:` 回答分片 |
| P2-4 `/think` | ✅ `reasoningChars` 非 0 |
| P2-5 `/think/stream` | ✅ `R:` 分片正常 |

**22 个 `[OK]`、0 个真失败**（页脚提示文字里的 `[X]` 不是失败）。
完整输出：`.workbuddy/logs/verify-2026-09-16.log`

**至此，本文档列过的所有风险项全部闭环。**

**一条"文档警告了、但实测没复现"的坑**：LangChain4j 文档说 DeepSeek 每个 chunk 都发完整
tool call ID，流式 + 工具调用必须设 `accumulateToolCallId(false)`。
但实测（P2-4：`/stream` + 真实触发工具调用）**用默认配置就跑通了**，回答逐 token 流出 `3214`。
所以这条警告在本场景没有复现，保留备用即可。
**判断它真被触发的标准**：答案里没有 `3214`、或日志报 tool call id 相关错误。
（可能只在多工具并行调用、或 tool call ID 被拆成多段时才暴露 —— 本测试只有单次简单调用。）

**实测验证记录（2026-09-17）—— MCP 三层验证**

`probe/run-mcp-check.ps1` 一次跑完「打包 MCP server → 起三个服务 → 自检 → 收摊」：

| 断言 | 结果 |
|---|---|
| server 通过**裸 MCP 协议**（纯 `urllib`，不 import 任何 AI 框架）报出 4 个工具 | ✅ |
| P1 / P2 的 `/api/chat/mcp/tools` 返回 4 个工具且含全部预期 | ✅ |
| P1 / P2 **实验组**（`withMcp=true`）答出锚点「龙血·磐石 / REVEALED_AREA」 | ✅ |
| P1 / P2 **对照组**（`withMcp=false`）答不出锚点 | ✅ |
| **三方工具清单完全一致**（裸协议 = P1 = P2） | ✅ |

**11 个 `[OK]`、0 个失败。** 完整输出：`.workbuddy/logs/mcp-check-2026-09-17.log`

顺带核实的两条版本事实（一手源）：`langchain4j-mcp` **只有 `1.20.0-beta30`**
（`1.20.0` 正式版 HTTP 404）、且**没有 Spring Boot starter**；
协议协商实测：两个客户端都请求 `2025-11-25`，而**这恰好是 Spring AI 2.0.1 server 的版本上限**
（对 ≤ 上限的请求原样回显，只有更新的版本才推行 `2025-11-25`）；
现代协议的 `server/discover` 该 server 不实现（无会话被 `-32601` 挡下、带会话则挂起不应答）。

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
4. ✅ **已完成（2026-09-16）**：加了 `/api/chat/think` + `/api/chat/think/stream` 端点和服务端行为探针，
   实测结论是**两侧都能拿到 `reasoning_content`** —— 旧结论"LangChain4j 拿不到"已被推翻并更正。
   真差别是 **Spring AI 能主动关思考（同一问题 total token 78 → 35，省 55%）、LangChain4j 原生不能**
   （旁路：`reasoning-effort: none`）。另附一个 `@AiService` 返回 `ChatResponse` 的陷阱。
   详见上面「思考模式实测」一节 —— 这一条对比面试时可以直接讲
5. ✅ **已完成（2026-09-17）**：加了 `/api/chat/memory`（两侧逐字段对齐，四轮 + 无记忆对照 + 窗口裁剪），
   实测结论：**一边靠自动配置（两个 Bean 白送）、一边 Store/Provider 全手写**；
   记忆挂载位置一个在 **Advisor（会话 id 是请求参数）**、一个在 **`@MemoryId` 接口契约**；
   同样"20 条窗口"因为存的东西不同，一边 10 轮、一边只够 4~5 轮。
   另附一个**只在链路上才看得见的 bug**：往注入的 `ChatClient.Builder` 上反复 `defaultAdvisors(...)`
   会跨请求累积，导致"换窗口"被上一个 advisor 静默顶掉。详见上面「对话记忆实测」一节
6. ✅ **已完成（2026-09-17）**：新增独立进程 `mcp-skill-server`（:8099），把「技能配置查询」
   做成 MCP 工具，两个后端各接一遍。实测结论：**第一次把问题空间挪出单个 JVM** ——
   三方（裸协议 / P1 / P2）看到的工具清单**逐项相同**，而这份清单**客户端编译期根本不知道**；
   装配成本又是"一边改 yml、一边手写 Bean"（第三次同题对照，LangChain4j 甚至只有
   `-beta` 版、没有 Boot starter）；协议协商实测：客户端请求 `2025-11-25`，
   而这**恰是该 server 的版本上限**（≤ 上限就回显，更高才推行它），原样谈成。
   另附一个**最危险的坑**：记忆串槽让对照组"抄"到实验组答案，
   表现为「锚点无效」而不是「实验失败」—— **假通过会直接把错误结论写进文档**。
   详见上面「MCP 实测」一节
7. 到这一步，你就有能力把它们接进 `AI开发学习` 里那个诊断助手了
