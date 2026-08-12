package com.xander.lab.agent.api;

import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentMessage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentConversationVO {
    private AgentConversation conversation;
    private List<AgentMessage> messages;
}