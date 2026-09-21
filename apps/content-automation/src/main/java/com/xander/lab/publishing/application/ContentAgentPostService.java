package com.xander.lab.publishing.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import com.xander.lab.common.PageData;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

/** Content-owned implementation of the Agent article contract. */
@Service
public class ContentAgentPostService implements ContentPostPort {

    private final JdbcTemplate jdbcTemplate;
    private final BlogPrivateReadService privateReadService;
    private final BlogMutationService mutationService;

    public ContentAgentPostService(JdbcTemplate jdbcTemplate,
                                   BlogPrivateReadService privateReadService,
                                   BlogMutationService mutationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.privateReadService = privateReadService;
        this.mutationService = mutationService;
    }

    @Override
    public ContentPostPage listMyPosts(Long userId, ContentPostQuery query, int page, int size) {
        requireUser(userId);
        ContentPostQuery safeQuery = query == null ? ContentPostQuery.NONE : query;
        if (safeQuery.juejinSynced() != null || safeQuery.csdnSynced() != null) {
            throw new IllegalArgumentException(
                    "分页读取不支持按同步状态过滤（分页 SQL 没有这两个条件），请用 listAllMyPosts");
        }
        PageData<BlogPostVO> result = privateReadService.listMine(userId, safeQuery.status(),
                safeQuery.search(), page, size);
        return toPage(result);
    }

    @Override
    public List<ContentPost> listAllMyPosts(Long userId, ContentPostQuery query, Integer limit) {
        requireUser(userId);
        ContentPostQuery safeQuery = query == null ? ContentPostQuery.NONE : query;
        return privateReadService.listAllMine(userId, safeQuery.status(), safeQuery.search(),
                        safeQuery.juejinSynced(), safeQuery.csdnSynced(), limit)
                .stream().map(ContentAgentPostService::toContract).toList();
    }

    @Override
    public ContentPost findOwned(Long userId, Long postId) {
        requireUser(userId);
        return toContract(privateReadService.findMine(userId, requirePost(postId)));
    }

    @Override
    public long countManaged(Long userId) {
        requireUser(userId);
        return privateReadService.countManaged(userId);
    }

    @Override
    @Transactional
    public ContentPost create(Long userId, ContentPostInput input, boolean publish) {
        requireUser(userId);
        if (input == null || blank(input.title()) || blank(input.content())) {
            throw new IllegalArgumentException("标题和正文不能为空");
        }
        String title = input.title().trim();
        String content = input.content();
        String summary = input.summary();
        if (summary == null || summary.isBlank()) {
            summary = excerpt(content, 160);
        }
        String author = jdbcTemplate.query(
                "SELECT COALESCE(NULLIF(nickname, ''), NULLIF(username, ''), 'DinQorAI')"
                        + " FROM sys_user WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), userId).stream().findFirst().orElse("DinQorAI");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String finalSummary = summary;
        String finalAuthor = author;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO blog_post (user_id, title, summary, content, category_id, author,"
                            + " read_time, status, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, title);
            statement.setString(3, finalSummary);
            statement.setString(4, content);
            statement.setString(5, input.categoryId());
            statement.setString(6, finalAuthor);
            statement.setString(7, Math.max(1, content.length() / 500) + " min");
            statement.setInt(8, publish ? 1 : 0);
            statement.setObject(9, publish ? LocalDate.now() : null);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("文章发布失败");
        }
        saveTags(key.longValue(), input.tags());
        return toContract(privateReadService.findMine(userId, key.longValue()));
    }

    @Override
    @Transactional
    public ContentPost update(Long userId, Long postId, ContentPostInput input) {
        requireUser(userId);
        if (input == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        BlogPostDTO request = new BlogPostDTO();
        request.setTitle(input.title());
        request.setSummary(input.summary());
        request.setContent(input.content());
        request.setCategoryId(input.categoryId());
        request.setCoverImage(input.coverImage());
        request.setTags(input.tags());
        return toContract(mutationService.update(userId, requirePost(postId), request));
    }

    @Override
    @Transactional
    public void moveToTrash(Long userId, Long postId) {
        requireUser(userId);
        mutationService.moveToTrash(userId, requirePost(postId));
    }

    private void saveTags(long postId, List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String tag : tags.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().limit(8).toList()) {
            jdbcTemplate.update("INSERT INTO blog_tag (name) VALUES (?)"
                    + " ON DUPLICATE KEY UPDATE id = id", tag);
            Long tagId = jdbcTemplate.queryForObject(
                    "SELECT id FROM blog_tag WHERE name = ?", Long.class, tag);
            jdbcTemplate.update("INSERT INTO blog_post_tag (post_id, tag_id) VALUES (?, ?)"
                    + " ON DUPLICATE KEY UPDATE post_id = post_id", postId, tagId);
        }
    }

    private ContentPostPage toPage(PageData<BlogPostVO> page) {
        List<ContentPost> records = page.getRecords() == null ? null
                : page.getRecords().stream().map(ContentAgentPostService::toContract).toList();
        return new ContentPostPage(records, page.getTotal(), page.getCurrent(), page.getPages(),
                page.getSize(), page.isHasMore());
    }

    private static ContentPost toContract(BlogPostVO post) {
        if (post == null) {
            return null;
        }
        return new ContentPost(post.getId(), post.getTitle(), post.getSummary(), post.getContent(),
                post.getCategory(), post.getCategoryName(), post.getCoverImage(), post.getTags(),
                post.getUserId(), post.getAuthor(), post.getDate(), post.getReadTime(), post.getTips(),
                post.getViews(), post.getStatus(), post.getCsdnSynced(), post.getJuejinSynced());
    }

    private static void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
    }

    private static long requirePost(Long postId) {
        if (postId == null || postId <= 0) {
            throw new IllegalArgumentException("文章不存在");
        }
        return postId;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String excerpt(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
