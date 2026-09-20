package com.xander.lab.agent.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Owner decision for a single durable agent tool approval request. */
@Data
public class AgentApprovalDecisionRequest {
    /** True executes the original invocation; false cancels it without calling the tool. */
    @NotNull(message = "请明确是否批准该操作")
    private Boolean approved;

    /** Optional owner-facing reason retained as a short audit note. */
    @Size(max = 500, message = "审批说明不能超过 500 个字符")
    private String reason;
}
