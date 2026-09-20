package com.xander.lab.automation.spi;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住五个记录类型的<b>分量名与顺序</b>，以及"它们确实没有框架依赖"。
 *
 * <p>为什么顺序也要钉：记录靠位置构造，而这些分量里到处是相邻同类型字段 ——
 * {@code EmailReminderView} 有六个相邻的 {@code String}，{@code ContentPlanEdit} 有五个。
 * 重排不会有任何编译错误，只会静默换字段（把收件人当主题发出去）。
 * 只断言"分量集合相同"是抓不到的。</p>
 *
 * <p>⚠️ 这里只钉契约自己。实现方把域类型换算成本类型时的落点，必须由<b>应用模块</b>的测试
 * 用互不相同的字面量逐字段钉住 —— 契约侧看不见 {@code EmailReminderTaskVO} /
 * {@code BlogContentPlanVO}。两边合起来才是完整的保护。</p>
 *
 * <p>⚠️ {@code EmailReminderView} 与 {@code EmailReminderDraft} 的 {@code sendTime} /
 * {@code scheduledAt} 是<b>相邻但顺序相反</b>的：读模型跟 {@code EmailReminderTaskVO} 的字段序
 * （{@code sendTime} 在前），写模型跟创建请求体的字段序（{@code scheduledAt} 在前）。
 * 这是刻意的 —— 两个换算方法各自是直线赋值。它不会变成静默错误，因为两者的类型不同
 * （{@code String} vs {@code Instant} / {@code OffsetDateTime}），换位是编译错误。
 * 但<b>不要</b>为了"看起来一致"去把其中一个改成另一个的顺序，那会把直线赋值变成交叉赋值。</p>
 */
class RecordShapeTest {

    @Test
    void emailReminderViewComponentsAndOrderAreFrozen() {
        assertRecord(EmailReminderView.class);
        assertEquals(List.of(
                "id:Long",
                "recipientEmail:String",
                "subject:String",
                "message:String",
                "frequency:String",
                "timezone:String",
                "sendTime:String",
                "scheduledAt:Instant",
                "status:String",
                "errorMessage:String",
                "sentAt:Instant"
        ), ContractShape.recordComponents(EmailReminderView.class),
                "分量名或顺序变了。这六个相邻的 String（recipientEmail / subject / message / "
                        + "frequency / timezone / sendTime）与后面的两个 Instant 之间没有任何"
                        + "编译期保护，重排只会静默换字段。");
    }

    @Test
    void emailReminderDraftComponentsAndOrderAreFrozen() {
        assertRecord(EmailReminderDraft.class);
        assertEquals(List.of(
                "clientRequestId:String",
                "recipientEmail:String",
                "subject:String",
                "message:String",
                "frequency:String",
                "timezone:String",
                "scheduledAt:OffsetDateTime",
                "sendTime:String",
                "recurrenceDay:Integer",
                "intervalDays:Integer",
                "contentType:String",
                "templateId:String"
        ), ContractShape.recordComponents(EmailReminderDraft.class),
                "分量名或顺序变了。scheduledAt 刻意是 OffsetDateTime 而不是 Instant："
                        + "域侧请求体就是 OffsetDateTime，换成本地时刻会丢掉调用方给的偏移量。");
    }

    @Test
    void contentPlanViewComponentsAndOrderAreFrozen() {
        assertRecord(ContentPlanView.class);
        assertEquals(List.of(
                "id:Long",
                "topic:String",
                "status:String",
                "timezone:String",
                "triggerTimes:List",
                "syncCsdn:Boolean",
                "syncJuejin:Boolean",
                "runOnce:Boolean",
                "nextRunAt:Instant",
                "lastRunAt:Instant",
                "errorMessage:String"
        ), ContractShape.recordComponents(ContentPlanView.class),
                "分量名或顺序变了。三个相邻的 Boolean（syncCsdn / syncJuejin / runOnce）"
                        + "换位不会有任何报错。");
    }

    @Test
    void contentPlanDraftComponentsAndOrderAreFrozen() {
        assertRecord(ContentPlanDraft.class);
        assertEquals(List.of(
                "clientRequestId:String",
                "topic:String",
                "topics:List",
                "triggerTimes:List",
                "triggerTime:String",
                "timezone:String",
                "audience:String",
                "tone:String",
                "syncCsdn:Boolean",
                "syncJuejin:Boolean"
        ), ContractShape.recordComponents(ContentPlanDraft.class),
                "分量名或顺序变了。topics 与 triggerTimes 都是 List，互换不会有任何报错。");
    }

    @Test
    void contentPlanEditComponentsAndOrderAreFrozen() {
        assertRecord(ContentPlanEdit.class);
        assertEquals(List.of(
                "topic:String",
                "triggerTimes:List",
                "triggerTime:String",
                "timezone:String",
                "audience:String",
                "tone:String",
                "syncCsdn:Boolean",
                "syncJuejin:Boolean"
        ), ContractShape.recordComponents(ContentPlanEdit.class),
                "分量名或顺序变了。五个相邻的 String 换位不会有任何报错。");
    }

    @Test
    void noRecordComponentMentionsAFrameworkType() {
        for (Class<?> type : List.of(EmailReminderView.class, EmailReminderDraft.class,
                ContentPlanView.class, ContentPlanDraft.class, ContentPlanEdit.class)) {
            ContractShape.assertNoFrameworkComponentTypes(type);
        }
    }

    /**
     * 契约模块必须与序列化框架无关。
     *
     * <p>唯一豁免是遗留的 {@code @JsonIgnoreProperties(ignoreUnknown = true)} —— 它改变线上
     * 反序列化行为，为历史兼容而留。本模块是全新的，一条都不该有。</p>
     */
    @Test
    void noRecordOrComponentCarriesAJacksonAnnotation() {
        for (Class<?> type : List.of(EmailReminderView.class, EmailReminderDraft.class,
                ContentPlanView.class, ContentPlanDraft.class, ContentPlanEdit.class)) {
            assertNoJackson(type.getAnnotations(), type.getSimpleName());
            for (RecordComponent component : type.getRecordComponents()) {
                assertNoJackson(component.getAnnotations(), type.getSimpleName() + "." + component.getName());
                try {
                    Field field = type.getDeclaredField(component.getName());
                    assertNoJackson(field.getAnnotations(), type.getSimpleName() + "." + component.getName());
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("记录分量没有对应的字段：" + component.getName(), e);
                }
            }
        }
    }

    private static void assertRecord(Class<?> type) {
        assertTrue(type.isRecord(), type.getSimpleName() + " 必须是 record："
                + "契约 DTO 用记录类型表达不可变值，且分量顺序由反射钉住。");
        assertEquals("com.xander.lab.automation.spi", type.getPackageName());
    }

    private static void assertNoJackson(Annotation[] annotations, String where) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            assertFalse(name.startsWith("com.fasterxml.jackson"),
                    where + " 上有 Jackson 注解 " + name + "。契约必须与序列化框架无关："
                            + "注解会把 Jackson 拖进每个依赖方，包括必须保持无 spring-webmvc 的网关。");
        }
    }
}
