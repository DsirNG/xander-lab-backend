package com.xander.lab.admin.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.admin.domain.ModelProvider;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@link ModelProvider}. */
@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProvider> {
}