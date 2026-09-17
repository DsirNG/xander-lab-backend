package com.xander.lab.core.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link UserLookupPort} 的**宽度**与签名。
 *
 * <p>这个接口的特殊之处是：它的风险不是"方法签名被改坏"（那会编译报错，
 * 两个调用点一起红），而是**悄悄变宽**。一个叫"用户查询端口"的接口一旦多出
 * {@code findById} / {@code getEmail} / {@code listUsers}，它就会变成
 * 所有人绕过 identity 边界的新入口 —— 而端口变宽**不会**让任何代码报错，
 * 也不会有任何指标变化。所以这里把"只有一个方法"这件事显式钉住。</p>
 *
 * <p>签名本身也钉住：返回值或参数类型变了，调用点是会编译报错，但
 * {@code String} → {@code Object} 这种放宽不一定 —— 而契约是跨服务的，
 * 形状必须稳定（§11）。</p>
 */
class UserLookupPortTest {

    @Test
    void thePortDeclaresExactlyOneMethod() {
        Method[] declared = UserLookupPort.class.getDeclaredMethods();

        assertEquals(1, declared.length,
                "端口变宽了。它存在的意义是收窄 identity 的暴露面，"
                        + "每多一个方法就多一条绕过边界的路。"
                        + "确实需要新方法时，请连同调用点一起提交并更新这条断言。");
        assertEquals("displayName", declared[0].getName());
    }

    @Test
    void displayNameTakesAUserIdAndReturnsANullableString() {
        // ⚠️ 按名字取，不按下标：getDeclaredMethods() 的顺序 JVM 并不保证。
        // 上面那条断言虽然钉了"只有一个方法"，但两条断言是**独立**的 ——
        // 一旦有人加了方法，这条会在检查一个完全不同的方法，却可能照样通过。
        // （在 automation-api 的注入验证里实测到过：加一个方法后下标 0 就换了对象。）
        Method displayName = null;
        for (Method method : UserLookupPort.class.getDeclaredMethods()) {
            if (method.getName().equals("displayName")) displayName = method;
        }
        assertNotNull(displayName, "端口上没有 displayName 方法");

        assertEquals(String.class, displayName.getReturnType(),
                "返回 null 表示'无法确定'，调用方各自兜底；改成 Optional 会改变契约形状");
        assertEquals(1, displayName.getParameterCount());
        assertEquals(Long.class, displayName.getParameterTypes()[0]);
    }

    @Test
    void thePortIsAnInterfaceInTheContractPackage() {
        assertTrue(UserLookupPort.class.isInterface(),
                "契约里放接口；实现留在提供方（identity）域内");

        assertEquals("com.xander.lab.core.api", UserLookupPort.class.getPackageName(),
                "挪出契约包会让两个调用点重新跨服务边界指向 identity 的内部");
    }
}
