package com.example.langchain4jdemo.assistant;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * LangChain4j 的招牌用法：**声明式 AI Service**。
 *
 * <p>你只写接口，不写实现。{@code @AiService} 让 Spring 在启动时生成代理实现，自动把
 * 「系统提示词 + 用户消息 + 工具调用 + 多轮记忆」这套编排接好。
 *
 * <p>这是和 Spring AI 最大的心智差异：
 * <ul>
 *   <li><b>LangChain4j</b>：接口 + 注解，声明式，实现由框架生成
 *   <li><b>Spring AI</b>：{@code ChatClient} 流式 API 手动链式调用，命令式，每一步你自己写
 * </ul>
 * 两种都好，但面试时能讲清这个差异，比背 API 有价值得多。
 *
 * <p><b>工具不用手动绑</b>：{@code @AiService} 的默认装配模式是 AUTOMATIC，它会自动把上下文里
 * <b>所有含 {@code @Tool} 方法的 Bean</b> 接进来 ——
 * {@link com.example.langchain4jdemo.tools.GameServerTools} 上标了 {@code @Component}，
 * 所以自动生效，**不需要**写 {@code tools = "..."}。
 *
 * <p>（{@code @AiService} 确实有 {@code tools} 属性，但它的 Javadoc 明确写着
 * 只在 {@code wiringMode = EXPLICIT} 时使用。AUTOMATIC 模式下写它是未定义行为 ——
 * 这也是本项目最初的一版写法，已按官方源码修正。）
 */
@AiService
public interface GameOpsAssistant {

    @SystemMessage("你是一个游戏服务端运维助手，回答要简洁、准确，用中文。")
    String chat(@UserMessage String message);

    /** 流式版本：返回 TokenStream，逐个 token 回调，见 ChatController 里的用法。 */
    @SystemMessage("你是一个游戏服务端运维助手，回答要简洁、准确，用中文。")
    TokenStream chatStream(@UserMessage String message);

    /**
     * 拿到 {@link AiMessage}（而不是只有文本的 {@code String}），这样才能读到思考内容。
     *
     * <p>需要它是因为 {@code reasoning_content} 不在文本里，而在消息对象上：
     * {@link AiMessage#thinking()}。返回 {@code String} 的方法会把思考内容直接丢掉。
     *
     * <p><b>⚠️ 这里是本项目踩到的一个真坑，务必别写成 {@code ChatResponse}：</b>
     * 直觉上「我要完整响应」就会想返回 {@code ChatResponse}，LangChain4j 的
     * {@code AiServiceValidation.SUPPORTED_RETURN_TYPES} 也确实把它列为合法返回类型，
     * <b>但那个集合只用于「校验时放行」，不决定运行时行为</b>。真正决定行为的是
     * {@code ServiceOutputParser.schemaNotRequired(...)}，它的白名单是：
     * <pre>
     *   String / AiMessage / TokenStream / Response / Map / void / Void
     * </pre>
     * <b>{@code ChatResponse} 不在其中</b>，于是框架会往 user 消息里追加一句
     * {@code "You must answer strictly in the following JSON format: {...}"}，
     * 让大模型去「生成」一个 ChatResponse 形状的 JSON —— 实测模型会老老实实编一个回来
     * （连 {@code modelName:"gpt-4o"}、{@code id:"chatcmpl-13squared"} 都是编的），
     * 于是 answer 字段变成一坨 JSON，而不是真正的回答。
     *
     * <p>{@code AiMessage} 没这个问题，源码两处都能确认：
     * <ol>
     *   <li>{@code schemaNotRequired} 白名单里有 {@code AiMessage} → 不会追加 JSON 指令
     *   <li>{@code ServiceOutputParser.parse(...)} 里有 {@code if (rawClass == AiMessage.class) return aiMessage;}
     *       → 原样返回真实对象，不做任何文本解析
     * </ol>
     *
     * <p><b>为什么用 {@code Result<AiMessage>} 而不是裸 {@code AiMessage}？</b>
     * 因为 {@code AiMessage} 上<b>没有</b> tokenUsage —— 思考内容在消息上，用量只在
     * {@link Result} 上（{@code Result.tokenUsage()}），两个都要就只能取 {@code Result}。
     *
     * <p>这里有个反直觉之处值得记下来：{@code Result} 同样<b>不在</b>
     * {@code ServiceOutputParser.schemaNotRequired(...)} 的白名单里，
     * 照上面那套推理它就该踩同样的 JSON 注入坑 —— 但实测没有。
     * 原因是 {@code DefaultAiServices} 给它开了<b>专门分支</b>：
     * <pre>
     *   boolean isReturnTypeResult = typeHasRawClass(returnType, Result.class);   // 源码第 353 行
     * </pre>
     * 所以「白名单」不是判断安全的唯一依据，得看有没有专门分支。
     * {@code ChatResponse} 两处都没有，才会退化成一坨模型编造的 JSON。
     */
    @SystemMessage("你是一个游戏服务端运维助手，回答要简洁、准确，用中文。")
    Result<AiMessage> chatWithThinking(@UserMessage String message);

    /**
     * 带记忆的问答 —— 与 Spring AI 侧对照的地方就在<b>这个签名</b>上。
     *
     * <p><b>LangChain4j 把「哪个会话」表达成了一个参数注解</b>：
     * {@code @MemoryId String memoryId}。框架看到它，就去
     * {@code ChatMemoryProvider.get(memoryId)} 取对应的 {@code ChatMemory} 槽位。
     *
     * <p>对照 Spring AI：那边同一个语义是
     * {@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))} —— 传在**请求**上，
     * 而不是声明在**方法**上。一个是编译期的接口契约，一个是运行期的调用参数：
     * <ul>
     *   <li><b>LangChain4j</b>：接口一旦这么声明，这个方法<b>永远</b>带记忆，调用方管不着
     *   <li><b>Spring AI</b>：同一个 {@code ChatClient} 每次调用自己决定用哪个会话、
     *       甚至决定这次到底要不要带记忆
     * </ul>
     *
     * <p>返回 {@code Result<String>} 而不是裸 {@code String}，理由和上面
     * {@link #chatWithThinking} 那段一样 —— 要 token 用量就得多包一层。
     * {@code String} 本身在 {@code ServiceOutputParser.schemaNotRequired(...)} 的白名单里，
     * 不会踩 JSON 注入的坑；{@code Result} 有专门分支（{@code DefaultAiServices} 第 353 行），
     * 也不会。两层都安全。
     *
     * <p><b>注意：加了这个 {@code @MemoryId} 之后，{@code chatMemoryProvider} Bean 就变成必需的</b>——
     * {@code AiServiceValidation} 会在启动时校验「有 {@code @MemoryId} 必须有 Provider」，
     * 缺了直接抛 {@code IllegalConfigurationException}，而不是等到调用时才失败。
     * 反过来，本项目里没有写 {@code @MemoryId} 的那几个方法（{@code chat} / {@code chatStream} /
     * {@code chatWithThinking}）虽然共享同一个 Provider，但<b>不会</b>走记忆 —— 校验是单向的。
     */
    @SystemMessage("你是一个游戏服务端运维助手，回答要简洁、准确，用中文。")
    Result<String> chatWithMemory(@MemoryId String memoryId, @UserMessage String message);

    /**
     * MCP 场景用（第 6 课）：同一个方法签名，工具来源却变了。
     *
     * <p>这个方法里<b>没有任何一行提到 MCP</b> —— 它只是「问一句话、拿回结果」。
     * MCP 是<b>装配期</b>接进来的：只要容器里存在一个 {@code ToolProvider} Bean
     * （见 {@code config/McpClientConfig}），{@code AiServicesAutoConfig} 就会把它
     * 接到这个接口的代理上。所以：
     *
     * <ul>
     *   <li>用注入的 {@code @AiService} 代理调它 → 本地工具 + MCP 工具都在；</li>
     *   <li>用 {@code AiServices.builder(...)} 现场构造、只挂本地工具 → 对照组。</li>
     * </ul>
     *
     * <p>这正是「声明式」的好处与坏处同时体现的地方：好处是业务方法干净得像伪代码；
     * 坏处是<b>光看这个方法，你根本不知道它背后会连出去</b>。
     * 排查问题时得回到装配处找答案 —— 这也是本项目一路插过最多坑的地方。
     *
     * <p><b>⚠️ 为什么这里必须有 {@code @MemoryId}？——被实测逼出来的。</b>
     * 最初这个方法的签名是 {@code chatWithTools(String message)}，没有 {@code @MemoryId}。
     * 结果 MCP 对照实验里，<b>「不挂 MCP」的对照组照样答对了</b>。
     * 查请求体才发现：对照组虽然确实没调 MCP（MCP server 侧 {@code tools/call} 计数为 0），
     * 但它的 {@code messages} 里带着<b>上一轮实验组的完整问答</b>（含 tool 结果和最终答案），
     * 于是它直接把答案复述了一遍。
     *
     * <p>根因：没有 {@code @MemoryId} 的方法，框架会给它分配一个<b>默认的记忆槽</b>；
     * 而实验组（Spring 的 {@code @AiService} 代理）和对照组
     * （{@code AiServices.builder(...)} 现场构造）如果共用同一个 {@code ChatMemoryProvider}，
     * 就会落到同一个槽里 —— 两个"独立"的服务实例，记忆是串的。
     * 这和记忆那一课发现的「{@code ChatMemoryService} 用 {@code computeIfAbsent} 缓存实例」
     * 是同一类问题的两面：<b>记忆的边界不在服务实例上，在 Provider 和 id 上。</b>
     *
     * <p>修法就是加上 {@code @MemoryId}，让调用方每次显式给一个 id
     * （见 {@code McpController}：两组各给一个随机 id + 对照组独立 store），
     * 这样两组之间天然隔离，实验也可重复。
     *
     * <p>返回 {@code Result<String>} 而不是裸 {@code String}，图的是 token 用量
     * （理由见 {@link #chatWithThinking} 那段）。
     */
    @SystemMessage("你是一个游戏服务端助手，既能查区服状态，也能查技能配置。回答要简洁、准确，用中文。")
    Result<String> chatWithTools(@MemoryId String sessionId, @UserMessage String message);
}
