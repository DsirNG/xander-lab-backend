package com.xander.lab.integration.api;

/**
 * 掘金凭证查询端口：<b>这个用户的掘金凭证现在可用吗</b>。
 *
 * <p>存在的理由、为什么只有一个方法、为什么与 {@link CsdnCredentialPort} 是两个端口而不是一个，
 * 以及"两个端口方法名相同 ⇒ 接收者写反编译得过"这条代价，全部写在
 * {@link CsdnCredentialPort} 的类注释里 —— 两者是同一个决定的两半，不要只读一半。</p>
 *
 * <h2>与 CSDN 那一个的<b>实现口径</b>差异（不是形状差异）</h2>
 * <p>两者签名逐字相同，但"可用"的判定条件并不完全一样，这个差异属于实现、不属于契约：
 * CSDN 额外要求 {@code csdnUsername} 非空（它的凭证是"cookie + 用户名"两段，
 * 少一段就无法生成回链），掘金只要求加密 cookie 非空（它的凭证是一个 JSON 对象，
 * 用户名只是展示用的附属信息）。契约上两者都只说"可用 / 不可用"，
 * 因为跨域位置没有任何调用方读得出这个区别。</p>
 *
 * <h2>失败语义</h2>
 * <p>与 {@link CsdnCredentialPort} 相同：未授权返回 {@code false}、<b>不抛</b>，
 * 纯查询、不联系外部平台、不消耗限流额度。同域的 {@code requireCredential}
 * 才是未授权时抛 {@code IllegalStateException} 的那个方法 —— 它要的是一份能用的凭证。</p>
 *
 * <h2>调用方身份</h2>
 * <p>显式收 {@code userId}，必须是参数名 {@code userId}。它是<b>凭证属主</b>，
 * 不一定是当前登录用户（{@code BlogPlanRunService} 传的是计划属主）。</p>
 */
public interface JuejinCredentialPort {

    /**
     * 这个用户的掘金凭证是否处于可用状态。
     *
     * @param userId 凭证属主，不一定是当前登录用户
     * @return 未授权时为 {@code false}；不抛异常
     */
    boolean isAuthorized(Long userId);
}
