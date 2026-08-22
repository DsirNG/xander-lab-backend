package com.xander.lab.recitation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.recitation.domain.RecitationMaterial;
import org.apache.ibatis.annotations.Mapper;

/** 背诵材料持久化映射。 */
@Mapper
public interface RecitationMaterialMapper extends BaseMapper<RecitationMaterial> {
}
