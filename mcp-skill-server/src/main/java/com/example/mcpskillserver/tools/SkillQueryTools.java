package com.example.mcpskillserver.tools;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.mcpskillserver.catalog.SkillCatalog;
import com.example.mcpskillserver.catalog.SkillCatalog.ConditionProperty;
import com.example.mcpskillserver.catalog.SkillCatalog.Effect;
import com.example.mcpskillserver.catalog.SkillCatalog.Skill;
import com.example.mcpskillserver.catalog.SkillCatalog.TargetType;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 暴露给 MCP 客户端的四个工具。
 *
 * <p>三个设计上的取舍，都值得记下来：
 *
 * <p><b>1）为什么返回值是「格式化文本」而不是 JSON？</b>
 * MCP 工具的返回值最终是被塞进模型的上下文里的，模型读的是自然语言。
 * JSON 对模型不算难，但紧凑的文本更省 token、也更不容易被误读。
 * 这里刻意写成「给人看的排版」—— 顺带让 dashboard 能直接展示原文，
 * 不用再去做一层解析。
 *
 * <p><b>2）查不到时为什么要把「可选项」一并返回？</b>
 * 模型只看到你的 description，猜错参数是很正常的
 * （比如它可能把 targetType 猜成 "SINGLE_ENEMY" 而不是 2）。
 * 把合法取值一起吐回去，模型下一轮就能自己纠正 ——
 * 这比返回一句干巴巴的 "not found" 有效得多，也是 MCP 工具
 * 和普通 REST 接口在设计上的一个明显区别。
 *
 * <p><b>3）@Tool 的 description 是写给模型看的。</b>
 * 它决定模型「什么时候想起你」。写「查询技能配置」，
 * 模型不一定知道该在什么场景下用；写清「当用户问技能 ID、技能效果、
 * 伤害目标的含义时用它」，命中率会明显不一样。
 */
public class SkillQueryTools {

    /** 条件表达式里的属性名：大写字母开头、可含数字和下划线的标识符。 */
    private static final Pattern IDENTIFIER = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\b");

    /** 表达式里合法的非属性标识符（它们是语法的一部分，不是配置项）。 */
    private static final List<String> RESERVED = List.of("SELF", "TARGET", "TRUE", "FALSE", "AND", "OR", "NOT");

    private final SkillCatalog catalog;

    public SkillQueryTools(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    @Tool(name = "list_skills",
            description = "列出技能配置。当用户想知道有哪些技能、或想按关键字（技能名/标签，如 DAMAGE、AOE、ALLY）"
                    + "筛选技能时使用。不传 keyword 则返回全部技能。")
    public String listSkills(@ToolParam(description = "可选关键字，用于按技能名或标签过滤", required = false) String keyword) {
        List<Skill> hits = this.catalog.search(keyword);
        if (hits.isEmpty()) {
            return "没有匹配 \"%s\" 的技能。当前共 %d 条技能，可用的标签有 DAMAGE / SINGLE / AOE / ALLY / UTILITY / DRAW / SHIELD / SPECIAL / SUMMON。"
                .formatted(keyword, this.catalog.skills().size());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("技能配置目录 v").append(this.catalog.version())
            .append("，共 ").append(this.catalog.skills().size()).append(" 条");
        if (keyword != null && !keyword.isBlank()) {
            sb.append("，其中匹配 \"").append(keyword).append("\" 的有 ").append(hits.size()).append(" 条");
        }
        sb.append("：\n");
        for (Skill s : hits) {
            sb.append("  #").append(s.id()).append("  ").append(s.name())
                .append("   ").append(s.type())
                .append("  消耗 ").append(s.cost())
                .append("  ").append(s.tags())
                .append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "get_skill",
            description = "按技能 ID 查询某个技能的完整配置，包括每条效果的动作、数值、目标类型和触发条件。"
                    + "当用户问到具体某个技能的详细信息（伤害多少、打谁、什么条件下触发）时使用。")
    public String getSkill(@ToolParam(description = "技能 ID，例如 1001") int skillId) {
        Optional<Skill> found = this.catalog.find(skillId);
        if (found.isEmpty()) {
            return "没有 ID 为 %d 的技能。可用 ID：%s".formatted(skillId,
                    this.catalog.skills().stream().map(s -> String.valueOf(s.id())).toList());
        }
        Skill s = found.get();
        StringBuilder sb = new StringBuilder();
        sb.append("技能 #").append(s.id()).append(" ").append(s.name()).append("\n");
        sb.append("类型：").append(s.type()).append("　消耗：").append(s.cost())
            .append("　标签：").append(s.tags()).append("\n");
        sb.append("描述：").append(s.description()).append("\n");
        sb.append("效果（").append(s.effects().size()).append(" 条）：\n");
        for (Effect e : s.effects()) {
            sb.append("  ").append(e.seq()).append(") ").append(e.op()).append(" ").append(e.value());
            this.catalog.targetType(e.targetType())
                .ifPresent(t -> sb.append("　目标：").append(describeTarget(t)));
            if (e.duration() != null) {
                sb.append("　持续 ").append(e.duration()).append(" 回合");
            }
            sb.append("\n");
            if (e.condition() != null && !e.condition().isBlank()) {
                sb.append("     触发条件：").append(e.condition());
                if (e.conditionNote() != null) {
                    sb.append("（").append(e.conditionNote()).append("）");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @Tool(name = "explain_effect_target",
            description = "解释「效果目标类型」（EffectTargetType）的含义。当用户问某个目标编号代表什么、"
                    + "或某个目标类型（如 SINGLE_ENEMY、ALL_ALLIES）是什么意思时使用。")
    public String explainEffectTarget(@ToolParam(description = "目标类型编号，例如 2；也可以传名称如 SINGLE_ENEMY") String target) {
        Optional<TargetType> byCode = Optional.empty();
        try {
            byCode = this.catalog.targetType(Integer.parseInt(target.trim()));
        }
        catch (NumberFormatException ignored) {
            // 不是数字就按名称查，下面统一兜底
        }
        Optional<TargetType> found = byCode.isPresent() ? byCode : this.catalog.targetTypeByName(target);

        if (found.isPresent()) {
            TargetType t = found.get();
            return "效果目标类型 %s：%s。\n全部可用取值：%s".formatted(describeTarget(t), t.desc(),
                    this.catalog.targetTypes().stream().map(SkillQueryTools::describeTarget).toList());
        }
        return "没有叫 \"%s\" 的效果目标类型。全部合法取值如下（请从中选择一个）：\n%s".formatted(target,
                this.catalog.targetTypes()
                    .stream()
                    .map(t -> "  #" + t.code() + " " + t.name() + "：" + t.desc())
                    .reduce("", (a, b) -> a + b + "\n"));
    }

    @Tool(name = "validate_condition",
            description = "校验技能条件表达式的合法性，例如 \"HP_PERCENT(SELF) < 50\"。"
                    + "当用户想确认某个条件写法对不对、或想了解条件里能用哪些属性时使用。"
                    + "会逐个检查表达式里用到的属性名是否已定义。")
    public String validateCondition(@ToolParam(description = "条件表达式，例如 HP_PERCENT(SELF) < 50 或 TURN_INDEX >= 3") String expression) {
        if (expression == null || expression.isBlank()) {
            return "表达式为空。示例：HP_PERCENT(SELF) < 50";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("表达式：").append(expression).append("\n");

        Matcher m = IDENTIFIER.matcher(expression);
        int total = 0;
        int unknown = 0;
        while (m.find()) {
            String name = m.group(1);
            if (RESERVED.contains(name)) {
                continue;
            }
            total++;
            Optional<ConditionProperty> p = this.catalog.property(name);
            if (p.isPresent()) {
                ConditionProperty cp = p.get();
                sb.append("  [OK]    ").append(name)
                    .append("　#").append(cp.code())
                    .append("　类型 ").append(cp.type())
                    .append("　").append(cp.desc()).append("\n");
            }
            else {
                unknown++;
                sb.append("  [未知]  ").append(name).append("　未定义的属性\n");
            }
        }

        if (total == 0) {
            sb.append("  没有解析出任何属性 —— 条件表达式必须至少引用一个已定义的属性。\n");
        }
        sb.append(unknown == 0 && total > 0 ? "结论：表达式合法。\n" : "结论：表达式不合法，请改用已定义的属性。\n");
        sb.append("已定义的属性：").append(this.catalog.conditionProperties().stream().map(ConditionProperty::name).toList());
        return sb.toString();
    }

    private static String describeTarget(TargetType t) {
        return "#" + t.code() + " " + t.name() + "（" + t.desc() + "）";
    }
}
