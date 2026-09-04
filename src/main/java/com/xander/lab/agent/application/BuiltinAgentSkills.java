package com.xander.lab.agent.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 产品自带的做法规则，进目录供模型按需读取。
 *
 * <p>这里只留<b>工作流级</b>的规则：一类活的次序、边界和交付形式。
 * 单个工具"该怎么用、什么时候别用"归 {@link AgentTool#promptSection()}——它跟着工具走，
 * 工具在表里准则就在提示词里，不需要模型先花一次调用去读。</p>
 *
 * <p>因此两个早先的技能没有留下来：{@code general-chat} 只是一句"没有明确目标就直接回答"，
 * 全局行动准则已经写了，进目录只会让模型为一句废话花一次工具调用；
 * {@code artifact-delivery} 那条"默认用代码围栏、别一律开交付卡"已经搬进
 * {@link EmitArtifactTool#promptSection()}，那才是它该在的地方——
 * 它必须无条件生效，而不是等模型想起来去读。</p>
 */
@Configuration
public class BuiltinAgentSkills {

    @Bean
    AgentSkill blogWritingSkill() {
        return skill("blog-writing",
                "写文章或草稿的做法：主题、受众、交付形式怎么定，草稿与发布的界线在哪，资料多时怎么取证。"
                        + "准备动笔写博客、文章、大纲、草稿时读它。",
                Set.of("blog_generate", "image_generate", "query_knowledge", "create_knowledge",
                        "recall_memory", "remember", "emit_artifact", "delegate_subtask"),
                "当前是内容创作任务：先理解主题、受众和交付形式；生成草稿不等于发布，发布必须由独立发布能力处理。"
                        + "需要翻查大量资料才能动笔时，把取证交给 delegate_subtask，主线只接收结论。");
    }

    @Bean
    AgentSkill contentPublishingSkill() {
        return skill("content-publishing",
                "把已有文章发到平台的做法：先确认发哪一篇、发到哪个平台，以及发布这件事的边界。"
                        + "用户要发布、发表或同步内容时读它。",
                Set.of("query_posts", "query_unsynced_posts", "publish_post"),
                "当前是内容发布任务：先确认目标文章和发布平台；发布属于外部副作用，必须经过后续审批策略。");
    }

    @Bean
    AgentSkill studyCoachSkill() {
        return skill("study-coach",
                "辅导学习的做法：怎么基于真实知识内容出题、怎么判题、判分结果怎么交回用户。"
                        + "用户要复习、测验、练习或讲题时读它。",
                Set.of("query_knowledge", "quiz_knowledge", "emit_quiz", "grade_answer",
                        "recall_memory", "remember"),
                "当前是学习辅导任务：基于真实知识内容出题或判题，不能凭空声称已经完成评分。");
    }

    @Bean
    AgentSkill knowledgeCurationSkill() {
        return skill("knowledge-curation",
                "整理知识与笔记的做法：来源怎么留、什么才值得写进长期记忆、要翻很多条目时怎么归纳。"
                        + "用户要整理笔记、知识库或让你记住一段资料时读它。",
                Set.of("query_knowledge", "create_knowledge", "recall_memory", "remember", "delegate_subtask"),
                "当前是知识整理任务：保留来源和用户边界；写入长期记忆前必须确认内容确实适合跨会话保存。"
                        + "要横向翻查很多条目才能归纳时，把检索交给 delegate_subtask，避免原始结果灌满主上下文。");
    }

    @Bean
    AgentSkill personalPlanningSkill() {
        return skill("personal-planning",
                "排计划和定提醒的做法：时间、时区、目标怎么问清楚，改动已存在的计划或提醒要注意什么。"
                        + "用户要安排日程、写计划或设定时提醒时读它。",
                Set.of("plan_tasks", "update_plan", "schedule_plan", "query_plans",
                        "email_reminder", "update_email_reminder", "query_email_reminders", "remember"),
                "当前是计划或提醒任务：明确时间、时区和目标；创建或修改计划属于持久化副作用。");
    }

    private AgentSkill skill(String id, String description, Set<String> tools, String instructions) {
        return new DefaultSkill(id, "1", description, tools, instructions);
    }

    private record DefaultSkill(String id, String version, String description, Set<String> toolNames,
                                String instructions) implements AgentSkill {
    }
}
