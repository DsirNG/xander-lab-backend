package com.xander.lab.recitation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.recitation.domain.KnowledgeQuizSession;
import org.apache.ibatis.annotations.Mapper;

/** 知识测验会话持久化映射。 */
@Mapper
public interface KnowledgeQuizSessionMapper extends BaseMapper<KnowledgeQuizSession> {
}
