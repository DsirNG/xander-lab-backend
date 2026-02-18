package com.xander.lab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.dto.CategoryVO;
import com.xander.lab.entity.BlogCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BlogCategoryMapper extends BaseMapper<BlogCategory> {

    /**
     * 查询所有分类，并统计每个分类下已发布的文章数量
     */
    @Select("""
            SELECT c.id, c.name,
                   COUNT(p.id) AS count
            FROM blog_category c
            LEFT JOIN blog_post p ON p.category_id = c.id AND p.status = 1
            GROUP BY c.id, c.name, c.sort
            ORDER BY c.sort ASC
            """)
    List<CategoryVO> selectCategoriesWithCount();
}
