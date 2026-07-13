package com.xander.lab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.entity.BlogPost;
import com.xander.lab.entity.BlogPostView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 博客阅读记录 Mapper
 */
@Mapper
public interface BlogPostViewMapper extends BaseMapper<BlogPostView> {

    /**
     * 原子递增文章阅读次数
     *
     * @param postId 文章ID
     */
    @Update("UPDATE blog_post SET views = views + 1 WHERE id = #{postId}")
    void incrementViewCount(@Param("postId") Long postId);
}
