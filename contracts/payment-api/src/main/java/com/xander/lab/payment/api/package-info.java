/**
 * 支付 / 积分的服务间契约（V9 §18 / §91）。
 *
 * <h2>这里放什么</h2>
 *
 * 放"实现跨部署单元"的类型：一个类型属于这里，判据不是"看起来像契约"，
 * 而是**它的实现跨部署单元**。{@link com.xander.lab.payment.api.BillingBiz}
 * 正是如此 —— 它是每个扣费调用点都要说的词表，而调用点散在六个域、三个部署单元里。
 *
 * <h2>本模块目前只有词表，端口还没进来</h2>
 *
 * 这是 `payment-api` 的**第一步（共两步）**：
 *
 * <ul>
 *   <li>{@code BillingBiz} 是 final 常量类、零 import，**搬**过来不碰任何行为。</li>
 *   <li>{@code BillingService}（被 7 个域引用）是具体 {@code @Service}，
 *       拖三个 MyBatis mapper，只能**抽端口**，不能搬。它**故意不在本提交里**：
 *       {@code chargeTokens(...)} 收 {@code com.xander.lab.common.ModelUsage}，
 *       而它住在 {@code libs/xander-model} 且带 Jackson 依赖
 *       （{@code ModelUsage.fromJson}），于是端口会逼出一个尚未做出的决定 ——
 *       契约能不能依赖"无框架的共享内核库"，还是必须自带等价 DTO？
 *       见 {@code docs/migration/lib-extraction.md} §3quinquies.5。</li>
 * </ul>
 *
 * 与 {@code integration-api} 同形：先做能做的，把不能做的和原因写清楚。
 *
 * <h2>硬约束（由门禁 <code>contract-dependency-boundary</code> 强制）</h2>
 *
 * <ol>
 *   <li>源码只 import JDK 与自己的包。连 {@code com.xander.lab.<其他域>} 都不许 ——
 *       那意味着契约反过来指回业务域，依赖方向又反了。</li>
 *   <li>POM 里除 test scope 外不得有任何依赖。契约被每个服务依赖，
 *       包括 V9 §19 那个"被拖进 spring-boot-mvc 就启动失败"的 WebFlux Gateway；
 *       一个长胖了的契约就是 §19 禁止的超级 common.jar 换了个文件夹。</li>
 * </ol>
 *
 * <h2>常量改动就是契约改动（V9 §11）</h2>
 *
 * {@code BillingBiz} 的每个字面量都是 {@code points_ledger.biz_type} 的列值。
 * 改名不会编译报错，只会让库里已有的流水行静默失配。真要改，按 §11 走
 * {@code docs/contract-changes.yaml} 并配迁移脚本。
 *
 * <h2>刻意还不在这里的东西</h2>
 *
 * <ul>
 *   <li>{@code ModelPrice} / {@code PointAccount} / {@code PointsLedger}：
 *       持久化实体与 mapper 的投影目标，不进契约。</li>
 *   <li>{@code BillingProperties}：配置（一口价表、预检阈值），属于实现。</li>
 *   <li>计价规则本身（token 折算、db 价优先于 yml 价）：那是行为。</li>
 * </ul>
 */
package com.xander.lab.payment.api;
