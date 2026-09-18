package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link JuejinCredentialPort} 的<b>宽度</b>与<b>完整签名</b>。
 *
 * <p>理由与 {@link CsdnCredentialPortTest} 相同 —— 两者是同一个决定的两半，不要只读一半。
 * 这份之所以仍然单独存在，是因为"宽度"是<b>逐端口</b>的性质：掘金那个实现类同样有六个公开方法，
 * 同样只有 {@code isAuthorized} 被跨域调用，把另外五个端出去是<b>另一个</b>错误，
 * 得由<b>另一条</b>断言挡住。</p>
 */
class JuejinCredentialPortTest {

    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "isAuthorized(Long) -> boolean");

    @Test
    void declaresExactlyTheOneKnownMethodWithThisExactSignature() {
        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(),
                ContractShape.declaredSignatures(JuejinCredentialPort.class),
                "端口宽度或签名变了。加方法前先问：这是 integration 域的凭证存储能力，"
                        + "还是调用方自己该做的事？有意加就一起改预期清单，并在 ADR 里记下为什么。");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(JuejinCredentialPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackageAndExtendsNothing() {
        assertTrue(JuejinCredentialPort.class.isInterface(), "端口必须是接口。");
        assertEquals("com.xander.lab.integration.api", JuejinCredentialPort.class.getPackageName());
        assertEquals(0, JuejinCredentialPort.class.getInterfaces().length,
                "端口不许 extends 别的接口：契约不能依赖契约。");
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyAsItsFirstParameter() {
        ContractShape.assertFirstParameterIsTheCallerId(JuejinCredentialPort.class);
    }

    @Test
    void carriesNoFrameworkTypesInAnySignature() {
        ContractShape.assertNoFrameworkTypes(JuejinCredentialPort.class);
    }

    /**
     * 必须是原语 {@code boolean}：装箱类型为 {@code null} 时调用方的
     * {@code if (!port.isAuthorized(userId))} 会在自动拆箱处 NPE。
     *
     * <p>⚠️ 特别注意<b>不要</b>把返回类型改成实现类里那个
     * {@code JuejinClient.Credential}：它属于 {@code integration} 域的
     * {@code infrastructure} 包，跨进程传不过去，而且会把"凭证长什么样"变成跨域契约 ——
     * 那正是本端口刻意不暴露 {@code requireCredential} 的原因。</p>
     */
    @Test
    void returnsAPrimitiveBooleanAndNotTheDomainsCredentialType() throws ReflectiveOperationException {
        Method isAuthorized = JuejinCredentialPort.class.getMethod("isAuthorized", Long.class);
        assertEquals(boolean.class, isAuthorized.getReturnType(),
                "isAuthorized 必须返回原语 boolean：装箱类型为 null 时调用方会在自动拆箱处 NPE；"
                        + "返回 JuejinClient.Credential 则是把域类型端出去（ADR-2 禁止）。");
    }
}
