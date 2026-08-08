package com.xander.lab.components.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.components.domain.ComponentScenario;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ComponentScenarioMapper extends BaseMapper<ComponentScenario> {
}
