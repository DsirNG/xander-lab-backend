package com.xander.lab.components.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.components.domain.ComponentCategory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ComponentCategoryMapper extends BaseMapper<ComponentCategory> {
}
