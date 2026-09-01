package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeUploadSession;
import org.apache.ibatis.annotations.Mapper;

/** 分片上传会话持久化映射。 */
@Mapper
public interface KnowledgeUploadSessionMapper extends BaseMapper<KnowledgeUploadSession> {
}
