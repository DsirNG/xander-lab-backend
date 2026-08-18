package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface JuejinCategoryMapper extends BaseMapper<JuejinCategory> {

    /**
     * 幂等写入或更新分类行（以 category_id 为主键）。目录同步以接口快照为准，
     * 名称与排序直接覆盖；并发同步收敛到同一行而不是读改写竞争。
     */
    @Insert("""
            INSERT INTO juejin_category
                (category_id, category_name, `rank`, created_at, updated_at)
            VALUES
                (#{categoryId}, #{categoryName}, #{rank}, #{createdAt}, #{updatedAt})
            AS new
            ON DUPLICATE KEY UPDATE
                category_name = new.category_name,
                `rank` = new.`rank`,
                updated_at = new.updated_at
            """)
    int upsert(JuejinCategory category);

    /** 最新一次分类目录同步时间；从未同步返回 NULL。 */
    @Select("SELECT MAX(updated_at) FROM juejin_category")
    LocalDateTime selectLatestSyncAt();
}
