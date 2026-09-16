package com.xander.lab.virtualpay.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.virtualpay.domain.VirtualPaySettings;
import org.apache.ibatis.annotations.Mapper;

/** 虚拟支付单行配置持久化。 */
@Mapper
public interface VirtualPaySettingsMapper extends BaseMapper<VirtualPaySettings> {
}
