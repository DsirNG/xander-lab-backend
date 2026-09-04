package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentUserSkill;
import com.xander.lab.agent.infrastructure.AgentUserSkillMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 这位用户当轮能读到的全部技能，内置的与他自己的合并成一份目录。
 *
 * <p>目录里只有名字和一句"什么时候该读它"，正文要靠 {@link LoadSkillTool} 显式取回。
 * 这是有意的分级：正文加起来能有几万字，全塞进提示词就把上下文吃光了，
 * 而一轮对话通常只需要其中一份，甚至一份都不需要。</p>
 *
 * <p>目录跟着工具面走（见 {@link AgentToolRegistry#describeToolSurface(Set, String)}），
 * 也就是落在提示词的缓存前缀里而不是对话记录里。参考实现把目录作为一条常驻消息注进对话记录，
 * 那样压缩有可能把它盖掉，于是还要额外一套"压缩后重新建立目录"的机制；
 * 放在前缀里就没有这个问题——它压根不属于会被压缩的那一段。
 * 代价是同一位用户的私有技能变动会让前缀失效一次，那本来就是提示词真的变了。</p>
 *
 * <p>用户私有技能只取每个 {@code skill_key} 版本号最大的那一条 ACTIVE 版本：
 * 建新版本不会自动归档旧版本，全列出来会在目录里渲染出同名的两条。</p>
 */
@Slf4j
@Component
public class AgentSkillCatalog {
    static final String CATALOG_OPEN_TAG = "<available_skills>";
    static final String CATALOG_CLOSE_TAG = "</available_skills>";
    /** 目录里每条描述的字符上限，与 {@code agent_skill.description} 的列宽一致。 */
    static final int DESCRIPTION_MAX_CHARS = 500;

    private final List<AgentSkill> builtins;
    private final AgentUserSkillMapper userSkillMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentSkillCatalog(List<AgentSkill> builtins, AgentUserSkillMapper userSkillMapper,
                             ObjectMapper objectMapper) {
        this.builtins = builtins.stream().sorted(Comparator.comparing(AgentSkill::id)).toList();
        this.userSkillMapper = userSkillMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 不查库的空目录，供不启动 MyBatis 的聚焦回归用。
     *
     * <p>不是"关掉技能"的开关：正式装配总是注入真的 mapper。</p>
     */
    static AgentSkillCatalog empty() {
        return new AgentSkillCatalog(List.of(), null, new ObjectMapper());
    }

    /**
     * 这一轮的技能供给：固定的工具面 + 一份目录。
     *
     * @param userId     归属用户，决定他自己的技能进不进目录
     * @param toolNames  这一轮的工具表，与用户说了什么无关
     */
    public AgentSkillOffer offer(Long userId, Set<String> toolNames) {
        List<AgentSkill> available = entries(userId);
        return new AgentSkillOffer(available, toolNames, render(available));
    }

    /** 目录里的全部技能，按名字排序；同名时内置的赢。 */
    public List<AgentSkill> entries(Long userId) {
        LinkedHashMap<String, AgentSkill> merged = new LinkedHashMap<>();
        for (AgentSkill builtin : builtins) merged.put(builtin.id(), builtin);
        for (AgentUserSkill owned : activeUserSkills(userId)) {
            // 内置技能是产品自带的可信能力，用户拿同名 skill_key 盖不掉它；
            // 反过来允许覆盖的话，改一个名字就能替换掉发布、判题这些带副作用的做法规则。
            if (merged.putIfAbsent(owned.getSkillKey(), userSkill(owned)) != null) {
                log.info("agent skill catalog name collision user={} skillKey={} kept=builtin",
                        userId, owned.getSkillKey());
            }
        }
        return merged.values().stream().sorted(Comparator.comparing(AgentSkill::id)).toList();
    }

    /** 按名字找一份技能；找不到返回空，由调用方决定怎么告诉模型。 */
    public Optional<AgentSkill> find(Long userId, String name) {
        if (!StringUtils.hasText(name)) return Optional.empty();
        String wanted = name.trim();
        return entries(userId).stream().filter(skill -> wanted.equals(skill.id())).findFirst();
    }

    /** 渲染成提示词里的目录块；一条都没有时返回空串，不留一个空标签。 */
    public String render(List<AgentSkill> available) {
        if (available.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(CATALOG_OPEN_TAG).append('\n');
        for (AgentSkill skill : available) {
            builder.append("- ").append(skill.id())
                    .append(skill.builtin() ? "" : "（用户自建）")
                    .append("：").append(bounded(skill.description())).append('\n');
        }
        return builder.append(CATALOG_CLOSE_TAG).toString();
    }

    /** 每个 {@code skill_key} 最新的那一条 ACTIVE 版本，按 key 排序。 */
    public List<AgentUserSkill> activeUserSkills(Long userId) {
        LinkedHashMap<String, AgentUserSkill> newest = new LinkedHashMap<>();
        for (AgentUserSkill skill : allActiveUserSkills(userId)) {
            newest.putIfAbsent(skill.getSkillKey(), skill);
        }
        return List.copyOf(newest.values());
    }

    /**
     * 这位用户全部 ACTIVE 的技能版本，按 (skill_key 升序, version 降序)。
     *
     * <p>唯一一处查这张表的地方，{@link AgentUserSkillService} 的读接口也走它——
     * 两处各写一遍 wrapper 的话，将来加一个 status 取值必然只改一处。</p>
     */
    public List<AgentUserSkill> allActiveUserSkills(Long userId) {
        if (userSkillMapper == null || userId == null) return List.of();
        return userSkillMapper.selectList(new LambdaQueryWrapper<AgentUserSkill>()
                .eq(AgentUserSkill::getUserId, userId)
                .eq(AgentUserSkill::getStatus, AgentUserSkill.STATUS_ACTIVE)
                .orderByAsc(AgentUserSkill::getSkillKey)
                .orderByDesc(AgentUserSkill::getVersion));
    }

    /** 指定 {@code skill_key} 最新的 ACTIVE 版本。 */
    public Optional<AgentUserSkill> activeUserSkill(Long userId, String skillKey) {
        if (!StringUtils.hasText(skillKey)) return Optional.empty();
        return activeUserSkills(userId).stream()
                .filter(skill -> skillKey.equals(skill.getSkillKey())).findFirst();
    }

    private AgentSkill userSkill(AgentUserSkill owned) {
        return new OwnedSkill(owned.getSkillKey(),
                owned.getVersion() == null ? "1" : String.valueOf(owned.getVersion()),
                owned.getDescription(), parseToolNames(owned), owned.getInstructions());
    }

    /**
     * 技能声明的工具名原样交回模型，不与注册表求交。
     *
     * <p>它只是提示"这类活通常用这些工具"，模型真正能调什么由工具表决定；
     * 一个拼错的名字最坏就是模型试着调一次、拿到"未注册工具"的错误再换。</p>
     */
    private Set<String> parseToolNames(AgentUserSkill owned) {
        if (!StringUtils.hasText(owned.getToolNamesJson())) return Set.of();
        try {
            return Set.copyOf(objectMapper.readValue(owned.getToolNamesJson(),
                    new TypeReference<List<String>>() { }));
        } catch (Exception malformed) {
            log.warn("agent skill toolNames unreadable user={} skillKey={} reason={}",
                    owned.getUserId(), owned.getSkillKey(), malformed.getMessage());
            return Set.of();
        }
    }

    private String bounded(String description) {
        String text = description == null ? "" : description.replace('\n', ' ').trim();
        return text.length() <= DESCRIPTION_MAX_CHARS ? text : text.substring(0, DESCRIPTION_MAX_CHARS) + "…";
    }

    private record OwnedSkill(String id, String version, String description, Set<String> toolNames,
                              String instructions) implements AgentSkill {
        @Override
        public boolean builtin() {
            return false;
        }
    }
}
