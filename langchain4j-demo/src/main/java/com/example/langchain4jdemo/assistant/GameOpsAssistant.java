package com.example.langchain4jdemo.assistant;

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
}
