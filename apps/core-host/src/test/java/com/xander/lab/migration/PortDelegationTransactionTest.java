package com.xander.lab.migration;

import com.xander.lab.admin.application.ModelGatewayService;
import com.xander.lab.automation.application.BlogContentPlanService;
import com.xander.lab.automation.application.BlogNotificationService;
import com.xander.lab.automation.application.EmailReminderService;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.identity.application.UserLookupService;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.publishing.application.BlogMediaService;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.publishing.application.PublicationService;
import com.xander.lab.recitation.application.KnowledgeQuizService;
import com.xander.lab.recitation.application.RecitationService;
import com.xander.lab.recitation.infrastructure.DashScopeRecitationAsrClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守一条**跨域抽取引入的、静默的**事务失效：端口方法转调同类的 {@code @Transactional} 方法时，
 * 自己必须也标 {@code @Transactional}。
 *
 * <h2>为什么必须由测试守</h2>
 * <p>Spring 的事务是**代理**实现的，而同类自调用（{@code this.create(...)}）不走代理 ——
 * 被调方法上的注解根本不生效。端口方法的标准写法恰好就是"薄壳 + 换算 + 转调同域富方法"，
 * 于是这个坑在每次抽端口时都会出现一次，而且：</p>
 * <ul>
 *   <li>编译通过；</li>
 *   <li>耦合指标一动不动；</li>
 *   <li>26 条门禁全绿；</li>
 *   <li>单元测试也全绿 —— 单元测试里没有代理，{@code @Transactional} 本来就不生效。</li>
 * </ul>
 * <p>它不是"少一层事务"这么轻：{@code EmailReminderService} 与 {@code BlogContentPlanService}
 * 的富 {@code create} 第一句都是
 * {@code SELECT id FROM sys_user WHERE id = #{userId} FOR UPDATE}，那个行锁的全部意义就是
 * 串行化同一用户的并发创建；没有事务时它<b>锁不住任何东西</b>，后面的"先查幂等键再插入"与
 * "先数配额再插入"一起退化成 TOCTOU。这个缺陷真的发生过：第九刀（{@code automation-api}）
 * 漏标了 5 个方法，直到本测试写出来才发现。</p>
 *
 * <h2>判据</h2>
 * <p>对每个实现了端口接口的类：若某个端口方法 M <b>没有</b> {@code @Transactional}，
 * 而同类上存在同名的重载、或 {@code <M>ForView} 方法且它<b>带</b> {@code @Transactional}，
 * 就报缺陷。</p>
 * <p>这是一个<b>保守近似</b>：它不解析方法体去证明 M 真的转调了那个兄弟方法，
 * 只检查"危险区"里有没有缺注解。这样宁可要求一个用不上的注解，也不会漏掉一个真的转调。
 * 实测在全仓 11 个端口实现上零误报（见 {@code @see} 记录）。</p>
 *
 * <p>为什么 {@code @Override} 不能用来判"这是端口方法"：{@code java.lang.Override} 是
 * {@code SOURCE} 保留，反射看不见。所以改为拿方法签名去比它实现的那组接口方法。</p>
 *
 * @see com.xander.lab.migration.PortDelegationTransactionTest#theAuditedClassListIsComplete()
 */
class PortDelegationTransactionTest {

    /**
     * 本仓库所有实现了端口/网关接口的类。
     *
     * <p>不用手写清单也可以（{@link #theAuditedClassListIsComplete()} 会用类路径扫描证明它完整），
     * 写出来是为了让失败信息能直接点名类与方法。</p>
     */
    private static final List<Class<?>> PORT_IMPLEMENTATIONS = List.of(
            ModelGatewayService.class,
            BlogContentPlanService.class,
            BlogNotificationService.class,
            EmailReminderService.class,
            BillingService.class,
            UserLookupService.class,
            OssService.class,
            BlogMediaService.class,
            BlogService.class,
            PublicationService.class,
            RecitationService.class,
            KnowledgeQuizService.class,
            DashScopeRecitationAsrClient.class);

    @Test
    void everyPortMethodThatDelegatesToATransactionalSiblingCarriesItsOwnTransactional() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : PORT_IMPLEMENTATIONS) {
            Set<String> portMethods = interfaceMethodKeys(type);
            Set<String> transactionalNames = new TreeSet<>();
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                if (method.getAnnotation(Transactional.class) != null) {
                    transactionalNames.add(method.getName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                if (!Modifier.isPublic(method.getModifiers())) continue;
                if (!portMethods.contains(key(method))) continue;
                if (method.getAnnotation(Transactional.class) != null) continue;
                String name = method.getName();
                String sibling = null;
                if (transactionalNames.contains(name)) {
                    sibling = name;
                } else if (transactionalNames.contains(name + "ForView")) {
                    sibling = name + "ForView";
                }
                if (sibling != null) {
                    offenders.add(type.getSimpleName() + "#" + name + " 转调同类的 " + sibling);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些端口方法转调了同类的 @Transactional 方法，但自己没标 —— 同类自调用不走代理，"
                        + "被调方法的注解不会生效，这条路径会变成无事务执行：\n  "
                        + String.join("\n  ", offenders)
                        + "\n在端口方法上补 @Transactional（读方法补 @Transactional(readOnly = true)）。"
                        + "不要改成把实现体复制一份过来，那会变成两份可以漂移的逻辑。");
    }

    /**
     * 清单完整性：类路径上任何一个 {@code *Port} / {@code *Gateway} 的实现类都必须被审到。
     *
     * <p>没有这条，新抽一个端口之后 {@link #PORT_IMPLEMENTATIONS} 会悄悄漏掉它，
     * 于是"每个端口方法都审过"这句话就是假的。这与门禁里
     * {@code shared-kernel-classification} 缺反向判据、{@code util} 变成死条目是同一类错误：
     * <b>判据只朝一个方向看，另一个方向没人看。</b></p>
     */
    @Test
    void theAuditedClassListIsComplete() {
        Set<String> found = new TreeSet<>();
        for (BeanDefinition definition : scanImplementations()) {
            found.add(definition.getBeanClassName());
        }
        Set<String> audited = new TreeSet<>();
        for (Class<?> type : PORT_IMPLEMENTATIONS) {
            audited.add(type.getName());
        }
        assertEquals(found, audited,
                "端口实现类的清单与实际不符。类路径上多出来的是没被审到的（新增端口实现请加进 "
                        + "PORT_IMPLEMENTATIONS），少掉的是已经不存在的（删掉对应条目）。");
    }

    @Test
    void theAuditedClassesReallyDoImplementSomething() {
        // 反向 sanity：清单里混进一个不实现任何接口的类，上面那条断言会静默放过它。
        for (Class<?> type : PORT_IMPLEMENTATIONS) {
            assertFalse(type.isInterface(), type.getSimpleName() + " 是接口，不该出现在实现清单里。");
            assertTrue(type.getInterfaces().length > 0,
                    type.getSimpleName() + " 没有实现任何接口，不该出现在这份清单里。");
        }
    }

    /** 扫描 {@code com.xander.lab} 下所有 {@code *Port} / {@code *Gateway} 的具体实现。 */
    private static Set<BeanDefinition> scanImplementations() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter((metadataReader, factory) -> {
            String name = metadataReader.getClassMetadata().getClassName();
            if (!name.startsWith("com.xander.lab.")) return false;
            if (!metadataReader.getClassMetadata().isConcrete()) return false;
            for (String iface : metadataReader.getClassMetadata().getInterfaceNames()) {
                String simple = iface.substring(iface.lastIndexOf('.') + 1);
                if (simple.endsWith("Port") || simple.endsWith("Gateway")) return true;
            }
            return false;
        });
        return new LinkedHashSet<>(provider.findCandidateComponents("com.xander.lab"));
    }

    /** 类及其父接口声明的全部方法签名，形如 {@code name(java.lang.Long, java.lang.String)}。 */
    private static Set<String> interfaceMethodKeys(Class<?> type) {
        Set<String> keys = new LinkedHashSet<>();
        for (Class<?> iface : type.getInterfaces()) {
            collectInterfaceMethods(iface, keys);
        }
        return keys;
    }

    private static void collectInterfaceMethods(Class<?> iface, Set<String> keys) {
        for (Method method : iface.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) continue;
            keys.add(key(method));
        }
        for (Class<?> parent : iface.getInterfaces()) {
            collectInterfaceMethods(parent, keys);
        }
    }

    private static String key(Method method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(parameters[i].getName());
        }
        return builder.append(')').toString();
    }
}
