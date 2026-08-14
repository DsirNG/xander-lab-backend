package com.xander.lab.billing.domain;

/** 计费目录枚举：与扣费调用点一一对应，作为 points_ledger.biz_type 的值。 */
public final class BillingBiz {

    private BillingBiz() {
    }

    /** 注册赠送 */
    public static final String GRANT_REGISTER = "grant";

    /** 博客智能体：一次成功生成（run） */
    public static final String BLOG_AGENT_RUN = "blog-agent-run";

    /** 博客智能体：一次成功改写（revise） */
    public static final String BLOG_AGENT_REVISE = "blog-agent-revise";

    /** 博客智能体：一次合规审查调用 */
    public static final String BLOG_AGENT_COMPLIANCE = "blog-agent-compliance";

    /** 博客智能体：生成一张知识插图（一口价） */
    public static final String BLOG_AGENT_ILLUSTRATION = "blog-agent-illustration";

    /** AI 选题生成 */
    public static final String BLOG_TOPIC = "blog-topic";

    /** Agent 对话：一轮执行 */
    public static final String AGENT_TURN = "agent-turn";

    /** 图生 3D：一次成功重建 */
    public static final String IMG2THREE = "img2three";

    /** 定时邮件提醒：一次实际发送 */
    public static final String EMAIL_REMINDER = "email-reminder";

    /** CSDN 发布：一次成功发文 */
    public static final String CSDN_PUBLISH = "csdn-publish";

    /** 掘金发布：一次成功发文 */
    public static final String JUEJIN_PUBLISH = "juejin-publish";
}