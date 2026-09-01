package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeStructureNode;
import org.apache.ibatis.annotations.Mapper;

/** 知识结构图节点持久化映射。 */
@Mapper
public interface KnowledgeStructureNodeMapper extends BaseMapper<KnowledgeStructureNode> {
}
