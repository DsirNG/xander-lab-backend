package com.xander.lab.components.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.components.domain.ComponentItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ComponentItemMapper extends BaseMapper<ComponentItem> {
}
