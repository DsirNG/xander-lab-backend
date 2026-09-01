package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeTag;
import org.apache.ibatis.annotations.Mapper;

/** 知识库标签持久化映射。 */
@Mapper
public interface KnowledgeTagMapper extends BaseMapper<KnowledgeTag> {
}
