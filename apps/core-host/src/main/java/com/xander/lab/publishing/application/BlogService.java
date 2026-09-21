package com.xander.lab.publishing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.Constants;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.core.api.UserLookupPort;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.api.BlogPublishRhythmVO;
import com.xander.lab.publishing.api.BlogManageStatsVO;
import com.xander.lab.publishing.api.CategoryVO;
import com.xander.lab.publishing.api.TagVO;
import com.xander.lab.common.PageData;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostCreationPort;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import com.xander.lab.content.api.ContentPostStatus;
import com.xander.lab.publishing.domain.BlogPost;
import com.xander.lab.publishing.domain.BlogPostView;
import com.xander.lab.publishing.domain.BlogTag;
import com.xander.lab.publishing.infrastructure.BlogCategoryMapper;
import com.xander.lab.publishing.infrastructure.BlogPostMapper;
import com.xander.lab.publishing.infrastructure.BlogPostViewMapper;
import com.xander.lab.publishing.infrastructure.BlogArticleStatusCounts;
import com.xander.lab.publishing.infrastructure.BlogDailyViewCount;
import com.xander.lab.publishing.infrastructure.BlogTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.function.Function;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 博客服务
 * 提供博客的CRUD、分类、标签和阅读记录功能
 *
 * <p><b>同时是 {@link ContentPostPort} 的实现。</b>端口上的 7 个方法显式收 {@code userId}，
 * 类上的旧公开方法保持签名不变、仍然是“读 {@code UserContext} 再转调同一个私有实现体”的薄壳 ——
 * 这样 {@code BlogController} 的 17 个调用点与同域的 {@code LocalBlogPublisher} /
 * {@code BlogPlanRunService} / {@code BlogAgentService} 一行都不用改。
 * 身份从 ThreadLocal 变成参数是拆服务的前提：跨进程没有 ThreadLocal 可传。
 * 端口只收跨部署单元真正调到的 7 个方法，其余 16 个留在这里（见 {@link ContentPostPort}）。</p>
 *
 * <p>端口出参是契约类型 {@link ContentPost} / {@link ContentPostPage}，类内实现体仍然用
 * {@code BlogPostVO} / {@code PageData}（MyBatis 的投影目标就是前者），换算只在端口边界
 * 用 {@link #toContract} / {@link #toContractPage} 做一次，不在实现体里来回转。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogService implements ContentPostPort, ContentPostCreationPort {
    /** 见 {@link ContentPostStatus}：三个常量现在是契约词表的别名，不是副本。 */
    public static final int STATUS_DRAFT = ContentPostStatus.DRAFT;
    public static final int STATUS_PUBLISHED = ContentPostStatus.PUBLISHED;
    public static final int STATUS_TRASHED = ContentPostStatus.TRASHED;

    private static final String PUBLISH_REQUEST_PREFIX = "blog:publish:request:";

    private final BlogPostMapper blogPostMapper;
    private final BlogCategoryMapper blogCategoryMapper;
    private final BlogTagMapper blogTagMapper;
    private final BlogPostViewMapper blogPostViewMapper;
    private final UserLookupPort userLookupPort;
    private final StringRedisTemplate redisTemplate;

    public BlogManageStatsVO getManageStats(Long userId, LocalDate today) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        LocalDate endDate = today == null ? LocalDate.now() : today;
        LocalDate startDate = endDate.minusDays(6);
        BlogArticleStatusCounts overview = blogPostMapper.selectArticleStatusCounts(userId);
        List<LocalDate> publishedDates = blogPostMapper.selectPublishedDates(userId, startDate, endDate);
        Map<LocalDate, Long> viewCounts = blogPostViewMapper.selectDailyViews(userId, startDate, endDate)
                .stream().collect(Collectors.toMap(BlogDailyViewCount::getDate, BlogDailyViewCount::getViews));
        List<BlogManageStatsVO.DailyViews> days = startDate.datesUntil(endDate.plusDays(1))
                .map(date -> new BlogManageStatsVO.DailyViews(date, viewCounts.getOrDefault(date, 0L)))
                .toList();
        long recentViews = days.stream().mapToLong(BlogManageStatsVO.DailyViews::views).sum();
        long recentPublished = publishedDates == null ? 0 : publishedDates.size();
        BlogArticleStatusCounts counts = overview == null ? new BlogArticleStatusCounts() : overview;
        return BlogManageStatsVO.builder()
                .totalArticles(counts.getTotalArticles())
                .drafts(counts.getDrafts())
                .published(counts.getPublished())
                .trashed(counts.getTrashed())
                .recentViews(recentViews)
                .recentPublished(recentPublished)
                .averageViews(recentPublished == 0 ? BigDecimal.ZERO.setScale(1)
                        : BigDecimal.valueOf(recentViews).divide(BigDecimal.valueOf(recentPublished), 1, RoundingMode.HALF_UP))
                .dailyViews(days)
                .build();
    }

    /** Current user's actual local-site publishing counts for Monday through Sunday. */
    public BlogPublishRhythmVO getWeeklyPublishRhythm(Long userId, LocalDate today) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        LocalDate reference = today == null ? LocalDate.now() : today;
        LocalDate weekStart = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        Map<LocalDate, Long> counts = blogPostMapper
                .selectPublishedDates(userId, weekStart, weekEnd).stream()
                .filter(date -> date != null)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        List<BlogPublishRhythmVO.DayCount> days = weekStart.datesUntil(weekStart.plusDays(7))
                .map(date -> new BlogPublishRhythmVO.DayCount(date, counts.getOrDefault(date, 0L)))
                .toList();
        long total = days.stream().mapToLong(BlogPublishRhythmVO.DayCount::count).sum();
        return BlogPublishRhythmVO.builder()
                .weekStart(weekStart)
                .dailyAverage(BigDecimal.valueOf(total)
                        .divide(BigDecimal.valueOf(7), 1, RoundingMode.HALF_UP))
                .days(days)
                .build();
    }

    /**
     * 根据 ID 批量查询当前用户的文章标题（仅已发布，用于定时发文计划
     * 让 AI 避开与已发布文章的标题/角度重复）
     */
    public List<String> getTitlesByIds(Long userId, List<Long> ids) {
        if (userId == null || ids == null || ids.isEmpty()) {
            return List.of();
        }
        return blogPostMapper.selectList(new LambdaQueryWrapper<BlogPost>()
                        .eq(BlogPost::getUserId, userId)
                        .eq(BlogPost::getStatus, STATUS_PUBLISHED)
                        .in(BlogPost::getId, ids))
                .stream()
                .map(BlogPost::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .toList();
    }

    /**
     * 创建博客
     * 自动计算阅读时间、处理标签关联
     *
     * @param dto 博客创建请求
     * @return 创建的博客VO
     */
    @Transactional
    public BlogPostVO createBlog(BlogPostDTO dto) {
        return createBlog(dto, true);
    }

    /** Creates either a draft or a published post. */
    @Transactional
    public BlogPostVO createBlog(BlogPostDTO dto, boolean publish) {
        return createBlog(dto, publish, null);
    }

    /** Reuses a completed publish request so a client retry cannot create a duplicate post. */
    @Transactional
    public BlogPostVO createBlog(BlogPostDTO dto, boolean publish, String requestId) {
        return createBlog(UserContext.getUserId(), toInput(dto), publish, requestId);
    }

    @Override
    @Transactional
    public ContentPost create(Long userId, ContentPostInput input, boolean publish) {
        requireUser(userId);
        return toContract(createBlog(userId, input, publish, null));
    }

    /** Creates a post for the token-protected content-automation compatibility boundary. */
    @Transactional
    public BlogPostVO createBlog(Long userId, ContentPostInput input, boolean publish,
                                 String requestId) {
        requireUser(userId);
        if (input == null || input.title() == null || input.title().isBlank()
                || input.content() == null || input.content().isBlank()) {
            throw new IllegalArgumentException("标题和正文不能为空");
        }
        return createBlogInternal(userId, input, publish, requestId);
    }

    @Override
    @Transactional
    public ContentPost createIdempotent(Long userId, ContentPostInput input, boolean publish,
                                        String requestId) {
        return toContract(createBlog(userId, input, publish, requestId));
    }

    /**
     * 创建的核心：身份由参数传入，不读 {@code UserContext}。
     *
     * <p>端口侧对 {@code userId} 有非空校验，旧侧没有 —— 旧的 {@code createBlog} 假定调用方已经过
     * {@code AuthInterceptor}，而端口不能这么假定：跨进程后没有那层兜底，而写出一篇
     * {@code user_id = NULL} 的文章是任何归属查询都捞不回来的数据。</p>
     */
    private BlogPostVO createBlogInternal(Long userId, ContentPostInput input, boolean publish,
                                          String requestId) {
        String requestKey = publish && requestId != null && !requestId.isBlank()
                ? PUBLISH_REQUEST_PREFIX + userId + ":" + requestId.trim() : null;
        if (requestKey != null) {
            Long existingId = getPublishedPostId(userId, requestId);
            if (existingId != null) return getBlogById(existingId);
        }
        BlogPost post = new BlogPost();
        post.setTitle(input.title());
        post.setSummary(input.summary());
        post.setContent(input.content());
        post.setCategoryId(input.categoryId());
        post.setCoverImage(input.coverImage());
        post.setUserId(userId);
        post.setAuthor(resolveAuthor(userId));
        post.setReadTime(Math.max(1, input.content().length() / 500) + " min");
        post.setStatus(publish ? STATUS_PUBLISHED : STATUS_DRAFT);
        post.setPublishedAt(publish ? LocalDate.now() : null);
        blogPostMapper.insert(post);

        // 处理标签
        if (input.tags() != null && !input.tags().isEmpty()) {
            for (String tagName : input.tags()) {
                BlogTag tag = blogTagMapper.selectOne(
                        new LambdaQueryWrapper<BlogTag>().eq(BlogTag::getName, tagName));
                if (tag == null) {
                    tag = new BlogTag();
                    tag.setName(tagName);
                    blogTagMapper.insert(tag);
                }
                blogTagMapper.insertPostTag(post.getId(), tag.getId());
            }
        }

        if (requestKey != null) {
            redisTemplate.opsForValue().set(requestKey, post.getId().toString(), 24, TimeUnit.HOURS);
        }
        return getManagedBlogById(userId, post.getId());
    }

    /** Returns the post created by this user's publish request, if it has committed. */
    public Long getPublishedPostId(String requestId) {
        return getPublishedPostId(UserContext.getUserId(), requestId);
    }

    /** 核心：身份由参数传入。三次读 {@code UserContext} 在一次请求内是同一个值，所以只读一次。 */
    private Long getPublishedPostId(Long userId, String requestId) {
        if (requestId == null || requestId.isBlank() || userId == null) return null;
        String key = PUBLISH_REQUEST_PREFIX + userId + ":" + requestId.trim();
        String postId = redisTemplate.opsForValue().get(key);
        if (postId == null) return null;
        try {
            BlogPost post = blogPostMapper.selectById(Long.parseLong(postId));
            if (post != null && userId.equals(post.getUserId())) return post.getId();
        } catch (NumberFormatException ignored) { }
        redisTemplate.delete(key);
        return null;
    }

    /** Updates only supplied fields. Passing tags replaces all existing tags for the post. */
    @Transactional
    public BlogPostVO updateBlog(Long id, String title, String summary, String content,
                                 String categoryId, String coverImage, List<String> tags) {
        return updateBlog(UserContext.getUserId(), id, title, summary, content, categoryId, coverImage, tags);
    }

    @Override
    @Transactional
    public ContentPost update(Long userId, Long postId, ContentPostInput input) {
        return toContract(updateBlog(userId, postId, input.title(), input.summary(), input.content(),
                input.categoryId(), input.coverImage(), input.tags()));
    }

    /**
     * 更新的核心：身份由参数传入。
     *
     * <p>“{@code null} 表示不改这一项、{@code tags} 非 {@code null} 表示整体替换”这套规则原样保留 ——
     * 注意<b>空字符串不是 {@code null} 的同义词</b>：{@code title = ""} 是“改成空”，
     * 这里按 {@code null} 判定，不要顺手换成 {@code StringUtils.hasText}。</p>
     */
    private BlogPostVO updateBlog(Long userId, Long id, String title, String summary, String content,
                                  String categoryId, String coverImage, List<String> tags) {
        BlogPost existing = requireOwnedPost(id, userId);
        if (title == null && summary == null && content == null && categoryId == null && coverImage == null && tags == null) {
            throw new IllegalArgumentException("至少提供一个需要更新的字段");
        }
        BlogPost update = new BlogPost();
        update.setId(id);
        update.setTitle(title);
        update.setSummary(summary);
        update.setContent(content);
        update.setCategoryId(categoryId);
        update.setCoverImage(coverImage);
        if (content != null && !content.isBlank()) {
            update.setReadTime(Math.max(1, content.length() / 500) + " min");
        }
        if (userId != null) {
            update.setAuthor(resolveAuthor(userId));
        }
        blogPostMapper.updateById(update);

        if (tags != null) {
            blogTagMapper.deletePostTags(id);
            for (String tagName : tags) {
                BlogTag tag = blogTagMapper.selectOne(
                        new LambdaQueryWrapper<BlogTag>().eq(BlogTag::getName, tagName));
                if (tag == null) {
                    tag = new BlogTag();
                    tag.setName(tagName);
                    blogTagMapper.insert(tag);
                }
                blogTagMapper.insertPostTag(id, tag.getId());
            }
        }
        return getManagedBlogById(existing.getUserId(), id);
    }

    @Transactional
    public BlogPostVO updateBlogFromDto(Long id, BlogPostDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return updateBlog(id, dto.getTitle(), dto.getSummary(), dto.getContent(),
                dto.getCategoryId(), dto.getCoverImage(), dto.getTags());
    }

    /**
     * 更新文章状态：0=草稿 1=发布 -1=回收站。
     * 从回收站恢复默认回到草稿。
     */
    @Transactional
    public BlogPostVO updateBlogStatus(Long id, Integer status) {
        Long userId = UserContext.getUserId();
        BlogPost existing = requireOwnedPost(id, userId);
        int nextStatus = normalizeManagedStatus(status);
        BlogPost update = new BlogPost();
        update.setId(id);
        update.setStatus(nextStatus);
        if (nextStatus == STATUS_PUBLISHED) {
            update.setPublishedAt(existing.getPublishedAt() == null ? LocalDate.now() : existing.getPublishedAt());
        }
        blogPostMapper.updateById(update);
        return getManagedBlogById(userId, id);
    }

    /** Soft-delete: move post to recycle bin (status=-1). */
    @Transactional
    public void deleteBlog(Long id) {
        moveToTrash(UserContext.getUserId(), id);
    }

    @Override
    @Transactional
    public void moveToTrash(Long userId, Long postId) {
        requireOwnedPost(postId, userId);
        BlogPost update = new BlogPost();
        update.setId(postId);
        update.setStatus(STATUS_TRASHED);
        blogPostMapper.updateById(update);
    }

    /** Permanently deletes a trashed post and related records. */
    @Transactional
    public void permanentlyDeleteBlog(Long id) {
        Long userId = UserContext.getUserId();
        BlogPost existing = requireOwnedPost(id, userId);
        if (existing.getStatus() == null || existing.getStatus() != STATUS_TRASHED) {
            throw new IllegalArgumentException("仅回收站中的文章可以彻底删除");
        }
        blogPostViewMapper.delete(new LambdaQueryWrapper<BlogPostView>().eq(BlogPostView::getPostId, id));
        blogTagMapper.deletePostTags(id);
        blogPostMapper.deleteById(id);
    }

    /** Author is derived from the authenticated account, never from client or AI input. */
    private String resolveAuthor(Long userId) {
        if (userId == null) return "Anonymous";
        String displayName = userLookupPort.displayName(userId);
        return displayName != null ? displayName : "User " + userId;
    }

    public PageData<BlogPostVO> getMyBlogs(Integer status, String search, Integer page, Integer size) {
        Long userId = UserContext.getUserId();
        requireUser(userId);
        return getMyBlogs(userId, status, search, page, size);
    }

    @Override
    public ContentPostPage listMyPosts(Long userId, ContentPostQuery query, int page, int size) {
        requireUser(userId);
        if (query.juejinSynced() != null || query.csdnSynced() != null) {
            throw new IllegalArgumentException(
                    "分页读取不支持按同步状态过滤（分页 SQL 没有这两个条件），请用 listAllMyPosts");
        }
        return toContractPage(getMyBlogs(userId, query.status(), query.search(), page, size));
    }

    /** 分页读取的核心：身份由参数传入。 */
    private PageData<BlogPostVO> getMyBlogs(Long userId, Integer status, String search, Integer page, Integer size) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        Integer statusFilter = null;
        if (status != null) {
            statusFilter = normalizeManagedStatus(status);
        }
        Page<BlogPostVO> pageParam = new Page<>(safePage, safeSize);
        blogPostMapper.selectMyPostList(pageParam, userId, statusFilter, search);

        List<BlogPostVO> records = fillTags(pageParam.getRecords());

        PageData<BlogPostVO> result = new PageData<>();
        result.setRecords(records);
        result.setTotal(pageParam.getTotal());
        result.setCurrent(pageParam.getCurrent());
        result.setSize(pageParam.getSize());
        result.setPages(pageParam.getPages());
        result.setHasMore(result.getCurrent() < result.getPages());
        return result;
    }

    /** 返回用户当前可管理的博客总数，不包含回收站文章。 */
    @Transactional(readOnly = true)
    public long countManagedBlogs(Long userId) {
        requireUser(userId);
        return blogPostMapper.selectCount(new LambdaQueryWrapper<BlogPost>()
                .eq(BlogPost::getUserId, userId)
                .in(BlogPost::getStatus, STATUS_DRAFT, STATUS_PUBLISHED));
    }

    @Override
    public long countManaged(Long userId) {
        return countManagedBlogs(userId);
    }

    /**
     * 通用查询当前用户文章（不分页），筛选条件可任意组合：
     * status 限定草稿/已发布/回收站；search 标题摘要关键词；
     * juejinSynced / csdnSynced：true 仅已成功同步、false 仅未同步、null 不限；
     * limit 非空时仅返回最新前 N 条。供智能体一次调用拿全量，无需逐页聚合。
     *
     * <p>跨部署单元的调用方（agent 的 {@code QueryPostsTool} / {@code QueryUnsyncedPostsTool}）
     * 已改走 {@link ContentPostPort#listAllMyPosts}；这个入口保留，签名与行为都不变。</p>
     */
    public List<BlogPostVO> listMyPosts(Integer status, String search, Boolean juejinSynced, Boolean csdnSynced, Integer limit) {
        Long userId = UserContext.getUserId();
        requireUser(userId);
        return listMyPosts(userId, status, search, juejinSynced, csdnSynced, limit);
    }

    @Override
    public List<ContentPost> listAllMyPosts(Long userId, ContentPostQuery query, Integer limit) {
        requireUser(userId);
        return listMyPosts(userId, query.status(), query.search(), query.juejinSynced(),
                query.csdnSynced(), limit).stream().map(BlogService::toContract).toList();
    }

    /** 全量读取的核心：身份由参数传入。 */
    private List<BlogPostVO> listMyPosts(Long userId, Integer status, String search,
                                         Boolean juejinSynced, Boolean csdnSynced, Integer limit) {
        Integer statusFilter = null;
        if (status != null) {
            statusFilter = normalizeManagedStatus(status);
        }
        List<BlogPostVO> records = blogPostMapper.selectMyPostsAll(userId, statusFilter, search, juejinSynced, csdnSynced, limit);
        return fillTags(records);
    }

    private List<BlogPostVO> fillTags(List<BlogPostVO> records) {
        return records.stream()
                .peek(vo -> {
                    if (vo.getTags() == null || vo.getTags().isEmpty()) {
                        vo.setTags(blogTagMapper.selectTagNamesByPostId(vo.getId()));
                    } else {
                        vo.parseTags();
                    }
                })
                .collect(Collectors.toList());
    }

    public BlogPostVO getManagedBlogById(Long userId, Long id) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        BlogPostVO post = blogPostMapper.selectPostDetailAnyStatus(id);
        if (post == null || post.getUserId() == null || !userId.equals(post.getUserId())) {
            return null;
        }
        post.setTags(blogTagMapper.selectTagNamesByPostId(post.getId()));
        return post;
    }

    @Override
    public ContentPost findOwned(Long userId, Long postId) {
        return toContract(getManagedBlogById(userId, postId));
    }

    private BlogPost requireOwnedPost(Long id, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        BlogPost existing = blogPostMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("文章不存在");
        }
        if (existing.getUserId() == null || !userId.equals(existing.getUserId())) {
            throw new IllegalArgumentException("无权操作该文章");
        }
        return existing;
    }

    private int normalizeManagedStatus(Integer status) {
        if (status == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        if (status != STATUS_DRAFT && status != STATUS_PUBLISHED && status != STATUS_TRASHED) {
            throw new IllegalArgumentException("不支持的文章状态");
        }
        return status;
    }

    /**
     * 获取博客列表（支持搜索、分类、标签过滤）
     */
    public PageData<BlogPostVO> getBlogs(String search, String category, String tag, Integer page, Integer size) {
        Page<BlogPostVO> pageParam = new Page<>(page, size);
        blogPostMapper.selectPostList(pageParam, search, category, tag);

        List<BlogPostVO> records = pageParam.getRecords().stream()
                .peek(vo -> vo.setTags(blogTagMapper.selectTagNamesByPostId(vo.getId())))
                .collect(Collectors.toList());

        PageData<BlogPostVO> result = new PageData<>();
        result.setRecords(records);
        result.setTotal(pageParam.getTotal());
        result.setCurrent(pageParam.getCurrent());
        result.setSize(pageParam.getSize());
        result.setPages(pageParam.getPages());
        return result;
    }

    /**
     * 获取最新博客列表
     *
     * @param limit 数量限制
     * @return 最新博客列表
     */
    public List<BlogPostVO> getRecentBlogs(int limit) {
        List<BlogPostVO> posts = blogPostMapper.selectRecentPosts(limit);
        posts.forEach(vo -> vo.setTags(blogTagMapper.selectTagNamesByPostId(vo.getId())));
        return posts;
    }

    /**
     * 根据ID获取博客详情
     *
     * @param id 博客ID
     * @return 博客详情VO
     */
    public BlogPostVO getBlogById(Long id) {
        BlogPostVO post = blogPostMapper.selectPostDetail(id);
        if (post != null) {
            post.setTags(blogTagMapper.selectTagNamesByPostId(post.getId()));
        }
        return post;
    }

    /**
     * 记录博客阅读（含防刷机制）
     * 登录用户按 userId 去重，未登录用户按 IP 去重，冷却时间 24 小时。
     * 每次有效阅读都会：
     * 1. 原子递增 blog_post.views
     * 2. 写入 blog_post_view 阅读记录
     * 3. 在 Redis 设置去重 key
     *
     * @param postId    文章ID
     * @param userId    当前登录用户ID（可为null）
     * @param ip        客户端IP
     * @param userAgent 浏览器UA
     * @return true=有效阅读（已计数），false=冷却期内重复阅读（未计数）
     */
    @Transactional
    public boolean recordView(Long postId, Long userId, String ip, String userAgent) {
        // 构建去重 key：登录用户按userId，未登录按IP
        String identifier = (userId != null) ? "user:" + userId : "ip:" + ip;
        String dedupKey = Constants.REDIS_VIEW_PREFIX + postId + ":" + identifier;

        // 冷却期内不重复计数
        if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
            return false;
        }

        // 1. 原子递增阅读次数
        blogPostViewMapper.incrementViewCount(postId);

        // 2. 写入阅读记录（保留数据，功能暂不开放）
        BlogPostView view = new BlogPostView();
        view.setPostId(postId);
        view.setUserId(userId);
        view.setIpAddress(ip);
        view.setUserAgent(userAgent != null && userAgent.length() > 500
                ? userAgent.substring(0, 500) : userAgent);
        blogPostViewMapper.insert(view);

        // 3. 设置 Redis 去重 key（24小时过期）
        redisTemplate.opsForValue().set(dedupKey, "1", Constants.VIEW_DEDUP_HOURS, TimeUnit.HOURS);

        log.info("[BlogView] 文章 {} 阅读 +1 ({})", postId, identifier);
        return true;
    }

    /**
     * 获取所有分类（含文章数量）
     *
     * @return 分类列表
     */
    public List<CategoryVO> getCategories() {
        return blogCategoryMapper.selectCategoriesWithCount();
    }

    /**
     * 获取所有标签（含文章数量，降序）
     *
     * @return 标签列表
     */
    public List<TagVO> getAllTags() {
        return blogTagMapper.selectAllTagsWithCount();
    }

    /**
     * 获取热门标签
     *
     * @param limit 数量限制
     * @return 热门标签列表
     */
    public List<TagVO> getPopularTags(int limit) {
        return blogTagMapper.selectPopularTags(limit);
    }

    // ------------------------------------------------------------------
    // 端口边界：共用校验与契约换算
    // ------------------------------------------------------------------

    /** 未登录的判定文案与 HTTP 状态都靠这一句，多处共用，文案必须逐字一致。 */
    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
    }

    /**
     * 前端 DTO → 契约输入。
     *
     * <p>{@code BlogPostDTO.publish} 刻意不映射：端口把“发不发”作为 {@code create} 的独立参数，
     * 同一个语义不该有两个来源（{@code BlogController} 读 DTO 上的 {@code publish}，
     * 那是前端 HTTP 契约的事）。</p>
     */
    private static ContentPostInput toInput(BlogPostDTO dto) {
        return new ContentPostInput(dto.getTitle(), dto.getSummary(), dto.getContent(),
                dto.getCategoryId(), dto.getCoverImage(), dto.getTags());
    }

    /**
     * {@code BlogPostVO} → {@link ContentPost}。
     *
     * <p><b>逐字段手写，不要换成 {@code BeanUtils.copyProperties} 或反射。</b>字段名对不上时
     * 反射版本静默留 {@code null}（而且它按名匹配，{@code category} 与 {@code categoryName}
     * 这种前缀关系还会互相污染），手写版本编译期就红。{@code tagsRaw} 刻意不映射 ——
     * 它是 {@code @JsonIgnore} 的持久化承接字段，不属于线上形状。</p>
     *
     * <p>{@code null} 原样透传，不做归一：镜像的判据是“换类型后序列化逐字节不变”，
     * 见 {@link ContentPost}。</p>
     */
    private static ContentPost toContract(BlogPostVO vo) {
        if (vo == null) return null;
        return new ContentPost(vo.getId(), vo.getTitle(), vo.getSummary(), vo.getContent(),
                vo.getCategory(), vo.getCategoryName(), vo.getCoverImage(), vo.getTags(),
                vo.getUserId(), vo.getAuthor(), vo.getDate(), vo.getReadTime(), vo.getTips(),
                vo.getViews(), vo.getStatus(), vo.getCsdnSynced(), vo.getJuejinSynced());
    }

    /** {@code PageData} → {@link ContentPostPage}，同样逐字段手写、同样不做归一。 */
    private static ContentPostPage toContractPage(PageData<BlogPostVO> page) {
        if (page == null) return null;
        List<ContentPost> records = page.getRecords() == null
                ? null
                : page.getRecords().stream().map(BlogService::toContract).toList();
        return new ContentPostPage(records, page.getTotal(), page.getCurrent(),
                page.getPages(), page.getSize(), page.isHasMore());
    }
}
