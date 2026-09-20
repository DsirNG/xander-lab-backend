package com.xander.lab.automation.spi;

/**
 * 向<b>所有当前在线</b>的连接广播一条<b>瞬时</b>消息，并返回接收到的连接数
 * （V11 Wave 0B 第二十刀）。
 *
 * <p>唯一消费方是 {@code admin}：{@code AdminNotificationController#testBroadcast}
 * 向在线客户端推一条诊断广播，用来验证实时通道是通的。</p>
 *
 * <h2>为什么是端口，而且是一条<b>窄</b>端口</h2>
 * <p>提供方 {@code automation.infrastructure.BlogPlanSseService} 是 {@code @Component}
 * —— 具体实现，按抽取判据第二种情况处理：抽端口，实现留在原地。但它还有一层更硬的约束：
 * 同一个类里的 {@code subscribe(Long userId)} 返回 {@code SseEmitter}，那是<b>框架类型</b>。
 * 按第三种判据（"接口但签名带框架类型"），整个类<b>碰不得</b> —— 把 {@code SseEmitter}
 * 放进契约会把 {@code spring-webmvc} 拖进每个依赖方的 classpath，而跨进程也传不过去。</p>
 *
 * <p>⭐ <b>但"整个类碰不得"不等于"其中一条方法碰不得"。</b> 消费方真正碰的那个方法
 * （{@code broadcast}）本来就不带框架类型：它收三个 {@code String}、返回 {@code int}。
 * 所以这里立一条窄端口，只带这一个方法 —— 这正是"端口宽度 = 调用方适配器被迫实现的成员集"
 * 的应用：{@code subscribe} / {@code push} / 连接注册表 / 封禁校验全部留在实现里，
 * 契约面只有一行。</p>
 *
 * <p>⚠️ 本包 {@code package-info} 与 {@link NotificationPort} 的注释此前都写着
 * "SSE 广播端口要的是 {@code SseEmitter}（框架类型），形状完全不同"。那句话对<b>这个类</b>是对的，
 * 对<b>这条边</b>是错的 —— 边只经过 {@code broadcast}。记在这里，免得下次又照着那句话绕过去。</p>
 *
 * <h2>为什么是三个参数，而不是与实现同形的五个</h2>
 * <p>实现里的签名曾是
 * {@code broadcast(String type, String title, String message, Long planId, Long runId)}，
 * 那两个 {@code Long} 是<b>blog-plan 域的概念</b>（一条发文计划、一次执行），
 * 而唯一的调用方（{@code admin} 的诊断端点）永远传 {@code null}。</p>
 *
 * <p>⚠️ <b>这里刻意没有沿用 {@link NotificationPort} 那条"与实现同形"的先例，两者情形不同：</b>
 * 那边的富余是<b>返回值</b>（消费方忽略它，不写任何东西），这边的富余是<b>参数</b>
 * —— 消费方被迫写一个自己永远不用的 {@code null}。这正是 §16.2 的反面判据
 * （"合并要么让一条路收下它永远不用的参数，要么砍掉另一条路的能力"）。
 * 一个接口要求调用方每次都撒谎，不是"惰性富余"，是<b>描述错了</b>。</p>
 *
 * <p>收窄<b>可证是行为保持的</b>：{@code broadcast} 只是把参数转发给 {@code push}，
 * 而 {@code push} 把 {@code null} 的 {@code planId} / {@code runId} 序列化成空串
 * —— 线上载荷逐字节不变。由 {@code BlogPlanSseServiceTest} 里那条
 * "扇出时 planId / runId 为 null" 的用例钉住。</p>
 *
 * <h2>为什么不与 {@link NotificationPort} 合并</h2>
 * <p>两个端口都带 "notification"，但能力不同：{@link NotificationPort} 是<b>写一条持久化</b>
 * 通知（落 {@code blog_notification} 表，离线用户回来能看到），本端口是<b>向在线连接推一条瞬时</b>
 * 消息（没人连着就等于没发）。方法表也不交叠，没有一条签名是共用的。
 * 合并出来的那个端口会让 {@code BlogNotificationService} 被迫实现"广播"、
 * 让 {@code BlogPlanSseService} 被迫实现"落库写通知" —— 两个类都得收下自己永远不用的能力。</p>
 *
 * <h2>为什么返回值是 {@code int} 而不是 {@code void}</h2>
 * <p>与实现同形（{@code BlogPlanSseService#broadcast} 的签名逐字相同），于是提供方那边的改动
 * 退化成"加 {@code implements} + 加 {@code @Override}"这一处<b>纯类型替换</b>，可证明行为不变。
 * 而且接收连接数确实是这次操作的自然结果（"广播了，多少个连接收到"），
 * 消费方也真的用它 —— {@code AdminNotificationController} 把它放进 {@code Result<Integer>}
 * 返回给管理台。它<b>不是</b>实现细节：管理台要的就是"刚才那条广播有没有人收到"。</p>
 *
 * <h2>不承诺的事</h2>
 * <ul>
 *   <li><b>不承诺送达。</b> 只发给广播那一刻已注册的连接；离线用户、已断开的标签页收不到。
 *       持久化兜底是通知表（{@link NotificationPort}）的职责，不是本端口的。</li>
 *   <li><b>不承诺事务。</b> 实现只碰内存里的连接注册表，不写库。</li>
 *   <li><b>不承诺顺序 / 去重。</b> 同一个用户的多个标签页各算一个接收连接，
 *       所以返回值是<b>连接数</b>而不是<b>用户数</b>。</li>
 * </ul>
 *
 * <p>⚠️ 端口不许有 {@code default} 方法：那是最隐蔽的变宽方式 —— 编译通过、指标不动、
 * 门禁不报，只有反射测试能拦。</p>
 */
public interface NotificationBroadcastPort {

    /**
     * 向所有当前在线的连接推送一条瞬时消息。
     *
     * @param type    消息类型，前端按它分流（如 {@code SSE_TEST_BROADCAST}）；{@code null} 时
     *                实现回退成 {@code NOTIFICATION}
     * @param title   标题；{@code null} 视为空串
     * @param message 正文；{@code null} 视为空串
     * @return 本次广播覆盖的<b>连接</b>数（一个用户的多个标签页各算一个）；没有任何连接时返回 0
     */
    int broadcast(String type, String title, String message);
}
