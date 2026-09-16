package com.xander.lab.recitation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.recitation.domain.KnowledgeQuizAttempt;
import org.apache.ibatis.annotations.Mapper;

/** 个人知识测验评分记录持久化映射。 */
@Mapper
public interface KnowledgeQuizAttemptMapper extends BaseMapper<KnowledgeQuizAttempt> {
}
