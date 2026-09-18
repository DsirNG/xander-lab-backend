package com.xander.lab.blogagent.spi;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * 博客生成智能体对外的跨域能力面。
 *
 * <h2>这一刀拆掉了什么</h2>
 * <p>三个工具里有两个走 {@code blogagent.application.BlogAgentService}：</p>
 * <table>
 *   <caption>消费方与其原来 import 的类型</caption>
 *   <tr><th>文件</th><th>原来 import</th></tr>
 *   <tr><td>{@code BlogGenerationTool}</td>
 *       <td>{@code blogagent.application.BlogAgentService}、
 *           {@code blogagent.api.BlogAgentTaskCreateRequest}、
 *           {@code blogagent.api.BlogAgentTaskVO}、{@code blogagent.domain.BlogAgentTask}</td></tr>
 *   <tr><td>{@code PublishPostTool}</td>
 *       <td>{@code blogagent.application.BlogAgentService}、{@code publishing.api.BlogPostVO}</td></tr>
 * </table>
 * <p>全部跨 {@code dinqor-agent} / {@code dinqor-content-automation} 部署单元边界。
 * 改完之后依赖方向是 {@code agent -&gt; blogagent-api &lt;- blogagent}，
 * 并且 {@code agent -&gt; publishing} 那条（{@code PublishPostTool} 是 {@code BlogPostVO}
 * 唯一的跨域读者）整条消失。</p>
 *
 * <h2>⚠️ 参数顺序：端口一律 userId 在首位，提供方适配器显式换位</h2>
 * <p>提供方这四个方法的声明顺序是**反的**：{@code publish(taskId, userId)}、
 * {@code findPublishedPost(taskId, userId)}、{@code runStream(taskId, userId, ...)}。
 * 本端口统一成 {@code (userId, ...)}，理由有三条：</p>
 * <ol>
 *   <li>前面四个契约（{@code EmailReminderPort}、{@code ContentPlanPort}、
 *       {@code RecitationMaterialPort}、{@code RecitationQuizPort}）全都是 userId 在首位，
 *       端口测试里那条"每个方法第一个参数是调用方 id"的守卫也是照这个写的。</li>
 *   <li>那条守卫只断言"第一个参数的类型是 {@code Long}" —— 这个断言对
 *       {@code publish(Long taskId, Long userId)} **同样成立**。也就是说，如果这里照抄提供方的
 *       顺序，守卫会绿着放过一个它自己名字所禁止的形状。这正是本项目反复踩的
 *       "判据只朝一个方向看"。所以 {@code BlogAgentPortTest} 改钉**参数名**
 *       （javac 开了 {@code -parameters}，名字会进 class 文件）。</li>
 *   <li>消费方的心智模型是"我（userId）对某个资源（taskId）做事"，而不是反过来。</li>
 * </ol>
 * <p>代价是提供方要多写一次显式换位（每个适配器一行）。**换位是这一刀最容易静默出错的地方**，
 * 所以它由 {@code BlogAgentPortMappingTest} 用互不相同的字面量（{@code 42} = userId、
 * {@code 9} = taskId）钉住 —— 写反会让 {@code 42} 当成 taskId 去查，落到"任务不存在"，
 * 是响亮的失败而不是静默的错数据；但即使响亮，也不该靠运气。</p>
 *
 * <h2>为什么没有 default 方法，也永远不该有</h2>
 * <p>给端口加一个 {@code default} 方法是"变宽"最隐蔽的形式：它能编译、耦合指标一动不动、
 * 26 条门禁全绿，只有反射测试会红。所以这里的每个方法都是抽象的，且完整签名由
 * {@code BlogAgentPortTest} 逐字钉住（方法名 + 参数类型 + 参数名 + 返回类型）。</p>
 *
 * <h2>刻意不在这里的</h2>
 * <ul>
 *   <li>图片生成。它是**另一个端口**（{@link BlogAgentImagePort}）而不是本端口的一个方法：
 *       提供方是 {@code blogagent.infrastructure.BlogAgentImageClient} —— 一个具体客户端
 *       加一个 {@code @ConfigurationProperties}，形状与这里五个"对用户资源做事"的方法完全不同，
 *       而且它是无状态的模型调用，连 {@code userId} 都不收。硬塞进来会让本端口的方法集
 *       变成两种形状的混合体，"端口宽度 = 调用方适配器被迫实现的成员集"这条判据也就失效了。</li>
 *   <li>{@code generateTopicSeries}：唯一调用方是 {@code automation.BlogContentPlanService}，
 *       与 blogagent **同属 {@code dinqor-content-automation}** ⇒ 改它不动任何跨单元指标。</li>
 *   <li>{@code listSessions} / {@code reviseStream} / {@code reviewCompliance} /
 *       {@code runStream(Long, Long, Consumer)}：调用方都是 {@code BlogAgentController}
 *       （同域前端 HTTP 层）。</li>
 * </ul>
 */
public interface BlogAgentPort {

    /**
     * 创建一个博客生成任务；带 {@code clientRequestId} 时按用户幂等。
     *
     * <p>不启动生成 —— 启动是 {@link #runStream}。调用方拿到的任务是 {@code created} 状态。</p>
     *
     * @param userId 调用方 id，显式传入（智能体轮次在线程池线程上跑，ThreadLocal 不跨线程）
     * @param draft  任务入参
     * @return 新建的或幂等命中的那个任务
     */
    BlogAgentTaskView create(Long userId, BlogAgentTaskDraft draft);

    /**
     * 跑完整条流水线（analyze → research → write → illustrate → review），边跑边推事件。
     *
     * <p>事件用两元组传：{@code event} 是事件名（{@code stage} / {@code delta}），
     * {@code data} 是载荷。{@code stage} 的载荷形如 {@code "write|正在撰写文章"}，
     * 调用方负责把 stage 段翻译成用户可读的文案 —— **端口不替调用方决定文案**，
     * 那是渲染方的策略。</p>
     *
     * <p>取消是协作式的：{@code cancellationRequested} 在每个远程阶段之间被查询，
     * 不是在任意点中断。模型调用本身不会被强行打断。</p>
     *
     * @param userId                调用方 id
     * @param taskId                要跑的任务 id
     * @param onEvent               事件回调，非空
     * @param cancellationRequested 取消查询，非空；返回 true 时在下个安全边界停下
     * @return 跑完后的任务视图
     */
    BlogAgentTaskView runStream(Long userId, Long taskId, BiConsumer<String, String> onEvent,
                                BooleanSupplier cancellationRequested);

    /**
     * 按幂等键查一个**已完成**的任务，用于工具重放时确认上次的产出。
     *
     * <p>只返回 {@code ready} 状态的任务：{@code created} / {@code running} 说明那次调用还没落地，
     * 调用方应当返回"未知"而不是"没做过"。</p>
     *
     * @param userId          调用方 id
     * @param clientRequestId 幂等键；空或 {@code null} 时返回 {@code null}
     * @return 已完成的任务，或 {@code null}
     */
    BlogAgentTaskView findReadyTaskByClientRequestId(Long userId, String clientRequestId);

    /**
     * 只读地取回一个生成任务已发布的本地文章，**不改变任务或文章的状态**。
     *
     * @param userId 调用方 id
     * @param taskId 生成任务 id
     * @return 已发布文章的视图；任务还没发布过时返回 {@code null}
     */
    BlogAgentPostView findPublishedPost(Long userId, Long taskId);

    /**
     * 把一个已完成的任务发布到本站，并记下 {@code publishedPostId}。
     *
     * <p>幂等：任务已有 {@code publishedPostId} 时直接返回那篇文章，不重复发布。</p>
     *
     * <p>⚠️ 这是**有副作用且不可撤销**的动作，调用方必须先取得用户确认。</p>
     *
     * @param userId 调用方 id
     * @param taskId 生成任务 id
     * @return 发布后的本地文章视图
     */
    BlogAgentPostView publish(Long userId, Long taskId);
}
