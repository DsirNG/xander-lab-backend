package com.xander.lab.agent.application;

/** Internal signal that a late worker no longer owns the conversation execution generation. */
final class AgentRunSupersededException extends RuntimeException {

    AgentRunSupersededException(Long conversationId, long runVersion) {
        super("智能体会话执行代次已失效：conversation=" + conversationId + ", runVersion=" + runVersion);
    }
}
