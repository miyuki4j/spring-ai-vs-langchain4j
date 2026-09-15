# stub —— 离线截获请求体（不花 API 费用、不需要真 key）

**用途**：验证"某个配置项到底有没有生效"。

做法是让框架把请求发到一个本地假端点，把**请求体原样存下来**，然后你直接看 JSON。

这比读文档硬、比看日志准，而且**不发真实请求、不花一分钱、不需要真 key**。
模型 API 的请求体就是一切真相 —— 你配的 `temperature`、`thinking`、`tools`、`model`、
`messages` 到底有没有生效、长什么样，一目了然。

## 跑

```powershell
# 终端 1：起 stub
cd D:\Self\AIWorkspaces\AI4JWorkspaces\stub
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java Stub.java

# 终端 2：把 demo 指向 stub（用 8084 避开你正在跑的 8081）
cd ..\spring-ai-demo
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8084 --spring.ai.deepseek.chat.base-url=http://localhost:9099 --spring.ai.deepseek.chat.api-key=sk-stub --spring.ai.deepseek.chat.thinking.type=disabled"

# 终端 3
curl.exe "http://localhost:8084/api/chat?message=hi"
```

Spring AI 会收到一个合法的假回答 `STUB-OK`，所以整条链路能正常走完并返回 200。

请求体存到 `req-N.json`。**注意路径会有两层 `stub\stub\`**：
因为 `java Stub.java` 的工作目录是 `stub\`，而程序内部写的是相对路径 `stub/`。

## 实测记录（2026-09-15）

| 配置 | 截到的 `thinking` 字段 |
|---|---|
| `spring.ai.deepseek.chat.thinking.type=disabled` | `"thinking":{"type":"disabled"}` |
| `spring.ai.deepseek.chat.thinking.type=enabled` | `"thinking":{"type":"enabled"}` |

结论：该属性**绑定生效、值正确、原样到达线上请求体、随配置切换**。

顺带从请求体确认的其它事实：

```json
{"messages":[{"content":"你是一个游戏服务端运维助手，回答要简洁、准确，用中文。","role":"system"},
             {"content":"hi","role":"user"}],
 "model":"deepseek-flash",
 "stream":false,
 "temperature":0.7,
 "thinking":{"type":"disabled"}}
```

- `model` = `deepseek-flash` ✅ 配置生效
- `temperature` = 0.7 ✅ 配置生效
- system message 的中文**完全正确** ✅ 同时排除了"编译时把中文搞坏"的疑虑
  （PS 5.1 里 `Get-Content` 用 GBK 读 UTF-8 文件会显示成乱码，用
  `[IO.File]::ReadAllText($f, [Text.Encoding]::UTF8)` 才是真实内容）
- `/api/chat` 的请求**不带 `tools`** ✅ 只有 `/api/chat/agent` 才带 ——
  这也是个可验证的行为差异

## 换成验证 LangChain4j

同理，把 `langchain4j.open-ai.chat-model.base-url` 指到 `http://localhost:9099/v1`
即可（那边约定带 `/v1`，Stub 接受任意路径）：

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8086 --langchain4j.open-ai.chat-model.base-url=http://localhost:9099/v1 --langchain4j.open-ai.chat-model.api-key=sk-stub"
```

## 能拿它验什么

- 任何"这个配置项到底生效没有"的疑问（比翻文档可靠）
- 框架实际发的**请求路径**（在 Stub 里把 `exchange.getRequestURI()` 也记下来即可）
- 流式请求的**请求体**（`"stream":true` 时框架带什么参数）
- 工具调用时 `tools` 数组的真实结构（工具名、参数 schema 长什么样）
