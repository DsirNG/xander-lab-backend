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
                Set.of("blog_generate", "image_generate", "image_resend", "query_knowledge", "create_knowledge",
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

    @Bean
    AgentSkill visualContentSkill() {
        return skill("visual-content",
                "生成或完善配图、封面和知识视觉内容的做法：先确认用途、受众、构图和文字，再决定生成图片还是交付可编辑素材。"
                        + "用户要配图、封面、插图或视觉化表达时读它。",
                Set.of("image_generate", "image_resend", "query_knowledge", "create_knowledge", "emit_artifact"),
                "当前是视觉内容任务：先确认图片用于封面、文章插图还是独立素材，保留用户要求的主体、风格、构图和文字。"
                        + "生成图片时把关键要求写进 prompt；生成后说明素材已经保存的位置。"
                        + "如果用户需要可继续编辑的图表、交互页面或多文件示例，使用 emit_artifact 交付完整文件；"
                        + "简单问答或一段短代码不要为了形式调用交付工具。"
                        + "不要把图片生成能力承诺成已启用的外部服务，先根据工具结果报告成功或失败。");
    }

    @Bean
    AgentSkill imagegenSkill() {
        return skill("imagegen",
                "使用图像生成能力创建或编辑项目图片；需要新图片、局部编辑、透明背景或参考图时读取。",
                Set.of("image_generate", "image_resend"),
                "Use the image generation tool when the user asks to make or edit a raster image for the project. "
                        + "Preserve the requested subject, composition, text, and edit invariants. "
                        + "Inspect the result before reporting it and save project-bound assets in the project workspace. "
                        + "Do not replace existing assets unless the user explicitly requests replacement.");
    }

    @Bean
    AgentSkill interactiveArtifactSkill() {
        return skill("interactive-artifact",
                "制作可运行的交互式图表、演示页面或数据探索素材的做法：先确定输入、交互和交付边界，再交付完整文件。"
                        + "用户要可视化、交互演示、数据探索器或可下载网页时读它。",
                Set.of("query_knowledge", "query_posts", "emit_artifact"),
                "当前是交互式交付任务：先明确数据来源、主要观察目标和需要的交互；"
                        + "能用现有数据就不要编造事实，数据不足时明确标注示例数据。"
                        + "交付时给出可直接运行的完整文件、入口和运行提示；文件之间有依赖时一起交付。"
                        + "保持键盘可用、文本可读和移动端基本可用。"
                        + "用户只要解释概念或查看一个短例子时，直接在回复中回答，不要创建交付卡。");
    }

    private AgentSkill skill(String id, String description, Set<String> tools, String instructions) {
        return new DefaultSkill(id, "1", description, tools, instructions);
    }

    private record DefaultSkill(String id, String version, String description, Set<String> toolNames,
                                String instructions) implements AgentSkill {
    }
}
