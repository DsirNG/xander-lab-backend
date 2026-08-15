package com.xander.lab.admin.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.admin.domain.FeatureModelConfig;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@link FeatureModelConfig}. */
@Mapper
public interface FeatureModelConfigMapper extends BaseMapper<FeatureModelConfig> {
}