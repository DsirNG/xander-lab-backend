package com.xander.lab.blogagent.spi;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 钉住三个记录类型的<b>分量名与顺序</b>，以及"它们确实没有框架依赖"。
 *
 * <p>为什么顺序也要钉：记录靠位置构造。{@link BlogAgentTaskView} 在 {@code id} 之后连着两个
 * {@code String}（{@code title} / {@code summary}），{@link BlogAgentPostView} 末尾连着两个
 * {@code Boolean}（{@code csdnSynced} / {@code juejinSynced}）。重排不会有任何编译错误，
 * 只会静默换字段 —— 把摘要当标题报给用户，或对着掘金的状态判 CSDN 的幂等。
 * 只断言"分量集合相同"是抓不到的。</p>
 *
 * <p>⚠️ 这里只钉契约自己。实现方把域实体 / VO 换算成本模块记录时的落点，必须由<b>应用模块</b>
 * 的测试用互不相同的字面量逐字段钉住 —— 契约侧看不见那些类型。两边合起来才是完整的保护。</p>
 */
class RecordShapeTest {

    @Test
    void taskDraftHasTwoAdjacentStrings() {
        assertEquals(List.of("input:String", "clientRequestId:String"),
                ContractShape.recordComponents(BlogAgentTaskDraft.class),
                "BlogAgentTaskDraft 的分量表变了。两个分量都是 String，写反不会有编译错误 —— "
                        + "症状是「任务内容变成了那串 invocation id」。");
    }

    @Test
    void taskViewHasFourComponents() {
        assertEquals(List.of("id:Long", "title:String", "summary:String", "publishedPostId:Long"),
                ContractShape.recordComponents(BlogAgentTaskView.class),
                "BlogAgentTaskView 的分量表变了。域实体有 25 个字段，这里只收跨域读得到的 4 个；"
                        + "加分量要先证明有跨域读取点，否则就是把实体的列耦合带进契约。");
    }

    @Test
    void postViewHasFiveComponentsWithTheTwoBooleansLast() {
        assertEquals(List.of("id:Long", "title:String", "status:Integer",
                        "csdnSynced:Boolean", "juejinSynced:Boolean"),
                ContractShape.recordComponents(BlogAgentPostView.class),
                "BlogAgentPostView 的分量表变了。csdnSynced 与 juejinSynced 是相邻的 Boolean，"
                        + "写反只会让工具对着掘金的状态判 CSDN 的幂等。");
    }

    @Test
    void postViewKeepsStatusBoxedSoUnknownStaysNull() {
        assertEquals(Integer.class,
                BlogAgentPostView.class.getRecordComponents()[2].getType(),
                "status 必须是装箱的 Integer。域侧 BlogPostVO.status 可以为 null，"
                        + "调用方的判据是 status == null || status != 1 —— 换成 int 会把"
                        + "「没传」变成「传了 0」（见「没传 ≠ 传了空」那条规则）。");
    }

    @Test
    void noRecordComponentMentionsAFrameworkType() {
        for (Class<?> type : List.of(BlogAgentTaskDraft.class, BlogAgentTaskView.class,
                BlogAgentPostView.class)) {
            ContractShape.assertNoFrameworkComponentTypes(type);
        }
    }

    @Test
    void noRecordOrComponentCarriesAJacksonOrValidationAnnotation() {
        for (Class<?> type : List.of(BlogAgentTaskDraft.class, BlogAgentTaskView.class,
                BlogAgentPostView.class)) {
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

    /**
     * 注解不许出现在记录、分量、以及分量的后备字段上。
     *
     * <p>三个位置都要查：{@code @JsonIgnoreProperties} 之类可以落在类型上，
     * {@code @JsonProperty} / {@code @NotBlank} 通常落在分量上，而框架也可能写到后备字段上。
     * 只查一处会漏。</p>
     */
    private static void assertNoFrameworkAnnotation(Annotation[] annotations, String where) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            if (name.startsWith("java.lang.") || name.startsWith("kotlin.")) continue;
            throw new AssertionError(where + " 上带了框架注解 " + name
                    + "：契约模块必须与序列化 / 校验框架无关（ADR-1）。"
                    + "注解带来的依赖会传给每一个依赖方，包括 V9 §19 要求保持 spring-webmvc-free 的网关。");
        }
    }
}
