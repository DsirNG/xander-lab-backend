package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.Constants;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.BlogPostDTO;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.dto.CategoryVO;
import com.xander.lab.dto.TagVO;
import com.xander.lab.dto.PageData;
import com.xander.lab.entity.BlogCategory;
import com.xander.lab.entity.BlogPost;
import com.xander.lab.entity.BlogPostView;
import com.xander.lab.entity.BlogTag;
import com.xander.lab.entity.User;
import com.xander.lab.mapper.BlogCategoryMapper;
import com.xander.lab.mapper.BlogPostMapper;
import com.xander.lab.mapper.BlogPostViewMapper;
import com.xander.lab.mapper.BlogTagMapper;
import com.xander.lab.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final String PUBLISH_REQUEST_PREFIX = "blog:publish:request:";

    private final BlogPostMapper blogPostMapper;
    private final BlogCategoryMapper blogCategoryMapper;
    private final BlogTagMapper blogTagMapper;
    private final BlogPostViewMapper blogPostViewMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

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
        post.setUserId(UserContext.getUserId());
        post.setAuthor(resolveAuthor(UserContext.getUserId()));
        post.setReadTime(Math.max(1, dto.getContent().length() / 500) + " min");
        post.setStatus(publish ? 1 : 0);
        post.setPublishedAt(publish ? LocalDate.now() : null);
        post.setCreatedAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        blogPostMapper.insert(post);

        // 处理标签
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            for (String tagName : dto.getTags()) {
                BlogTag tag = blogTagMapper.selectOne(
                        new LambdaQueryWrapper<BlogTag>().eq(BlogTag::getName, tagName));
                if (tag == null) {
                    tag = new BlogTag();
                    tag.setName(tagName);
                    tag.setCreatedAt(LocalDateTime.now());
                    blogTagMapper.insert(tag);
                }
                blogTagMapper.insertPostTag(post.getId(), tag.getId());
            }
        }

        if (requestKey != null) {
            redisTemplate.opsForValue().set(requestKey, post.getId().toString(), 24, TimeUnit.HOURS);
        }
        return getBlogById(post.getId());
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
                                 String categoryId, List<String> tags) {
        if (blogPostMapper.selectById(id) == null) {
            throw new IllegalArgumentException("文章不存在");
        }
        if (title == null && summary == null && content == null && categoryId == null && tags == null) {
            throw new IllegalArgumentException("至少提供一个需要更新的字段");
        }
        BlogPost update = new BlogPost();
        update.setId(id);
        update.setTitle(title);
        update.setSummary(summary);
        update.setContent(content);
        update.setCategoryId(categoryId);
        if (UserContext.getUserId() != null) {
            update.setAuthor(resolveAuthor(UserContext.getUserId()));
        }
        update.setUpdatedAt(LocalDateTime.now());
        blogPostMapper.updateById(update);

        if (tags != null) {
            blogTagMapper.deletePostTags(id);
            for (String tagName : tags) {
                BlogTag tag = blogTagMapper.selectOne(
                        new LambdaQueryWrapper<BlogTag>().eq(BlogTag::getName, tagName));
                if (tag == null) {
                    tag = new BlogTag();
                    tag.setName(tagName);
                    tag.setCreatedAt(LocalDateTime.now());
                    blogTagMapper.insert(tag);
                }
                blogTagMapper.insertPostTag(id, tag.getId());
            }
        }
        return getBlogById(id);
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

    /** Permanently deletes a post and its associated view and tag records. */
    @Transactional
    public void deleteBlog(Long id) {
        if (blogPostMapper.selectById(id) == null) {
            throw new IllegalArgumentException("文章不存在");
        }
        blogPostViewMapper.delete(new LambdaQueryWrapper<BlogPostView>().eq(BlogPostView::getPostId, id));
        blogTagMapper.deletePostTags(id);
        blogPostMapper.deleteById(id);
    }

    /**
     * 获取博客列表（支持搜索、分类、标签过滤）
     *
     * @param search   搜索关键词
     * @param category 分类ID
     * @param tag      标签名
     * @param page     页码
     * @param size     每页条数
     * @return 分页博客列表
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
        view.setCreatedAt(LocalDateTime.now());
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
