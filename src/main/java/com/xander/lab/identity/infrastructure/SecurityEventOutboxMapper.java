package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 安全事件发件箱 Mapper（V9 §27）。
 */
@Mapper
public interface SecurityEventOutboxMapper extends BaseMapper<SecurityEventOutboxEntry> {

    /**
     * 领取待投递事件。
     *
     * <p>Publisher Worker 是 {@code global_once}，用 MySQL-backed ShedLock 互斥；
     * 即便锁失效导致重复投递，消费端也必须是幂等的（本地 Set 插入天然幂等）。</p>
     */
    @Select("""
            SELECT * FROM security_event_outbox
             WHERE status IN ('PENDING', 'FAILED')
               AND next_attempt_at <= NOW(3)
             ORDER BY id
             LIMIT #{limit}
            """)
    List<SecurityEventOutboxEntry> claimDue(@Param("limit") int limit);
}
