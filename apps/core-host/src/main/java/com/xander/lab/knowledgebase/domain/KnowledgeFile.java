package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库里的一个文件。必须归属某个非根文件夹：根目录只放文件夹。
 *
 * <p>正文不在本表，见 {@link KnowledgeFileContent}——列表查询很频繁，不能把大文本一起捞出来。</p>
 */
@Data
@TableName("knowledge_file")
public class KnowledgeFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 所在文件夹 knowledge_folder.id，必须大于 0。 */
    private Long folderId;
    /** 上传时的原始文件名（已过滤路径分隔符）。 */
    private String originalName;
    /** 列表展示名，可重命名。 */
    private String displayName;
    /** 小写扩展名，不含点。决定能否抽取正文。 */
    private String extension;
    /** 上传声明的 MIME 类型，仅作展示与下载头，不作安全判据。 */
    private String contentType;
    private Long sizeBytes;
    /** 文件内容 SHA-256 十六进制值；分片合并后由服务端重算。 */
    private String fileHash;
    private String storageUrl;
    /** 是否成功抽取出纯文本正文；未抽取的文件识别不出章节。 */
    private Boolean textExtracted;
    private Integer charCount;
    /** 识别出的顶层章节数量，0 表示未分章。 */
    private Integer chapterCount;
    /** 见 {@link StructureStatus}，库里存其 name()。 */
    private String structureStatus;
    /** 面向用户的结构图状态说明，直接展示。 */
    private String structureMessage;
    /** 来源分片上传会话 id；直传的小文件为空。 */
    private Long uploadSessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
