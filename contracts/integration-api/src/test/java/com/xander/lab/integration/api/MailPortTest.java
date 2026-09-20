package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link MailPort} 的形状。
 *
 * <p>存在的理由与其它契约模块的端口测试相同：<b>端口变宽不会触发任何一道其它防线</b> ——
 * 编译过、耦合指标一动不动、26 条门禁全绿、消费方的 Mockito 桩一个都不用改
 * （所以消费方测试也全绿）。只有反射能守。</p>
 *
 * <p>本类额外钉一条性质：端口上<b>一个渲染参数都没有</b>。
 * {@code MailService.sendReminderMail} 收了 {@code scheduledAt} / {@code contentType} /
 * {@code templateId} 三个只服务于渲染的参数，而渲染需要业务域的模板类与别人家的配置键
 * ——那正是 {@code integration → automation} 反向边的来源。</p>
 */
class MailPortTest {

    @Test
    void declaresExactlyTheseThreeMethods() {
        assertEquals(List.of(
                "sendHtmlMail(String, String, String, String) -> void",
                "sendSimpleMail(String, String, String) -> void",
                "sendVerificationCode(String, String) -> void"
        ), ContractShape.declaredSignatures(MailPort.class));
    }

    /** {@code default} 方法会在不通知任何实现的情况下扩大契约面。 */
    @Test
    void declaresNoDefaultMethods() {
        ContractShape.assertNoDefaultMethods(MailPort.class);
    }

    /** 契约被每个服务依赖，包括"被拖进 spring-boot-mvc 就启动失败"的 WebFlux Gateway。 */
    @Test
    void declaresNoFrameworkTypes() {
        ContractShape.assertNoFrameworkTypes(MailPort.class);
    }

    /**
     * 三个方法都<b>不收</b> {@code userId}，而且这是刻意的。
     *
     * <p>{@code sendVerificationCode} 发生在登录<b>之前</b>（那时还没有身份）；
     * 另外两个是传输，归属判定在调用方自己的库里、调本端口之前就做完了。</p>
     */
    @Test
    void takesNoCallerIdAndThatIsDeliberate() {
        ContractShape.assertNoParameterIsNamed(MailPort.class, "userId");
    }

    /**
     * ⭐ 端口只带"发什么"，不带"怎么渲染"。
     *
     * <p>不许出现任何一个渲染参数：{@code scheduledAt}（要时区格式化）、
     * {@code contentType} / {@code templateId}（要挑版式）、{@code displayZone}。
     * 它们一旦回到端口上，提供方就又要认识业务域的模板类、又要读别人家的配置键，
     * 反向边与"读别人的配置"会一起复活。</p>
     *
     * <p>⚠️ 判据是<b>参数名</b>而不是类型：这三个参数里有两个是 {@code String}，
     * 与 {@code to} / {@code subject} 同型 —— 只断言类型的话，加回去一个
     * {@code sendHtmlMail(String, String, String, String, String templateId)} 是<b>绿着过</b>的。</p>
     */
    @Test
    void carriesNoRenderingParameter() {
        List<String> forbidden = List.of("scheduledAt", "contentType", "templateId", "displayZone", "zone");
        for (Method method : MailPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            for (Parameter parameter : method.getParameters()) {
                assertTrue(!forbidden.contains(parameter.getName()),
                        MailPort.class.getSimpleName() + "." + method.getName() + " 上出现了渲染参数 "
                                + parameter.getName() + "。渲染（模板、展示时区、PLAIN/HTML 归一化）"
                                + "属于调用方 —— 一旦它回到端口上，提供方就要重新 import 业务域的模板类、"
                                + "并读 email-reminder.display-zone（owner 是 content-automation）那个键。");
            }
        }
    }

    /** {@code sendHtmlMail} 收的是<b>两份成品正文</b>，不是"一份正文 + 一个 html 开关"。 */
    @Test
    void sendHtmlMailTakesFinishedPlainTextAndHtml() {
        Method method = methodNamed("sendHtmlMail");
        assertEquals(4, method.getParameterCount());
        assertEquals("to", method.getParameters()[0].getName());
        assertEquals("subject", method.getParameters()[1].getName());
        assertEquals("plainText", method.getParameters()[2].getName());
        assertEquals("html", method.getParameters()[3].getName());
    }

    /**
     * 按名字取方法，并且断言**恰好一个**同名方法。
     *
     * <p>⚠️ 收齐了同名方法却用 {@code findFirst()} 取第一个 —— 端口上出现**重载**时
     * 它会**按 JVM 的方法顺序**挑一个，上面的断言就开始检查另一个方法 ——
     * 而它们仍然"通过"。**没有失败信息，因为根本没有失败。**</p>
     */
    private static Method methodNamed(String name) {
        List<Method> matches = Arrays.stream(MailPort.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> method.getName().equals(name))
                .toList();
        assertEquals(1, matches.size(),
                "端口上应当恰好有一个方法叫 " + name + "，实际 " + matches.size()
                        + " 个。重载会让上面这些按名字取方法的断言悄悄检查错对象。");
        return matches.get(0);
    }
}
