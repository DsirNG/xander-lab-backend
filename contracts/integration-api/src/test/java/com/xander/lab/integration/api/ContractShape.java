package com.xander.lab.integration.api;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约形状的共用断言。与 {@code contracts/blogagent-api} 里的同名类是同一条判据的另一份落地：
 * 契约模块之间不能互相依赖（只允许 JDK + 本包），所以每个契约模块各留一份。其中
 * {@link #assertFirstParameterIsTheCallerId} 两份逐字相同，{@link #assertNoParameterIsNamed}
 * 只差失败消息里举的例子（举的例子必须是本模块自己的端口）。
 *
 * <p>存在的理由：端口的"变宽"不会触发任何一道其它防线 —— 编译过、耦合指标一动不动、
 * 26 条门禁全绿、单元测试也全绿。只有反射能守。</p>
 */
final class ContractShape {

    private ContractShape() {
    }

    /** 方法名 + 参数表 + 返回类型，按名字排序。数组先取组件类型（{@code byte[].class.getName()} 是 {@code "[B"}）。 */
    static List<String> declaredSignatures(Class<?> contract) {
        return Arrays.stream(contract.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(ContractShape::signature)
                .sorted()
                .toList();
    }

    static String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(ContractShape::typeName)
                .collect(Collectors.joining(", "));
        return method.getName() + "(" + parameters + ") -> " + typeName(method.getReturnType());
    }

    static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.getComponentType()) + "[]" : type.getSimpleName();
    }

    /** {@code default} 方法会在不通知任何实现的情况下扩大契约面。 */
    static void assertNoDefaultMethods(Class<?> contract) {
        for (Method method : contract.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(method.isDefault(),
                    method.getName() + " 是 default 方法：它会在不通知任何实现的情况下扩大契约面。"
                            + "把它做成抽象方法，让每个实现显式表态。");
        }
    }

    /**
     * 端口的<b>每个</b>方法都必须把调用方 id 作为第一个参数：有参数、类型 {@code Long}、名字 {@code userId}。
     *
     * <p>三件事要一起钉，缺一条就有一种错误形状能绿着过去：只钉"有参数"会放过
     * {@code sync(Long taskId, Long userId)}（两个都是 {@code Long}，而语义是"拿 taskId 当用户 id 去查"）；
     * 只钉类型会放过 {@code String userId}；只钉名字会放过零参方法。跨进程后提供方读不到调用方的
     * ThreadLocal，身份只能靠参数传。</p>
     *
     * <p>读参数名要求 javac 开着 {@code -parameters}（本仓库经 spring-boot-starter-parent 开启），
     * 名字会进 class 文件的 {@code MethodParameters} 属性 —— 已用 {@code javap -v} 实测确认。
     * 若哪天它被关掉，这条断言会以"参数名变成了 {@code arg0}"的形态变红，那也是一个真实信号。</p>
     */
    static void assertFirstParameterIsTheCallerId(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            Parameter[] parameters = method.getParameters();
            String where = type.getSimpleName() + "." + method.getName();
            assertTrue(parameters.length > 0,
                    where + " 一个参数都没有，不可能显式收到调用方身份。"
                            + "跨进程后提供方读不到调用方的 ThreadLocal，身份只能靠参数传。");
            assertTrue(parameters[0].getType() == Long.class,
                    where + " 的第一个参数类型是 " + parameters[0].getType().getSimpleName()
                            + "，不是 Long —— 它必须是调用方 id。");
            assertTrue("userId".equals(parameters[0].getName()),
                    where + " 的第一个参数叫 " + parameters[0].getName() + "，不叫 userId。"
                            + "只断言「类型是 Long」是不够的：sync(Long taskId, Long userId) "
                            + "同样满足那个断言。要么把参数名改成 userId，要么把它挪到第二位"
                            + "并让真正的 userId 排第一。");
        }
    }

    /**
     * 这个端口里<b>不许</b>出现名为 {@code name} 的参数。
     *
     * <p>这是 {@link #assertFirstParameterIsTheCallerId} 的<b>反向</b>判据，两者服务同一件事：
     * 让"这个端口收不收调用方 id"变成一个被显式断言过的决定，而不是一处可以随手的改动。</p>
     *
     * <p>为什么反向判据是必需的：{@code MailPort} 是一次<b>传输</b>，它不知道也不需要知道
     * 收件人是谁 —— 归属判定（"这个提醒是不是你的"）在调用方自己的库里、在调本端口<b>之前</b>
     * 就做完了；而 {@code sendVerificationCode} 发生在<b>登录之前</b>，那时还没有身份。
     * 但"别的端口都收 userId"这条一致性会诱使后来者"顺手补上" —— 补完之后多出一个没人读的参数，
     * 而"这里需要身份"这个错误信号会被写进契约，下一个读代码的人会去找它到底在哪儿被用上。
     * 没有这条断言，那种改动是全绿的。</p>
     *
     * @param name 不许出现的参数名，如 {@code userId}
     */
    static void assertNoParameterIsNamed(Class<?> contract, String name) {
        for (Method method : contract.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            for (Parameter parameter : method.getParameters()) {
                assertFalse(name.equals(parameter.getName()),
                        contract.getSimpleName() + "." + method.getName() + " 里出现了一个叫 " + name
                                + " 的参数。这个端口的提供方是无状态的传输层，不需要身份；"
                                + "归属判定在调用方自己那一侧、调本端口之前就做完了。"
                                + "加一个没人读的参数不是「一致性」，而是把「这里需要身份」这个错误信号"
                                + "写进契约 —— 要么删掉它，要么先证明这个端口里真的有一步要用到它。");
            }
        }
    }

    /** 签名里不许出现非 JDK、非本契约包的类型：跨进程传不过去，还会把框架拖进每个依赖方的 classpath。 */
    static void assertNoFrameworkTypes(Class<?> contract) {
        String contractPackage = contract.getPackageName() + ".";
        for (Method method : contract.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertContractType(method, method.getReturnType(), "返回值", contractPackage);
            for (Class<?> parameter : method.getParameterTypes()) {
                assertContractType(method, parameter, "参数", contractPackage);
            }
        }
    }

    private static void assertContractType(Method method, Class<?> raw, String role, String contractPackage) {
        Class<?> type = raw.isArray() ? raw.getComponentType() : raw;
        String name = type.getName();
        assertTrue(type.isPrimitive() || name.startsWith("java.") || name.startsWith(contractPackage),
                method.getName() + " 的" + role + "里有非 JDK / 非本契约类型：" + name
                        + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
    }
}
