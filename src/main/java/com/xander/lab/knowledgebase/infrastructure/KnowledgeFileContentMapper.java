package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeFileContent;
import org.apache.ibatis.annotations.Mapper;

/** 文件纯文本正文持久化映射。单独一张表，避免文件列表查询把 LONGTEXT 一起捞出来。 */
@Mapper
public interface KnowledgeFileContentMapper extends BaseMapper<KnowledgeFileContent> {
}
