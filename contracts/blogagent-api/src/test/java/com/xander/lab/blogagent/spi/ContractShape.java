package com.xander.lab.blogagent.spi;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约形状的公共断言，本模块的测试共用。
 *
 * <p>抽出来是因为端口要钉的是同一组性质（宽度、完整签名、无 {@code default}、无框架类型、
 * 首参是调用方 id），三个记录类型要钉的也是同一组（分量名 + 顺序 + 无框架类型），
 * 而每个类型各抄一份实现会把"怎么钉"变成四份可以各自漂移的代码。</p>
 *
 * <p><b>为什么不复用生产代码里的任何东西</b>：契约模块没有生产代码可复用（只有接口与记录），
 * 而且就算有也不能用 —— 用被测方的函数去算判据，等于让它自己给自己打分。
 * 这一点和门禁校验生成物清单那条规矩是同一条。</p>
 *
 * <p>与 {@code contracts/integration-api} 里的同名类是同一条判据的另一份落地：契约模块之间
 * 不能互相依赖（只允许 JDK + 本包），所以每个契约模块各留一份。其中
 * {@link #assertFirstParameterIsTheCallerId} 两份逐字相同，{@link #assertNoParameterIsNamed}
 * 只差失败消息里举的例子（举的例子必须是本模块自己的端口）。</p>
 *
 * <p>⚠️ 那两条参数判据是<b>一对</b>：一个断言"这个端口收了名为 {@code userId} 的调用方 id"，
 * 另一个断言"这个端口里根本没有叫 {@code userId} 的参数"。有了反向的那条，
 * "收不收身份"才是一个被断言过的决定，而不是一处可以随手的改动 ——
 * 这正是本项目反复踩的"判据只朝一个方向看"。</p>
 */
final class ContractShape {

    private ContractShape() {
    }

    /** 全部声明方法的完整签名，按字典序排好，便于与预期清单整体比较。 */
    static List<String> declaredSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(ContractShape::signature)
                .sorted()
                .toList();
    }

    /**
     * 记录类型的全部分量，形如 {@code name:String}，<b>按声明顺序</b>。
     *
     * <p>顺序必须在判据里，因为记录靠位置构造：{@code BlogAgentTaskView} 里 {@code id} 之后
     * 连着两个 {@code String}，重排不会有任何编译错误，只会静默换字段。只断言"分量集合相同"
     * 是抓不到的。</p>
     */
    static List<String> recordComponents(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + typeName(component.getType()))
                .toList();
    }

    /**
     * 契约里不许有 {@code default} 方法。
     *
     * <p>端口"变宽"不会触发任何一道防线：编译过、指标不动、门禁不报。而 {@code default}
     * 是变宽的<b>静默</b>形式 —— 它连实现方都不通知，直接就把契约面扩大了。要加能力就加抽象
     * 方法，让每个实现显式表态（编译不过就是最好的通知）。</p>
     */
    static void assertNoDefaultMethods(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(method.isDefault(),
                    type.getSimpleName() + "." + method.getName() + " 是 default 方法：它会在不通知"
                            + "任何实现的情况下扩大契约面。把它做成抽象方法，让每个实现显式表态。");
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
     * 端口的<b>任何</b>方法都不得有一个叫 {@code name} 的参数。
     *
     * <p>这是 {@link #assertFirstParameterIsTheCallerId} 的<b>反向</b>判据，两者服务同一件事：
     * 让"这个端口收不收调用方 id"变成一个被显式断言过的决定，而不是一处可以随手的改动。</p>
     *
     * <p>为什么反向判据是必需的：{@code BlogAgentImagePort} 的提供方是一次无状态的模型调用，
     * 它不需要身份，而真正需要身份的两步（存素材、扣积分）在别的端口上、且都显式收了
     * {@code userId}。但"别的端口都收 userId"这条一致性会诱使后来者"顺手补上" ——
     * 补完之后多出一个没人读的参数，而"这里需要身份"这个错误信号会被写进契约，
     * 下一个读代码的人会去找它到底在哪儿被用上。没有这条断言，那种改动是全绿的。</p>
     *
     * @param name 不许出现的参数名，如 {@code userId}
     */
    static void assertNoParameterIsNamed(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            for (Parameter parameter : method.getParameters()) {
                assertFalse(name.equals(parameter.getName()),
                        type.getSimpleName() + "." + method.getName() + " 里出现了一个叫 " + name
                                + " 的参数。这个端口的提供方是无状态的模型调用，不需要身份；"
                                + "真正需要身份的两步（存素材、扣积分）在 MediaAssetPort / BillingPort 上，"
                                + "它们都显式收了 userId。加一个没人读的参数不是「一致性」，"
                                + "而是把「这里需要身份」这个错误信号写进契约 —— 要么删掉它，"
                                + "要么先证明这个端口里真的有一步要用到它。");
            }
        }
    }

    /** 签名里不许出现非 JDK、非本契约包的类型。 */
    static void assertNoFrameworkTypes(Class<?> type) {
        String contractPackage = type.getPackageName();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertContractType(method, method.getReturnType(), "返回值", contractPackage);
            for (Class<?> parameter : method.getParameterTypes()) {
                assertContractType(method, parameter, "参数", contractPackage);
            }
        }
    }

    /** 记录的分量里不许出现非 JDK、非本契约包的类型。 */
    static void assertNoFrameworkComponentTypes(Class<?> type) {
        String contractPackage = type.getPackageName();
        for (RecordComponent component : type.getRecordComponents()) {
            Class<?> raw = component.getType();
            // 数组 / 泛型容器先取组件类型：byte[].class.getName() 是 "[B"，
            // List<String>.class 是 java.util.List。两者都要能过。
            Class<?> type0 = raw.isArray() ? raw.getComponentType() : raw;
            String name = type0.getName();
            assertTrue(type0.isPrimitive()
                            || name.startsWith("java.")
                            || name.startsWith(contractPackage + "."),
                    component.getName() + " 的类型不是 JDK / 契约类型：" + name
                            + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
        }
    }

    private static void assertContractType(Method method, Class<?> raw, String role,
                                           String contractPackage) {
        // 数组先取组件类型：byte[].class.getName() 是 "[B"，直接判会误报成"非 JDK 类型"。
        Class<?> type = raw.isArray() ? raw.getComponentType() : raw;
        String name = type.getName();
        assertTrue(type.isPrimitive()
                        || name.startsWith("java.")
                        || name.startsWith(contractPackage + "."),
                method.getName() + " 的" + role + "里有非 JDK / 非契约类型：" + name
                        + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
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
}
