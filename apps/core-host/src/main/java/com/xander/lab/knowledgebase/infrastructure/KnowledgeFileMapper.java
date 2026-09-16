package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import org.apache.ibatis.annotations.Mapper;

/** 知识库文件元数据持久化映射。正文不在本表，见 {@link KnowledgeFileContentMapper}。 */
@Mapper
public interface KnowledgeFileMapper extends BaseMapper<KnowledgeFile> {
}
