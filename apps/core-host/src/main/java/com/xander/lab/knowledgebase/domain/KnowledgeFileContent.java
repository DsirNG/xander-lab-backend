package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件抽取出的纯文本正文，与文件一对一。
 *
 * <p>{@link KnowledgeChapter} 的起止下标以本表 {@code content} 为坐标系；按章按需生成结构图时
 * 需要回读它，所以正文必须落库而不是只在上传请求里过一遍。</p>
 */
@Data
@TableName("knowledge_file_content")
public class KnowledgeFileContent {

    /** 主键就是文件 id（一对一），不是自增列。 */
    @TableId(value = "file_id", type = IdType.INPUT)
    private Long fileId;
    private Long userId;
    private String content;
    private LocalDateTime createdAt;
}
