package com.xander.lab.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.dto.CategoryVO;
import com.xander.lab.dto.PageData;
import com.xander.lab.dto.TagVO;
import com.xander.lab.mapper.BlogCategoryMapper;
import com.xander.lab.mapper.BlogPostMapper;
import com.xander.lab.mapper.BlogTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 博客业务服务层
 */
@Service
@RequiredArgsConstructor
public class BlogService {

    private final BlogPostMapper blogPostMapper;
    private final BlogCategoryMapper blogCategoryMapper;
    private final BlogTagMapper blogTagMapper;

    /**
     * 获取博客列表（支持搜索、分类、标签筛选，支持分页）
     *
     * @param search   关键词
     * @param category 分类ID
     * @param tag      标签名称
     * @param page     页码
     * @param size     每页数量
     * @return 文章分页对象
     */
    public PageData<BlogPostVO> getBlogs(String search, String category, String tag, int page, int size) {
        Page<BlogPostVO> pageParam = new Page<>(page, size);
        IPage<BlogPostVO> result = blogPostMapper.selectPostList(pageParam, search, category, tag);
        result.getRecords().forEach(BlogPostVO::parseTags);
        return PageData.of(result);
    }

    /**
     * 获取最新发布的文章（前N条）
     *
     * @param limit 条数限制，默认5
     * @return 文章列表（不含 content）
     */
    public List<BlogPostVO> getRecentBlogs(int limit) {
        List<BlogPostVO> list = blogPostMapper.selectRecentPosts(limit);
        list.forEach(BlogPostVO::parseTags);
        return list;
    }

    /**
     * 获取文章详情（含 content）
     *
     * @param id 文章ID
     * @return 文章详情，不存在时返回 null
     */
    public BlogPostVO getBlogById(Long id) {
        BlogPostVO vo = blogPostMapper.selectPostDetail(id);
        if (vo != null) {
            vo.parseTags();
        }
        return vo;
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
     * 获取所有标签（含文章数量，按数量降序）
     *
     * @return 标签列表
     */
    public List<TagVO> getAllTags() {
        return blogTagMapper.selectAllTagsWithCount();
    }

    /**
     * 获取热门标签（前N个）
     *
     * @param limit 条数限制，默认8
     * @return 标签列表
     */
    public List<TagVO> getPopularTags(int limit) {
        return blogTagMapper.selectPopularTags(limit);
    }
}
