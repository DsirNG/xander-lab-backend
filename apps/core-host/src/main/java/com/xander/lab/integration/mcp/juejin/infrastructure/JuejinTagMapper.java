package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface JuejinTagMapper extends BaseMapper<JuejinTag> {

    /**
     * 幂等写入或更新标签行（以 tag_id 为主键）。同一标签可能同时出现在多个分类的
     * hot_tags 里，也出现在 query_tag_list 全量列表中；名称总是覆盖，但来源分类
     * 只在本次给定时才更新，避免后到的无归属行把已有分类归属清空。
     */
    @Insert("""
            INSERT INTO juejin_tag
                (tag_id, tag_name, category_id)
            VALUES
                (#{tagId}, #{tagName}, #{categoryId})
            AS new
            ON DUPLICATE KEY UPDATE
                tag_name = new.tag_name,
                category_id = IFNULL(new.category_id, juejin_tag.category_id)
            """)
    int upsert(JuejinTag tag);

    /** 最新一次标签目录同步时间；从未同步返回 NULL。 */
    @Select("SELECT MAX(updated_at) FROM juejin_tag")
    LocalDateTime selectLatestSyncAt();
}
