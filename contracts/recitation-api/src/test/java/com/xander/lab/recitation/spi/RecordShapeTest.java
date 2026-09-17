package com.xander.lab.recitation.spi;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住七个记录类型的<b>分量名与顺序</b>，以及"它们确实没有框架依赖"。
 *
 * <p>为什么顺序也要钉：记录靠位置构造，而 {@link MaterialView} 在 {@code id} 之后连着四个
 * {@code String}（{@code title} / {@code content} / {@code knowledgeType} / {@code testMode}）。
 * 重排不会有任何编译错误，只会静默换字段 —— 把正文当标题写进智能体的提示词。
 * 只断言"分量集合相同"是抓不到的。</p>
 *
 * <p>测验那一组有同样的两处：{@link QuizSessionView} 里四个相邻的 {@code String}
 * （{@code title} / {@code knowledgeType} / {@code testMode} / {@code content}）与两个相邻的
 * {@code LocalDateTime}（{@code lastReviewAt} / {@code nextReviewAt}）；
 * {@link QuizOutcomeView} 里两个相邻的 {@code String}
 * （{@code masteryLevel} / {@code previousMasteryLevel}）。</p>
 *
 * <p>⚠️ 这里只钉契约自己。实现方把域类型换算成本模块记录时的落点，必须由<b>应用模块</b>
 * 的测试用互不相同的字面量逐字段钉住 —— 契约侧看不见那些域类型。两边合起来才是完整的保护。</p>
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
    void quizSessionViewComponentsAndOrderAreFrozen() {
        assertRecord(QuizSessionView.class);
        assertEquals(List.of(
                "quizSessionId:Long",
                "materialId:Long",
                "title:String",
                "knowledgeType:String",
                "testMode:String",
                "content:String",
                "contentTruncated:boolean",
                "contentLength:int",
                "masteryScore:BigDecimal",
                "masteryLevel:String",
                "reviewCount:int",
                "lastReviewAt:LocalDateTime",
                "nextReviewAt:LocalDateTime",
                "dueForReview:boolean",
                "recentQuizzes:List"
        ), ContractShape.recordComponents(QuizSessionView.class),
                "分量名或顺序变了。这里有两处换位风险：title / knowledgeType / testMode / content "
                        + "四个相邻 String（换位会把测试模式当正文去出题），"
                        + "以及 lastReviewAt / nextReviewAt 两个相邻 LocalDateTime（换位会让"
                        + "\"上次复习\"落在未来、复习建议整体错位）。");
    }

    @Test
    void quizSessionViewKeepsAllFifteenBecauseEveryOneHasAReader() {
        // 这一条与 MaterialView 的收窄断言方向相反，故意留着：
        // "收窄"的判据是"每个字段都有具名消费方"，不是"字段数必须比域侧少"。
        // 唯一跨域消费方 QuizKnowledgeTool 逐个读了全部 15 个并写进工具输出。
        // 谁以后想砍字段，得先指出哪个消费方不读了。
        List<String> names = ContractShape.recordComponents(QuizSessionView.class).stream()
                .map(component -> component.substring(0, component.indexOf(':')))
                .toList();
        assertEquals(15, names.size(),
                "QuizSessionView 的分量数变了。它是本模块唯一一个\"全读\"的视图；"
                        + "砍字段要先给出\"哪个消费方不读它\"。");
        for (String required : List.of("content", "testMode", "dueForReview", "nextReviewAt",
                "recentQuizzes", "contentTruncated")) {
            assertTrue(names.contains(required),
                    required + " 被从 QuizSessionView 里删掉了。它由 QuizKnowledgeTool 读取并写进"
                            + "工具输出，删了会让智能体少一条出题依据（且不会有编译错误）。");
        }
    }

    @Test
    void quizAttemptViewComponentsAndOrderAreFrozen() {
        assertRecord(QuizAttemptView.class);
        assertEquals(List.of(
                "questionCount:int",
                "correctCount:int",
                "score:BigDecimal",
                "verdict:String",
                "itemsJson:String",
                "createdAt:LocalDateTime"
        ), ContractShape.recordComponents(QuizAttemptView.class),
                "分量名或顺序变了。questionCount / correctCount 两个相邻 int 换位不会有编译错误，"
                        + "只会让智能体以为\"答对 2 题中的 1 题\"是\"答对 1 题中的 2 题\"。");
    }

    @Test
    void quizAttemptViewDropsTheThreeFrontendOnlyComponents() {
        // 域侧 recitation.api.KnowledgeQuizAttemptView 有 9 个分量，跨域只读 6 个。
        // id / materialId / testMode 都是给知识页列表用的，智能体不需要。
        List<String> names = ContractShape.recordComponents(QuizAttemptView.class).stream()
                .map(component -> component.substring(0, component.indexOf(':')))
                .toList();
        for (String absent : List.of("id", "materialId", "testMode")) {
            assertFalse(names.contains(absent),
                    absent + " 被加进了 QuizAttemptView，但跨域这一侧没有消费方读它。"
                            + "materialId 尤其不该带：整份历史都属于同一个 materialId，"
                            + "那个值由外层 QuizSessionView.materialId() 给。");
        }
    }

    @Test
    void quizAttemptViewItemsIsTextBecauseTheDomainFieldHasNoShape() {
        // 域侧 KnowledgeQuizAttemptView.items 的类型是 Object（items_json 列反序列化而来），
        // 一个没有形状的字段。契约不许带 JsonNode（ADR-1），所以它走文本 ——
        // 与 agent-api 的 RemoteTool 同一个做法。
        assertEquals("itemsJson:String",
                ContractShape.recordComponents(QuizAttemptView.class).stream()
                        .filter(component -> component.startsWith("itemsJson:"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("QuizAttemptView 上没有 itemsJson 分量")),
                "itemsJson 的类型不是 String。它必须是文本：域侧那个字段是 Object（没有形状），"
                        + "而契约不许带 JsonNode。改成某个结构类型意味着契约开始假装知道"
                        + "历史行里存的是什么形状 —— 那些行里可能有旧版本写下的内容。");
    }

    @Test
    void quizOutcomeViewComponentsAndOrderAreFrozen() {
        assertRecord(QuizOutcomeView.class);
        assertEquals(List.of(
                "attemptId:Long",
                "materialId:Long",
                "title:String",
                "score:BigDecimal",
                "questionCount:int",
                "correctCount:int",
                "masteryLevel:String",
                "previousMasteryLevel:String",
                "reviewCount:int",
                "nextReviewAt:LocalDateTime"
        ), ContractShape.recordComponents(QuizOutcomeView.class),
                "分量名或顺序变了。masteryLevel / previousMasteryLevel 两个相邻 String 换位"
                        + "不会有编译错误，只会让用户看到\"从 APPLYING 降到了 FAMILIAR\"这种反向文案。");
    }

    @Test
    void quizOutcomeViewDropsTheAuditColumnNobodyReads() {
        // 域侧 QuizOutcome 有 11 个分量，少的是 lastReviewAt：
        // 唯一跨域消费方 GradeAnswerTool 不读它，服务自己的测试也不读它。
        assertFalse(ContractShape.recordComponents(QuizOutcomeView.class).stream()
                        .anyMatch(component -> component.startsWith("lastReviewAt:")),
                "lastReviewAt 被加进了 QuizOutcomeView，但没有任何读者。"
                        + "收窄型契约的字段集必须从消费方读取点重新反推。");
    }

    @Test
    void gradedItemDraftComponentsAndOrderAreFrozen() {
        assertRecord(GradedItemDraft.class);
        assertEquals(List.of(
                "question:String",
                "userAnswer:String",
                "comment:String",
                "credit:BigDecimal"
        ), ContractShape.recordComponents(GradedItemDraft.class),
                "分量名或顺序变了。前三个都是 String，换位不会有任何报错 —— "
                        + "把用户原话当题目写进去会让掌握度页面上的证据整体错位。");
    }

    @Test
    void gradedItemDraftCreditStaysNullableSoAnUngradedQuestionIsNotSilentlyScoredZero() {
        // credit 的 null = "智能体没判这一题"，提供方会拒绝整次提交；
        // 0 = "明确判了完全不对"，是一条正常结果。
        // 混同的后果是静默把没判的题算成答错，把用户掌握度往下压 —— 而且不报错。
        assertEquals(BigDecimal.class,
                GradedItemDraft.class.getRecordComponents()[3].getType(),
                "credit 必须保持 BigDecimal（可空引用类型）。换成原始类型或加兜底"
                        + "会让\"没判分\"与\"0 分\"不再可分。");
    }

    @Test
    void noRecordComponentMentionsAFrameworkType() {
        for (Class<?> type : ALL_RECORDS) {
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
     * 本模块的全部记录类型。
     *
     * <p>写成一份清单是为了让"新加了一个记录类型但忘了加进框架依赖检查"变成一次失败 ——
     * 分两处各列一遍的话，新类型只加进一处就会漏掉另一半检查。</p>
     */
    private static final List<Class<?>> ALL_RECORDS = List.of(
            MaterialView.class, MaterialDraft.class, MaterialFilter.class,
            QuizSessionView.class, QuizAttemptView.class, QuizOutcomeView.class,
            GradedItemDraft.class);

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
