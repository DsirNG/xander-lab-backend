package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户私有的标签词表条目；标签不跨用户共享。 */
@Data
@TableName("knowledge_tag")
public class KnowledgeTag {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 标签名，同一用户下唯一（去首尾空白后比较）。 */
    private String name;
    /** 前端展示色值，形如 {@code #2F6FED}；为空由前端取默认色。 */
    private String color;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
