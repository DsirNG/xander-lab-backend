package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeFolder;
import org.apache.ibatis.annotations.Mapper;

/** 知识库文件夹持久化映射。 */
@Mapper
public interface KnowledgeFolderMapper extends BaseMapper<KnowledgeFolder> {
}
