package com.xander.lab.recitation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.recitation.domain.RecitationAttempt;
import org.apache.ibatis.annotations.Mapper;

/** 背诵录音任务持久化映射。 */
@Mapper
public interface RecitationAttemptMapper extends BaseMapper<RecitationAttempt> {
}
