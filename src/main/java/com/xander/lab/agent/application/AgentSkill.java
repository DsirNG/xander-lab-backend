package com.xander.lab.agent.application;

import java.util.Set;

/**
 * 一份可按需读取的做法规则。
 *
 * <p>技能<b>不是能力开关</b>：工具表与它无关，每轮都是完整的。技能只回答"这类活该怎么干"，
 * 由模型自己从目录里认领，用 {@link LoadSkillTool} 把正文读回来。</p>
 *
 * <p>早先这里有一个 {@code matchScore(String)}：服务端按关键词给每个技能打分，命中的技能
 * 决定这一轮的工具表。于是"给我一个 sse 代码"只命中交付类技能，模型当轮只剩 emit_artifact，
 * 除了开交付卡别无选择；反过来"帮我写个服务端"一个词都不命中，连交付卡都拿不到。
 * 关键词判不出意图，却替模型把决定做完了，所以它整个消失了。</p>
 */
public interface AgentSkill {

    /** kebab-case 稳定标识，也是模型在 load_skill 里报的名字。 */
    String id();

    String version();

    /**
     * 一句话说明"什么时候该读它"，进目录供模型自己路由。
     *
     * <p>不是正文摘要——正文归 {@link #instructions()}，只有真被读取时才进上下文。
     * 目录每轮都在提示词里，所以这句话要短。</p>
     */
    String description();

    /** 这类活通常要用到的工具，随读取结果一起交回模型作提示；它不收窄工具表。 */
    Set<String> toolNames();

    /** 完整正文，只有模型显式读取时才进上下文。 */
    String instructions();

    /** 内置技能是产品自带的可信能力；用户私有技能来自 {@code agent_skill} 表。 */
    default boolean builtin() {
        return true;
    }
}
