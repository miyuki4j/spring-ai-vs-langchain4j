package com.example.springaidemo.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.springaidemo.tools.GameServerTools;

/**
 * 对话记忆的实测端点 —— 一趟请求里跑完「三轮对话 + 一次回忆 + 一次无记忆对照」。
 *
 * <p>为什么把四轮塞进一个 HTTP 请求，而不是让页面分四次调？因为「记忆」这件事的证据不在
 * 单轮回答里，而在<b>轮与轮之间</b>：同一个 {@code conversationId} 下，第二轮要看得见第一轮，
 * 第四轮要看得见前三轮。放在一次请求里，会话生命周期明确、不会被页面的并发刷新搅乱。
 *
 * <p>实验设计（这是本端点的重点，比代码本身重要）：
 * <ol>
 *   <li><b>第 1 轮</b>问「1 区在线多少人」—— 必须调工具才知道，答案 3214
 *   <li><b>第 2 轮</b>让模型「记住热更批次号 LOONG-7749」—— 这个值<b>工具里查不到</b>，
 *       只能靠记忆留存（这是全场实验的「锚点」）
 *   <li><b>第 3 轮</b>问「2 区在线多少人」—— 答案 1870
 *   <li><b>第 4 轮</b>复述：既要说出 3214 / 1870（工具能查到，但模型并不知道该查哪个区），
 *       也要说出 LOONG-7749（工具<b>根本不可能</b>产生）
 *   <li><b>对照组</b>：拿第 4 轮的问题，在<b>一个全新的 conversationId</b> 上再问一次。
 *       没有记忆时，LOONG-7749 无论如何都答不出来 —— 这一问才是「记忆真的起作用了」
 *       的证据。只看第 4 轮答对了，其实分不清「它有记忆」还是「它猜的」。
 * </ol>
 *
 * <p>第 2 条那个设计是刻意的：如果四轮都问「某区在线多少人」，模型在没有记忆时也可能
 * 挨个调工具试 s1/s2/s3 蒙对，对照实验就失效了。放一个<b>工具无论如何产不出来</b>的值进去，
 * 对照组必然失败，实验才是封闭的。
 *
 * <hr>
 *
 * <h2>踩过的坑：不要在每次请求里 {@code defaultAdvisors(...)}</h2>
 *
 * <p>这个类第一版是「每次请求现建 ChatClient」：
 * <pre>{@code
 * ChatClient client = builder.defaultSystem(SYSTEM).defaultAdvisors(advisor).build();
 * }</pre>
 * 肉眼看没问题，实测却有一个非常隐蔽的故障：<b>换小窗口不生效</b>。
 *
 * <p>三个事实叠在一起才凑出这个 bug，单看任何一个都不像问题：
 * <ol>
 *   <li><b>{@code ChatClient.Builder} 注入到单例里就只有一份。</b>它是 prototype 作用域，
 *       但本类只在构造时注入一次，之后一直用同一个实例。而
 *       {@code DefaultChatClientBuilder#defaultAdvisors} 是<b>往自己的 defaultRequest 上 addAll</b>
 *       （不是覆盖），{@code build()} 出来的 ChatClient 也共享这个 defaultRequest ——
 *       所以每请求调一次，advisor 就<b>累积</b>一个。
 *   <li><b>两个 {@code MessageChatMemoryAdvisor} 的 order 一样。</b>
 *       默认 order 都是 {@code Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}
 *       （{@code HIGHEST_PRECEDENCE + 200}），同 order 就按加入顺序执行 ——
 *       <b>上一轮请求遗留的那个排在新加的前面</b>，先跑、先注入。
 *   <li><b>框架自己带了「防重复注入」的静默逻辑。</b>
 *       {@code MessageChatMemoryAdvisor.before()} 里有
 *       {@code isMemoryAlreadyInPrompt(prompt, memoryMessages)}：如果记忆消息已经是 prompt
 *       的一段连续子序列，就<b>跳过注入</b>。本意是防两个 advisor 把同一份历史插两遍，
 *       结果在这里变成了「后加的 advisor 被悄悄忽略」。
 * </ol>
 *
 * <p>合起来的后果：小窗口那次请求里，<b>先生效的是上一轮请求遗留的默认窗口（20 条）记忆</b>，
 * 新加的小窗口 advisor 被去重逻辑吞掉。于是「压小窗口」这个实验在 P1 侧<b>等于没做</b> ——
 * 而 {@code memoryMessages} 这类字段读的是我们自己的 memory 对象，仍然显示 2，
 * <b>所有弱断言全部通过</b>；只有「窗口裁掉后锚点必须丢」这条最硬的断言把它捅出来了。
 *
 * <p>修法：<b>ChatClient 只 build 一次，记忆 advisor 按调用传</b>
 * （{@code .advisors(a -> a.advisors(advisor).param(...))}）。这也正是 Spring AI 的设计意图 ——
 * 记忆是<b>调用参数</b>，不是 builder 上的常量。
 *
 * <p>顺带两个从源码里核实的常量，理解 advisor 顺序时会用到
 * （两个类都在 {@code spring-ai-client-chat} 里）：
 * <ul>
 *   <li>{@code MessageChatMemoryAdvisor} 默认 order = {@code HIGHEST_PRECEDENCE + 200}
 *   <li>{@code ToolCallingAdvisor.DEFAULT_ORDER} = {@code HIGHEST_PRECEDENCE + 300}
 * </ul>
 * 记忆 advisor 数值更小 = 更靠上游，先注入历史；工具 advisor 在其下游，
 * 所以工具循环里那几轮工具调用与结果<b>不会</b>写进对话记忆（记忆里的角色永远是
 * {@code user,assistant} 一对一轮）。这一点在下面的实测输出里能直接看到。
 */
@RestController
@RequestMapping("/api/chat/memory")
public class MemoryController {

    private static final String SYSTEM = "你是一个游戏服务端运维助手，回答要简洁、准确，用中文。";

    /** 前三轮：两条要查工具、一条纯靠记忆。 */
    private static final List<String> ROUNDS = List.of(
            "1区现在在线多少人？",
            "记住这个热更批次号：LOONG-7749。收到请只回复「已记录」。",
            "2区现在在线多少人？");

    /** 第 4 轮：回忆。 */
    private static final String RECALL = "请复述一下：我前面问过的区服的在线人数，以及我让你记住的热更批次号。";

    /**
     * <b>记忆锚点</b>：一个工具<b>产不出来</b>的值。
     *
     * <p>为什么必须有它？因为 3214 / 1870 这两个数字虽然是「查出来的」，
     * 但模型在回忆轮<b>完全可以自己再调一次工具拿回来</b> —— 实测就发生过：
     * 小窗口把第 1 轮裁掉之后，回答里照样给出了 3214。
     * 所以那两个数字只能当辅助证据，<b>只有锚点才能区分「记住了」和「现查了一遍」</b>。
     */
    private static final String ANCHOR = "LOONG-7749";

    /** 辅助事实：工具查得到，命中不算记忆生效的证据。 */
    private static final List<String> FETCHABLE = List.of("3214", "1870");

    /** 回忆轮回答里应该出现的三个事实，锚点在最后，便于页面上单独标注。 */
    private static final List<String> EXPECTED = List.of("3214", "1870", ANCHOR);

    /**
     * 整个控制器只 build 一次 ChatClient —— 理由见类注释「不要在每次请求里 defaultAdvisors」。
     * <b>记忆 advisor 不挂在这里</b>，而是每次调用时通过 {@code .advisors(...)} 传进去。
     */
    private final ChatClient client;
    private final ChatMemory autoConfiguredChatMemory;
    private final MessageChatMemoryAdvisor autoConfiguredAdvisor;
    private final GameServerTools tools;

    public MemoryController(ChatClient.Builder builder,
                            ChatMemory chatMemory,
                            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                            GameServerTools tools) {
        this.autoConfiguredChatMemory = chatMemory;
        this.autoConfiguredAdvisor = messageChatMemoryAdvisor;
        this.tools = tools;
        this.client = builder.defaultSystem(SYSTEM).build();
    }

    /**
     * @param conversationId 会话标识。同一个 id 复用记忆；换 id 就是新会话。
     * @param reset          是否先清空该会话记忆（默认 true，让每次调用都是干净的三轮实验）
     * @param maxMessages    窗口大小。不传 = 用自动配置 Bean 的默认值（20）。
     *                       传了就现场构造一个小窗口的 ChatMemory，
     *                       用来演示「按条数裁剪会把最早的事实挤掉」——
     *                       probe 里传 <b>2</b>：一轮就把窗口占满，第 2 轮的锚点也会被挤掉
     *                       （传 4 不行：那时第 2 轮还在窗口里，锚点照样命中，实验就分不出差别）。
     * @param control        是否跑无记忆对照组（默认 true）
     */
    @GetMapping
    public MemoryRun run(@RequestParam(defaultValue = "demo-a") String conversationId,
                         @RequestParam(defaultValue = "true") boolean reset,
                         @RequestParam(required = false) Integer maxMessages,
                         @RequestParam(defaultValue = "true") boolean control) {

        // 自动配置的 ChatMemory（窗口 20）走这里；传了 maxMessages 就现场造一个小的
        boolean customWindow = maxMessages != null;
        ChatMemory memory = customWindow ? smallWindow(maxMessages) : autoConfiguredChatMemory;
        MessageChatMemoryAdvisor advisor = customWindow
                ? MessageChatMemoryAdvisor.builder(memory).build()
                : autoConfiguredAdvisor;

        if (reset) {
            memory.clear(conversationId);
        }

        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < ROUNDS.size(); i++) {
            turns.add(ask(advisor, memory, conversationId, i + 1, ROUNDS.get(i)));
        }
        Turn recall = ask(advisor, memory, conversationId, ROUNDS.size() + 1, RECALL);

        // 对照组：同一个问题、全新的会话 id —— 没有历史可读，锚点必然想不起来
        Turn controlTurn = null;
        if (control) {
            String freshId = conversationId + "-control-" + System.nanoTime();
            memory.clear(freshId);
            controlTurn = ask(advisor, memory, freshId, -1, RECALL);
        }

        return new MemoryRun(
                conversationId,
                reset,
                customWindow ? maxMessages : 20,
                customWindow ? "本次请求自定义（MessageWindowChatMemory.builder().maxMessages(n)）"
                             : "自动配置（MessageWindowChatMemory 的 DEFAULT_MAX_MESSAGES = 20）",
                turns,
                recall,
                controlTurn,
                Evidence.of(recall, controlTurn),
                customWindow
                        ? "自定义小窗口：" + maxMessages + " 条消息 = " + (maxMessages / 2) + " 轮，最早的事实会被挤掉。"
                        : "自动配置窗口 20 条消息 = 10 轮，本次 4 轮不会触发裁剪。");
    }

    /** 现场构造一个小窗口的 ChatMemory。生产里不会这么写，这里纯粹为了做对照实验。 */
    private static ChatMemory smallWindow(int maxMessages) {
        return MessageWindowChatMemory.builder()
                .maxMessages(maxMessages)
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
    }

    /**
     * 走一轮真实调用，并记录「本轮送进去的历史条数」。
     *
     * <p>{@code memory.get(cid).size()} 取的是<b>调用之前</b>的长度 —— 也就是本轮 prompt 里
     * 会被拼进去的历史消息数。Advisor 是在调用<b>之后</b>才把本轮消息写回记忆的
     * （{@code after()} 里干这事），所以这样取到的就是「本轮的成本由前几轮决定」的那个数字。
     *
     * <p>调用<b>之后</b>再取一次，把「记忆里现在到底剩了哪几条」也带出去
     * （{@code memoryRoles} / {@code memoryFirstUser}）。
     * 这两项是裁剪实验的<b>直接证据</b>：窗口一变，{@code memoryFirstUser} 就会从第 1 轮的问题
     * 变成第 2 轮的 —— 不用靠「回答里少了哪个数字」去反推，也就不用猜模型是记性差还是被裁了。
     *
     * @param advisor 本次请求要用的记忆 advisor。注意它是<b>按调用传</b>的，不是挂在 client 上 ——
     *                正是这一点让「本次请求换小窗口」真的生效（见类注释里那个坑）。
     */
    private Turn ask(MessageChatMemoryAdvisor advisor, ChatMemory memory, String conversationId,
                     int round, String question) {
        int historyBefore = memory.get(conversationId).size();

        long start = System.nanoTime();
        ChatResponse response = client.prompt()
                .user(question)
                .tools(tools)
                // 记忆 advisor 与「会话标识」都在这里按调用传入。
                // 会话标识的常量值是 "chat_memory_conversation_id"。
                .advisors(a -> a.advisors(advisor).param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatResponse();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 本轮已写入，此时的列表就是「下一轮会看到什么」
        List<Message> after = memory.get(conversationId);
        List<String> roles = after.stream().map(m -> m.getMessageType().getValue()).toList();
        String firstUser = after.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .findFirst()
                .orElse(null);

        return new Turn(round, question, text(response), historyBefore, roles, head(firstUser),
                Tokens.of(response), elapsedMs);
    }

    /** 只留开头一段 —— 页面用它判断「最早那条还在不在」，不需要全文。 */
    private static String head(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }

    private static String text(ChatResponse response) {
        // getResult() 也可能是 null（例如只返回元数据的分片），这里按单次调用处理
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
    }

    /** {@code /api/chat/memory} 的返回体。字段名与 langchain4j-demo 侧完全对齐，页面直接并排画。 */
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
     * 一轮对话。
     *
     * @param memoryMessages 本轮调用前，该会话已积累的历史消息条数（= 本轮 prompt 的额外开销）
     * @param memoryRoles    本轮结束后记忆里的消息角色序列，如 {@code [user, assistant, user, assistant]}。
     *                       和 Spring AI 的 {@code MessageType.getValue()} 对齐成小写名，
     *                       两侧字段形状一致，页面才能并排画
     * @param memoryFirstUser 记忆里最早那条 user 消息的开头。窗口裁剪实验的直接证据 ——
     *                       它从「第 1 轮的问题」变成「第 2 轮的问题」，就说明第 1 轮被挤掉了
     */
    public record Turn(int round, String question, String answer, int memoryMessages,
                       List<String> memoryRoles, String memoryFirstUser,
                       Tokens tokens, long elapsedMs) {
    }

    public record Tokens(Integer prompt, Integer completion, Integer total) {

        static Tokens of(ChatResponse response) {
            Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
            return usage == null ? null
                    : new Tokens(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }
    }

    /**
     * 结论区。
     *
     * <p><b>{@code anchorHit} 才是判定记忆是否生效的那一项</b>，{@code recallHit} 只能当参考：
     * 3214 / 1870 工具能现查，模型在回忆轮完全可以再调一次工具把它们拿回来。
     * 只有 {@code anchorHit} 与 {@code controlHit} 一起看（一真一假），实验才成立。
     */
    public record Evidence(boolean recallHit, boolean anchorHit,
                           List<String> hits, List<String> misses,
                           boolean controlHit, String verdict) {

        static Evidence of(Turn recall, Turn control) {
            List<String> hits = new ArrayList<>();
            List<String> misses = new ArrayList<>();
            String answer = normalize(recall.answer());
            for (String fact : EXPECTED) {
                (answer.contains(fact) ? hits : misses).add(fact);
            }
            boolean anchorHit = hits.contains(ANCHOR);
            // 对照组要严谨：必须连锚点都答不出来才算「没记忆」
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

        /** 去掉千分位逗号和空白、转大写，避免 "3,214" / "LOONG-7749" 这类书写差异造成假阴性。 */
        private static String normalize(String text) {
            return text == null ? "" : text.replace(",", "").replace(" ", "").replace("，", "").toUpperCase();
        }
    }
}
