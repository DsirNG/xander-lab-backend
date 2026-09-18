package com.xander.lab.blogagent.application;

import com.xander.lab.blogagent.api.BlogAgentTaskCreateRequest;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentLeaseScheduler;
import com.xander.lab.blogagent.infrastructure.BlogAgentMessageMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.blogagent.infrastructure.BlogAgentSourceMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentTaskMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentVersionMapper;
import com.xander.lab.blogagent.spi.BlogAgentImage;
import com.xander.lab.blogagent.spi.BlogAgentImageUsage;
import com.xander.lab.blogagent.spi.BlogAgentPostView;
import com.xander.lab.blogagent.spi.BlogAgentTaskDraft;
import com.xander.lab.blogagent.spi.BlogAgentTaskView;
import com.xander.lab.common.ModelUsage;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.publishing.application.PublicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉住 {@link BlogAgentService} 作为 {@code BlogAgentPort} 实现时的<b>换算落点与换位</b>。
 *
 * <h2>为什么这一刀能只用 mock + {@code thenCallRealMethod}</h2>
 * <p>四个"薄壳"端口方法（{@code create} / {@code findReadyTaskByClientRequestId} /
 * {@code publish} / {@code runStream}）的函数体是"纯换算 + 转调同类富方法"，字段一个都不碰 ——
 * 所以可以在 mock 上直接跑真实方法体，让里面的 {@code this.xxx(...)} 落到打桩的富方法上，
 * 单独观察那一行换算。这比构造 14 个依赖的真实实例便宜得多。</p>
 * <p>{@code findPublishedPost} 是例外：它<b>本身就是实现</b>（没有 {@code ForView} 兄弟），
 * 函数体碰字段，所以它单独用真实实例测。</p>
 *
 * <h2>⚠️ 字面量必须互不相同</h2>
 * <p>端口是 {@code (userId, taskId)}，域侧是 {@code (taskId, userId)}。{@code 42L} 与 {@code 9L}
 * 一旦取成同一个数，换位写反不会有任何断言变红 —— 这是这一刀最容易静默出错的地方。</p>
 */
class BlogAgentPortMappingTest {

    private static final Long USER_ID = 42L;
    private static final Long TASK_ID = 9L;
    private static final Long POST_ID = 5L;

    /**
     * 草稿 → 域请求体。两个分量都是 {@code String} 且相邻，所以必须**互不相同**：
     * 写反的症状是"任务内容变成了那串 invocation id"。
     */
    @Test
    void theDraftLandsOnItsOwnFieldOfTheDomainRequest() {
        BlogAgentService service = mock(BlogAgentService.class);
        when(service.create(any(), any(BlogAgentTaskDraft.class))).thenCallRealMethod();
        when(service.create(any(), any(BlogAgentTaskCreateRequest.class))).thenAnswer(invocation -> {
            BlogAgentTaskCreateRequest request = invocation.getArgument(1);
            assertThat(request.getInput()).isEqualTo("内容字段");
            assertThat(request.getClientRequestId()).isEqualTo("幂等键字段");
            return task(11L, "标题", "摘要", null);
        });

        BlogAgentTaskView view = service.create(USER_ID,
                new BlogAgentTaskDraft("内容字段", "幂等键字段"));

        assertThat(view.id()).isEqualTo(11L);
        assertThat(view.title()).isEqualTo("标题");
        assertThat(view.summary()).isEqualTo("摘要");
    }

    /**
     * 域实体 → 契约视图。四个分量互不相同，且刻意让 {@code publishedPostId} 非空 ——
     * 为 {@code null} 时"字段漏带"与"字段本来就是空"看起来一样。
     */
    @Test
    void everyTaskComponentComesFromItsOwnSourceField() {
        BlogAgentService service = mock(BlogAgentService.class);
        when(service.findReadyTaskByClientRequestId(any(), any())).thenCallRealMethod();
        when(service.findReadyTaskByClientRequestIdForView(any(), any()))
                .thenReturn(task(11L, "标题字段", "摘要字段", POST_ID));

        BlogAgentTaskView view =
                service.findReadyTaskByClientRequestId(USER_ID, "幂等键字段");

        assertThat(view.id()).isEqualTo(11L);
        assertThat(view.title()).isEqualTo("标题字段");
        assertThat(view.summary()).isEqualTo("摘要字段");
        assertThat(view.publishedPostId()).isEqualTo(POST_ID);
    }

    /**
     * {@code BlogPostVO}（17 个字段）→ {@code BlogAgentPostView}（5 个）。
     *
     * <p>⚠️ {@code csdnSynced} 与 {@code juejinSynced} 是相邻的 {@code Boolean}，
     * 而 {@code Boolean} 只有两个取值 —— <b>一组字面量钉不满</b>（{@code (true,false)} 时对调仍全绿）。
     * 所以下面用<b>两组互补</b>的字面量各测一次。</p>
     */
    @Test
    void everyPostComponentComesFromItsOwnSourceField() {
        assertPostMapping(true, false);
        assertPostMapping(false, true);
    }

    private void assertPostMapping(boolean csdnSynced, boolean juejinSynced) {
        BlogAgentService service = mock(BlogAgentService.class);
        when(service.publish(any(), any())).thenCallRealMethod();
        when(service.publishForView(any(), any()))
                .thenReturn(post(POST_ID, "标题字段", 1, csdnSynced, juejinSynced));

        BlogAgentPostView view = service.publish(USER_ID, TASK_ID);

        assertThat(view.id()).isEqualTo(POST_ID);
        assertThat(view.title()).isEqualTo("标题字段");
        assertThat(view.status()).isEqualTo(1);
        assertThat(view.csdnSynced()).isEqualTo(csdnSynced);
        assertThat(view.juejinSynced()).isEqualTo(juejinSynced);
    }

    /**
     * ⚠️ <b>换位断言</b>：端口 {@code publish(userId, taskId)} 必须转调
     * {@code publishForView(taskId, userId)}。写反了会把 userId 当 taskId 去查
     * （响亮的失败，不是静默的错数据），但不该靠运气。
     *
     * <p>⚠️ 这里用 {@code thenAnswer} 而不是"拿具体字面量打桩、写反了自然匹配不上"：
     * 后者也能红，但症状是 {@code null} 一路传到断言里变成 NPE，报的信息指向"某处为 null"
     * 而不是"两个 Long 换位了"。**守卫的失败信息必须指向真正的原因** ——
     * 这条在本项目已经踩过一次（{@code getDeclaredMethods()[0]} 越界）。</p>
     *
     * <p>另外：{@link #everyPostComponentComesFromItsOwnSourceField()} 用的是
     * {@code any(), any()}，所以它<b>抓不到换位</b>。两个测试各有各的职责，缺一不可。</p>
     */
    @Test
    void publishSwapsTheTwoLongArgumentsOnTheWayToTheDomainMethod() {
        BlogAgentService service = mock(BlogAgentService.class);
        when(service.publish(any(), any())).thenCallRealMethod();
        when(service.publishForView(any(), any())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, Long.class)).isEqualTo(TASK_ID);
            assertThat(invocation.getArgument(1, Long.class)).isEqualTo(USER_ID);
            return post(POST_ID, "标题", 1, null, null);
        });

        assertThat(service.publish(USER_ID, TASK_ID).id()).isEqualTo(POST_ID);
    }

    /** ⚠️ 同一条换位断言，另一个方法：{@code runStream(userId, taskId, ...)}。 */
    @Test
    void runStreamSwapsTheTwoLongArgumentsOnTheWayToTheDomainMethod() {
        BlogAgentService service = mock(BlogAgentService.class);
        when(service.runStream(any(), any(), any(), any())).thenCallRealMethod();
        when(service.runStreamForView(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, Long.class)).isEqualTo(TASK_ID);
            assertThat(invocation.getArgument(1, Long.class)).isEqualTo(USER_ID);
            return taskVO(11L, "标题字段", "摘要字段", null);
        });

        BlogAgentTaskView view = service.runStream(USER_ID, TASK_ID,
                (event, data) -> { }, () -> false);

        assertThat(view.title()).isEqualTo("标题字段");
    }

    /**
     * {@code runStream} 是三参/四参两个重载 + 端口四参，最容易踩的是"三参重载内部
     * 把 {@code (taskId, userId)} 写回 {@code runStream(...)}" —— 那会静默解析到端口方法
     * （同样的 {@code (Long, Long, BiConsumer, BooleanSupplier)}），两个 Long 直接换位。
     *
     * <p>这条断言钉两件事：①转发目标是 {@code runStreamForView} 而不是端口方法
     * （用 {@code never()} 直接证明）；②转发时参数顺序保持 {@code (taskId, userId)}
     * （用 {@code thenAnswer} 断言实际收到的两个值）。</p>
     */
    @Test
    void theThreeArgumentOverloadForwardsToTheForViewMethodNotThePortMethod() {
        BlogAgentService service = mock(BlogAgentService.class);
        when(service.runStream(any(), any(), any(BiConsumer.class))).thenCallRealMethod();
        when(service.runStreamForView(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, Long.class)).isEqualTo(TASK_ID);
            assertThat(invocation.getArgument(1, Long.class)).isEqualTo(USER_ID);
            return taskVO(11L, "标题字段", null, null);
        });

        BlogAgentTaskVO vo = service.runStream(TASK_ID, USER_ID, (event, data) -> { });

        assertThat(vo.getTask().getId()).isEqualTo(11L);
        // 如果这行转发写成了 runStream(taskId, userId, onEvent, () -> false)，
        // 它会落到端口方法上 —— 下面这条就是那个错误的判据。
        verify(service, never()).runStream(any(), any(), any(), any());
    }

    /**
     * {@code findPublishedPost} 直接就是端口实现（没有 {@code ForView} 兄弟），函数体碰字段，
     * 所以这里用真实实例。它同时钉两件事：参数归一成 {@code (userId, taskId)}，
     * 以及**归属校验**（拿别人的任务 id 查要报错，而不是返回别人的文章）。
     */
    @Test
    void findPublishedPostReadsWithUserIdFirstAndRejectsSomeoneElsesTask() {
        BlogAgentTaskMapper taskMapper = mock(BlogAgentTaskMapper.class);
        BlogService blogService = mock(BlogService.class);
        BlogAgentTask owned = task(TASK_ID, "标题", "摘要", POST_ID);
        owned.setUserId(USER_ID);
        when(taskMapper.selectById(TASK_ID)).thenReturn(owned);
        when(blogService.getBlogById(POST_ID)).thenReturn(post(POST_ID, "标题字段", 1, true, false));

        BlogAgentService service = newService(taskMapper, blogService);

        BlogAgentPostView view = service.findPublishedPost(USER_ID, TASK_ID);
        assertThat(view.id()).isEqualTo(POST_ID);
        assertThat(view.title()).isEqualTo("标题字段");

        // 换位（把 userId 当 taskId）会让 selectById(42) 查不到任务 ⇒ 抛异常。
        assertThatThrownBy(() -> service.findPublishedPost(TASK_ID, USER_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 图片能力（同一模块的第二个端口）=====
    // BlogAgentImageClient.GeneratedImage（域内类型，带 ModelUsage）
    //   -> BlogAgentImage（契约类型，带 BlogAgentImageUsage）

    /**
     * 逐分量落点。六个字面量<b>互不相同</b>，所以任何一处错位都会红。
     *
     * <p>⚠️ 三个 {@code long} 尤其危险：它们是相邻的同类型分量，重排不会有任何编译错误，
     * 只会让用量按错误的口径记账 —— 而 token 记错不抛异常，只静默少记或多记。
     * 三个值刻意取成互不相同，逐位断言。</p>
     *
     * <p>⚠️ 位置 2、3（{@code contentType} / {@code extension}）与位置 5、6
     * （{@code model} / {@code size}）各是两个相邻的 {@code String}，同样逐位断言。</p>
     */
    @Test
    void everyImageComponentComesFromItsOwnSourceField() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        BlogAgentProperties properties = mock(BlogAgentProperties.class);
        when(properties.getImageSize()).thenReturn("1536x1024");
        when(imageClient.generate("拼好的 prompt")).thenReturn(new BlogAgentImageClient.GeneratedImage(
                new byte[]{7, 8, 9}, "image/webp", "webp", new ModelUsage(120, 20, 80), "端点模型字段"));

        BlogAgentService service = newService(mock(BlogAgentTaskMapper.class),
                mock(BlogService.class), imageClient, properties);

        BlogAgentImage image = service.generate("拼好的 prompt");

        assertThat(image.bytes()).isEqualTo(new byte[]{7, 8, 9});
        assertThat(image.contentType()).isEqualTo("image/webp");
        assertThat(image.extension()).isEqualTo("webp");
        assertThat(image.usage().inputTokens()).isEqualTo(120);
        assertThat(image.usage().cachedTokens()).isEqualTo(20);
        assertThat(image.usage().outputTokens()).isEqualTo(80);
        assertThat(image.model()).isEqualTo("端点模型字段");
        assertThat(image.size()).isEqualTo("1536x1024");
    }

    /**
     * ⚠️ <b>回归守卫</b>：{@code model} 必须来自<b>生成结果</b>，不是配置项。
     *
     * <p>提供方的 {@code generate} 会在候选端点之间<b>兜底重试</b>，所以真正产出这张图的可能是
     * 兜底端点；走管理台网关路径时 {@code properties.getImageModel()} 甚至可能是空的。
     * 下面两个字面量刻意不同，取错会红。</p>
     *
     * <p>改这一处之前，跨域的 {@code ImageGenerationTool} 写进素材 meta 的是<b>配置里的首选模型</b>，
     * 于是"兜底端点生成的图"会被记成首选模型的名字 —— 而图片已经生成、已经入库，没人会发现。
     * 同域的插图路径（{@code generateIllustrations}）一直是写 {@code generated.model()} 的，
     * 这一刀把同一个口径带给了跨域消费方。</p>
     */
    @Test
    void imageModelComesFromTheGeneratedResultNotFromTheConfiguration() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        BlogAgentProperties properties = mock(BlogAgentProperties.class);
        when(properties.getImageModel()).thenReturn("配置里的首选模型");
        when(imageClient.generate(anyString())).thenReturn(new BlogAgentImageClient.GeneratedImage(
                new byte[]{1}, "image/png", "png", ModelUsage.NONE, "实际兜底端点模型"));

        BlogAgentImage image = newService(mock(BlogAgentTaskMapper.class), mock(BlogService.class),
                imageClient, properties).generate("prompt");

        assertThat(image.model()).isEqualTo("实际兜底端点模型");
    }

    /**
     * {@code size} 由提供方适配器从 {@code BlogAgentProperties} 填 —— 这正是"消费方不必读
     * {@code @ConfigurationProperties}"的意思：配置项留在提供方，只有它的<b>取值</b>过界。
     */
    @Test
    void imageSizeComesFromTheConfigurationBecauseTheClientDoesNotReportIt() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        BlogAgentProperties properties = mock(BlogAgentProperties.class);
        when(properties.getImageSize()).thenReturn("1024x1024");
        when(imageClient.generate(anyString())).thenReturn(new BlogAgentImageClient.GeneratedImage(
                new byte[]{1}, "image/png", "png", ModelUsage.NONE, "m"));

        BlogAgentImage image = newService(mock(BlogAgentTaskMapper.class), mock(BlogService.class),
                imageClient, properties).generate("prompt");

        assertThat(image.size()).isEqualTo("1024x1024");
    }

    /**
     * 提供方拿到 {@code null} 用量时必须给 {@link BlogAgentImageUsage#NONE}，不能把 {@code null}
     * 过界。
     *
     * <p>契约承诺 {@code usage()} 非空（没拿到用量时给 {@code NONE}），所以消费方直接读三个分量、
     * 不做兜底。这条就是那个承诺的守卫：改坏了的症状是消费方在扣费那一步 NPE ——
     * 图片已经生成、已经入库，却因为记账抛异常让整次工具调用失败。</p>
     */
    @Test
    void nullUsageBecomesNoneRatherThanNull() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        when(imageClient.generate(anyString())).thenReturn(new BlogAgentImageClient.GeneratedImage(
                new byte[]{1}, "image/png", "png", null, "m"));

        BlogAgentImage image = newService(mock(BlogAgentTaskMapper.class), mock(BlogService.class),
                imageClient, mock(BlogAgentProperties.class)).generate("prompt");

        assertThat(image.usage()).isSameAs(BlogAgentImageUsage.NONE);
        assertThat(image.usage().isEmpty()).isTrue();
    }

    /**
     * prompt <b>原样</b>透传 —— 适配器不许加风格前缀。
     *
     * <p>措辞是渲染方的策略：独立配图工具拼 {@code TOOL_IMAGE_STYLE}，同域的博客插图拼
     * "准确、克制、专业的知识插图"。两者刻意不同，端口不替任何一方决定。
     * 用<b>精确字符串</b>打桩（而不是 {@code anyString()}）就是在断言"没加前缀也没截断"。</p>
     */
    @Test
    void generatePassesThePromptThroughUnchanged() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        String prompt = "原样传过来的 prompt，不许被加前缀";
        when(imageClient.generate(prompt)).thenReturn(new BlogAgentImageClient.GeneratedImage(
                new byte[]{1}, "image/png", "png", ModelUsage.NONE, "m"));

        newService(mock(BlogAgentTaskMapper.class), mock(BlogService.class),
                imageClient, mock(BlogAgentProperties.class)).generate(prompt);

        verify(imageClient).generate(prompt);
    }

    /** {@code isEnabled} 直接转发：未配置时是 {@code false}，不是异常。 */
    @Test
    void isEnabledForwardsTheClientsAnswerWithoutSwallowingIt() {
        BlogAgentImageClient enabled = mock(BlogAgentImageClient.class);
        when(enabled.isEnabled()).thenReturn(true);
        BlogAgentImageClient disabled = mock(BlogAgentImageClient.class);
        when(disabled.isEnabled()).thenReturn(false);

        assertThat(newService(mock(BlogAgentTaskMapper.class), mock(BlogService.class),
                enabled, mock(BlogAgentProperties.class)).isEnabled()).isTrue();
        assertThat(newService(mock(BlogAgentTaskMapper.class), mock(BlogService.class),
                disabled, mock(BlogAgentProperties.class)).isEnabled()).isFalse();
    }

    /**
     * 真实实例只给两个真的会被用到的依赖打桩，其余为 null —— 这些方法不碰它们。
     * 写成一个方法而不是用 {@code @InjectMocks}，是为了让"这个测试到底依赖哪些协作者"一眼可见。
     */
    private static BlogAgentService newService(BlogAgentTaskMapper taskMapper, BlogService blogService) {
        return newService(taskMapper, blogService, mock(BlogAgentImageClient.class),
                mock(BlogAgentProperties.class));
    }

    /**
     * 图片能力那两个方法碰的是 {@code imageClient} 与 {@code properties} 两个字段，
     * 所以这个重载把它们暴露出来。其余 12 个依赖仍然是 mock —— 这两个方法不碰它们。
     */
    private static BlogAgentService newService(BlogAgentTaskMapper taskMapper, BlogService blogService,
                                              BlogAgentImageClient imageClient,
                                              BlogAgentProperties properties) {
        return new BlogAgentService(taskMapper,
                mock(BlogAgentSourceMapper.class),
                mock(BlogAgentVersionMapper.class),
                mock(BlogAgentModelClient.class),
                new ObjectMapper(),
                mock(TransactionTemplate.class),
                blogService,
                imageClient,
                mock(MediaAssetPort.class),
                properties,
                mock(BlogAgentMessageMapper.class),
                mock(BlogAgentEventService.class),
                mock(BlogAgentLeaseScheduler.class),
                mock(BillingPort.class));
    }

    private static BlogAgentTask task(Long id, String title, String summary, Long publishedPostId) {
        BlogAgentTask task = new BlogAgentTask();
        task.setId(id);
        task.setTitle(title);
        task.setSummary(summary);
        task.setPublishedPostId(publishedPostId);
        return task;
    }

    private static BlogAgentTaskView taskView(Long id, String title, String summary, Long publishedPostId) {
        return new BlogAgentTaskView(id, title, summary, publishedPostId);
    }

    /** 域侧 {@code runStreamForView} 返回的是 VO（顺带带 tags / sources / versions），不是实体。 */
    private static BlogAgentTaskVO taskVO(Long id, String title, String summary, Long publishedPostId) {
        BlogAgentTaskVO vo = new BlogAgentTaskVO();
        vo.setTask(task(id, title, summary, publishedPostId));
        return vo;
    }

    private static BlogPostVO post(Long id, String title, Integer status,
                                   Boolean csdnSynced, Boolean juejinSynced) {
        BlogPostVO post = new BlogPostVO();
        post.setId(id);
        post.setTitle(title);
        post.setStatus(status);
        post.setCsdnSynced(csdnSynced);
        post.setJuejinSynced(juejinSynced);
        return post;
    }
}
