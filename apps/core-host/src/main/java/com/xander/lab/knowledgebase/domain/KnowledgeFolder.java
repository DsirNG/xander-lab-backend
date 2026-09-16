package com.xander.lab.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文件夹树的一个节点。根目录不是一条记录，而是 {@code parentId == ROOT_PARENT_ID} 这个约定，
 * 因此根目录本身没有归属用户、也放不下文件。
 */
@Data
@TableName("knowledge_folder")
public class KnowledgeFolder {

    /** 根目录的父 ID 约定值。用 0 而不是 null，同级同名唯一索引才拦得住重名（唯一索引不约束 NULL）。 */
    public static final long ROOT_PARENT_ID = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属用户 sys_user.id；本模块所有查询都必须带这个条件。 */
    private Long userId;
    /** 父文件夹 id，0 表示根目录下的一级文件夹。 */
    private Long parentId;
    private String name;
    /** 物化祖先路径，形如 {@code /3/17/}，以自身 id 结尾；子树查询与移动环检测都靠它。 */
    private String path;
    /** 层级深度，根目录下的一级文件夹为 1。 */
    private Integer depth;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
