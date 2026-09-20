package com.xander.lab.integration.api;

/**
 * 邮件发送端口：把一段<b>已经渲染好的</b>内容投递出去。
 *
 * <h2>为什么要有这个接口</h2>
 * <p>它由 {@code integration.mail.MailService} 实现，而调用方分散在<b>另外两个部署单元</b>：
 * {@code identity}（{@code AuthService} 发登录验证码）在 {@code dinqor-core-host}、
 * {@code automation}（{@code EmailReminderScheduler} 发定时提醒）在
 * {@code dinqor-content-automation}，实现类在 {@code dinqor-platform-integration}。
 * 拆服务之后让它们 import 那个具体 {@code @Service}，就是"一个服务直接调另一个服务的内部类"
 * ——依赖方向是反的。</p>
 *
 * <h2>⭐ 端口只带"发什么"，不带"怎么渲染"</h2>
 * <p>{@code MailService} 上有一个
 * {@code sendReminderMail(to, subject, userMessage, scheduledAt, contentType, templateId)}，
 * 六个参数里有四个（{@code scheduledAt} / {@code contentType} / {@code templateId}，
 * 以及隐含的时区）<b>只服务于渲染</b>：归一化内容类型、按显示时区格式化时间、
 * 挑一个品牌化 HTML 版式、再生成纯文本兜底。</p>
 *
 * <p>于是那个方法把两件不同的事焊在了一起：</p>
 * <ul>
 *   <li><b>渲染</b>——"这封提醒长什么样"。它依赖
 *       {@code automation.domain.EmailReminderMailTemplates}（<b>业务域</b>的模板类），
 *       以及 {@code email-reminder.display-zone} 这个在配置台账里 <b>owner 记在
 *       {@code content-automation} 名下</b>的键。这两样东西都不是 integration 的。</li>
 *   <li><b>发送</b>——"把它投出去"。这才是 integration 的能力（SMTP、{@code mail.from}）。</li>
 * </ul>
 *
 * <p>结果就是提供方（integration）反过来依赖消费方（automation）的业务类型
 * ——{@code integration → automation} 这条反向边，以及"integration 读别人的配置"。
 * 本端口把渲染彻底留在调用方：{@link #sendHtmlMail} 只收<b>渲染好的</b>
 * {@code plainText} 与 {@code html}，端口上<b>一个时间参数都没有</b>。</p>
 *
 * <h2>为什么是三个方法</h2>
 * <p>每个方法都有一个具名消费方，且都是"投递"这一件事的不同载体：</p>
 * <ul>
 *   <li>{@link #sendSimpleMail} —— 纯文本单段投递。消费方：{@code automation}
 *       的提醒渲染走 PLAIN 分支时，以及同单元的 {@code RedisHealthMonitor} 告警。</li>
 *   <li>{@link #sendVerificationCode} —— 登录验证码，正文版式由邮件域自己的
 *       {@code LoginVerificationMailTemplates} 生成（它本来就住在 {@code integration.mail}）。
 *       消费方：{@code identity} 的 {@code AuthService}。</li>
 *   <li>{@link #sendHtmlMail} —— HTML 正文 + 纯文本兜底的多段投递。消费方：{@code automation}
 *       的提醒渲染走 HTML 分支时。</li>
 * </ul>
 *
 * <p>⚠️ 没有把 {@link #sendSimpleMail} 与 {@link #sendHtmlMail} 合并成一个带
 * {@code html} 可选参数的方法：两者的<b>载体不同</b>（{@code SimpleMailMessage} 与
 * MIME multipart），而且 {@code html == null} 时"退回纯文本"这种隐式回退会让
 * "渲染失败"看起来像"这封邮件本来就没有正文"。</p>
 *
 * <h2>调用方身份：<b>刻意不收</b> {@code userId}</h2>
 * <p>三个方法都不收 {@code userId}，而且<b>不许加</b>。判据是"这个参数有没有具名消费者"：</p>
 * <ul>
 *   <li>{@link #sendVerificationCode} 发生在<b>登录之前</b>——那时还没有身份，
 *       收件地址就是全部依据。</li>
 *   <li>{@link #sendSimpleMail} / {@link #sendHtmlMail} 是<b>传输</b>：它们不知道、
 *       也不需要知道收件人是谁。归属判定（"这个提醒是不是你的"）在
 *       {@code automation} 自己的库里、在调用本端口<b>之前</b>就已经做完了。</li>
 * </ul>
 * <p>⚠️ 加一个没人读的参数不是"一致性"，而是把"这里需要身份"这个错误信号写进契约，
 * 下一位读者会据此以为提供方会拿它做鉴权。由 {@code MailPortTest} 的反向守卫钉住。</p>
 *
 * <h2>失败语义（跨进程后仍然成立，实现必须遵守）</h2>
 * <ul>
 *   <li>投递失败时<b>抛异常</b>，不返回布尔——调用方（{@code EmailReminderScheduler}）
 *       靠"抛没抛"决定重试还是标失败，而"返回 false"和"忘了检查返回值"长得一样。</li>
 *   <li>⚠️ 实现<b>不得</b>把这些方法包进数据库事务。SMTP 是网络 I/O，
 *       {@code EmailReminderScheduler} 刻意在"原子认领"之后、事务之外调用它
 *       ——否则一次 SMTP 超时会占着数据库连接与行锁。</li>
 * </ul>
 *
 * <p><b>⚠️ 端口宽度没有任何东西在守。</b>加一个 {@code default} 方法不会编译报错、
 * 不会动任何耦合指标、门禁也不查。方法个数与完整签名由 {@code MailPortTest} 用反射钉住。</p>
 */
public interface MailPort {

    /**
     * 发送一封纯文本邮件。
     *
     * @param to      收件地址
     * @param subject 主题
     * @param content 正文（纯文本）
     */
    void sendSimpleMail(String to, String subject, String content);

    /**
     * 发送登录验证码邮件，正文版式由邮件域生成。
     *
     * <p>调用点发生在登录之前，所以没有、也不该有调用方身份。</p>
     *
     * @param email 收件邮箱
     * @param code  验证码
     */
    void sendVerificationCode(String email, String code);

    /**
     * 发送一封 HTML 邮件，并带一份纯文本兜底。
     *
     * <p>渲染（模板、时区格式化、PLAIN/HTML 归一化）由<b>调用方</b>完成——
     * 见类注释"端口只带'发什么'"。</p>
     *
     * @param to        收件地址
     * @param subject   主题
     * @param plainText 纯文本兜底正文（客户端不支持 HTML 时显示）
     * @param html      HTML 正文
     */
    void sendHtmlMail(String to, String subject, String plainText, String html);
}
