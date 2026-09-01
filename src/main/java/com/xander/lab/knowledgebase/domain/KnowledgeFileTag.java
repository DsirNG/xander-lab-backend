package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文件与标签的多对多关联。 */
@Data
@TableName("knowledge_file_tag")
public class KnowledgeFileTag {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 冗余的所属用户 id：按标签反查文件时不必回表 knowledge_file。 */
    private Long userId;
    private Long fileId;
    private Long tagId;
    /** 见 {@link TagSource}。自动标签可被整体重算，手动标签不会被覆盖。 */
    private String source;
    private LocalDateTime createdAt;
}
