package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeFileTag;
import org.apache.ibatis.annotations.Mapper;

/** 文件与标签的关联持久化映射。 */
@Mapper
public interface KnowledgeFileTagMapper extends BaseMapper<KnowledgeFileTag> {
}
