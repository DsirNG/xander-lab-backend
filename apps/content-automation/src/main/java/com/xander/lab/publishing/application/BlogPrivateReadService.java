package com.xander.lab.publishing.application;

import com.xander.lab.common.PageData;
import com.xander.lab.publishing.api.BlogPostVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Database-backed private publishing reads owned by content-automation. */
@Service
public class BlogPrivateReadService {

    private static final String SELECT_COLUMNS = """
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

    public BlogPrivateReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageData<BlogPostVO> listMine(long userId, Integer status, String search,
                                         int page, int size) {
        Integer normalizedStatus = normalizeStatus(status);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        QueryParts query = queryParts(userId, normalizedStatus, search);

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_post p" + query.where(),
                query.args().toArray(), Long.class);
        List<BlogPostVO> records = jdbcTemplate.query(
                SELECT_COLUMNS + query.where()
                        + " GROUP BY p.id, p.title, p.summary, p.content, p.category_id, c.name,"
                        + " p.cover_image, p.user_id, p.author, p.published_at, p.read_time,"
                        + " p.tips, p.views, p.status"
                        + " ORDER BY COALESCE(p.published_at, p.updated_at) DESC, p.id DESC"
                        + " LIMIT ? OFFSET ?",
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

    public BlogPostVO findMine(long userId, long postId) {
        List<BlogPostVO> posts = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE p.user_id = ? AND p.id = ?"
                        + " GROUP BY p.id, p.title, p.summary, p.content, p.category_id, c.name,"
                        + " p.cover_image, p.user_id, p.author, p.published_at, p.read_time,"
                        + " p.tips, p.views, p.status",
                new BlogPostRowMapper(), userId, postId);
        return posts.isEmpty() ? null : posts.get(0);
    }

    /** Full owned-post query used by the Agent internal content contract. */
    public List<BlogPostVO> listAllMine(long userId, Integer status, String search,
                                        Boolean juejinSynced, Boolean csdnSynced, Integer limit) {
        Integer normalizedStatus = normalizeStatus(status);
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" WHERE p.user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (normalizedStatus == null) {
            sql.append(" AND p.status IN (0, 1)");
        } else {
            sql.append(" AND p.status = ?");
            args.add(normalizedStatus);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (p.title LIKE CONCAT('%', ?, '%')"
                    + " OR p.summary LIKE CONCAT('%', ?, '%'))");
            args.add(search.trim());
            args.add(search.trim());
        }
        appendSyncFilter(sql, args, "juejin", juejinSynced);
        appendSyncFilter(sql, args, "csdn", csdnSynced);
        sql.append(" GROUP BY p.id, p.title, p.summary, p.content, p.category_id, c.name,"
                + " p.cover_image, p.user_id, p.author, p.published_at, p.read_time,"
                + " p.tips, p.views, p.status"
                + " ORDER BY COALESCE(p.published_at, p.updated_at) DESC, p.id DESC");
        if (limit != null) {
            int safeLimit = Math.min(Math.max(limit, 1), 100);
            sql.append(" LIMIT ?");
            args.add(safeLimit);
        }
        return jdbcTemplate.query(sql.toString(), new BlogPostRowMapper(), args.toArray());
    }

    public long countManaged(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_post WHERE user_id = ? AND status IN (0, 1)",
                Long.class, userId);
        return count == null ? 0L : count;
    }

    private static void appendSyncFilter(StringBuilder sql, List<Object> args,
                                         String platform, Boolean synced) {
        if (synced == null) {
            return;
        }
        sql.append(synced ? " AND EXISTS (" : " AND NOT EXISTS (")
                .append("SELECT 1 FROM blog_publish_record r"
                        + " WHERE r.task_id = -p.id AND r.platform = ?"
                        + " AND r.status = 'SUCCEEDED')");
        args.add(platform);
    }

    private QueryParts queryParts(long userId, Integer status, String search) {
        StringBuilder where = new StringBuilder(" WHERE p.user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (status == null) {
            where.append(" AND p.status IN (0, 1)");
        } else {
            where.append(" AND p.status = ?");
            args.add(status);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (p.title LIKE CONCAT('%', ?, '%')"
                    + " OR p.summary LIKE CONCAT('%', ?, '%'))");
            args.add(search.trim());
            args.add(search.trim());
        }
        return new QueryParts(where.toString(), args);
    }

    private static Integer normalizeStatus(Integer status) {
        if (status == null || status == 0 || status == 1 || status == -1) {
            return status;
        }
        throw new IllegalArgumentException("状态参数必须是 0、1 或 -1");
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
