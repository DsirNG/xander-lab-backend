package com.xander.lab.blogagent.spi;

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
 * <p>为什么顺序也要钉：记录靠位置构造。{@link BlogAgentTaskView} 在 {@code id} 之后连着两个
 * {@code String}（{@code title} / {@code summary}），{@link BlogAgentPostView} 末尾连着两个
 * {@code Boolean}（{@code csdnSynced} / {@code juejinSynced}），{@link BlogAgentImage} 的位置 2、3
 * 是两个相邻的 {@code String}、位置 5、6 也是，{@link BlogAgentImageUsage} 更是三个相邻的
 * {@code long}。重排不会有任何编译错误，只会静默换字段 —— 把摘要当标题报给用户、
 * 对着掘金的状态判 CSDN 的幂等、把尺寸当成模型名写进素材 meta、或者按错误的口径记账。
 * 只断言"分量集合相同"是抓不到的。</p>
 *
 * <p>⚠️ 这里只钉契约自己。实现方把域实体 / VO 换算成本模块记录时的落点，必须由<b>应用模块</b>
 * 的测试用互不相同的字面量逐字段钉住 —— 契约侧看不见那些类型。两边合起来才是完整的保护。</p>
 */
class RecordShapeTest {

    /** 本模块全部记录类型。新增一个就加进来，否则下面两条"没有框架依赖"的断言会静默漏掉它。 */
    private static final List<Class<?>> ALL_RECORDS = List.of(
            BlogAgentTaskDraft.class,
            BlogAgentTaskView.class,
            BlogAgentPostView.class,
            BlogAgentImage.class,
            BlogAgentImageUsage.class);

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
    void imageHasSixComponentsWithTheBytesFirstAndSizeLast() {
        assertEquals(List.of("bytes:byte[]", "contentType:String", "extension:String",
                        "usage:BlogAgentImageUsage", "model:String", "size:String"),
                ContractShape.recordComponents(BlogAgentImage.class),
                "BlogAgentImage 的分量表变了。前五个分量与提供方的 GeneratedImage 逐位同名同序"
                        + "（所以换算是一次位置映射），size 追加在**末尾**；插在中间会让「逐位对齐」"
                        + "失效，而失效了不会有编译错误。位置 2、3 与 5、6 各是两个相邻的 String，"
                        + "写反的症状是「contentType 被当成扩展名存下去」或者「尺寸被当成模型名写进 meta」。");
    }

    /**
     * {@code size} 必须在<b>最后</b>，而且要单独钉一次。
     *
     * <p>因为它是唯一一个"提供方那边没有对应分量"的字段 —— 将来有人要加第七个分量时，
     * 最自然的写法是插在 {@code model} 旁边（语义上它们是一组），而那正好会破坏位置映射。
     * 这条断言让那次改动变红，而不是让换算静默错位。</p>
     */
    @Test
    void imageKeepsSizeLastSoTheFirstFiveStayPositionallyAligned() {
        var components = BlogAgentImage.class.getRecordComponents();
        assertEquals("size", components[components.length - 1].getName(),
                "size 必须是最后一个分量。它是契约侧新增的字段（提供方的 GeneratedImage 里没有），"
                        + "追加在末尾才让「前五个逐位对齐」这句话成立。");
        assertEquals(String.class, components[components.length - 1].getType(),
                "size 是提供方请求尺寸的字符串形式（如 1536x1024）。");
    }

    @Test
    void imageUsageHasThreeAdjacentLongs() {
        assertEquals(List.of("inputTokens:long", "cachedTokens:long", "outputTokens:long"),
                ContractShape.recordComponents(BlogAgentImageUsage.class),
                "BlogAgentImageUsage 的分量表变了。这是**三个相邻的 long**：重排不会有任何编译错误，"
                        + "只会让用量按错误的口径记账 —— 而 token 记错不会抛异常，只会静默少记或多记。"
                        + "分量名与顺序刻意与 ModelUsage / payment.api.TokenUsage 一致，"
                        + "好让换算是一次直接映射。");
    }

    /**
     * ⚠️ 空用量必须是"三个分量都 {@code <= 0}"，不是"某一个为 0"。
     *
     * <p>网关没返回 {@code usage} 时提供方给 {@link BlogAgentImageUsage#NONE}，
     * 消费方据此退回一口价。如果 {@code isEmpty()} 写成"任一为 0 就算空"，
     * 一次只命中缓存、输出为 0 的正常调用会被判成"没有用量"，于是**静默不扣费**。</p>
     */
    @Test
    void imageUsageIsEmptyOnlyWhenEveryComponentIsNonPositive() {
        assertTrue(BlogAgentImageUsage.NONE.isEmpty(), "全零必须是空用量。");
        assertTrue(new BlogAgentImageUsage(0, 0, 0).isEmpty(), "全零与 NONE 等价，不做区分。");
        assertTrue(new BlogAgentImageUsage(-1, 0, -5).isEmpty(), "负数按 0 处理。");
        assertFalse(new BlogAgentImageUsage(120, 0, 0).isEmpty(),
                "只有 inputTokens 为正就不是空 —— 「任一为正即非空」。"
                        + "写成「任一为 0 就算空」会让这类调用静默不扣费。");
        assertFalse(new BlogAgentImageUsage(0, 20, 0).isEmpty(),
                "只有 cachedTokens 为正同样不是空。");
        assertFalse(new BlogAgentImageUsage(0, 0, 80).isEmpty(),
                "只有 outputTokens 为正同样不是空 —— 这正是「只出答案、没有输入」的那一类。");
    }

    @Test
    void noRecordComponentMentionsAFrameworkType() {
        for (Class<?> type : ALL_RECORDS) {
            ContractShape.assertNoFrameworkComponentTypes(type);
        }
    }

    @Test
    void noRecordOrComponentCarriesAJacksonOrValidationAnnotation() {
        for (Class<?> type : ALL_RECORDS) {
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
                    + "：契约模块必须与序列化 / 校验框架无关。"
                    + "注解带来的依赖会传给每一个依赖方，包括必须保持 spring-webmvc-free 的网关。");
        }
    }
}
