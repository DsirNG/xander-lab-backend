package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.knowledgebase.domain.KnowledgeUploadChunk;
import org.apache.ibatis.annotations.Mapper;

/** 已落盘上传分片持久化映射。它是「哪些分片已到」的权威来源。 */
@Mapper
public interface KnowledgeUploadChunkMapper extends BaseMapper<KnowledgeUploadChunk> {
}
