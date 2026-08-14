package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 掘金分类目录行（tag_api/v1/query_category_list 同步而来，发布时名称→ID 匹配）。 */
@Data
@TableName("juejin_category")
public class JuejinCategory {
    @TableId(type = IdType.INPUT) private Long categoryId;
    private String categoryName;
    /** query_category_list 返回的展示排序（rank 升序）。 */
    private Integer rank;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
