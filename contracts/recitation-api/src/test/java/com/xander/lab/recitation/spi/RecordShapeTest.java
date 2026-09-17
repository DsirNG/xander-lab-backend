package com.xander.lab.recitation.spi;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住三个记录类型的<b>分量名与顺序</b>，以及"它们确实没有框架依赖"。
 *
 * <p>为什么顺序也要钉：记录靠位置构造，而 {@link MaterialView} 在 {@code id} 之后连着四个
 * {@code String}（{@code title} / {@code content} / {@code knowledgeType} / {@code testMode}）。
 * 重排不会有任何编译错误，只会静默换字段 —— 把正文当标题写进智能体的提示词。
 * 只断言"分量集合相同"是抓不到的。</p>
 *
 * <p>⚠️ 这里只钉契约自己。实现方把 {@code recitation.api.RecitationMaterialView} 换算成本模块
 * 记录时的落点，必须由<b>应用模块</b>的测试用互不相同的字面量逐字段钉住 ——
 * 契约侧看不见那个 VO。两边合起来才是完整的保护。</p>
 */
class RecordShapeTest {

    @Test
    void materialViewComponentsAndOrderAreFrozen() {
        assertRecord(MaterialView.class);
        assertEquals(List.of(
                "id:Long",
                "title:String",
                "content:String",
                "knowledgeType:String",
                "testMode:String",
                "masteryScore:BigDecimal",
                "masteryLevel:String",
                "reviewCount:int",
                "nextReviewAt:LocalDateTime"
        ), ContractShape.recordComponents(MaterialView.class),
                "分量名或顺序变了。id 之后四个相邻的 String（title / content / knowledgeType / "
                        + "testMode）之间没有任何编译期保护，重排只会静默换字段。");
    }

    @Test
    void materialViewIsTheNarrowedNineNotTheViewsFourteen() {
        // 收窄的判据：字符数 / 上次复习时间 / 归档时间 / 创建时间 / 更新时间
        // 在跨域这一侧一个读取点都没有。它们如果哪天被加进来，必须同时给出具名消费方 ——
        // "顺便带上"会把提供方的展示细节变成契约的一部分。
        List<String> names = ContractShape.recordComponents(MaterialView.class).stream()
                .map(component -> component.substring(0, component.indexOf(':')))
                .toList();
        assertEquals(List.of("id", "title", "content", "knowledgeType", "testMode",
                        "masteryScore", "masteryLevel", "reviewCount", "nextReviewAt"), names);
        for (String absent : List.of("characterCount", "lastReviewAt", "archivedAt",
                "createdAt", "updatedAt")) {
            assertFalse(names.contains(absent),
                    absent + " 被加进了 MaterialView，但跨域这一侧没有任何消费方读它。"
                            + "收窄型契约的字段集必须从消费方读取点重新反推，"
                            + "不能因为「视图里有」就带上。");
        }
    }

    @Test
    void materialDraftComponentsAndOrderAreFrozen() {
        assertRecord(MaterialDraft.class);
        assertEquals(List.of(
                "title:String",
                "content:String",
                "knowledgeType:String",
                "testMode:String"
        ), ContractShape.recordComponents(MaterialDraft.class),
                "分量名或顺序变了。四个都是 String，换位不会有任何报错。");
    }

    @Test
    void materialFilterComponentsAndOrderAreFrozen() {
        assertRecord(MaterialFilter.class);
        assertEquals(List.of(
                "search:String",
                "masteryLevel:String",
                "dueOnly:boolean",
                "limit:int"
        ), ContractShape.recordComponents(MaterialFilter.class),
                "分量名或顺序变了。只有四个分量：第五个归档维度（提供方的 ArchiveFilter）"
                        + "没有任何跨域消费方使用，刻意不带过界。");
    }

    @Test
    void noRecordComponentMentionsAFrameworkType() {
        for (Class<?> type : List.of(MaterialView.class, MaterialDraft.class, MaterialFilter.class)) {
            ContractShape.assertNoFrameworkComponentTypes(type);
        }
    }

    /**
     * ADR-1：契约模块必须与序列化框架无关。
     *
     * <p>唯一豁免是遗留的 {@code @JsonIgnoreProperties(ignoreUnknown = true)} —— 它改变线上
     * 反序列化行为，为历史兼容而留。本模块是全新的，一条都不该有。
     * {@code MaterialDraft} 尤其容易踩：它对应域侧的 {@code CreateRecitationMaterialRequest}，
     * 那个类型带 {@code jakarta.validation} 注解，搬过来的时候顺手带上就违反 ADR-1。</p>
     */
    @Test
    void noRecordOrComponentCarriesAJacksonOrValidationAnnotation() {
        for (Class<?> type : List.of(MaterialView.class, MaterialDraft.class, MaterialFilter.class)) {
            assertNoFrameworkAnnotation(type.getAnnotations(), type.getSimpleName());
            for (RecordComponent component : type.getRecordComponents()) {
                String where = type.getSimpleName() + "." + component.getName();
                assertNoFrameworkAnnotation(component.getAnnotations(), where);
                try {
                    Field field = type.getDeclaredField(component.getName());
                    assertNoFrameworkAnnotation(field.getAnnotations(), where);
                } catch (NoSuchFieldException e) {
                    throw new AssertionError("记录分量没有对应的字段：" + component.getName(), e);
                }
            }
        }
    }

    private static void assertRecord(Class<?> type) {
        assertTrue(type.isRecord(), type.getSimpleName() + " 必须是 record："
                + "契约 DTO 用记录类型表达不可变值，且分量顺序由反射钉住。");
        assertEquals("com.xander.lab.recitation.spi", type.getPackageName());
    }

    private static void assertNoFrameworkAnnotation(Annotation[] annotations, String where) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            assertFalse(name.startsWith("com.fasterxml.jackson"),
                    where + " 上有 Jackson 注解 " + name + "。ADR-1 要求契约与序列化框架无关："
                            + "注解会把 Jackson 拖进每个依赖方，包括必须保持无 spring-webmvc 的网关。");
            assertFalse(name.startsWith("jakarta.validation"),
                    where + " 上有校验注解 " + name + "。那是 HTTP 边界的东西（靠控制器的 @Valid 生效），"
                            + "而且会给契约加依赖。MaterialDraft 对应的域侧请求体带这些注解，"
                            + "搬过来时必须去掉。");
        }
    }
}
