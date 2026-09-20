package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {

    /** Stores an event only for the expected execution generation. */
    @Insert("""
            <script>
            INSERT INTO agent_event
                (conversation_id, run_version, event_type, data_content)
            SELECT #{event.conversationId}, #{event.runVersion}, #{event.eventType},
                   #{event.dataContent}
            FROM agent_conversation
            WHERE id = #{event.conversationId}
              AND user_id = #{userId}
              AND run_version = #{event.runVersion}
              <if test="requireRunning">AND status = 'running'</if>
            </script>
            """)
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertForRun(@Param("event") AgentEvent event,
                     @Param("userId") Long userId,
                     @Param("requireRunning") boolean requireRunning);
}
