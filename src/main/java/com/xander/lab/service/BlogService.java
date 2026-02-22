package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.*;
import com.xander.lab.entity.BlogPost;
import com.xander.lab.entity.BlogTag;
import com.xander.lab.entity.User;
import com.xander.lab.mapper.BlogCategoryMapper;
import com.xander.lab.mapper.BlogPostMapper;
import com.xander.lab.mapper.BlogTagMapper;
import com.xander.lab.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    private final UserMapper userMapper;

    /**
     * 发布新博客
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createBlog(BlogPostDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("请先登录");
        }

        User user = userMapper.selectById(userId);

        BlogPost post = new BlogPost();
        post.setTitle(dto.getTitle());
        post.setSummary(dto.getSummary());
        post.setContent(dto.getContent());
        post.setCategoryId(dto.getCategoryId());
        post.setUserId(userId);
        post.setAuthor(user != null ? user.getNickname() : "匿名");
        post.setStatus(1); // 默认直接发布
        post.setPublishedAt(LocalDate.now());

        // 计算阅读时间 (粗略估算：500字/分钟)
        int wordCount = dto.getContent() != null ? dto.getContent().length() : 0;
        int minutes = Math.max(1, wordCount / 500);
        post.setReadTime(minutes + " min");

        blogPostMapper.insert(post);

        // 处理标签
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            for (String tagName : dto.getTags()) {
                // 查找或创建标签
                BlogTag tag = blogTagMapper.selectOne(new LambdaQueryWrapper<BlogTag>().eq(BlogTag::getName, tagName));
                if (tag == null) {
                    tag = new BlogTag();
                    tag.setName(tagName);
                    blogTagMapper.insert(tag);
                }
                // 关联标签
                blogTagMapper.insertPostTag(post.getId(), tag.getId());
            }
        }

        return post.getId();
    }

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
