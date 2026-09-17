package com.example.mcpskillserver.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 技能配置目录：把 {@code skills.json} 读进内存，供 MCP 工具查询。
 *
 * <p><b>为什么数据放 JSON 文件而不是写死在代码里？</b>
 * 因为这样你可以直接把自己的真实技能配置丢进 {@code resources/skills.json}，
 * 一行 Java 都不用改，MCP 工具立刻就能查到。教学项目里这比硬编码有用得多。
 *
 * <p><b>顺带记一个和上一课呼应的点</b>：Spring Boot 4 默认的 Jackson 已经是
 * <b>Jackson 3</b>，包名从 {@code com.fasterxml.jackson} 变成了 {@code tools.jackson}
 * （javap 实证）。这不是小改动 —— 从 Jackson 2 抄来的代码在这个模块里
 * 会因为 import 不到 {@code com.fasterxml.jackson.databind.ObjectMapper} 而编译失败。
 * 另一个变化是 {@code JacksonException} 现在继承 {@code RuntimeException}，
 * 所以下面读文件只需要处理 {@code IOException}，不用再管 JSON 解析异常。
 */
@Component
public class SkillCatalog {

    // ─────────────────────────────────────────────────────────────
    // 数据结构。用 record 而不是 POJO：Jackson 3 原生支持 record 反序列化
    // （靠 record 的组件名做属性映射），不需要写 getter/setter，也不需要无参构造。
    // ─────────────────────────────────────────────────────────────

    /** 效果目标类型 —— 对应真实项目里的 EffectTargetType。 */
    public record TargetType(int code, String name, String desc) {
    }

    /** 条件属性 —— 对应真实项目里的 ConditionPropertyType。 */
    public record ConditionProperty(int code, String name, String type, String desc) {
    }

    /** 效果动作类型，例如 DAMAGE / HEAL / SHIELD。 */
    public record EffectOp(String op, String desc) {
    }

    /** 技能的一条效果。 */
    public record Effect(int seq, String op, int value, int targetType,
                         Integer duration, String condition, String conditionNote) {
    }

    /** 一个技能。 */
    public record Skill(int id, String name, String type, int cost,
                        List<String> tags, String description, List<Effect> effects) {
    }

    /** skills.json 的整体结构。 */
    public record Document(String version, String comment, Integer anchorSkillId,
                           List<TargetType> targetTypes,
                           List<ConditionProperty> conditionProperties,
                           List<EffectOp> effectOps,
                           List<Skill> skills) {
    }

    private final Document doc;

    public SkillCatalog(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("skills.json").getInputStream()) {
            this.doc = mapper.readValue(in, Document.class);
        }
        catch (IOException e) {
            // 读不到就让它启动失败 —— 一个查不出数据的配置服务，早失败比晚失败好。
            throw new IllegalStateException("读取 skills.json 失败", e);
        }
    }

    public String version() {
        return this.doc.version();
    }

    public List<Skill> skills() {
        return this.doc.skills();
    }

    public List<TargetType> targetTypes() {
        return this.doc.targetTypes();
    }

    public List<ConditionProperty> conditionProperties() {
        return this.doc.conditionProperties();
    }

    public List<EffectOp> effectOps() {
        return this.doc.effectOps();
    }

    public Optional<Skill> find(int id) {
        return this.doc.skills().stream().filter(s -> s.id() == id).findFirst();
    }

    /** 按名字 / ID / 标签做模糊匹配；keyword 为空则返回全部。 */
    public List<Skill> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return this.doc.skills();
        }
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        return this.doc.skills()
            .stream()
            .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(k)
                    || String.valueOf(s.id()).contains(k)
                    || s.tags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(k)))
            .toList();
    }

    public Optional<TargetType> targetType(int code) {
        return this.doc.targetTypes().stream().filter(t -> t.code() == code).findFirst();
    }

    public Optional<TargetType> targetTypeByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return this.doc.targetTypes()
            .stream()
            .filter(t -> t.name().equalsIgnoreCase(name.trim()))
            .findFirst();
    }

    public Optional<ConditionProperty> property(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return this.doc.conditionProperties()
            .stream()
            .filter(p -> p.name().equalsIgnoreCase(name.trim()))
            .findFirst();
    }
}
