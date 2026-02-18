package com.xander.lab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.entity.BlogPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BlogPostMapper extends BaseMapper<BlogPost> {

    /**
     * 查询文章列表（不含 content，支持分类/标签/关键词动态筛选，支持分页）
     */
    IPage<BlogPostVO> selectPostList(Page<BlogPostVO> page,
                                     @Param("search") String search,
                                     @Param("category") String category,
                                     @Param("tag") String tag);

    /**
     * 查询最新文章（前N条，不含 content）
     * 对应 XML: BlogPostMapper.xml -> selectRecentPosts
     */
    List<BlogPostVO> selectRecentPosts(@Param("limit") int limit);

    /**
     * 查询文章详情（含 content）
     * 对应 XML: BlogPostMapper.xml -> selectPostDetail
     */
    BlogPostVO selectPostDetail(@Param("id") Long id);
}
