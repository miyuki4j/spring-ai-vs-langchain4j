package com.example.langchain4jdemo.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Contract;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.langchain4jdemo.assistant.GameOpsAssistant;
import com.example.langchain4jdemo.config.MemoryConfig;
import com.example.langchain4jdemo.tools.GameServerTools;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * 对话记忆的实测端点 —— 题目、轮次、返回结构和 spring-ai-demo 的
 * {@code memory/MemoryController} <b>逐字段对齐</b>，页面才能直接并排画。
 *
 * <p>实验设计（四轮 + 一组对照）在对面那个类里写了完整说明，这里只重复最关键的一条：
 * <b>第 2 轮让模型记住的热更批次号 LOONG-7749，工具无论如何都产不出来</b>，
 * 所以「有记忆答对、无记忆答错」这组对照是封闭的 —— 排除了「模型自己猜对」。
 *
 * <p><b>本类还有第二个教学目的：演示 LangChain4j 的两条取服务路径。</b>
 * <ul>
 *   <li>默认（不传 {@code maxMessages}）：直接用注入进来的 {@code @AiService} 代理 ——
 *       记忆由 {@code ChatMemoryProvider} Bean 在启动时装配好。
 *   <li>传了 {@code maxMessages}：走 {@link AiServices#builder(Class)} <b>现场构造</b>一个新的服务实例。
 * </ul>
 * 为什么换窗口必须走第二条路？因为框架在 {@code ChatMemoryService} 里维护了一张
 * {@code ConcurrentHashMap<Object, ChatMemory>} 当缓存，
 * {@code getOrCreateChatMemory()} 用的是 {@code computeIfAbsent} ——
 * <b>同一个 memoryId 的 ChatMemory 实例只创建一次</b>，
 * 之后改 Provider 也不会重建它。所以「按请求换窗口」在 {@code @AiService} 代理上做不到。
 *
 * <p>这一点和 Spring AI 恰好相反：那边的记忆挂在 Advisor 上、会话 id 是<b>请求参数</b>
 * （{@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))}），
 * 同一个 ChatClient 每次调用想换就换。一个把记忆当<b>接口契约</b>，一个当<b>调用参数</b>。
 *
 * <p>补一条从对面踩坑带回来的教训：Spring AI 侧若图省事、在每次请求里往注入的
 * {@code ChatClient.Builder} 上 {@code defaultAdvisors(...)}，advisor 会<b>跨请求累积</b>
 * （{@code defaultAdvisors} 是 addAll 不是覆盖），于是「本次换小窗口」会被上一轮遗留的
 * 默认窗口 advisor 悄悄顶掉 —— 而且框架有个「历史已在 prompt 里就不重复注入」的静默去重逻辑，
 * 连报错都没有。LangChain4j 这边<b>天然没有这条路</b>：{@code @AiService} 是启动期装配、
 * 运行时改不了，要按请求换协作者就只能像下面这样重新 {@code AiServices.builder()}，
 * 每次都是全新实例，不存在累积。完整故障链见 spring-ai-demo 的 {@code MemoryController} 类注释。
 */
@RestController
@RequestMapping("/api/chat/memory")
public class MemoryController {

    /** 前三轮：两条要查工具、一条纯靠记忆（锚点）。 */
    private static final List<String> ROUNDS = List.of(
            "1区现在在线多少人？",
            "记住这个热更批次号：LOONG-7749。收到请只回复「已记录」。",
            "2区现在在线多少人？");

    private static final String RECALL = "请复述一下：我前面问过的区服的在线人数，以及我让你记住的热更批次号。";

    /**
     * <b>记忆锚点</b>：一个工具<b>产不出来</b>的值。
     *
     * <p>为什么必须有它？因为 3214 / 1870 虽然是「查出来的」，但模型在回忆轮
     * <b>完全可以自己再调一次工具拿回来</b> —— 实测就发生过：窗口把第 1 轮裁掉之后，
     * 回答里照样给出了 3214。所以那两个数字只能当辅助证据，
     * <b>只有锚点才能区分「记住了」和「现查了一遍」</b>。
     */
    private static final String ANCHOR = "LOONG-7749";

    /** 辅助事实：工具查得到，命中不算记忆生效的证据。 */
    private static final List<String> FETCHABLE = List.of("3214", "1870");

    private static final List<String> EXPECTED = List.of("3214", "1870", ANCHOR);

    private final GameOpsAssistant assistant;
    private final ChatMemoryProvider provider;
    private final ChatModel chatModel;
    private final ChatMemoryStore store;
    private final GameServerTools tools;

    public MemoryController(GameOpsAssistant assistant,
                            ChatMemoryProvider gameOpsChatMemoryProvider,
                            ChatModel chatModel,
                            ChatMemoryStore gameOpsChatMemoryStore,
                            GameServerTools tools) {
        this.assistant = assistant;
        this.provider = gameOpsChatMemoryProvider;
        this.chatModel = chatModel;
        this.store = gameOpsChatMemoryStore;
        this.tools = tools;
    }

    /**
     * @param conversationId 会话标识，也就是 {@code @MemoryId} 收到的东西
     * @param reset          是否先清空该会话记忆（默认 true）
     * @param maxMessages    窗口大小。不传 = 用 Bean 里配的 20。
     *                       传 4 时第 1 轮的 3214 会在第 4 轮回忆前被裁掉，
     *                       用来演示「按条数裁剪，最早的事实先丢」。
     * @param control        是否跑无记忆对照组（默认 true）
     */
    @GetMapping
    public MemoryRun run(@RequestParam(defaultValue = "demo-a") String conversationId,
                         @RequestParam(defaultValue = "true") boolean reset,
                         @RequestParam(required = false) Integer maxMessages,
                         @RequestParam(defaultValue = "true") boolean control) {

        boolean customWindow = maxMessages != null;
        ChatMemoryProvider activeProvider = customWindow ? smallWindowProvider(maxMessages) : provider;
        // 注意：自定义窗口时必须走命令式构造，理由见类注释里的缓存那一段
        GameOpsAssistant svc = customWindow ? imperative(activeProvider) : assistant;

        if (reset) {
            service(activeProvider, conversationId).clear();
        }

        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < ROUNDS.size(); i++) {
            turns.add(ask(svc, activeProvider, conversationId, i + 1, ROUNDS.get(i)));
        }
        Turn recall = ask(svc, activeProvider, conversationId, ROUNDS.size() + 1, RECALL);

        Turn controlTurn = null;
        if (control) {
            String freshId = conversationId + "-control-" + System.nanoTime();
            service(activeProvider, freshId).clear();
            controlTurn = ask(svc, activeProvider, freshId, -1, RECALL);
        }

        return new MemoryRun(
                conversationId,
                reset,
                customWindow ? maxMessages : MemoryConfig.MAX_MESSAGES,
                customWindow
                        ? "本次请求自定义（AiServices.builder() 现场构造 + 小窗口 Provider）"
                        : "Bean 装配（config/MemoryConfig 里的 ChatMemoryProvider，窗口 "
                          + MemoryConfig.MAX_MESSAGES + " 条）",
                turns,
                recall,
                controlTurn,
                Evidence.of(recall, controlTurn),
                customWindow
                        ? "自定义小窗口：" + maxMessages + " 条消息 = " + (maxMessages / 2) + " 轮，最早的事实会被挤掉。"
                        : "Bean 窗口 20 条消息 = 10 轮，本次 4 轮不会触发裁剪。");
    }

    /**
     * 命令式构造服务实例 —— LangChain4j 的「逃生舱」。
     *
     * <p>{@code @AiService} 是声明式的、启动时装配、之后改不了；
     * 需要<b>按请求换协作者</b>时就只能自己 {@code AiServices.builder()} 一个。
     * 这时工具也得自己挂（{@code @AiService} 的自动收集不参与）。
     */
    private GameOpsAssistant imperative(ChatMemoryProvider activeProvider) {
        return AiServices.builder(GameOpsAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(activeProvider)
                .tools(tools)
                .build();
    }

    /** 同 Store、不同窗口的 Provider。 */
    private ChatMemoryProvider smallWindowProvider(int maxMessages) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
    }

    private static ChatMemory service(ChatMemoryProvider p, String conversationId) {
        return p.get(conversationId);
    }

    /**
     * 走一轮真实调用。
     *
     * <p>{@code memoryMessages} 取的是 {@code chatMemory.messages().size()} ——
     * 注意<b>不能</b>直接读 {@code ChatMemoryStore.getMessages().size()}：
     * 字节码显示 {@code MessageWindowChatMemory.messages()} 是
     * {@code windowed(store.getMessages(id))}，<b>窗口是在读的时候才应用的</b>。
     * 读 Store 拿到的是原始条数，读 {@code messages()} 拿到的才是真正进 prompt 的条数。
     *
     * <p>调用后再取一次，把「记忆里现在剩了哪几条」也带出去。
     * {@code memoryFirstUser} 是裁剪实验的直接证据：窗口一变，它会从第 1 轮的问题变成第 2 轮的。
     */
    private Turn ask(GameOpsAssistant svc, ChatMemoryProvider p, String conversationId, int round, String question) {
        int historyBefore = service(p, conversationId).messages().size();

        long start = System.nanoTime();
        Result<String> result = svc.chatWithMemory(conversationId, question);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        List<ChatMessage> after = service(p, conversationId).messages();
        List<String> roles = after.stream().map(MemoryController::roleOf).toList();
        String firstUser = after.stream()
                .filter(m -> m.type() == ChatMessageType.USER)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .findFirst()
                .orElse(null);

        return new Turn(round, question, result.content(), historyBefore, roles, head(firstUser),
                Tokens.of(result.tokenUsage()), elapsedMs);
    }

    /**
     * 把 LangChain4j 的角色名映射成和 Spring AI 侧一致的小写名。
     *
     * <p>两侧的枚举根本不是同一套：LangChain4j 是
     * {@code SYSTEM / USER / AI / TOOL_EXECUTION_RESULT / CUSTOM}，
     * Spring AI 是 {@code USER / ASSISTANT / SYSTEM / TOOL}。
     * 不映射的话页面上会一边显示 {@code ai}、一边显示 {@code assistant}，没法并排读。
     */
    private static String roleOf(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case AI -> "assistant";
            case TOOL_EXECUTION_RESULT -> "tool";
            case CUSTOM -> "custom";
        };
    }

    private static String head(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }

    /** 与 spring-ai-demo 侧逐字段对齐的返回体。 */
    public record MemoryRun(String conversationId,
                            boolean memoryWasReset,
                            int windowMaxMessages,
                            String windowSource,
                            List<Turn> turns,
                            Turn recall,
                            Turn control,
                            Evidence evidence,
                            String note) {
    }

    /**
     * 一轮对话。字段名与 spring-ai-demo 侧逐一对齐。
     *
     * @param memoryMessages  本轮调用前，该会话已积累的历史消息条数（= 本轮 prompt 的额外开销）
     * @param memoryRoles     本轮结束后记忆里的消息角色序列，已归一化成
     *                        {@code user / assistant / tool / system}，两侧同一套叫法
     * @param memoryFirstUser 记忆里最早那条 user 消息的开头 —— 窗口裁剪实验的直接证据
     */
    public record Turn(int round, String question, String answer, int memoryMessages,
                       List<String> memoryRoles, String memoryFirstUser,
                       Tokens tokens, long elapsedMs) {
    }

    public record Tokens(Integer prompt, Integer completion, Integer total) {

        /** 字段名跟 Spring AI 侧对齐，不用 LangChain4j 自己的 input/output 叫法。 */
        static Tokens of(TokenUsage usage) {
            return usage == null ? null
                    : new Tokens(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
        }
    }

    /**
     * 结论区。{@code anchorHit} 才是判定记忆是否生效的那一项 —— 理由见 {@link #ANCHOR}。
     */
    public record Evidence(boolean recallHit, boolean anchorHit,
                           List<String> hits, List<String> misses,
                           boolean controlHit, String verdict) {

        public static Evidence of(Turn recall, Turn control) {
            List<String> hits = new ArrayList<>();
            List<String> misses = new ArrayList<>();
            String answer = normalize(recall.answer());
            for (String fact : EXPECTED) {
                (answer.contains(fact) ? hits : misses).add(fact);
            }
            boolean anchorHit = hits.contains(ANCHOR);
            boolean controlHit = control != null && normalize(control.answer()).contains(ANCHOR);

            String verdict;
            if (anchorHit && controlHit) {
                verdict = "两侧都能说出锚点 —— 提示词可能已经把答案泄给对照组了，这个实验不成立";
            }
            else if (anchorHit && control == null) {
                verdict = "锚点被复述出来了（未跑对照组，无法排除猜对）";
            }
            else if (anchorHit) {
                String extra = hits.containsAll(FETCHABLE) ? "，并附带两个工具可查的数字" : "";
                verdict = "记忆生效：锚点 " + ANCHOR + "（工具产不出的值）被复述出来" + extra
                        + "，无记忆对照侧连锚点都答不出";
            }
            else {
                String half = hits.isEmpty() ? "三个事实全丢"
                        : "只答对 " + hits.size() + "/" + EXPECTED.size() + "（丢的是 " + misses + "）";
                String caveat = misses.containsAll(FETCHABLE)
                        ? "" : "；注意命中的 3214/1870 不能当记忆证据 —— 工具能现查";
                verdict = "锚点 " + ANCHOR + " 没被复述出来，" + half
                        + " → 记忆没生效，或被窗口裁掉了" + caveat;
            }
            return new Evidence(hits.size() == EXPECTED.size(), anchorHit, hits, misses, controlHit, verdict);
        }

        private static String normalize(String text) {
            return text == null ? "" : text.replace(",", "").replace(" ", "").replace("，", "").toUpperCase();
        }
    }
}
