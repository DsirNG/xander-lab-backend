package com.xander.lab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.dto.TagVO;
import com.xander.lab.entity.BlogTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BlogTagMapper extends BaseMapper<BlogTag> {

    /**
     * 查询所有标签及其文章数量，按数量降序
     */
    @Select("""
            SELECT t.name, COUNT(pt.post_id) AS count
            FROM blog_tag t
            LEFT JOIN blog_post_tag pt ON pt.tag_id = t.id
            LEFT JOIN blog_post p ON p.id = pt.post_id AND p.status = 1
            GROUP BY t.id, t.name
            ORDER BY count DESC
            """)
    List<TagVO> selectAllTagsWithCount();

    /**
     * 查询热门标签（前N个）
     */
    @Select("""
            SELECT t.name, COUNT(pt.post_id) AS count
            FROM blog_tag t
            LEFT JOIN blog_post_tag pt ON pt.tag_id = t.id
            LEFT JOIN blog_post p ON p.id = pt.post_id AND p.status = 1
            GROUP BY t.id, t.name
            ORDER BY count DESC
            LIMIT #{limit}
            """)
    List<TagVO> selectPopularTags(@Param("limit") int limit);

    @Select("""
            SELECT t.name
            FROM blog_tag t
            INNER JOIN blog_post_tag pt ON pt.tag_id = t.id
            WHERE pt.post_id = #{postId}
            """)
    List<String> selectTagNamesByPostId(@Param("postId") Long postId);

    /**
     * 插入文章标签关联
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO blog_post_tag (post_id, tag_id)
            VALUES (#{postId}, #{tagId})
            """)
    void insertPostTag(@Param("postId") Long postId, @Param("tagId") Long tagId);

    /**
     * 删除文章的所有标签关联
     */
    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM blog_post_tag WHERE post_id = #{postId}
            """)
    void deletePostTags(@Param("postId") Long postId);
}
