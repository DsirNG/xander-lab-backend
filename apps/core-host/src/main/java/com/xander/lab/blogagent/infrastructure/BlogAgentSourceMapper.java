package com.xander.lab.blogagent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.blogagent.domain.BlogAgentSource;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BlogAgentSourceMapper extends BaseMapper<BlogAgentSource> { }
