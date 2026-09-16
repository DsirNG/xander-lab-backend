package com.xander.lab.publishing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.Constants;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.api.BlogPublishRhythmVO;
import com.xander.lab.publishing.api.BlogManageStatsVO;
import com.xander.lab.publishing.api.CategoryVO;
import com.xander.lab.publishing.api.TagVO;
import com.xander.lab.common.PageData;
import com.xander.lab.publishing.domain.BlogPost;
import com.xander.lab.publishing.domain.BlogPostView;
import com.xander.lab.publishing.domain.BlogTag;
import com.xander.lab.identity.domain.User;
import com.xander.lab.publishing.infrastructure.BlogCategoryMapper;
import com.xander.lab.publishing.infrastructure.BlogPostMapper;
import com.xander.lab.publishing.infrastructure.BlogPostViewMapper;
import com.xander.lab.publishing.infrastructure.BlogArticleStatusCounts;
import com.xander.lab.publishing.infrastructure.BlogDailyViewCount;
import com.xander.lab.publishing.infrastructure.BlogTagMapper;
import com.xander.lab.identity.infrastructure.UserMapper;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogService {
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_TRASHED = -1;

    private static final String PUBLISH_REQUEST_PREFIX = "blog:publish:request:";

    private final BlogPostMapper blogPostMapper;
    private final BlogCategoryMapper blogCategoryMapper;
    private final BlogTagMapper blogTagMapper;
    private final BlogPostViewMapper blogPostViewMapper;
    private final UserMapper userMapper;
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
        String requestKey = publish && requestId != null && !requestId.isBlank()
                ? PUBLISH_REQUEST_PREFIX + UserContext.getUserId() + ":" + requestId.trim() : null;
        if (requestKey != null) {
            Long existingId = getPublishedPostId(requestId);
            if (existingId != null) return getBlogById(existingId);
        }
        BlogPost post = new BlogPost();
        post.setTitle(dto.getTitle());
        post.setSummary(dto.getSummary());
        post.setContent(dto.getContent());
        post.setCategoryId(dto.getCategoryId());
        post.setCoverImage(dto.getCoverImage());
        post.setUserId(UserContext.getUserId());
        post.setAuthor(resolveAuthor(UserContext.getUserId()));
        post.setReadTime(Math.max(1, dto.getContent().length() / 500) + " min");
        post.setStatus(publish ? 1 : 0);
        post.setPublishedAt(publish ? LocalDate.now() : null);
        blogPostMapper.insert(post);

        // 处理标签
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            for (String tagName : dto.getTags()) {
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
        return getManagedBlogById(UserContext.getUserId(), post.getId());
    }

    /** Returns the post created by this user's publish request, if it has committed. */
    public Long getPublishedPostId(String requestId) {
        if (requestId == null || requestId.isBlank() || UserContext.getUserId() == null) return null;
        String key = PUBLISH_REQUEST_PREFIX + UserContext.getUserId() + ":" + requestId.trim();
        String postId = redisTemplate.opsForValue().get(key);
        if (postId == null) return null;
        try {
            BlogPost post = blogPostMapper.selectById(Long.parseLong(postId));
            if (post != null && UserContext.getUserId().equals(post.getUserId())) return post.getId();
        } catch (NumberFormatException ignored) { }
        redisTemplate.delete(key);
        return null;
    }

    /** Updates only supplied fields. Passing tags replaces all existing tags for the post. */
    @Transactional
    public BlogPostVO updateBlog(Long id, String title, String summary, String content,
                                 String categoryId, String coverImage, List<String> tags) {
        BlogPost existing = requireOwnedPost(id, UserContext.getUserId());
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
        if (UserContext.getUserId() != null) {
            update.setAuthor(resolveAuthor(UserContext.getUserId()));
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
        Long userId = UserContext.getUserId();
        requireOwnedPost(id, userId);
        BlogPost update = new BlogPost();
        update.setId(id);
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
        User user = userMapper.selectById(userId);
        if (user == null) return "User " + userId;
        if (user.getNickname() != null && !user.getNickname().isBlank()) return user.getNickname();
        if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername();
        return "User " + userId;
    }

    public PageData<BlogPostVO> getMyBlogs(Integer status, String search, Integer page, Integer size) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
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
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        return blogPostMapper.selectCount(new LambdaQueryWrapper<BlogPost>()
                .eq(BlogPost::getUserId, userId)
                .in(BlogPost::getStatus, STATUS_DRAFT, STATUS_PUBLISHED));
    }

    /**
     * 通用查询当前用户文章（不分页），筛选条件可任意组合：
     * status 限定草稿/已发布/回收站；search 标题摘要关键词；
     * juejinSynced / csdnSynced：true 仅已成功同步、false 仅未同步、null 不限；
     * limit 非空时仅返回最新前 N 条。供智能体一次调用拿全量，无需逐页聚合。
     */
    public List<BlogPostVO> listMyPosts(Integer status, String search, Boolean juejinSynced, Boolean csdnSynced, Integer limit) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
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
}
