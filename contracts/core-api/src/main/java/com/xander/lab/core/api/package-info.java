/**
 * 核心宿主的服务间契约（V9 §18 / §91）。
 *
 * <h2>这里放什么</h2>
 *
 * 放"实现跨部署单元"的类型：一个类型属于这里，判据不是"看起来像契约"，
 * 而是**它的实现跨部署单元**。{@link com.xander.lab.core.api.ModelGateway}
 * 正是如此 —— 实现（{@code ModelGatewayService}，读 {@code model_provider} /
 * {@code model_feature_config} 两张表）留在 dinqor-core-host 的 admin 域，
 * 而调用方分散在三个部署单元：dinqor-agent、dinqor-content-automation、
 * dinqor-core-host。接口由提供方持有、却被跨服务边界依赖，拆分之后方向就反了，
 * 所以端口搬到双方都能依赖的模块。
 *
 * <h2>与 {@code integration-api} 的差别：这次是"搬"，不是"抽端口"</h2>
 *
 * {@code ModelGateway} 本来就是接口，连着 {@code ModelEndpoint} 换个包即可，
 * 不需要新造端口。只有当被跨域引用的类型是**具体实现**（{@code @Service}、
 * 拖 SDK / mapper / {@code @Value}）时，才必须开端口。
 *
 * <h2>为什么 {@link com.xander.lab.core.api.UserLookupPort} 也在本模块</h2>
 *
 * §18 固定了 8 个契约名，里面**没有** identity。而 {@code core-api} 是
 * dinqor-core-host 这个部署单元的契约，identity 正好住在那里 —— 与 {@code ModelGateway}
 * 在同一个单元，所以判据一致。
 *
 * <p>抽这个端口之前，有两个域各自注入 {@code identity.infrastructure.UserMapper}
 * （MyBatis mapper）和 {@code identity.domain.User}（{@code sys_user} 实体），
 * 只为了渲染一个作者名：{@code publishing.application.BlogService} 与
 * {@code components.application.ComponentService}。这正是 identity 自己的
 * {@code package-info} 禁止的事（"feature modules ... must not parse tokens or
 * query the user table directly"），而且对 {@code publishing} 来说还跨了部署单元
 * （dinqor-content-automation 直接伸进 dinqor-core-host）。</p>
 *
 * <p>端口只有一个方法、无法确定时返回 {@code null}，因为两个调用方的兜底**不一样**
 * （{@code "User " + id} 与 {@code "匿名用户" + id}），而兜底是显示策略不是 identity 的数据。
 * **不要加宽这个端口**：每多一个方法就多一条绕过边界的路，而加宽不会让任何东西报错，
 * 所以 {@code UserLookupPortTest} 把方法个数钉住了。</p>
 *
 * <h2>为什么 {@link com.xander.lab.core.api.AccessTokenLookupPort} 也在本模块</h2>
 *
 * <p>判据与上面两条完全相同：实现（{@code identity.application.AuthService}）在
 * dinqor-core-host，而 §18 的 8 个契约名里没有 identity，所以 identity 的能力落在这里。</p>
 *
 * <p>消费方是 {@code agent}：{@code /ws/agent} 的握手路径不在 {@code /api/**} 下，
 * 走不到 {@code AuthInterceptor}，于是它直接注入了 {@code AuthService}
 * —— {@code agent -> identity} 这条跨部署单元边的全部内容就是那两条 import。
 * 现在它只依赖本端口。</p>
 *
 * <p>⚠️ 本端口与 {@link com.xander.lab.core.api.UserLookupPort} 有一处<b>相反</b>的地方：
 * 它的作用是<b>从令牌得出</b>调用方身份，所以它的参数里<b>不许</b>出现 {@code userId}
 * —— 出现就意味着有人在让它做"这个令牌属不属于这个人"，那是另一个能力。
 * 守卫因此是反向的（{@code assertNoParameterIsNamed(port, "userId")}），
 * 详见 {@code AccessTokenLookupPortTest}。</p>
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
 * {@code FEATURE_*} 与 {@code API_STYLE_*} 不是普通字符串常量：它们被持久化为
 * {@code model_feature_config.feature_key} / {@code primary_api_style} /
 * {@code fallback_api_style} 的列值。改名不会编译报错，但会让库里已有的行**静默失配**。
 * {@code ModelGatewayTest} 因此把字面量逐个钉住。真要改，按 §11 走
 * {@code docs/contract-changes.yaml}。
 *
 * <h2>刻意还不在这里的东西</h2>
 *
 * <ul>
 *   <li>{@code ModelGatewayService}、{@code FeatureModelConfigService}：
 *       解析规则、api-style 白名单校验、缓存与持久化都是**行为**，留在 admin 域。</li>
 *   <li>{@code ProviderKeyCipher} 与供应商注册表：本包只描述"一个端点长什么样"，
 *       不负责"密钥怎么保护"。</li>
 *   <li>admin 的 HTTP 面（用户封禁、供应商管理）。它服务前端，是另一个受众。</li>
 * </ul>
 */
package com.xander.lab.core.api;
