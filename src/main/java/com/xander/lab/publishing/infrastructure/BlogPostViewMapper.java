package com.xander.lab.publishing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.publishing.domain.BlogPostView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 博客阅读记录 Mapper
 */
@Mapper
public interface BlogPostViewMapper extends BaseMapper<BlogPostView> {

    @Select("""
            SELECT DATE(v.created_at) AS date, COUNT(*) AS views
            FROM blog_post_view v
            JOIN blog_post p ON p.id = v.post_id
            WHERE p.user_id = #{userId}
              AND v.created_at >= #{startDate}
              AND v.created_at < DATE_ADD(#{endDate}, INTERVAL 1 DAY)
            GROUP BY DATE(v.created_at)
            ORDER BY DATE(v.created_at)
            """)
    List<BlogDailyViewCount> selectDailyViews(@Param("userId") Long userId,
                                               @Param("startDate") LocalDate startDate,
                                               @Param("endDate") LocalDate endDate);

    /**
     * 原子递增文章阅读次数
     *
     * @param postId 文章ID
     */
    @Update("UPDATE blog_post SET views = views + 1 WHERE id = #{postId}")
    void incrementViewCount(@Param("postId") Long postId);
}
