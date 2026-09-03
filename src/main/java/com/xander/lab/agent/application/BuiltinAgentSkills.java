package com.xander.lab.agent.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/** First-party skill plugins; external uploaded skills are intentionally not executable in this phase. */
@Configuration
public class BuiltinAgentSkills {

    @Bean
    AgentSkill generalChatSkill() {
        return skill("general-chat", Set.of(),
                "没有明确业务目标时直接回答；不要把猜测当成已经执行的操作。", 0,
                "general conversation");
    }

    @Bean
    AgentSkill blogWritingSkill() {
        return skill("blog-writing", Set.of("blog_generate", "image_generate", "query_knowledge",
                        "create_knowledge", "recall_memory", "remember", "emit_artifact"), 3,
                "当前是内容创作任务：先理解主题、受众和交付形式；生成草稿不等于发布，发布必须由独立发布能力处理。",
                "blog/article/draft/content request");
    }

    @Bean
    AgentSkill contentPublishingSkill() {
        return skill("content-publishing", Set.of("query_posts", "query_unsynced_posts", "publish_post"), 3,
                "当前是内容发布任务：先确认目标文章和发布平台；发布属于外部副作用，必须经过后续审批策略。",
                "publish/sync content request");
    }

    @Bean
    AgentSkill studyCoachSkill() {
        return skill("study-coach", Set.of("query_knowledge", "quiz_knowledge", "emit_quiz", "grade_answer",
                        "recall_memory", "remember"), 3,
                "当前是学习辅导任务：基于真实知识内容出题或判题，不能凭空声称已经完成评分。",
                "study/review/quiz request");
    }

    @Bean
    AgentSkill knowledgeCurationSkill() {
        return skill("knowledge-curation", Set.of("query_knowledge", "create_knowledge", "recall_memory", "remember"), 3,
                "当前是知识整理任务：保留来源和用户边界；写入长期记忆前必须确认内容确实适合跨会话保存。",
                "knowledge/note/memory request");
    }

    @Bean
    AgentSkill personalPlanningSkill() {
        return skill("personal-planning", Set.of("plan_tasks", "update_plan", "schedule_plan", "query_plans",
                        "email_reminder", "update_email_reminder", "query_email_reminders", "remember"), 3,
                "当前是计划或提醒任务：明确时间、时区和目标；创建或修改计划属于持久化副作用。",
                "plan/reminder/schedule request");
    }

    @Bean
    AgentSkill artifactDeliverySkill() {
        return skill("artifact-delivery", Set.of("emit_artifact"), 3,
                "当前需要交付可复制或下载的代码/文件：多文件结果必须通过 artifact 工具持久化，不能只声称已经生成。",
                "code/project/file delivery request");
    }

    private AgentSkill skill(String id, Set<String> tools, String instructions, int score, String reason) {
        return new DefaultSkill(id, "1", tools, score, instructions, reason);
    }

    private AgentSkill skill(String id, Set<String> tools, int score, String instructions, String reason) {
        return skill(id, tools, instructions, score, reason);
    }

    private record DefaultSkill(String id, String version, Set<String> toolNames, int baseScore,
                                String instructions, String reason) implements AgentSkill {
        @Override
        public int matchScore(String userMessage) {
            String text = userMessage == null ? "" : userMessage;
            return switch (id) {
                case "blog-writing" -> score(text, "博客", "文章", "草稿", "大纲", "blog", "article", "写作");
                case "content-publishing" -> score(text, "发布", "发表", "同步", "publish", "上线");
                case "study-coach" -> score(text, "学习", "复习", "出题", "测验", "练习", "答题", "quiz", "study");
                case "knowledge-curation" -> score(text, "知识库", "笔记", "记住", "记忆", "资料", "知识", "note", "memory");
                case "personal-planning" -> score(text, "计划", "提醒", "日程", "安排", "定时", "plan", "reminder", "schedule");
                case "artifact-delivery" -> score(text, "代码", "源码", "项目", "网页", "脚本", "组件", "实现", "code", "build");
                default -> 0;
            };
        }

        private int score(String text, String... terms) {
            int matches = 0;
            for (String term : terms) if (text.contains(term)) matches++;
            return matches == 0 ? 0 : Math.min(baseScore, matches + 1);
        }

        @Override
        public String matchReason(String userMessage) {
            return reason;
        }
    }
}
