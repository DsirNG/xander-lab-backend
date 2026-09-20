/**
 * 支付 / 积分的服务间契约。
 *
 * <h2>这里放什么</h2>
 *
 * <p>放"实现跨部署单元"的类型：一个类型属于这里，判据不是"看起来像契约"，
 * 而是**它的实现跨部署单元**。本模块三组成员都符合：</p>
 *
 * <ul>
 *   <li>{@link com.xander.lab.payment.api.BillingBiz} —— 词表。每个扣费调用点都要说的
 *       "我在为什么收费"，而调用点散在七个域、四个部署单元里。</li>
 *   <li>{@link com.xander.lab.payment.api.BillingPort} —— 端口。{@code BillingService}
 *       被那七个域引用，而它拖三个 MyBatis mapper，只能抽端口不能搬。</li>
 *   <li>{@link com.xander.lab.payment.api.TokenUsage} —— 端口的入参 DTO。它必须自带一份，
 *       因为网关口径的 {@code com.xander.lab.common.ModelUsage} 住在带 Jackson 的
 *       {@code libs/xander-model}，契约 import 不到它（见下）。</li>
 * </ul>
 *
 * <h2>为什么自带 {@code TokenUsage} 而不是放宽门禁</h2>
 *
 * <p>{@code chargeTokens(...)} 的入参在实现里是 {@code ModelUsage}，而它带 Jackson 依赖。
 * 契约只许 JDK，所以这里写一份等价的 {@code TokenUsage}，换算落在调用方边界上 ——
 * 而不是把 {@code xander-model} 拖进契约的 classpath。</p>
 *
 * <h2>硬约束（由门禁 <code>contract-dependency-boundary</code> 强制）</h2>
 *
 * <ol>
 *   <li>源码只 import JDK 与自己的包。连 {@code com.xander.lab.<其他域>} 都不许 ——
 *       那意味着契约反过来指回业务域，依赖方向又反了。
 *       <b>也包括不许 import 另一个契约</b>：契约之间互引会造出一条谁都不看的方向。</li>
 *   <li>POM 里除 test scope 外不得有任何依赖。契约被每个服务依赖，包括不能沾
 *       spring-webmvc 的 WebFlux Gateway；一个长胖了的契约就是"超级 common.jar"
 *       换了个文件夹。</li>
 * </ol>
 *
 * <h2>常量改动就是契约改动</h2>
 *
 * <p>{@code BillingBiz} 的每个字面量都是 {@code points_ledger.biz_type} 的列值。
 * 改名不会编译报错，只会让库里已有的流水行静默失配 —— 改之前先看数据怎么迁移。</p>
 *
 * <p>端口的<b>形状</b>同样是契约：三个相邻的 {@code String}（业务项 / 幂等键 / 业务引用）
 * 换位不会有编译错误，只会静默错账。{@code BillingPortTest} 钉住完整签名。</p>
 *
 * <h2>刻意还不在这里的东西</h2>
 *
 * <ul>
 *   <li>{@code ModelPrice} / {@code PointAccount} / {@code PointsLedger}：
 *       持久化实体与 mapper 的投影目标，不进契约。</li>
 *   <li>{@code BillingProperties}：配置（一口价表、预检阈值），属于实现。</li>
 *   <li>计价规则本身（token 折算、db 价优先于 yml 价）：那是行为。</li>
 *   <li>{@code pageLedger} / {@code overview} / {@code priceList} / {@code listModelPrices} /
 *       {@code upsertModelPrice} / {@code deleteModelPrice}：只被 billing 自己的两个
 *       Controller 调用的前端视图方法，刻意<b>不在端口上</b>（见 {@code BillingPort} javadoc）。</li>
 * </ul>
 */
package com.xander.lab.payment.api;
