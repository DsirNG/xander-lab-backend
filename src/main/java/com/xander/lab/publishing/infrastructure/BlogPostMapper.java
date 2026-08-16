package com.xander.lab.publishing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.domain.BlogPost;
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

    /**
     * 查询文章详情（含 content，不限状态；管理端/作者用）
     */
    BlogPostVO selectPostDetailAnyStatus(@Param("id") Long id);

    /**
     * 当前用户文章列表（可按状态/关键词筛选）
     */
    IPage<BlogPostVO> selectMyPostList(Page<BlogPostVO> page,
                                       @Param("userId") Long userId,
                                       @Param("status") Integer status,
                                       @Param("search") String search);

    /**
     * 当前用户全部文章（不分页，可选状态/关键词筛选；unsyncedPlatform 非空时
     * 只返回未成功同步到该平台的文章，platform 取 'juejin' / 'csdn'）
     * 对应 XML: BlogPostMapper.xml -> selectMyPostsAll
     */
    List<BlogPostVO> selectMyPostsAll(@Param("userId") Long userId,
                                      @Param("status") Integer status,
                                      @Param("search") String search,
                                      @Param("unsyncedPlatform") String unsyncedPlatform);
}
