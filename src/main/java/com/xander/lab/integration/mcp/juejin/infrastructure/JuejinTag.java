package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 掘金标签目录行（query_category_list 的 hot_tags 与 query_tag_list 同步而来，发布时名称→ID 匹配）。 */
@Data
@TableName("juejin_tag")
public class JuejinTag {
    @TableId(type = IdType.INPUT) private Long tagId;
    private String tagName;
    /** 来源分类（hot_tags 所属分类）；query_tag_list 全量列表无归属时为 NULL。 */
    private Long categoryId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
