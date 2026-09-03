package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentApproval;

/** Signals that the current turn has been durably paused for a user decision. */
public class AgentApprovalRequiredException extends RuntimeException {
    private final AgentApproval approval;

    public AgentApprovalRequiredException(AgentApproval approval) {
        super("该工具调用等待用户审批");
        this.approval = approval;
    }

    public AgentApproval approval() {
        return approval;
    }
}
