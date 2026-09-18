package com.xander.lab.core.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link AccessTokenLookupPort} 的**宽度**、签名，以及一条**反向**守卫。
 *
 * <p>与 {@link UserLookupPortTest} 同一个理由：这个接口的风险不是「签名被改坏」
 * （那会编译报错，两个调用点一起红），而是**悄悄变宽** ——
 * 一个叫「令牌查询端口」的接口一旦多出 {@code getRole} / {@code listSessions} /
 * {@code refresh}，它就会变成所有人绕过 identity 边界的新入口。
 * 而端口变宽**不会**让任何代码报错，也不会有任何指标变化。</p>
 *
 * <p>⚠️ 本测试比 {@code UserLookupPortTest} 多一条<b>反向</b>守卫，原因见
 * {@link #takesNoUserIdBecauseItProducesOne()}。</p>
 */
class AccessTokenLookupPortTest {

    @Test
    void thePortDeclaresExactlyOneMethod() {
        Method[] declared = AccessTokenLookupPort.class.getDeclaredMethods();

        assertEquals(1, declared.length,
                "端口变宽了。它存在的意义是让 agent 的握手不必依赖 identity 的实现类，"
                        + "每多一个方法就多一条绕过边界的路。"
                        + "确实需要新方法时，请连同调用点一起提交并更新这条断言。");
    }

    @Test
    void thePortDeclaresNoDefaultMethods() {
        for (Method method : AccessTokenLookupPort.class.getDeclaredMethods()) {
            assertFalse(method.isDefault(),
                    method.getName() + " 是 default 方法。default 会让实现类不写也能编译，"
                            + "于是「端口有几个方法」这件事在实现侧失去意义 —— "
                            + "而端口变宽本来就是本测试要拦的东西。");
        }
    }

    @Test
    void resolveUserIdTakesAnAccessTokenAndReturnsANullableUserId() {
        // ⚠️ 按名字取，不按下标：getDeclaredMethods() 的顺序 JVM 并不保证。
        // 上面那条断言虽然钉了「只有一个方法」，但两条断言是**独立**的 ——
        // 一旦有人加了方法，这条会在检查一个完全不同的方法，却可能照样通过。
        Method resolveUserId = null;
        for (Method method : AccessTokenLookupPort.class.getDeclaredMethods()) {
            if (method.getName().equals("resolveUserId")) resolveUserId = method;
        }
        assertNotNull(resolveUserId, "端口上没有 resolveUserId 方法");

        assertEquals(Long.class, resolveUserId.getReturnType(),
                "返回 null 表示「这个令牌不构成有效身份」，调用方据此拒绝握手；"
                        + "改成 Optional 会改变契约形状，也会与 UserLookupPort 的约定不一致");
        assertEquals(1, resolveUserId.getParameterCount());
        assertEquals(String.class, resolveUserId.getParameterTypes()[0],
                "入参是令牌原文；收别的类型说明有人在端口上做令牌之外的事");
        assertEquals("accessToken", resolveUserId.getParameters()[0].getName(),
                "参数名是契约的一部分（javac 开了 -parameters）。"
                        + "改名的代价是每个实现类都要跟着改，所以要显式钉住。");
    }

    /**
     * ⚠️ 反向守卫：这个端口<b>产出</b> userId，所以它<b>不许</b>收 userId。
     *
     * <p>其他几个端口（{@code UserLookupPort} / {@code NotificationPort} /
     * {@code BlogAgentPort} …）的第一个参数都是调用方身份，守卫是<b>正面</b>的：
     * 断言参数名是 {@code userId}。本端口是反过来的 —— 它的整个作用就是从令牌里
     * 得出身份，方法里出现一个 {@code userId} 参数说明有人在让它做
     * 「校验这个令牌属不属于这个人」，那是<b>另一个能力</b>（且会诱使实现者
     * 把归属校验和令牌校验混成一步）。</p>
     *
     * <p>注入验证：往签名里加一个 {@code Long userId} 参数，这条必须变红。</p>
     */
    @Test
    void takesNoUserIdBecauseItProducesOne() {
        for (Method method : AccessTokenLookupPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            for (Parameter parameter : method.getParameters()) {
                assertFalse("userId".equals(parameter.getName()),
                        method.getName() + " 里出现了一个叫 userId 的参数。"
                                + "本端口的作用是「从令牌得出 userId」，收一个 userId 意味着"
                                + "有人在让它校验「这个令牌属不属于这个人」—— 那是另一个能力，"
                                + "混在一起会让实现者把归属校验和令牌校验写成一步。"
                                + "归属校验应该在调用方做（agent 的握手用 conversationService.get "
                                + "校验会话归属），不要在令牌端口里做。");
            }
        }
    }

    @Test
    void thePortIsAnInterfaceInTheContractPackage() {
        assertTrue(AccessTokenLookupPort.class.isInterface(),
                "契约里放接口；实现留在提供方（identity）域内");

        assertEquals("com.xander.lab.core.api", AccessTokenLookupPort.class.getPackageName(),
                "挪出契约包会让两个调用点重新跨服务边界指向 identity 的内部。"
                        + "它属于 core-api 而不是 agent-api，判据是「实现跨部署单元」"
                        + "（identity 在 dinqor-core-host），见 package-info");
    }

    @Test
    void everySignatureIsFreeOfFrameworkTypes() {
        // 契约必须与序列化框架无关（ADR-1），且不能被拖进任何 Web 框架。
        for (Method method : AccessTokenLookupPort.class.getDeclaredMethods()) {
            assertTrue(isJdkOnly(method.getReturnType()),
                    method.getName() + " 的返回类型 " + method.getReturnType().getName()
                            + " 不是 JDK 类型");
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertTrue(isJdkOnly(parameterType),
                        method.getName() + " 的参数类型 " + parameterType.getName()
                                + " 不是 JDK 类型");
            }
        }
    }

    private static boolean isJdkOnly(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) return true;
        return type.getName().startsWith("java.");
    }
}
