package com.xander.lab.payment.api;

/**
 * 计费目录：与扣费调用点一一对应，作为 {@code points_ledger.biz_type} 的值。
 *
 * <p>名字叫"枚举"但用的是常量类 —— 这些值要落库，也要出现在流水视图里，
 * 用 {@code String} 才能在不迁移数据的前提下新增业务项。</p>
 *
 * <h2>为什么这个类在契约里</h2>
 *
 * <p>它不是"billing 域的内部枚举"。**每一个扣费调用点都必须说出自己在为什么收费**，
 * 而这些调用点分布在六个域、三个部署单元里（agent / integration / automation /
 * blogagent / studio，以及 billing 自己）。拆分之后，那四个服务要么各自复制一份词表
 * （然后就一定会漂移），要么反向依赖另一个服务的内部 —— 两条路都不能走。
 * 所以词表本身是契约。</p>
 *
 * <h2>⚠️ 改动这些字面量等于改契约</h2>
 *
 * <p>它们被持久化为 {@code points_ledger.biz_type} 的列值。改名**不会编译报错**，
 * 只会让库里已有的流水行静默失配：统计与对账按旧值查不到，而新的扣费照常写入。
 * 同时 {@code BillingService.isCreditBiz()} 按 {@link #GRANT_REGISTER} 与
 * {@link #MERGE_CREDIT} 判断一笔流水是收入还是消耗 —— 新增一个"收入类"业务项时
 * 忘了同步那处判断，余额方向就会反。{@code BillingBizTest} 把全部字面量逐个钉住。</p>
 */
public final class BillingBiz {

    private BillingBiz() {
    }

    /** 注册赠送 */
    public static final String GRANT_REGISTER = "grant";

    /** 账号合并转入（微信账号并入既有 PC 账号时的余额转移） */
    public static final String MERGE_CREDIT = "merge-credit";

    /** 博客智能体：一次成功生成（run） */
    public static final String BLOG_AGENT_RUN = "blog-agent-run";

    /** 博客智能体：一次成功改写（revise） */
    public static final String BLOG_AGENT_REVISE = "blog-agent-revise";

    /** 博客智能体：一次合规审查调用 */
    public static final String BLOG_AGENT_COMPLIANCE = "blog-agent-compliance";

    /** 博客智能体：生成一张知识插图，按图片模型实际 token 用量计费。 */
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
