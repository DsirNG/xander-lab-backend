package com.xander.lab.identity.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 账号合并时迁移临时账号名下用户数据的目标表。
 *
 * <p>微信账号合并属于账号生命周期操作，由 identity 模块独占执行：把「先跳过绑定再补绑」时
 * 创建的微信临时账号名下各业务数据归属迁到既有 PC 账号，再删除临时账号行。为此需要更新
 * 其他模块拥有的表——这是唯一被允许的跨表归属迁移点（子记录通过外键级联跟随父记录，无需逐行迁移）。</p>
 */
@Mapper
public interface AccountMergeMapper {

    @Update("UPDATE agent_conversation SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentAgentConversations(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE blog_agent_task SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentBlogAgentTasks(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE blog_content_plan SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentBlogContentPlans(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE blog_notification SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentBlogNotifications(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE blog_publish_record SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentBlogPublishRecords(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE blog_media_asset SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentBlogMediaAssets(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE email_reminder_task SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentEmailReminders(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE img2three_task SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentImg2threeTasks(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE flow_project SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentFlowProjects(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE flow_ai_task SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentFlowAiTasks(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE studio_project SET user_id = #{toUserId} WHERE user_id = #{fromUserId}")
    int reparentStudioProjects(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);
}