package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {

    /**
     * 记录一次召回，用于把高频记忆排到前面。
     * 单条 UPDATE 避免读改写竞争，且不属于业务事务的必要部分。
     */
    @Update("""
            <script>
            UPDATE agent_memory
            SET hit_count = hit_count + 1, last_used_at = NOW()
            WHERE user_id = #{userId}
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markRecalled(@Param("userId") Long userId, @Param("ids") java.util.List<Long> ids);
}
