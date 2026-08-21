package com.xander.lab.billing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.billing.domain.ModelPrice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型定价 Mapper。
 */
@Mapper
public interface ModelPriceMapper extends BaseMapper<ModelPrice> {
}
