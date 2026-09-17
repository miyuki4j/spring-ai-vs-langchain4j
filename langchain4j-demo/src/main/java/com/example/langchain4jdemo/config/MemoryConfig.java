package com.example.langchain4jdemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

/**
 * LangChain4j 侧的对话记忆装配 —— 和 spring-ai-demo 的 {@code memory/MemoryConfig} 对照着看。
 *
 * <p><b>这是两个框架差距最明显的一处，值得记牢：</b>
 * <ul>
 *   <li><b>Spring AI</b>：引进 {@code spring-ai-starter-model-chat-memory} 依赖，
 *       容器里自动就有 {@code ChatMemoryRepository} + {@code ChatMemory} 两个 Bean，
 *       业务侧只需要补一个 Advisor。**靠自动配置**。
 *   <li><b>LangChain4j</b>：{@code langchain4j-spring-boot-starter:1.20.0-beta30} 的
 *       {@code META-INF/spring/...AutoConfiguration.imports} 里只有
 *       {@code LangChain4jAutoConfig} 一个类，而它是个**空壳**（只承载 properties）——
 *       <b>一个记忆相关的自动配置都没有</b>。Store 和 Provider 全得自己写，就是下面这些。
 * </ul>
 * 两条路没有优劣，但面试时能说清「一边开箱即用、一边必须手写」，比背 API 有价值。
 *
 * <p><b>那这两个 Bean 是怎么被接上的？</b>不是靠 {@code @Autowired}，
 * 而是 {@code AiServicesAutoConfig} 注册的一个 {@code BeanFactoryPostProcessor}
 * 在启动时直接改 {@code @AiService} 的 BeanDefinition：
 * <pre>
 *   chatMemories       = getBeanNamesForType(ChatMemory.class)
 *   chatMemoryProviders= getBeanNamesForType(ChatMemoryProvider.class)
 *   // 然后把命中的 Bean 名 addBeanReference 进 AiServiceFactory 的属性
 * </pre>
 * 这段是从字节码里逐条读出来的（{@code AiServicesAutoConfig#addBeanReference}），
 * 它有个**很阴的失败模式**，见 {@link #gameOpsChatMemoryProvider} 的注释。
 */
@Configuration
public class MemoryConfig {

    /** 窗口大小：20 条消息 = 10 轮（每轮 user + assistant 两条）。 */
    public static final int MAX_MESSAGES = 20;

    /**
     * 存储层。key 是 {@code Object}（不是 Spring AI 那边的 {@code String}）——
     * 因为 {@code memoryId} 的原始类型是 {@code Object}，你用 Long 当用户 id 也行。
     *
     * <p>生产上这里换 Redis：实现 {@link ChatMemoryStore} 的三个方法即可
     * （{@code getMessages} / {@code updateMessages} / {@code deleteMessages}），
     * 和 Spring AI 那边换 {@code ChatMemoryRepository} 是同一个动作。
     * 你现在这个 demo 用 InMemory 没问题，但**多实例部署时它就是定时炸弹** ——
     * 请求打到 B 实例读不到 A 实例写的历史，重启全丢。
     */
    @Bean
    public ChatMemoryStore gameOpsChatMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    /**
     * 会话工厂：按 {@code memoryId} 分发到不同的 {@code ChatMemory} 槽位。
     *
     * <p><b>这个 Bean 有就生效、没有就静默不生效 —— 这点必须知道。</b>
     * 下面是 {@code AiServicesAutoConfig#addBeanReference} 的字节码还原：
     * <pre>
     *   if (wiringMode == AUTOMATIC) {
     *       if (beanNames.length == 1)  propertyValues.add(factoryPropertyName, new RuntimeBeanReference(beanNames[0]));
     *       else if (beanNames.length &gt; 1) throw conflict(...);   // 启动直接炸
     *       // 0 个 → 什么都不做，**不报错、不警告**
     *   }
     * </pre>
     * 也就是说：<b>忘了声明这个 Bean，不会报错，只是记忆永远不生效</b>；
     * 而声明了两个同类型 Bean，启动就抛 {@code IllegalConfigurationException}
     * （提示你改用 {@code @AiService(wiringMode = EXPLICIT, chatMemoryProvider = "...")}）。
     * 一个静默、一个爆炸，两种失败方式刚好相反，都是真实会踩的坑。
     *
     * <p><b>反向的校验也存在，但只管一个方向</b>（{@code AiServiceValidation.validateMethod} 字节码）：
     * <pre>
     *   if (!hasChatMemoryProvider) {
     *       for (Parameter p : method.getParameters())
     *           if (p.isAnnotationPresent(MemoryId.class))
     *               throw illegalConfiguration("In order to use @MemoryId, please configure the ChatMemoryProvider on the '%s'.");
     *   }
     * </pre>
     * 即「用了 {@code @MemoryId} 却没配 Provider」→ 启动即抛；
     * 而「配了 Provider 但方法没写 {@code @MemoryId}」→ <b>没有任何校验</b>，那类方法就是不走记忆。
     */
    @Bean
    public ChatMemoryProvider gameOpsChatMemoryProvider(ChatMemoryStore store) {
        // 注意：这里的 lambda 每个 memoryId 只会被调用一次 —— 框架在
        // ChatMemoryService 里又套了一层 ConcurrentHashMap 缓存
        // （getOrCreateChatMemory 用的是 computeIfAbsent），
        // 所以「同一个 memoryId 拿到同一个 ChatMemory 实例」是被保证的。
        // 反过来说：**想去掉缓存就只能靠框架的 evict 接口，改 Provider 本身没用**。
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(MAX_MESSAGES)
                .chatMemoryStore(store)
                .build();
    }
}
