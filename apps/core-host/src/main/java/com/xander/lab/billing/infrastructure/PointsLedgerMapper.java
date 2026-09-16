package com.xander.lab.billing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.billing.domain.PointsLedger;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分流水 Mapper。
 */
@Mapper
public interface PointsLedgerMapper extends BaseMapper<PointsLedger> {
}