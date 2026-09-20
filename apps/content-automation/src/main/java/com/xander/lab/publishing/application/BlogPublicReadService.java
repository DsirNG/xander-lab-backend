package com.xander.lab.publishing.application;

import com.xander.lab.common.PageData;
import com.xander.lab.publishing.api.BlogPostVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Database-backed public publishing reads for the Wave 2 content owner.
 *
 * <p>The service intentionally uses parameterized JDBC instead of importing core-host's
 * MyBatis mapper or application service. That keeps the deployment boundary real while the
 * public response contract remains byte-for-byte compatible at the JSON field level.</p>
 */
@Service
public class BlogPublicReadService {

    private static final String LIST_SELECT_COLUMNS = """
            SELECT p.id,
                   p.title,
                   p.summary,
                   p.category_id AS category,
                   c.name AS categoryName,
                   p.cover_image AS coverImage,
                   p.user_id AS userId,
                   p.author,
                   p.published_at AS date,
                   p.read_time AS readTime,
                   p.tips,
                   p.views,
                   p.status,
                   EXISTS (SELECT 1 FROM blog_publish_record r
                           WHERE r.task_id = -p.id AND r.platform = 'csdn'
                             AND r.status = 'SUCCEEDED') AS csdnSynced,
                   EXISTS (SELECT 1 FROM blog_publish_record r
                           WHERE r.task_id = -p.id AND r.platform = 'juejin'
                             AND r.status = 'SUCCEEDED') AS juejinSynced,
                   GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tagsRaw
            FROM blog_post p
            LEFT JOIN blog_category c ON c.id = p.category_id
            LEFT JOIN blog_post_tag pt ON pt.post_id = p.id
            LEFT JOIN blog_tag t ON t.id = pt.tag_id
            """;

    private static final String DETAIL_SELECT_COLUMNS = """
            SELECT p.id,
                   p.title,
                   p.summary,
                   p.content,
                   p.category_id AS category,
                   c.name AS categoryName,
                   p.cover_image AS coverImage,
                   p.user_id AS userId,
                   p.author,
                   p.published_at AS date,
                   p.read_time AS readTime,
                   p.tips,
                   p.views,
                   p.status,
                   EXISTS (SELECT 1 FROM blog_publish_record r
                           WHERE r.task_id = -p.id AND r.platform = 'csdn'
                             AND r.status = 'SUCCEEDED') AS csdnSynced,
                   EXISTS (SELECT 1 FROM blog_publish_record r
                           WHERE r.task_id = -p.id AND r.platform = 'juejin'
                             AND r.status = 'SUCCEEDED') AS juejinSynced,
                   GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tagsRaw
            FROM blog_post p
            LEFT JOIN blog_category c ON c.id = p.category_id
            LEFT JOIN blog_post_tag pt ON pt.post_id = p.id
            LEFT JOIN blog_tag t ON t.id = pt.tag_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public BlogPublicReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageData<BlogPostVO> listPublished(String search, String category, String tag,
                                              int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        QueryParts query = queryParts(search, category, tag);

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_post p WHERE p.status = 1" + query.where(),
                query.args().toArray(), Long.class);
        List<BlogPostVO> records = jdbcTemplate.query(
                LIST_SELECT_COLUMNS + " WHERE p.status = 1" + query.where()
                        + " GROUP BY p.id, p.title, p.summary, p.category_id, c.name,"
                        + " p.cover_image, p.user_id, p.author, p.published_at, p.read_time,"
                        + " p.tips, p.views, p.status"
                        + " ORDER BY p.published_at DESC LIMIT ? OFFSET ?",
                new BlogPostRowMapper(), append(query.args(), safeSize, (safePage - 1) * safeSize));

        PageData<BlogPostVO> result = new PageData<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setCurrent(safePage);
        result.setSize(safeSize);
        result.setPages(total == 0 ? 0 : (total + safeSize - 1) / safeSize);
        result.setHasMore(safePage * (long) safeSize < total);
        return result;
    }

    public List<BlogPostVO> recent(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        return jdbcTemplate.query(
                LIST_SELECT_COLUMNS + " WHERE p.status = 1"
                        + " GROUP BY p.id, p.title, p.summary, p.category_id, c.name,"
                        + " p.cover_image, p.user_id, p.author, p.published_at, p.read_time,"
                        + " p.tips, p.views, p.status"
                        + " ORDER BY p.published_at DESC LIMIT ?",
                new BlogPostRowMapper(), safeLimit);
    }

    public BlogPostVO findPublished(long id) {
        List<BlogPostVO> posts = jdbcTemplate.query(
                DETAIL_SELECT_COLUMNS + " WHERE p.id = ? AND p.status = 1"
                        + " GROUP BY p.id, p.title, p.summary, p.content, p.category_id, c.name,"
                        + " p.cover_image, p.user_id, p.author, p.published_at, p.read_time,"
                        + " p.tips, p.views, p.status",
                new BlogPostRowMapper(), id);
        return posts.isEmpty() ? null : posts.get(0);
    }

    private QueryParts queryParts(String search, String category, String tag) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            where.append(" AND p.category_id = ?");
            args.add(category);
        }
        if (tag != null && !tag.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM blog_post_tag pt2"
                    + " INNER JOIN blog_tag t2 ON t2.id = pt2.tag_id"
                    + " WHERE pt2.post_id = p.id AND LOWER(t2.name) = LOWER(?))");
            args.add(tag);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (p.title LIKE CONCAT('%', ?, '%')"
                    + " OR p.summary LIKE CONCAT('%', ?, '%')"
                    + " OR EXISTS (SELECT 1 FROM blog_post_tag pt3"
                    + " INNER JOIN blog_tag t3 ON t3.id = pt3.tag_id"
                    + " WHERE pt3.post_id = p.id"
                    + " AND LOWER(t3.name) LIKE CONCAT('%', LOWER(?), '%')))");
            args.add(search);
            args.add(search);
            args.add(search);
        }
        return new QueryParts(where.toString(), args);
    }

    private static Object[] append(List<Object> args, Object... tail) {
        List<Object> all = new ArrayList<>(args);
        for (Object value : tail) {
            all.add(value);
        }
        return all.toArray();
    }

    private record QueryParts(String where, List<Object> args) {
    }

    private static final class BlogPostRowMapper implements org.springframework.jdbc.core.RowMapper<BlogPostVO> {
        @Override
        public BlogPostVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            BlogPostVO post = new BlogPostVO();
            post.setId(rs.getLong("id"));
            post.setTitle(rs.getString("title"));
            post.setSummary(rs.getString("summary"));
            post.setContent(rs.getString("content"));
            post.setCategory(rs.getString("category"));
            post.setCategoryName(rs.getString("categoryName"));
            post.setCoverImage(rs.getString("coverImage"));
            post.setUserId(rs.getObject("userId", Long.class));
            post.setAuthor(rs.getString("author"));
            post.setDate(rs.getObject("date", java.time.LocalDate.class));
            post.setReadTime(rs.getString("readTime"));
            post.setTips(rs.getString("tips"));
            post.setViews(rs.getObject("views", Integer.class));
            post.setStatus(rs.getObject("status", Integer.class));
            post.setCsdnSynced(rs.getBoolean("csdnSynced"));
            post.setJuejinSynced(rs.getBoolean("juejinSynced"));
            post.setTagsRaw(rs.getString("tagsRaw"));
            post.parseTags();
            return post;
        }
    }
}
