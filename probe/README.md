# probe/ —— 可复现的验证脚本

这个目录的存在意义：**README 里每一条结论，都要能被人用脚本重跑一遍**。
凡是不带脚本支撑的说法，就只是说法。

依赖：Python 3（只用标准库）+ curl.exe（`run-verify.ps1` 用）。
key 从环境变量 `DEEPSEEK_API_KEY` 或各项目的 `.idea/workspace.xml` 读，**脚本不会打印 key**。

| 脚本 | 回答什么问题 | 怎么跑 |
|---|---|---|
| `deepseek-thinking-probe.py` | DeepSeek 服务端在 `thinking` / `reasoning_effort` 各取值下，到底返回不返回 `reasoning_content`？计费多少 token？ | `python deepseek-thinking-probe.py` |
| `think-compare.py` | 两个 demo 的 `/think`、`/think/stream` 各返回什么？（需要两个服务在跑） | `python think-compare.py` |
| `parse-p2-request.py` | langchain4j-demo 实际发出去的请求体长什么样？顶层有哪些键？有没有被塞 JSON 指令？ | `python parse-p2-request.py [日志路径]` |
| `run-verify.ps1` | 起两个服务 → 等端口 → 跑 `verify-demos.ps1`（11 条用例）→ 收服务，一把跑完 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-verify.ps1` |
| `check-dashboard.py` | 可视化对照台能不能取到数？逐项验 CORS 放行、`/think` 字段形状、SSE 帧格式，以及**流能否正常结束** | `python check-dashboard.py`（需先起两个后端） |
| `run-dashboard-check.ps1` | 同上，但把「起服务 → 自检 → 收服务」包在一起，一次跑完 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-dashboard-check.ps1` |
| `check-memory.py` | 两边真的记住对话了吗？用**工具产不出的锚点**验证；量化每条历史消息的 token 成本；窗口裁剪是否可观测；顺序敏感（防 advisor 跨请求累积导致"小窗口没生效"回归） | `python check-memory.py`（需先起两个后端） |
| `run-memory-check.ps1` | 同上，把「起服务 → 自检 → 收服务」包在一起。可用 `$env:MEMCHECK_ARGS="--no-small-window"` 省一半调用 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-memory-check.ps1` |
| `check-mcp-server.py` | **不经过任何框架**，用裸 HTTP 直接说 MCP 协议（initialize → initialized → tools/list → tools/call ×2）。另含 **[6] 协议版本协商矩阵**与 **[6b] `server/discover` 探测**：逐个版本问过去，看清"版本回显还是推上限、现代协议认不认"。回答"MCP server 本身到底对不对"——这层先通了，客户端连不上就一定是客户端侧的事 | `python check-mcp-server.py`（需先起 mcp-skill-server） |
| `run-mcp-server-check.ps1` | 只起 `mcp-skill-server`（8099）+ 跑上面的裸协议自检，**秒级、不需要 API key**。改完 server 侧代码先跑它最快 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-mcp-server-check.ps1` |
| `check-mcp.py` | 三方交叉验证：裸协议看到的工具清单、P1 报的、P2 报的，必须**完全一致**；再用锚点 + 对照证明 MCP 调用链真的通了 | `python check-mcp.py`（需先起三个服务） |
| `run-mcp-check.ps1` | 同上，把「打包 MCP server → 起三个服务 → 自检 → 收服务」包在一起一次跑完 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-mcp-check.ps1` |

## MCP 自检为什么要分成两层：先裸协议，再上框架

前几课的坑都在**单个进程内**（advisor 顺序、TokenStream 懒执行、返回类型白名单），
MCP 第一次把问题空间变成了**跨进程**：客户端说"连不上"，可能是 server 没起、
可能是传输方式配错、可能是协议版本没谈拢、也可能只是 URL 路径少写了 `/mcp`。
四者现象一模一样（都是超时或 404），光看客户端日志分不出来。

所以验证被刻意劈成两层：

```
第 0 层  check-mcp-server.py   裸 HTTP + JSON-RPC，不 import 任何 AI 框架
第 1 层  check-mcp.py          两个框架的客户端各自接进来，再和裸协议的结果对表
```

**第 0 层的价值不在于"多测一遍"，而在于它把变量砍到只剩一个。**
它通了，就说明 server、传输、路径、协议协商全都是对的 ——
这时候第 1 层出问题，答案只可能在客户端侧，不用再把服务端翻一遍。

顺带它还证明了一件容易被忽略的事：**MCP 是公开协议，不是某个框架的私有接口。**
同一个 server，Spring AI 用 `spring-ai-starter-mcp-client` 能连，
LangChain4j 用 `StreamableHttpMcpTransport` 能连，
一个 60 行的 Python 脚本用标准库 `urllib` 也能连 —— 三种客户端看到的工具清单**逐个字符相同**。
这正是「框架自带的 tool calling」永远做不到的事：那种工具是进程内的 Java 方法，
换个框架就没了。

> 一个容易写错的细节：Streamable HTTP 的响应体常常不是 JSON，而是 SSE
> （`Content-Type: text/event-stream`，真正的载荷在 `data:` 行里）。
> 而且客户端必须同时声明 `Accept: application/json, text/event-stream`，
> 少一个就可能被服务端按规范回 406。这两点在裸协议脚本里都踩到了。


## 协议版本协商：一个矩阵把"谁将就谁"问清楚

"MCP 协议版本谈不拢"这件事，一开始被记成了一句很省事的话：
**"Spring AI 2.0.1 的 server 返回的是 legacy `2025-11-25`，客户端只能回落。"**
这句话看着合理，却是**错的** —— 它把"客户端请求的版本"和"服务端能给的版本"混成了一个。

真相要用一次矩阵才能看出来。给 `initialize` 换着版本发（`check-mcp-server.py` 第 [6] 步）：

| 客户端请求 | 服务端返回 | 含义 |
|---|---|---|
| `2026-07-28` | `2025-11-25` | 超出上限 → 推行自己的最高版 |
| `2025-11-25` | `2025-11-25` | 回显 |
| `2025-06-18` | `2025-06-18` | 回显 |
| `2025-03-26` | `2025-03-26` | 回显 |
| `2024-11-05` | `2024-11-05` | 回显 |
| `1999-01-01` | `2025-11-25` | 未知 → 推行自己的最高版 |

矩阵一出来，三件事同时清楚了：

1. **服务端不是"只认 legacy"，它宽松接受多版本**（一直低到 `2024-11-05`），请求什么就回什么；
2. `2025-11-25` 是它的**版本上限** —— 只有请求高过上限、或版本压根不存在时，才被推行；
3. 两个框架客户端请求的都是 `2025-11-25`，所以"谈成 `2025-11-25`"**不是服务端降级，
   而是客户端提案正好落在服务端天花板上**。

**教训**：单个客户端的日志只能告诉你"这次谈成了哪一版"，告诉不了你"为什么是这一版"。
要多花几个往返（这里就是发 6 个版本），把**服务端的行为边界**测出来，
"回落 / 降级 / 兼容"这类判断词才有事实支撑，否则只是把观测到的结果又复述了一遍。

`server/discover` 同理。第一次测只看到 `-32601 Session ID missing`，
很容易读成"补个会话就能用"；于是第 [6b] 步带着**有效会话**再发一次 ——
结果请求被**直接挂起、不应答**（HTTP 0 / 超时）。两发合起来，才敢写
"这个 server 不实现现代协议的协商路径"。

## 记忆自检里三个"想当然"被实测打掉的例子

写 `check-memory.py` 的过程本身就是个方法论样本 —— 三次判断错了，而且**每次都是"看起来
更严格"，实际是错的**（第三次连错法都不一样：代码没错，断言也没错，是弱断言集体放行）：

1. **「prompt token 应当逐轮递增」—— 错。**
   直觉上历史越长 prompt 越大，第一版就这么断言了，结果两侧全红。
   真因是工具调用：触发工具的那一轮，prompt 里除了历史消息还要回灌工具定义和工具结果，
   一次多出四五百 token，量级远超几条历史消息。prompt 序列于是变成
   `[906, 467, 1012, 522]` 这种锯齿形，跟记忆毫无关系。
   → 正确做法是**用受控对照**：拿同一道题、同一套工具、只差历史条数的两次调用去减，
   差值才是记忆的成本。（实测 12.8 / 16.9 token 每条。）

2. **「用 3214 这种查得到的数字当记忆证据」—— 错。**
   3214 是工具返回的，模型在回忆轮**完全可以自己再调一次工具把它查回来**。
   实测确实发生了：窗口把第 1 轮裁掉之后，回答里照样出现 3214。
   → 记忆锚点必须选**工具产不出**的值（本项目用批次号 `LOONG-7749`）。
   这条和"对照组"是同一个道理的两面：**能靠其它途径拿到的信息，都不能当记忆的证据**。

3. **「弱断言全绿 = 实验没坏」—— 错，而且这次坏的是代码，不是断言。**
   小窗口那一节第一次跑出来是"只红 1 项"：窗口参数被采纳、每轮历史不超窗口、
   memoryFirstUser 变了 —— 全绿。红的只有最硬的那条：锚点没被裁掉。
   排查发现是 Spring AI 侧的**真 bug**：controller 在每次请求里往注入的
   `ChatClient.Builder` 上 `defaultAdvisors(...)`，而那是 `addAll` 不是覆盖
   （`DefaultChatClientBuilder#defaultAdvisors` → `DefaultChatClientRequestSpec.advisors`），
   于是 advisor **跨请求累积**；两个 `MessageChatMemoryAdvisor` 默认 order 相同
   （都是 `HIGHEST_PRECEDENCE + 200`），同 order 按加入顺序排，**上一轮遗留的默认窗口
   advisor 排在前面先注入**，新加的小窗口再被 `isMemoryAlreadyInPrompt()` 静默去重掉 ——
   小窗口等于没生效，全程不报错。
   为什么弱断言全绿：它们读的都是**我们自己 new 出来的那个 memory 对象**，它当然是 2 条。
   真正决定模型看到什么的是**链上生效的那个 advisor**。
   → 断言要挑**穿过整条链路才能得到的量**（锚点进没进 prompt、模型说没说得出），
   而不是自己手里对象的内部状态。修法见 `spring-ai-demo/.../memory/MemoryController`
   的类注释：client 只 build 一次，记忆 advisor 按调用传。
   `check-memory.py` 里为此留了一条顺序敏感断言：**同一 JVM 内先默认窗口、后小窗口，
   两次的锚点结果必须不同** —— 这个 bug 一旦回来，它立刻红。

还顺带纠正了一个参数选择：小窗口最初取 4 条，结果 Spring AI 恰好还能保住锚点所在那一轮，
两侧断言分叉；取 2 条才能让两侧都必然裁掉锚点。**实验参数也要按"能否区分"来选，不能拍脑袋。**

另外，自检脚本现在会把**小窗口那轮的完整回答原文**打出来。这是被第 3 条逼出来的：
当时只打了 `hits/misses`，看不到回答长什么样，只能靠 prompt token 去反推，
绕了一大圈。**"看不到原始数据"本身就是一个应该被修掉的缺陷。**

## 为什么 `check-dashboard.py` 一定要把流读完

最初这脚本只读前 3 个 SSE 帧就退出，结果**漏掉了一个会让页面卡死的场景**：
如果服务端把数据发完却不关闭连接，浏览器侧的 `reader.read()` 会永远等下去 ——
页面上表现为流式那一侧永远停在打字光标、时间轴永远画不出来。

只读几帧是测不出这个的，必须读到 EOF 才能区分「正常结束」和「没关流」。
这类 bug 的共同点是**不报错、只是永远等**，所以更依赖脚本主动把边界跑完。

## 为什么需要 `parse-p2-request.py` 这种"从日志还原请求体"的手法

有些行为**只在发出去的那一字节上才能看见**。比如 LangChain4j 的 `@AiService`
返回 `ChatResponse` 时，框架会往 user 消息里**悄悄追加一段 JSON schema 指令** ——
这既不报错、也不在返回值里体现，只有打开 `log-requests: true` 抓请求体才看得见。
`readme` 里那条"`ChatResponse` 是陷阱"的结论就是这么定下来的。

同一手法在 `stub/README.md` 里也用过一次（那次是用本地 stub 服务器截获 Spring AI 的请求体）。

## 关于 `.ps1` 的编码（本机踩过的坑）

本机只有 Windows PowerShell 5.1（没有 pwsh 7）。5.1 默认按 GBK 解析**无 BOM** 的 `.ps1`，
脚本里的中文注释会把括号读崩，报"缺少右 }"这类**假错误**。
所以本目录的 `.ps1` 一律存成 **UTF-8 带 BOM**。用编辑器改完脚本后，记得确认 BOM 还在：

```python
b = open(path, 'rb').read()
assert b[:3] == b'\xef\xbb\xbf', 'BOM 丢了'
```

## 日志去哪了

跑出来的日志统一放 `.workbuddy/logs/`（该目录已被 `.gitignore` 排除，属本地工作产物）：

- `verify-<日期>.log` —— 验证脚本的完整输出
- `p1-run.log` / `p2-run.log` —— 两个服务的控制台日志（`parse-p2-request.py` 依赖这个）
- `p1-build.log` / `p2-build.log` —— `mvn test` 输出
