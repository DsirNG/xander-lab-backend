package com.xander.lab.automation.spi;

/**
 * 写一条<b>持久化</b>用户通知的能力。
 *
 * <p>唯一消费方是 {@code knowledgebase}：{@code KnowledgeIngestionService} 在大部头文档
 * 不自动生成结构图时，发一条「阅读某一章时会为该章单独生成」的通知。通知表
 * {@code blog_notification} 归 {@code automation} 所有（见 {@code knowledgebase/package-info.java}
 * 的「不拥有」段落），所以 knowledgebase 只能通过这个端口写。</p>
 *
 * <h2>为什么是端口，不是把类型搬过来</h2>
 * <p>这条缝两端的类型都不满足「搬进契约」的条件：</p>
 * <ul>
 *   <li>{@code automation.domain.BlogNotification} 带 {@code @TableName("blog_notification")}
 *       —— 是<b>域实体</b>。域实体不许跨有界上下文边界；把它放进契约等于把
 *       MyBatis 注解拖进每个依赖方的 classpath。</li>
 *   <li>{@code automation.application.BlogNotificationService} 是 {@code @Service}，
 *       是<b>具体实现</b>，按抽取判据第二种情况处理：抽端口，实现留在原地。</li>
 * </ul>
 *
 * <h2>两个常量为什么跟着端口搬走</h2>
 * <p>{@link #TYPE_KNOWLEDGE_STRUCTURE_PENDING} 与 {@link #RESOURCE_KNOWLEDGE_FILE}
 * 原本声明在 {@code BlogNotification} 实体上。抽端口时它们<b>必须一起搬走</b>，
 * 否则消费方为了拿这两个常量仍然要 import 那个实体，实现语句一条都减不掉 —— 端口就白抽了。
 * 判据是：{@code grep -rn "import com.xander.lab.automation" <消费方包>} 的结果只剩契约包。</p>
 *
 * <p>搬得干净是因为它们在 {@code automation} 内部<b>没有任何使用者</b>：另外四个
 * {@code TYPE_*}（{@code REVIEW_REJECTED} / {@code CSDN_FAILED} / {@code JUEJIN_FAILED} /
 * {@code RUN_FAILED}）由 {@code BlogPlanRunService} 使用，属于 automation 自己的词表，
 * <b>刻意留在实体上</b>，不进这个端口 —— 端口只带消费方真正用到的东西。</p>
 *
 * <p>⚠️ 这两个常量的<b>字面量</b>是落库的：{@code blog_notification.type} 与
 * {@code .resource_type} 里存的就是这两个字符串，前端也按它们分流。所以契约测试除了钉常量存在，
 * 还要钉字面量本身（见 {@code NotificationPortTest}）—— 改字面量不是重构，是数据迁移。</p>
 *
 * <h2>为什么返回值是 {@code Long} 而不是 {@code void}</h2>
 * <p>与实现同形。{@code BlogNotificationService#notifyResource} 的签名逐字相同，
 * 于是提供方那边的改动退化成「加 {@code implements} + 加 {@code @Override}」这一处
 * <b>纯类型替换</b>，可证明行为不变。若在这里收窄成 {@code void}，Java 不允许
 * 同名同参只差返回值，实现侧就必须<b>改名</b> —— 那是与这条缝无关的改动，白增风险。</p>
 *
 * <p>返回的 id 也是这次操作的自然结果（「写一条通知，得到它的 id」），不是实现细节；
 * 当前唯一消费方忽略它，所以这点富余是<b>惰性</b>的。真出现第二个消费方时再讨论收窄。</p>
 *
 * <h2>刻意不放进这个端口的</h2>
 * <ul>
 *   <li><b>读侧</b>：{@code list} / {@code unreadCount} / {@code markRead} / {@code markAllRead}
 *       —— 它们服务的是前端 Navbar 铃铛（{@code automation.api} 的控制器），不是跨域消费方。</li>
 *   <li><s><b>SSE 广播</b>：{@code BlogPlanSseService} 要的是 {@code SseEmitter}（框架类型），
 *       形状完全不同，且消费方是 {@code admin}。</s>
 *       ✅ <b>已实现</b>：{@link NotificationBroadcastPort}。
 *       ⚠️ 当时这句话对<b>类</b>成立、对<b>边</b>不成立：{@code subscribe} 返回
 *       {@code SseEmitter} 从来没跨过边界，{@code admin} 只调 {@code broadcast}
 *       （三个 {@code String} 进、一个 {@code int} 出）。教训记在
 *       {@code NotificationBroadcastPort} 的类注释里。</li>
 * </ul>
 *
 * <p>⚠️ 本端口<b>不承诺</b>事务语义。实现是 {@code REQUIRES_NEW}（通知只是旁路提醒，
 * 写不进去不该回滚调用方主流程），但那是实现方的选择；消费方仍然自己兜底 try/catch，
 * 因为它要保证的是「通知发不出去不影响文件本身可用」。</p>
 *
 * <p>⚠️ 端口不许有 {@code default} 方法：那是最隐蔽的变宽方式 —— 编译通过、指标不动、
 * 门禁不报，只有反射测试能拦。</p>
 */
public interface NotificationPort {

    /** 大部头知识库文件未自动生成结构图，提示用户按章生成。 */
    String TYPE_KNOWLEDGE_STRUCTURE_PENDING = "KNOWLEDGE_STRUCTURE_PENDING";

    /** 通用关联资源类型：知识库文件。 */
    String RESOURCE_KNOWLEDGE_FILE = "KNOWLEDGE_FILE";

    /**
     * 为「非计划类」业务写一条带回跳资源的通知。
     *
     * @param userId       收件人；实现方对 {@code null} 抛 401
     * @param type         通知类型，见本接口的 {@code TYPE_*} 常量
     * @param title        标题
     * @param message      正文
     * @param resourceType 关联资源类型，见 {@link #RESOURCE_KNOWLEDGE_FILE}
     * @param resourceId   关联资源主键
     * @return 新建通知的主键
     */
    Long notifyResource(Long userId, String type, String title, String message,
                        String resourceType, Long resourceId);
}
