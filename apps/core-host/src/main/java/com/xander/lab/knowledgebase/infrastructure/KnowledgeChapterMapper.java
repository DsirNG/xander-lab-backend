package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeChapter;
import org.apache.ibatis.annotations.Mapper;

/** 章节目录持久化映射。 */
@Mapper
public interface KnowledgeChapterMapper extends BaseMapper<KnowledgeChapter> {
}
