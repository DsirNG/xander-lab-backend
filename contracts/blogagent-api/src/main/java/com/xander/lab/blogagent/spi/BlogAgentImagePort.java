package com.xander.lab.blogagent.spi;

/**
 * 博客生成智能体的<b>图片生成</b>能力面。
 *
 * <h2>为什么是第二个端口，而不是加在 {@link BlogAgentPort} 上</h2>
 *
 * <p>因为形状完全不同。{@link BlogAgentPort} 的五个方法都是"对一个用户拥有的资源做事"
 * （任务、文章），提供方是 {@code BlogAgentService} 的领域方法；而图片生成提供方是
 * {@code blogagent.infrastructure.BlogAgentImageClient} —— 一个<b>具体客户端</b>
 * （{@code RestClient} + {@code HttpClient} + 端点兜底），加上一个
 * {@code @ConfigurationProperties}（{@code BlogAgentProperties}）。</p>
 *
 * <p>按抽取判据，{@code @Component} 的具体实现 ⇒ <b>抽端口</b>，实现留在原地；
 * {@code @ConfigurationProperties} 是框架类型 ⇒ <b>搬不动</b>。所以消费方读到的
 * 两样东西（客户端 + 配置）里，只有"能力"该过界，"配置"不该 —— 配置项改名、缺省、
 * 整个不存在（网关路径）都是提供方的自由，而契约不许有这种自由。</p>
 *
 * <h2>⚠️ 这个端口不收 {@code userId}，这是刻意的，不是漏了</h2>
 *
 * <p>前面五个端口的每一个方法都以 {@code userId} 打头，并且有一条守卫守着
 * （{@code BlogAgentPortTest} 里断言第一个参数<b>名字</b>是 {@code userId}）。
 * 这里是那条守卫的<b>唯一例外</b>，理由具体而不是"图省事"：</p>
 *
 * <ul>
 *   <li>提供方是一次<b>无状态的模型调用</b> —— 它不查库、不读 {@code UserContext}、
 *       不按用户分片，给它一个 {@code userId} 只会得到一个没人用的参数。</li>
 *   <li>这一路上真正需要身份的两步都在<b>别的端口</b>上，而且都显式收了：
 *       {@code MediaAssetPort.recordGenerated(userId, ...)} 把图片存进谁的名下，
 *       {@code BillingPort.chargeTokens(userId, ...)} 扣谁的积分。身份没有丢，
 *       只是没在不需要它的那一跳上重复。</li>
 *   <li>加一个不用的参数不是"一致性"，而是把"这里需要身份"这个错误信号
 *       写进契约 —— 下一个读代码的人会去找它到底在哪儿被用上。</li>
 * </ul>
 *
 * <p>守卫是<b>双向</b>的：{@code BlogAgentImagePortTest.takesNoCallerIdAndThatIsDeliberate}
 * 断言这个端口里<b>没有</b>任何参数叫 {@code userId}，并附上上面这段理由。
 * 所以以后有人"顺手补上 userId"时，红的是那条测试而不是别的什么。</p>
 *
 * <h2>为什么没有 {@code default} 方法，也永远不该有</h2>
 *
 * <p>给端口加一个 {@code default} 方法是"变宽"最隐蔽的形式：它能编译、耦合指标一动不动、
 * 26 条门禁全绿，只有反射测试会红。所以这里的每个方法都是抽象的，且完整签名由
 * {@code BlogAgentImagePortTest} 逐字钉住（方法名 + 参数类型 + 参数名 + 返回类型）。</p>
 *
 * <h2>刻意不在这里的</h2>
 *
 * <ul>
 *   <li>端点选择、兜底重试、下载地址校验、10MB 上限、{@code usage} 解析 ——
 *       全是提供方的实现细节，消费方一个都不需要知道。</li>
 *   <li>插图风格文案。{@link #generate} 收的就是<b>拼好的 prompt</b>；
 *       "独立配图"的风格由消费方自己拼（{@code ImageGenerationTool.TOOL_IMAGE_STYLE}），
 *       "知识插图"的风格由同域的 {@code BlogAgentService} 自己拼。两者刻意不同，
 *       端口不该替任何一方决定措辞。</li>
 *   <li>{@code maxIllustrations} / {@code staleTaskMinutes} / 超时 —— 与图片生成能力无关。</li>
 * </ul>
 */
public interface BlogAgentImagePort {

    /**
     * 图片生成能力当前是否可用（模型已配置）。
     *
     * <p>提供方有两套配置来源（管理台功能模型配置 / 环境变量），未配置时<b>返回 {@code false}
     * 而不是抛异常</b> —— 消费方据此给出"未配置模型，暂不能生成图片"这类可读的失败，
     * 而不是把一次配置缺失报成一次调用故障。</p>
     *
     * @return 可生成时 true
     */
    boolean isEnabled();

    /**
     * 按 prompt 生成一张图片，返回图片本体与描述它怎么被生成的元数据。
     *
     * <p>提供方内部可能在多个候选端点之间兜底重试；重试属于已处理的降级，
     * 只有<b>全部</b>端点都失败才抛出。抛出的异常消息已经过脱敏与截断，
     * 消费方可以直接把它当作"原因未知的调用失败"处理，<b>不要</b>把原始报文透给用户。</p>
     *
     * <p>⚠️ 这是有外部副作用（消耗额度）且耗时较长的动作，消费方必须在调用前后
     * 各查一次取消。</p>
     *
     * @param prompt 完整的图片描述，非空；风格前缀由调用方自己拼
     * @return 生成的图片；{@link BlogAgentImage#model()} 是实际端点模型，
     *         {@link BlogAgentImage#size()} 是请求尺寸
     */
    BlogAgentImage generate(String prompt);
}
