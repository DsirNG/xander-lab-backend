package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link CsdnCredentialPort} 的<b>宽度</b>与<b>完整签名</b>。
 *
 * <p>为什么只有反射能守：端口"变宽"（加方法、加 {@code default}、改参数表）不会触发任何一道
 * 其它防线 —— 编译过、耦合指标一动不动、26 条门禁全绿、单元测试也全绿。
 * 这里是 {@code automation → integration} 那条跨部署单元边的边界，变宽的后果是
 * {@code integration} 的凭证存储被逐个搬进 {@code automation}。</p>
 *
 * <p>⚠️ 这里刻意只放了实现类六个公开方法里的<b>一个</b>。另外五个
 * （{@code isExpired} / {@code markExpired} / {@code requireCookie} / {@code save} /
 * {@code delete}）的调用点全在 {@code integration} 域内 —— 把它们一起端出去，
 * 等于让消费方的适配器被迫实现四个它从不调用的成员。<b>一半的价值来自没搬的东西。</b></p>
 */
class CsdnCredentialPortTest {

    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "isAuthorized(Long) -> boolean");

    @Test
    void declaresExactlyTheOneKnownMethodWithThisExactSignature() {
        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(),
                ContractShape.declaredSignatures(CsdnCredentialPort.class),
                "端口宽度或签名变了。加方法前先问：这是 integration 域的凭证存储能力，"
                        + "还是调用方自己该做的事？有意加就一起改预期清单，并在设计文档里记下为什么。");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(CsdnCredentialPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackageAndExtendsNothing() {
        assertTrue(CsdnCredentialPort.class.isInterface(), "端口必须是接口。");
        assertEquals("com.xander.lab.integration.api", CsdnCredentialPort.class.getPackageName(),
                "包名必须是 integration.api —— 模块名 integration-api 决定了它。");
        assertEquals(0, CsdnCredentialPort.class.getInterfaces().length,
                "端口不许 extends 别的接口：契约不能依赖契约，继承会把别的契约的方法并进来。");
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyAsItsFirstParameter() {
        ContractShape.assertFirstParameterIsTheCallerId(CsdnCredentialPort.class);
    }

    @Test
    void carriesNoFrameworkTypesInAnySignature() {
        ContractShape.assertNoFrameworkTypes(CsdnCredentialPort.class);
    }

    /**
     * 返回类型必须是<b>原语</b> {@code boolean}，不能是装箱的 {@code Boolean}。
     *
     * <p>两个原因，第二个是会真的炸的那个：</p>
     * <ul>
     *   <li>契约里没有"不知道"这个状态 —— 没有凭证就是 {@code false}，
     *       而 {@code Optional<Boolean>} 或 {@code Boolean} 会把这个不存在的状态写进契约，
     *       下一个读代码的人就会去找"什么情况下会是 null"。</li>
     *   <li>调用方两处都写 {@code if (!port.isAuthorized(userId))}。装箱类型一旦为
     *       {@code null}，这一行<b>自动拆箱直接 NPE</b>，而不是走"未授权"那条分支 ——
     *       症状是一次 NPE 而不是一次"跳过同步并记 AUTH_REQUIRED"。跨进程之后
     *       "字段没填"比同进程时更常见，所以这个形状必须钉死。</li>
     * </ul>
     */
    @Test
    void returnsAPrimitiveBooleanSoCallersCannotReceiveAnUnknownState() throws ReflectiveOperationException {
        Method isAuthorized = CsdnCredentialPort.class.getMethod("isAuthorized", Long.class);
        assertEquals(boolean.class, isAuthorized.getReturnType(),
                "isAuthorized 必须返回原语 boolean：装箱类型为 null 时，调用方的 "
                        + "`if (!port.isAuthorized(userId))` 会在自动拆箱处 NPE。");
    }

    /**
     * CSDN 与掘金是<b>两个</b>端口，不是一个带 {@code platform} 参数的端口，也不是互相继承的两个接口。
     *
     * <p>⚠️ 这条断言很弱（它只挡住"合并成一个端口"和"一个 extends 另一个"这两种改法），
     * 但它守的是一个<b>已经付过代价的决定</b>：合并需要一个域里不存在的跨域枚举，
     * 而且会让只关心 CSDN 的消费方被迫依赖一个同时谈掘金的类型。
     * 完整权衡写在 {@link CsdnCredentialPort} 的类注释里；要改就先把那段改掉。</p>
     *
     * <p>⚠️ 这条<b>不是</b>在断言两个端口的签名相同。签名相同是本刀付出的一条代价
     * （"接收者写反"编译得过），只能由不对称桩的行为测试守 —— 见
     * {@code BlogPlanRunServiceTest#theTwoCredentialPortsAreNotInterchangeableEvenThoughTheirSignaturesAreIdentical}。
     * 在这里断言"两者签名相同"会让未来某次把签名改开的好改动<b>变红</b>，
     * 那是一条会朝错误方向报警的判据。</p>
     */
    @Test
    void staysASeparateInterfaceFromTheJuejinOneInsteadOfBeingMergedIntoAPlatformParameter() {
        assertNotEquals(CsdnCredentialPort.class, JuejinCredentialPort.class);
        assertFalse(CsdnCredentialPort.class.isAssignableFrom(JuejinCredentialPort.class),
                "两个凭证端口不许互相继承：契约不能依赖契约。");
        assertFalse(JuejinCredentialPort.class.isAssignableFrom(CsdnCredentialPort.class),
                "两个凭证端口不许互相继承：契约不能依赖契约。");
    }
}
