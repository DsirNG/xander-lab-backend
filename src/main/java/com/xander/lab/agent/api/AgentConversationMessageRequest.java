package com.xander.lab.agent.api;

import com.xander.lab.agent.domain.AgentAttachment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentConversationMessageRequest {
    @Size(max = 4000, message = "消息内容不能超过 4000 个字符")
    private String content;

    @Valid
    @Size(max = 5, message = "每次最多上传5个附件")
    private List<AgentAttachmentRequest> attachments = new ArrayList<>();

    @AssertTrue(message = "消息内容和附件不能同时为空")
    public boolean isContentOrAttachmentPresent() {
        return (content != null && !content.isBlank()) || (attachments != null && !attachments.isEmpty());
    }

    /** Supplies a useful default instruction for an attachment-only turn. */
    public String effectiveContent() {
        return content == null || content.isBlank() ? "请分析我上传的附件。" : content.trim();
    }

    public List<AgentAttachment> domainAttachments() {
        return attachments == null
                ? List.of()
                : attachments.stream().map(AgentAttachmentRequest::toDomain).toList();
    }
}
