package com.xander.lab.payment.api;

/**
 * 积分账本的跨域端口：七个域要说的全部话。
 *
 * <h2>为什么端口在契约里，而不是留在 billing 域</h2>
 *
 * <p>{@code BillingService} 被 <b>7 个域</b>引用，散在 <b>4 个部署单元</b>里：
 * agent（{@code dinqor-agent}）、integration（{@code dinqor-platform-integration}）、
 * automation / blogagent（{@code dinqor-content-automation}），以及同单元的
 * identity / profile / studio。它是具体 {@code @Service}、拖三个 MyBatis mapper，
 * 所以只能**抽端口**、不能搬（搬走的是实体和 mapper，不是这条边）。</p>
 *
 * <p>端口放在这里而不是 {@code billing.application}，是因为拆分之后 agent 那个进程
 * <b>不再有 billing 的类路径</b>。把接口留在 billing 域里，等于让 agent 反向依赖
 * 另一个服务的内部 —— 依赖方向又反了，而且这种反了的方向不会让任何指标变红。</p>
 *
 * <h2>为什么是 {@link TokenUsage} 而不是 {@code com.xander.lab.common.ModelUsage}</h2>
 *
 * <p>{@code ModelUsage} 就在那儿、字段一模一样、调用方手上拿的也是它，复用显然更省事。
 * 但它住在 {@code libs/xander-model}，那个模块为 {@code ModelUsage.fromJson(JsonNode)}
 * 声明了 {@code jackson-databind}；契约只许 import JDK（门禁
 * {@code contract-dependency-boundary}），所以契约 import 不到它。</p>
 *
 * <p>这不是"门禁太死"，而是<b>契约模块必须与序列化框架无关</b>。
 * 让契约依赖一个带 Jackson 的库，下一步就是它依赖一个带 MyBatis 的库，再下一步
 * 就是"超级 common.jar"换个名字回来。代价是调用方在边界上做一次三字段换算 ——
 * 这个代价是明写的，不是漏掉的，见 {@link TokenUsage}。</p>
 *
 * <h2>端口语义（实现必须满足，且与实现是否启用无关）</h2>
 *
 * <ul>
 *   <li><b>幂等</b>：{@code bizKey} 是幂等键，落库唯一。同一 {@code bizKey} 重复扣费
 *       只记一次（重试、并发都算），实现靠唯一键冲突吞掉第二次。</li>
 *   <li><b>计费在成功哨点</b>：调用方只在业务真正成功之后才调这些方法，所以
 *       <b>没有退款链路</b>。不要在开始前扣费。</li>
 *   <li><b>计费总开关</b>：实现有一个全局开关，关闭时所有写方法静默返回
 *       （不抛异常）。调用方不需要自己判断开关。</li>
 *   <li><b>userId 为 null</b>：所有方法都容忍，静默返回，不抛。</li>
 *   <li><b>异常</b>：所有方法抛的都是<b>非受检</b>异常。{@link #ensureAvailable} 在余额
 *       不足时抛（当前实现是 HTTP 402，由全局异常处理器映射）；{@link #chargeTokens}
 *       在插图这类"必须按 token 计费"的业务拿不到用量时抛。异常类型由实现决定，
 *       契约只说"会抛、且是非受检"，所以调用方按 {@code RuntimeException} 兜。</li>
 * </ul>
 *
 * <h2>为什么 {@link #flatPoints} 在端口上</h2>
 *
 * <p>它不是"顺便暴露的查询"。一口价计费的调用点必须先问价再扣费
 * （{@code chargeFlat(..., flatPoints(bizType), ...)}），价目表属于 billing 的配置；
 * 不让调用方问，调用方就只能自己抄一份价格表 —— 那就是必然会漂移的重复。</p>
 *
 * <h2>刻意不在端口上的方法</h2>
 *
 * <p>{@code pageLedger} / {@code overview} / {@code priceList} / {@code listModelPrices} /
 * {@code upsertModelPrice} / {@code deleteModelPrice} 都是<b>本域</b>的：它们只被
 * {@code billing/api/PointsController} 与 {@code billing/api/AdminModelPriceController}
 * 调用，返回值是前端视图（{@code PointsLedgerPage} / {@code ModelPriceView}）。
 * 把它们放进端口，就是让"另一个服务能替 billing 改价目表"，而没有任何调用点需要。</p>
 *
 * <h2>⚠️ 端口"变宽"没有任何东西守着</h2>
 *
 * <p>加一个 {@code default} 方法：编译通过、实现语句一条不动、26 个门禁全绿。
 * 也就是说端口变宽是<b>静默</b>的。目前只有 {@code BillingPortTest} 用反射钉住
 * 方法个数与名字，新增方法会让它失败 —— 那是唯一的一道闸，别绕过它。</p>
 */
public interface BillingPort {

    /**
     * token 精确计费：按用量折成积分后扣减。
     *
     * <p>{@code usage} 允许为 {@code null}（网关没返回用量）。实现此时按
     * {@link TokenUsage#NONE} 处理：能查到这个 {@code bizType} 的一口价就退回一口价，
     * 查不到就什么都不做；<b>例外</b>是插图类业务，拿不到用量时实现抛异常而不是
     * 退回固定图片价 —— 那会把一次真实调用记成零成本。</p>
     *
     * @param bizType 业务项，取值见 {@link BillingBiz}；同时决定余额是加还是减
     * @param bizKey  幂等键（唯一索引），例如 {@code "task:12:run"}
     * @param bizRef  业务引用，便于排查时回跳，不参与幂等
     * @param usage   本次模型调用的 token 用量；{@code null} 等价于 {@link TokenUsage#NONE}
     * @param model   模型名，用于选单价；未知模型回退默认价。可为 {@code null}
     * @param remark  流水备注，给人看的
     */
    void chargeTokens(Long userId, String bizType, String bizKey, String bizRef,
                      TokenUsage usage, String model, String remark);

    /**
     * 一口价计费：没有 token 计量的业务（邮件、CSDN / 掘金发布）用它。
     *
     * <p>调用方要先用 {@link #flatPoints(String)} 问价再传进来。{@code points <= 0}
     * 时实现什么都不做 —— 所以"某个业务项没配价"表现为<b>不计费</b>，而不是报错。
     * 这是刻意保留的：加一个新业务项时忘了配价，宁可少扣也不要让业务失败。</p>
     */
    void chargeFlat(Long userId, String bizType, String bizKey, String bizRef,
                    long points, String remark);

    /**
     * 查一口价目录里某个业务项的价（单位：毫分）。
     *
     * <p>返回 0 表示该项没有配价。纯查询，不写库。</p>
     */
    long flatPoints(String bizType);

    /**
     * 余额预检：发起一次可能扣费的消耗之前调用。
     *
     * <p>余额低于实现配置的阈值时抛非受检异常（当前是 HTTP 402）。余额充足时返回，
     * <b>不返回布尔值</b> —— 让调用方自己 if 判断，就一定会有调用点忘记判断，
     * 而忘记判断的表现是"余额为负还能继续用"。</p>
     */
    void ensureAvailable(Long userId);

    /** 当前余额（单位：毫分）。没有账户行的用户返回 0，不抛。 */
    long balance(Long userId);

    /** 注册赠送（幂等：同一 {@code bizKey} 只赠送一次）。账户不存在时实现会先建户。 */
    void grantRegister(Long userId, String bizKey);

    /**
     * 账号合并：把来源账号的余额整体转入目标账号，并删除来源账户行。
     *
     * <p>幂等：来源账户不存在或余额为 0 时直接返回；{@code bizKey} 保证同一合并只入账一次。
     * 两个 userId 相同、或任一为 {@code null} 时静默返回。</p>
     */
    void transferBalance(Long fromUserId, Long toUserId, String bizKey, String remark);
}
