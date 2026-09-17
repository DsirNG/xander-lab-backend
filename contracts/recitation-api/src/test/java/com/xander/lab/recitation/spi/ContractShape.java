package com.xander.lab.recitation.spi;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约形状的公共断言，本模块的测试共用。
 *
 * <p>抽出来是因为端口要钉的是同一组性质（宽度、完整签名、无 {@code default}、无框架类型），
 * 三个记录类型要钉的也是同一组（分量名 + 顺序 + 无框架类型），而每个类型各抄一份实现会把
 * "怎么钉"变成四份可以各自漂移的代码。</p>
 *
 * <p><b>为什么不复用生产代码里的任何东西</b>：契约模块没有生产代码可复用（只有接口与记录），
 * 而且就算有也不能用 —— 用被测方的函数去算判据，等于让它自己给自己打分。
 * 这一点和门禁校验生成物清单那条规矩是同一条。</p>
 *
 * <p>与 {@code contracts/automation-api} 里同名类逐字相同，只有包名不同：契约模块之间
 * 不能互相依赖（只允许 JDK + 本包），所以这份小工具每个契约模块各留一份。</p>
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
     * <p>顺序必须在判据里，因为记录靠位置构造：{@code MaterialView} 里 {@code id} 之后连着
     * 四个 {@code String}，重排不会有任何编译错误，只会静默换字段。只断言"分量集合相同"
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
