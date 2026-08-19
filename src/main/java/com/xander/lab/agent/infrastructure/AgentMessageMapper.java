package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    /** Inserts only while the supplied owner and execution generation are still active. */
    @Insert("""
            INSERT INTO agent_message
                (conversation_id, role, kind, tool_name, content)
            SELECT #{message.conversationId}, #{message.role}, #{message.kind},
                   #{message.toolName}, #{message.content}
            FROM agent_conversation
            WHERE id = #{message.conversationId}
              AND user_id = #{userId}
              AND status = 'running'
              AND run_version = #{runVersion}
            """)
    int insertForActiveRun(@Param("message") AgentMessage message,
                           @Param("userId") Long userId,
                           @Param("runVersion") long runVersion);
}
