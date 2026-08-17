package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.WechatCredential;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@link WechatCredential}. */
@Mapper
public interface WechatCredentialMapper extends BaseMapper<WechatCredential> {
}