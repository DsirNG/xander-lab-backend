package com.xander.lab.studio.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.studio.domain.FlowSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * FlowCraft 快照 Mapper
 */
@Mapper
public interface FlowSnapshotMapper extends BaseMapper<FlowSnapshot> {
}
