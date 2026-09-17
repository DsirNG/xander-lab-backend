package com.xander.lab.publishing;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xander.lab.common.PageData;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostStatus;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住"这次抽契约是纯重构"这件事本身。
 *
 * <h2>为什么这个测试必须住在应用模块</h2>
 * <p>它要同时看见两侧：契约侧（{@code content.api.ContentPost} / {@code ContentPostPage}）
 * 与实现侧（{@code publishing.api.BlogPostVO} / {@code common.PageData}）。
 * 契约模块<b>看不见</b> {@code BlogPostVO}（那是内容域的类型，而且 {@code PageData}
 * 住在带 MyBatis-Plus 的库里，契约根本依赖不到它）。所以镜像关系只能在这里守。</p>
 *
 * <h2>它到底在守什么</h2>
 * <p>{@code ContentPost} 与 {@code ContentPostPage} 是<b>镜像</b>而不是收窄：同名、同类型、
 * 同顺序，只少了 {@code tagsRaw}（那是被 {@code @JsonIgnore} 挡掉的内部字段）。
 * 选择镜像的全部价值就在于"换类型后序列化逐字节不变"，从而这次抽取可以被证明是纯重构 ——
 * 不进 {@code contract-changes.yaml}，不需要走 §11 的契约变更流程。</p>
 *
 * <p>这个价值只在"镜像关系真的成立"时才有。而它成立与否<b>没有任何编译期保证</b>：
 * 给 {@code BlogPostVO} 加一个字段、或给 {@code ContentPost} 少写一个分量，两边都照常编译。
 * 于是这里用三条独立的断言把它钉住：</p>
 * <ol>
 *   <li>类型层面的可序列化属性名集合必须相同（不需要实例，直接看类型）；</li>
 *   <li>实例层面的 JSON 树必须相同（能抓到 getter 上的注解、格式化器之类的差异）；</li>
 *   <li><b>生产代码里的那条转换</b>（{@code BlogService.toContract}）的输出必须与源对象同树
 *       —— 前两条只说明"两个类型长得一样"，这一条才说明"实现真的按镜像搬了"。</li>
 * </ol>
 */
class ContentPostShapeTest {

    /**
     * ⚠️ 必须显式注册 JSR-310 模块：裸 {@code new ObjectMapper()} 序列化 {@code LocalDate}
     * 会抛 {@code InvalidDefinitionException}（"Java 8 date/time type not supported by default"）。
     *
     * <p>这不是测试环境的特殊要求 —— 它正是 {@code McpJsonMapperFactory} 那次线上缺陷的同一个根因
     * （SDK 的默认 mapper 就是裸 {@code new ObjectMapper()}）。这里踩到它，说明"注册模块"这件事
     * 在每个自建 mapper 的地方都躲不掉。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    private static final List<String> POST_WIRE_KEYS = List.of(
            "id", "title", "summary", "content", "category", "categoryName",
            "coverImage", "tags", "userId", "author", "date", "readTime", "tips",
            "views", "status", "csdnSynced", "juejinSynced");

    private static final List<String> PAGE_WIRE_KEYS = List.of(
            "records", "total", "current", "pages", "size", "hasMore");

    // ---------------------------------------------------------------- 类型层

    /**
     * 两侧的可序列化属性名集合必须完全相同。
     *
     * <p>方向性很重要：{@code ContentPost} 多一个属性 → 前端会多拿到一个键；
     * 少一个属性 → 前端会少拿到一个键。{@code assertEquals} 两个方向都拦。</p>
     */
    @Test
    void contentPostExposesExactlyTheSameSerializablePropertiesAsBlogPostVo() {
        List<String> voProperties = serializablePropertyNames(BlogPostVO.class);
        List<String> contractProperties = serializablePropertyNames(ContentPost.class);

        assertEquals(voProperties, contractProperties,
                "ContentPost 必须是 BlogPostVO 的镜像：多一个或少一个键都会改变线上 JSON，"
                        + "这次抽取就不再是可证明的纯重构");
        assertEquals(POST_WIRE_KEYS.stream().sorted().toList(), voProperties,
                "BlogPostVO 的线上形状变了。注意 tagsRaw 是被 @JsonIgnore 挡掉的，不该出现在这里");
    }

    @Test
    void contentPostPageExposesExactlyTheSameSerializablePropertiesAsPageData() {
        List<String> pageDataProperties = serializablePropertyNames(PageData.class);
        List<String> contractProperties = serializablePropertyNames(ContentPostPage.class);

        assertEquals(pageDataProperties, contractProperties,
                "ContentPostPage 必须是 PageData 的镜像，理由同上");
        assertEquals(PAGE_WIRE_KEYS.stream().sorted().toList(), pageDataProperties,
                "PageData 的线上形状变了");
    }

    // ---------------------------------------------------------------- 实例层

    /**
     * 用同一组值构造两侧对象，序列化后的 JSON 树必须逐节点相同。
     *
     * <p>这一条比属性名集合更强：它还能抓到"同名但序列化行为不同"的情况 ——
     * 例如某一侧加了 {@code @JsonFormat}、或者把 {@code List} 换成了别的集合类型
     * 导致序列化形状变化。</p>
     */
    @Test
    void theSameValuesSerializeToTheSameJsonTreeOnBothSides() {
        BlogPostVO vo = fullVo();
        ContentPost post = equivalentContractPost();

        assertEquals(MAPPER.valueToTree(vo), MAPPER.valueToTree(post),
                "同样的值在两侧必须序列化成同一棵树，否则前端会看到形状变化");
    }

    @Test
    void theSamePageValuesSerializeToTheSameJsonTreeOnBothSides() {
        PageData<BlogPostVO> pageData = new PageData<>();
        pageData.setRecords(List.of(fullVo()));
        pageData.setTotal(101L);
        pageData.setCurrent(2L);
        pageData.setPages(11L);
        pageData.setSize(10L);
        pageData.setHasMore(true);

        ContentPostPage contractPage = new ContentPostPage(
                List.of(equivalentContractPost()), 101L, 2L, 11L, 10L, true);

        assertEquals(MAPPER.valueToTree(pageData), MAPPER.valueToTree(contractPage),
                "同样的分页值在两侧必须序列化成同一棵树");
    }

    // ---------------------------------------------------------------- 生产映射

    /**
     * <b>生产代码里那条转换</b>必须输出与源对象同树。
     *
     * <p>上面两条只证明"两个类型长得一样"，不能证明 {@code BlogService.toContract}
     * 真的按镜像逐字段搬了 —— 它完全可能漏掉 {@code categoryName}、或者把
     * {@code csdnSynced} 与 {@code juejinSynced} 写反（两个都是 {@code Boolean}，
     * 编译通过、类型全对）。而 {@code toContract} 是私有静态方法，没有公开路径能调到，
     * 所以这里用反射直接调它。</p>
     *
     * <p>反射而非公开：如果哪天有人把它改名或改成非静态，本测试会失败并提示改这里 ——
     * 这是有意的，因为那条映射是这次重构里唯一"人工逐个字段抄"的地方。</p>
     */
    @Test
    void theProductionMappingKeepsTheWireShapeIdentical() throws ReflectiveOperationException {
        BlogPostVO vo = fullVo();
        Method toContract = BlogService.class.getDeclaredMethod("toContract", BlogPostVO.class);
        toContract.setAccessible(true);

        Object converted = toContract.invoke(null, vo);

        assertEquals(MAPPER.valueToTree(vo), MAPPER.valueToTree(converted),
                "BlogService.toContract 的输出必须与 BlogPostVO 同树："
                        + "它逐个字段手写（刻意不用 BeanUtils），漏字段或把两个相邻同类型字段写反都不会编译报错");
    }

    /**
     * {@code tagsRaw} 不许出现在转换结果里 —— 它在 {@code BlogPostVO} 上被
     * {@code @JsonIgnore} 挡着，契约侧没有注解可挡，只能不声明。
     * 上面那条同树断言已经覆盖了这点，这里再显式说一次，避免以后有人"顺手补全"。
     */
    @Test
    void theProductionMappingDropsTagsRawBecauseTheContractCannotIgnoreIt() {
        List<String> componentNames = java.util.Arrays.stream(ContentPost.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertTrue(componentNames.stream().noneMatch(name -> name.toLowerCase().contains("raw")),
                "ContentPost 不该有 *Raw 分量");
    }

    // ---------------------------------------------------------------- 常量别名

    /**
     * {@code BlogService.STATUS_*} 必须是 {@code ContentPostStatus.*} 的<b>别名</b>，
     * 而且仍然是编译期常量（{@code switch} 的 {@code case} 标签要用）。
     *
     * <p>保留别名而不是删掉，是因为同域的 {@code BlogController}（前端 HTTP 契约）与
     * {@code BlogPlanRunService} 都在用 —— 删了会把一次契约抽取变成一次前端可见的改名。
     * 但别名一旦被改成"另一个字面量"，两边就会各自漂移，所以这里钉住相等。</p>
     */
    @Test
    void theBlogServiceConstantsAreAliasesOfTheContractVocabulary() throws ReflectiveOperationException {
        assertAlias("STATUS_DRAFT", ContentPostStatus.DRAFT);
        assertAlias("STATUS_PUBLISHED", ContentPostStatus.PUBLISHED);
        assertAlias("STATUS_TRASHED", ContentPostStatus.TRASHED);
    }

    private static void assertAlias(String fieldName, int expected) throws ReflectiveOperationException {
        Field field = BlogService.class.getField(fieldName);
        assertEquals(int.class, field.getType(), fieldName + " 必须是 int");
        assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
                fieldName + " 必须是 static final：它被当作 switch 的 case 标签用");
        assertEquals(expected, field.getInt(null),
                fieldName + " 必须是 ContentPostStatus 里那个值的别名，不能是独立字面量");
    }

    // ---------------------------------------------------------------- ADR-1

    /**
     * ADR-1：契约模块必须与序列化框架无关。
     *
     * <p>门禁 {@code contract-dependency-boundary} 查的是 import 与 POM 依赖；
     * 这里补一个类型层面的检查 —— 分量上不许挂 Jackson 注解。注解不产生 import 的
     * 情形是存在的（同包或 {@code @JsonIgnore} 这类可能被静态导入），
     * 而它恰恰是"为了让它序列化得对"最容易被加上的东西。</p>
     */
    @Test
    void theContractTypesCarryNoSerializationFrameworkAnnotations() {
        assertNoJacksonAnnotations(ContentPost.class);
        assertNoJacksonAnnotations(ContentPostPage.class);
    }

    private static void assertNoJacksonAnnotations(Class<?> type) {
        for (RecordComponent component : type.getRecordComponents()) {
            for (java.lang.annotation.Annotation annotation : component.getAnnotations()) {
                String name = annotation.annotationType().getName();
                assertTrue(!name.startsWith("com.fasterxml.jackson"),
                        type.getSimpleName() + "." + component.getName() + " 上挂了 " + name
                                + "：ADR-1 要求契约与序列化框架无关，DTO 能不能序列化由适配器决定");
            }
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 每个字段一个可区分的值，保证任何一处映射写错都能被同树断言抓到。 */
    private static BlogPostVO fullVo() {
        BlogPostVO vo = new BlogPostVO();
        vo.setId(11L);
        vo.setTitle("标题-标题");
        vo.setSummary("摘要-摘要");
        vo.setContent("正文-正文");
        vo.setCategory("backend");
        vo.setCategoryName("后端开发");
        vo.setCoverImage("https://cdn.example.com/c.png");
        vo.setTags(List.of("java", "spring"));
        vo.setTagsRaw("java,spring");
        vo.setUserId(22L);
        vo.setAuthor("XanderDING");
        vo.setDate(DATE);
        vo.setReadTime("3 min");
        vo.setTips("提示-提示");
        vo.setViews(800);
        vo.setStatus(ContentPostStatus.PUBLISHED);
        vo.setCsdnSynced(true);
        vo.setJuejinSynced(false);
        return vo;
    }

    private static ContentPost equivalentContractPost() {
        return new ContentPost(
                11L, "标题-标题", "摘要-摘要", "正文-正文", "backend", "后端开发",
                "https://cdn.example.com/c.png", List.of("java", "spring"), 22L, "XanderDING",
                DATE, "3 min", "提示-提示", 800, ContentPostStatus.PUBLISHED, true, false);
    }

    private static List<String> serializablePropertyNames(Class<?> type) {
        JavaType javaType = MAPPER.getTypeFactory().constructType(type);
        BeanDescription description = MAPPER.getSerializationConfig().introspect(javaType);
        return description.findProperties().stream()
                .map(BeanPropertyDefinition::getName)
                .sorted()
                .toList();
    }
}
